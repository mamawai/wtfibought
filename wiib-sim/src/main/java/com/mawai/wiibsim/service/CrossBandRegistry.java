package com.mawai.wiibsim.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全仓安全带注册表：tick 巡检的"免检证"。
 *
 * <p>每次精查（{@link CrossLiquidationService#checkUser}）末尾按账户快照算出一条带：
 * <b>只要所有持仓 symbol 的价格不出各自区间，数学上保证账户爆不了</b>——于是带内 tick 直接跳过，
 * 不再打 DB。危险度编码在带宽里：高杠杆/深回撤账户带极窄（几乎每 tick 精查），健康账户带宽（长期免检）。</p>
 *
 * <h3>带宽推导（爆仓条件 equity ≤ mm，见 CrossMarginService.CrossAccount#liquidatable）</h3>
 * <p>记快照时权益 E、维持保证金 mm、占用 Σ起始保证金 U、总名义 ΣV=Σ(参考价×qty)，半宽 x（比例）。
 * 带内任意价格向量下，浮盈亏相对快照的最坏偏移 W ≤ Σ qty·ref·x = x·ΣV（每仓取各自最不利方向，
 * 已覆盖"全部持仓同时反向"的最坏情形）。两种情形要分别兜住：</p>
 * <pre>
 * ① 无资金流出：equity(t) ≥ E − W，需 E − W &gt; mm            → x &lt; (E − mm)/ΣV
 * ② 有资金流出（现货买入/逐仓开仓/划转等 12 个口，全过 assertCanAfford 闸 available ≥ 0）：
 *    流出时刻 balance ≥ U + pending − upnl(τ) ≥ U − upnl_ref − W（占用制不变量），
 *    之后 equity(t) = balance + upnl(t) ≥ U − 2W，需 U − 2W &gt; mm → x &lt; (U − mm)/(2·ΣV)
 * </pre>
 * <p>取 x = 0.7 × min(E − mm, (U − mm)/2) / ΣV。0.7 的安全系数吃掉两项二阶残差：空头不利方向
 * 名义变大 → mm 同涨（费率 0.4%~5%，量级 ≤ 0.05·W）；名义跨档 mm 费率跳档。②的意义：<b>余额流出
 * 不需要任何失效钩子</b>——闸本身保证流出后权益不低于占用，带宽已按此下界收窄。</p>
 *
 * <h3>失效（纪元）语义</h3>
 * <p>会让带失真的另一类变化是<b>仓位集合/占用变动</b>（开平仓、成交、SL/TP、强平、资金费、调杠杆），
 * 它们全部汇于 refreshUserIndex / settle，一处 {@link #bump} 即全覆盖。bump 是纪元自增：带打着
 * 出生纪元的标签，落后即死（{@link #shouldCheck} 视同无带）。两个竞态由此闭合：</p>
 * <ul>
 * <li><b>精查期间变动</b>：checkUser 先捕纪元→读快照→回填；期间 bump 过则回填的带标签落后，天然无效；</li>
 * <li><b>事务提交前重建</b>：bump 在事务内立即跳一次（杀旧带）+ afterCompletion 再跳一次
 * （杀"提交前有 tick 用旧快照重建"的带），零窗口。回滚也跳，代价只是多一次精查，方向 fail-safe。</li>
 * </ul>
 *
 * <p>状态全在 JVM 内存：丢失（重启/作废）的唯一后果是退化为"每 tick 精查"，即优化前的行为，
 * 方向永远 fail-safe。epochs 只增不删——remove 后归零再涨可能撞回历史纪元，让迟到的旧带复活。</p>
 */
@Component
public class CrossBandRegistry {

    /** 安全系数：覆盖 mm 随价漂移与跨档跳变的二阶项（推导见类注释），余量 &gt; 4 倍 */
    private static final BigDecimal SAFETY = new BigDecimal("0.7");
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private record Band(long epoch, Map<String, double[]> ranges) {}

    /** userId → 纪元。只增不删，防迟到旧带撞回历史纪元复活（条目仅一个 Long） */
    private final ConcurrentHashMap<Long, Long> epochs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Band> bands = new ConcurrentHashMap<>();

    /** 当前纪元；精查开始时捕获，回填时作标签 */
    public long epoch(Long userId) {
        return epochs.getOrDefault(userId, 0L);
    }

    /** 账户状态变动 → 作废旧带。事务内调用会自动补挂提交/回滚后的第二跳（见类注释竞态②） */
    public void bump(Long userId) {
        doBump(userId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    doBump(userId);
                }
            });
        }
    }

    private void doBump(Long userId) {
        epochs.merge(userId, 1L, Long::sum);
    }

    /** tick 判定：true = 要精查（无带 / 带的纪元落后 / 该 symbol 无区间 / 价格出带） */
    public boolean shouldCheck(Long userId, String symbol, double price) {
        Band band = bands.get(userId);
        if (band == null || band.epoch() != epoch(userId)) return true;
        double[] range = band.ranges().get(symbol);
        return range == null || price < range[0] || price > range[1];
    }

    /**
     * 精查末尾回填。不做 check-then-put：带永远打 expectedEpoch 标签，
     * 期间被 bump 过标签就落后，shouldCheck 判死——写入方无需感知竞态。
     */
    public void put(Long userId, long expectedEpoch, Map<String, double[]> ranges) {
        bands.put(userId, new Band(expectedEpoch, ranges));
    }

    /** 清理（无持仓/破产）。只清带不清纪元，理由见 epochs 字段注释 */
    public void remove(Long userId) {
        bands.remove(userId);
    }

    /** 带半宽（比例），公式推导见类注释；≤0 返回 0 = 不建带、每 tick 必查 */
    public static BigDecimal halfWidth(BigDecimal equity, BigDecimal usedMargin,
                                       BigDecimal maintenanceMargin, BigDecimal totalNotional) {
        if (totalNotional.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal equityBuffer = equity.subtract(maintenanceMargin);
        BigDecimal outflowFloor = usedMargin.subtract(maintenanceMargin).divide(TWO, 8, RoundingMode.DOWN);
        BigDecimal buffer = equityBuffer.min(outflowFloor);
        if (buffer.signum() <= 0) return BigDecimal.ZERO;
        // DOWN 舍入：宁可带窄一点多查一次，不许带宽超出数学边界
        return buffer.multiply(SAFETY).divide(totalNotional, 8, RoundingMode.DOWN);
    }

    /** 按半宽展开各 symbol 的免检区间 [ref×(1−x), ref×(1+x)] */
    public static Map<String, double[]> ranges(Map<String, BigDecimal> refPrices, BigDecimal halfWidth) {
        double x = halfWidth.doubleValue();
        Map<String, double[]> out = new HashMap<>();
        for (var e : refPrices.entrySet()) {
            double ref = e.getValue().doubleValue();
            out.put(e.getKey(), new double[]{ref * (1 - x), ref * (1 + x)});
        }
        return out;
    }
}
