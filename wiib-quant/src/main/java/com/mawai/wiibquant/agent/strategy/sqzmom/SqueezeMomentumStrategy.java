package com.mawai.wiibquant.agent.strategy.sqzmom;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibquant.agent.strategy.core.StrategyMarketView;
import com.mawai.wiibquant.agent.strategy.core.StrategyRiskPolicy;
import com.mawai.wiibquant.agent.strategy.core.StrategySignal;
import com.mawai.wiibquant.agent.strategy.core.StrategySignalState;
import com.mawai.wiibquant.agent.strategy.core.TradingStrategySpi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Squeeze Momentum 空头策略（LazyBear SQZMOM 的压缩检测 + 自研进出场，41币消融定稿形态）。
 *
 * <p>原理：BB 量收盘价统计离散度，KC 量真实波幅基线。BB 完全缩进 KC 内 = 波动率压缩蓄能
 * (sqzOn)，重新撑出 = 释放(sqzOff)。压缩够久后的首次向下释放开空，吃下跌瀑布的第一波。</p>
 *
 * <p>对齐原版的两个数学事实（Pine 源码逐行核对）：
 * ① 原版 BB 乘数输入没接线（BB/KC 共用 multKC），且 BB/KC 长度默认相等，乘数在 squeeze
 *    判定中约掉 → sqzOn ⟺ stdev(close,L) &lt; SMA(TR,L)，on/off 二元，noSqz(混合态)不存在；
 * ② 动量柱 val = 对 (close − avg(唐奇安中点, SMA)) 的 L 期线性回归端点(LSMA)，
 *    每个历史点用各自的滚动中枢；stdev 用总体口径(÷N)，对齐 Pine。</p>
 *
 * <p>只做空是硬编码而非参数：41币×6年取证，多单合计精确为零期望（胜率钉在 2:1 赔率的
 * 盈亏平衡点 33.3%，向上突破后价格无漂移），费后必亏；全部利润来自空单——加密下跌瀑布
 * (爆仓连环)的跟随性远强于向上突破。已知失效场景：2021 式全场逼空的狂热牛。</p>
 *
 * <p>消融已否决、勿再尝试：≤2H 周期（费率屠杀/半死带）、动量柱定多、1D 趋势闸（顺逆势
 * 无分离）、远尾追踪止盈（TP阶梯平坦，行情 2~4R 熄火）、+1R 保本止损（误杀回踩续势的
 * 赢单，收益腰斩）、区间锚止损、保证金定仓（部署必须按风险定量 ≤1%/笔 + 杠杆 ≤5x）。</p>
 *
 * <p>进场：4H 收盘 sqzOn 连续 ≥ squeezeMinBars 根后首现 sqzOff，且动量柱亮红（val&lt;0 且
 * 比上根更小 = 向下加速；暗色柱=动量萎靡不做），次根 5m 开盘市价开空。
 * 出场：SL = entry + slAtrMult×ATR，TP = entry − tpRMultiple×risk，挂死后持仓期零干预。</p>
 */
public final class SqueezeMomentumStrategy implements TradingStrategySpi {

    private static final String ID = "SQZMOM";

    private final SqzMomParams params;
    private final List<String> symbols;
    private final StrategyRiskPolicy riskPolicy = StrategyRiskPolicy.defaults();

    public SqueezeMomentumStrategy(SqzMomParams params, List<String> symbols) {
        this.params = params == null ? SqzMomParams.defaults() : params;
        this.symbols = List.copyOf(symbols);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<String> symbols() {
        return symbols;
    }

    @Override
    public StrategyRiskPolicy riskPolicy() {
        return riskPolicy;
    }

    /**
     * 无状态实现：触发 = 决策桶闭合瞬间的一次性 on→off 跳变，同一释放不会重复触发；
     * MARKET 信号发出即被引擎接走次根成交，无需挂单/持仓状态机。
     */
    @Override
    public Optional<StrategySignal> onBarClosed(String symbol, StrategyMarketView view) {
        if (!symbols.contains(symbol)) return Optional.empty();
        if (view.hasBaseGap()) return Optional.empty();
        // 决策只在决策桶闭合那一刻做（5m 与决策桶均 UTC 对齐，closeTime+1 恰为桶右边界）；
        // 其余 5m bar 直接跳过，省掉逐根重聚合（回测主开销）
        if ((view.nowMs() + 1) % params.decisionTfMillis() != 0) return Optional.empty();

        int minBars = 2 * params.length() + 1;   // val 的 LSMA 要 2L 窗口（每点带各自滚动中枢），+1 给 valPrev
        List<KlineBar> bars = view.closedBars(params.decisionTfMillis(),
                minBars + params.squeezeMinBars() + 30);   // 余量给 sqzOn 连击计数
        if (bars.size() < minBars) return Optional.empty();

        Ind ind = compute(bars, params.length());
        if (ind == null || !ind.released() || ind.sqzRunBefore() < params.squeezeMinBars()) {
            return Optional.empty();
        }
        // 只接亮红柱：val<0 且比上根更小（向下加速）；亮绿(多头侧零期望)与暗色柱(萎靡)都不做
        if (!(ind.val() < 0 && ind.val() < ind.valPrev()) || !Double.isFinite(ind.val())) {
            return Optional.empty();
        }
        if (!(ind.atr() > 0)) return Optional.empty();

        KlineBar trig = bars.getLast();
        BigDecimal entryRef = trig.close();   // 次根 5m 开盘市价的参考价
        BigDecimal stop = entryRef.add(BigDecimal.valueOf(ind.atr() * params.slAtrMult()))
                .setScale(8, RoundingMode.HALF_UP);
        BigDecimal risk = stop.subtract(entryRef);
        if (risk.signum() <= 0) return Optional.empty();
        BigDecimal tp = entryRef.subtract(risk.multiply(BigDecimal.valueOf(params.tpRMultiple())))
                .setScale(8, RoundingMode.HALF_UP);
        if (tp.signum() <= 0) return Optional.empty();   // 极端低价+宽止损时 TP 穿零，弃

        String reason = String.format(Locale.ROOT, "SQZMOM释放SHORT sqzRun=%d val=%.4f",
                ind.sqzRunBefore(), ind.val());
        double score = Math.min(1.0, ind.sqzRunBefore() / 12.0);   // 压缩越久能量越足
        return Optional.of(new StrategySignal(ID, symbol, "SHORT", false,
                entryRef, stop, tp, score, reason, trig.closeTime(), "MARKET"));
    }

    /**
     * 指标切片（对最新闭合决策bar）：只用闭合bar，无未来数据。数据不足返回 null。
     *
     * @param released     上一根 sqzOn 且本根 sqzOff（一次性跳变，即触发时机）
     * @param sqzRunBefore 截至上一根的 sqzOn 连续根数
     * @param sqzNow       本根是否 sqzOn（监控快照用，信号逻辑不消费）
     * @param atr          SMA(TR,L)，与 KC 同源，供 ATR 止损锚
     */
    record Ind(double val, double valPrev, boolean released, int sqzRunBefore, boolean sqzNow, double atr) {
    }

    static Ind compute(List<KlineBar> bars, int len) {
        int n = bars.size();
        if (n < 2 * len + 1) return null;
        double[] c = new double[n], h = new double[n], l = new double[n], tr = new double[n];
        for (int i = 0; i < n; i++) {
            c[i] = bars.get(i).close().doubleValue();
            h[i] = bars.get(i).high().doubleValue();
            l[i] = bars.get(i).low().doubleValue();
            tr[i] = i == 0 ? h[0] - l[0]
                    : Math.max(h[i] - l[i], Math.max(Math.abs(h[i] - c[i - 1]), Math.abs(l[i] - c[i - 1])));
        }
        boolean[] on = new boolean[n];
        boolean[] off = new boolean[n];
        double[] d = new double[n];       // close 对滚动混合中枢的偏离（动量柱原料）
        double atrLast = 0;
        for (int i = len - 1; i < n; i++) {
            double basis = 0, trSum = 0, hh = Double.NEGATIVE_INFINITY, ll = Double.POSITIVE_INFINITY;
            for (int j = i - len + 1; j <= i; j++) {
                basis += c[j];
                trSum += tr[j];
                if (h[j] > hh) hh = h[j];
                if (l[j] < ll) ll = l[j];
            }
            basis /= len;
            double var = 0;
            for (int j = i - len + 1; j <= i; j++) {
                double e = c[j] - basis;
                var += e * e;
            }
            double sd = Math.sqrt(var / len);   // 总体标准差(÷N)，对齐 Pine stdev
            double rangeMa = trSum / len;
            on[i] = sd < rangeMa;               // 乘数约掉后的等价形式（见类注释①）
            off[i] = sd > rangeMa;
            d[i] = c[i] - ((hh + ll) / 2 + basis) / 2;   // avg(唐奇安中点, SMA)
            atrLast = rangeMa;
        }
        double val = lsmaEndpoint(d, n - 1, len);
        double valPrev = lsmaEndpoint(d, n - 2, len);
        boolean released = off[n - 1] && on[n - 2];
        int run = 0;
        for (int i = n - 2; i >= len - 1 && on[i]; i--) run++;
        return new Ind(val, valPrev, released, run, on[n - 1], atrLast);
    }

    /** 监控快照：压缩计数与动量柱色，离"压缩足量后向下释放"还差什么一目了然。 */
    @Override
    public StrategySignalState signalState(String symbol, StrategyMarketView view) {
        int tfHours = (int) (params.decisionTfMillis() / 3_600_000L);
        int minBars = 2 * params.length() + 1;
        List<KlineBar> bars = view.closedBars(params.decisionTfMillis(),
                minBars + params.squeezeMinBars() + 30);
        if (bars.size() < minBars) {
            return new StrategySignalState(ID, symbol, "攒历史数据中",
                    StrategySignalState.kv(tfHours + "H桶", bars.size() + " / " + minBars));
        }
        Ind ind = compute(bars, params.length());
        if (ind == null) {
            return new StrategySignalState(ID, symbol, "攒历史数据中", StrategySignalState.kv());
        }
        boolean redAccel = Double.isFinite(ind.val()) && ind.val() < 0 && ind.val() < ind.valPrev();
        String momo = !Double.isFinite(ind.val()) ? "N/A"
                : ind.val() < 0
                    ? (redAccel ? "亮红·向下加速" : "暗红·下行减速")
                    : (ind.val() > ind.valPrev() ? "亮绿·向上加速" : "暗绿·上行减速");
        int curRun = ind.sqzNow() ? ind.sqzRunBefore() + 1 : 0;
        String state;
        if (ind.sqzNow()) {
            state = curRun >= params.squeezeMinBars()
                    ? "压缩已足量，等向下释放开空"
                    : "压缩蓄能中，还需 " + (params.squeezeMinBars() - curRun) + " 桶";
        } else if (ind.released() && ind.sqzRunBefore() >= params.squeezeMinBars()) {
            state = redAccel ? "本桶刚释放·红柱达标(已触发开空)" : "本桶刚释放·柱色不符,放弃";
        } else {
            state = "未压缩，等波动率收缩";
        }
        return new StrategySignalState(ID, symbol, state, StrategySignalState.kv(
                "压缩(BB<KC)", ind.sqzNow() ? "ON" : "OFF",
                "连续压缩", curRun + " / 需" + params.squeezeMinBars() + "桶",
                "动量柱", momo,
                "动量值", String.format(Locale.ROOT, "%.4f", ind.val())));
    }

    /** 最小二乘直线在窗口末点的取值 = Pine linreg(src, len, 0)。x=0..len-1，端点取 x=len-1。 */
    static double lsmaEndpoint(double[] y, int endIdx, int len) {
        int start = endIdx - len + 1;
        double sumX = len * (len - 1) / 2.0;
        double sumXX = (double) (len - 1) * len * (2L * len - 1) / 6.0;
        double sumY = 0, sumXY = 0;
        for (int k = 0; k < len; k++) {
            sumY += y[start + k];
            sumXY += k * y[start + k];
        }
        double slope = (len * sumXY - sumX * sumY) / (len * sumXX - sumX * sumX);
        double intercept = (sumY - slope * sumX) / len;
        return intercept + slope * (len - 1);
    }
}
