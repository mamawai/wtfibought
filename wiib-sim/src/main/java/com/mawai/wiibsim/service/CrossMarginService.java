package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.FuturesPosition;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全仓账本核心（占用制）。
 *
 * <p>占用制一句话：全仓开仓不划钱，本金一直躺在余额钱包里，仓位上只记"占用了多少起始保证金"。
 * 举例：钱包 10000 开一笔占用 2000 的全仓后，钱包仍是 10000，可用变 8000；
 * 浮盈 +500 时可用 8500——浮盈开仓由公式天然成立，不需要特殊逻辑。</p>
 *
 * <p>三条核心公式（只看全仓仓位，逐仓/游戏钱包不参与）：
 * <pre>
 * equity    = 余额 + Σ浮盈亏                    （账户净值，强平判定用）
 * available = equity − Σ占用保证金 − Σ挂单占用    （唯一额度口径，对齐 Binance availableBalance）
 * 强平条件   = equity ≤ Σ维持保证金 → 全组爆
 * </pre></p>
 *
 * <p>available 是余额钱包所有去向的统一闸门：全仓开仓、逐仓开仓/加仓/挂单/追加保证金、
 * 现货买入、划转游戏钱包，一律走 {@link #assertCanAfford}。占用制承诺的"这 2000 是给全仓仓位兜底的"
 * 只有堵死全部出口才成立——放行任一出口按"维持保证金"这种宽口径，全仓仓位就会被抽到只剩
 * 维持保证金（20x 下差一个数量级），下一个 tick 就爆。</p>
 */
public interface CrossMarginService {

    /** 全仓账户快照：一次算齐 equity/available/维持保证金，四处（开仓校验/划转/强平/展示）共用一个口径 */
    CrossAccount snapshot(Long userId);

    /**
     * 带钉住价的快照（插针语义）：pinSymbol 的估值用 pinPrice 而非缓存价，其余 symbol 照缓存。
     * 强平巡检用——触发 tick 的插针价哪怕下一秒回落，判定仍按触发那一刻的价格。
     */
    CrossAccount snapshot(Long userId, String pinSymbol, BigDecimal pinPrice);

    /**
     * 余额钱包动钱的唯一额度闸：available ≥ cost，不足抛 FUTURES_CROSS_AVAILABLE_NOT_ENOUGH。
     * 返回快照供调用方复用（省一次重算）。
     *
     * <p>它只管一件事：这笔钱是不是已经被全仓仓位占着。真要划走现金的场景（逐仓开仓、现货买入、
     * 划转游戏钱包）还得各自过 balance ≥ cost——浮盈算得进 available，但浮盈不是钱包里的现金。</p>
     *
     * <p>必须在落库占用/扣款的同一个事务里调：内部先锁 user 行再取快照，锁到事务提交才放。</p>
     */
    CrossAccount assertCanAfford(Long userId, BigDecimal cost);

    /**
     * 全仓资金结算（平仓盈亏/手续费/资金费）：直接加减余额，允许为负；
     * 扣穿且已无全仓仓位 = 穿仓落地 → 立即破产（清空两钱包，次一交易日重置）。
     * 仍有仓位的负余额留给强平巡检——浮盈可能救回来，不在这里武断处决。
     */
    void settle(Long userId, BigDecimal delta);

    /**
     * 全仓仓位的预估强平价（展示用）。
     * 把"余额 + 其他全仓仓位浮盈亏 − 其他仓位维持保证金"当作本仓的兜底金，
     * 套逐仓静态强平价公式即得——其他仓位价格按当下冻结，是 Binance 同款近似。
     */
    BigDecimal estimateLiqPrice(FuturesPosition position, CrossAccount account);

    /** 用户是否持有全仓仓位（查库，事务内能看到本事务刚开/刚平的仓位） */
    boolean hasCrossPositions(Long userId);

    /** 按 DB 实况同步该用户的全仓索引（开/平/强平后调用，幂等自愈）。事务内调用时等提交后才刷 */
    void refreshUserIndex(Long userId);

    /** 某 symbol 上持有全仓仓位的用户集合（价格 tick 定向触发健康检查用） */
    Set<String> usersOnSymbol(String symbol);

    /** 全局持有全仓仓位的用户集合（兜底轮询用） */
    Set<String> allCrossUsers();

    /**
     * 账户快照。positions 为快照时点的全仓持仓（价格已冻结在 unrealizedPnl/maintenanceMargin 里）；
     * refPrices 为快照估值实际用到的各 symbol 价格——安全带必须锚在这组价上（带的数学保证
     * 以快照时点状态为基准，事后重取缓存价会引入漂移）。
     */
    record CrossAccount(BigDecimal balance,
                        BigDecimal unrealizedPnl,
                        BigDecimal usedMargin,
                        BigDecimal pendingReserved,
                        BigDecimal maintenanceMargin,
                        List<FuturesPosition> positions,
                        Map<String, BigDecimal> refPrices) {

        public BigDecimal equity() {
            return balance.add(unrealizedPnl);
        }

        public BigDecimal available() {
            return equity().subtract(usedMargin).subtract(pendingReserved);
        }

        /** 最大可流出现金 = min(可用额度, 余额钱包)：浮盈顶得了开仓额度，但顶不了现金流出 */
        public BigDecimal maxOutflow() {
            return available().min(balance).max(BigDecimal.ZERO);
        }

        /** 强平线：净值 ≤ 维持保证金（与逐仓判定符号一致，等于也爆） */
        public boolean liquidatable() {
            return !positions.isEmpty() && equity().compareTo(maintenanceMargin) <= 0;
        }
    }
}
