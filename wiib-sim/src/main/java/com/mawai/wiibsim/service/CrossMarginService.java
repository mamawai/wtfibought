package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.FuturesPosition;

import java.math.BigDecimal;
import java.util.List;
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
 * available = equity − Σ占用保证金 − Σ挂单占用    （开新仓/挂单额度）
 * 强平条件   = equity ≤ Σ维持保证金 → 全组爆
 * </pre></p>
 */
public interface CrossMarginService {

    /** 全仓账户快照：一次算齐 equity/available/维持保证金，四处（开仓校验/划转/强平/展示）共用一个口径 */
    CrossAccount snapshot(Long userId);

    /**
     * 开仓/挂单额度校验：available ≥ cost，不足抛 FUTURES_CROSS_AVAILABLE_NOT_ENOUGH。
     * 返回快照供调用方复用（省一次重算）。
     */
    CrossAccount assertCanAfford(Long userId, BigDecimal cost);

    /**
     * 余额钱包流出硬底线：流出后 equity 必须仍高于维持保证金，否则抛 CROSS_OUTFLOW_BLOCKED。
     * 覆盖：划转到游戏钱包、现货/B股买入、逐仓开仓/加仓/追加保证金。
     * 无全仓仓位的用户走 Redis 集合 O(1) 直接放行，零开销。
     */
    void assertOutflowAllowed(Long userId, BigDecimal amount);

    /**
     * 全仓资金结算（平仓盈亏/手续费/资金费）：直接加减余额，允许为负；
     * 扣穿且已无全仓仓位 = 穿仓落地 → 立即破产（清空两钱包，次一交易日重置）。
     * 仍有仓位的负余额留给强平巡检——浮盈可能救回来，不在这里武断处决。
     */
    void settle(Long userId, BigDecimal delta);

    /** 最大可流出金额 = max(0, equity − 维持保证金 − 0.01缓冲)；无全仓仓位返回 null 表示不受限 */
    BigDecimal maxOutflow(Long userId);

    /**
     * 全仓仓位的预估强平价（展示用）。
     * 把"余额 + 其他全仓仓位浮盈亏 − 其他仓位维持保证金"当作本仓的兜底金，
     * 套逐仓静态强平价公式即得——其他仓位价格按当下冻结，是 Binance 同款近似。
     */
    BigDecimal estimateLiqPrice(FuturesPosition position, CrossAccount account);

    /** 用户是否持有全仓仓位（Redis 集合 O(1)，流出守卫的快路径） */
    boolean hasCrossPositions(Long userId);

    /** 按 DB 实况同步该用户的全仓索引（开/平/强平后调用，幂等自愈） */
    void refreshUserIndex(Long userId);

    /** 某 symbol 上持有全仓仓位的用户集合（价格 tick 定向触发健康检查用） */
    Set<String> usersOnSymbol(String symbol);

    /** 全局持有全仓仓位的用户集合（兜底轮询用） */
    Set<String> allCrossUsers();

    /**
     * 账户快照。positions 为快照时点的全仓持仓（价格已冻结在 unrealizedPnl/maintenanceMargin 里）。
     */
    record CrossAccount(BigDecimal balance,
                        BigDecimal unrealizedPnl,
                        BigDecimal usedMargin,
                        BigDecimal pendingReserved,
                        BigDecimal maintenanceMargin,
                        List<FuturesPosition> positions) {

        public BigDecimal equity() {
            return balance.add(unrealizedPnl);
        }

        public BigDecimal available() {
            return equity().subtract(usedMargin).subtract(pendingReserved);
        }

        /** 强平线：净值 ≤ 维持保证金（与逐仓判定符号一致，等于也爆） */
        public boolean liquidatable() {
            return !positions.isEmpty() && equity().compareTo(maintenanceMargin) <= 0;
        }
    }
}
