package com.mawai.wiibservice.agent.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;

/**
 * 加密货币技术指标计算器。
 *
 * <p>所有指标算法集中在本类，外部通常只调用 {@link #calcAll} 一次性拿到全套指标；
 * 包内代码和测试可按需单独调用具体指标方法（如 {@link #atr}、{@link #rsiSeries}）。</p>
 *
 * <h2>指标字段速查</h2>
 * <table border="1">
 * <tr><th>字段</th><th>名称</th><th>说明</th></tr>
 * <tr><td>ma7 / ma25 / ma99</td><td>简单移动平均线</td>
 * <td>MA7最敏感，MA25中期，MA99长期；ma99 仅在样本不少于 99 根时输出</td></tr>
 * <tr><td>ema12 / ema20 / ema26</td><td>指数移动平均线</td>
 * <td>比MA更灵敏，EMA12&gt;EMA26金叉看涨，死叉看跌</td></tr>
 * <tr><td>ma_alignment</td><td>均线排列</td>
 * <td>样本足够时按 MA7/MA25/MA99 判断；不足 99 根时按 MA7/MA25 降级判断</td></tr>
 * <tr><td>rsi14</td><td>相对强弱指数</td>
 * <td>0-100。&gt;70超买可能回落，&lt;30超卖可能反弹。需配合trend判断动量方向</td></tr>
 * <tr><td>rsi14_trend</td><td>RSI趋势摘要</td>
 * <td>rising_n=连续n期上涨，falling_n=连续n期下跌，mostly_up/down=多数上涨/下跌，sideways=震荡</td></tr>
 * <tr><td>macd_dif / macd_dea</td><td>MACD快线/慢线</td>
 * <td>DIF&gt;DEA金叉看涨，DIF&lt;DEA死叉看跌</td></tr>
 * <tr><td>macd_hist</td><td>MACD柱状图</td>
 * <td>正值柱看涨，负值柱看跌。HIST上升=动能改善，下降=动能恶化</td></tr>
 * <tr><td>macd_cross</td><td>MACD交叉信号</td>
 * <td>golden=刚发生金叉，death=刚发生死叉；无新交叉时不输出该字段</td></tr>
 * <tr><td>macd_hist_trend</td><td>MACD柱状图趋势</td>
 * <td>rising_5=连续5期动能改善，falling_5=连续5期动能恶化</td></tr>
 * <tr><td>boll_upper / boll_mid / boll_lower</td><td>布林带上/中/下轨</td>
 * <td>上/下轨是动态通道边界，中轨=动态均线；用于观察价格相对波动区间</td></tr>
 * <tr><td>boll_pb</td><td>布林带百分比</td>
 * <td>通常 0-100；价格突破轨道时可小于 0 或大于 100；50=在中轨附近</td></tr>
 * <tr><td>boll_bandwidth</td><td>布林带宽度</td>
 * <td>数值小=收敛(即将变盘)，数值大=扩张(趋势延续)</td></tr>
 * <tr><td>atr14</td><td>平均真实波幅</td>
 * <td>衡量波动率大小。常用于计算止损距离（入场价±N×ATR）</td></tr>
 * <tr><td>kdj_k / kdj_d / kdj_j</td><td>随机指标</td>
 * <td>K&gt;D金叉，K&lt;D死叉。J&gt;80超买，J&lt;20超卖</td></tr>
 * <tr><td>adx</td><td>平均趋向指数</td>
 * <td>0-100。ADX&gt;25=趋势市(追趋势策略)，ADX&lt;15=震荡市(高抛低吸)</td></tr>
 * <tr><td>plus_di / minus_di</td><td>趋向线</td>
 * <td>+DI&gt;-DI看涨，-DI&gt;+DI看跌。差值越大趋势越强</td></tr>
 * <tr><td>obv</td><td>能量潮</td>
 * <td>涨时OBV创新高=健康涨势，跌时OBV创新低=健康跌势。量价背离=警惕反转</td></tr>
 * <tr><td>obv_ma20</td><td>OBV移动平均</td>
 * <td>OBV上穿MA20=资金流入积极，下穿=资金流出</td></tr>
 * <tr><td>obv_trend</td><td>OBV趋势摘要</td>
 * <td>与RSI trend同义，判断近5期量能方向</td></tr>
 * <tr><td>volume_ma20</td><td>成交量20均量</td>
 * <td>判断当前量能处于历史什么水平</td></tr>
 * <tr><td>volume_ratio</td><td>量比</td>
 * <td>最新量/20均量。&gt;1.5放量，&lt;0.5缩量。配合涨跌判断是否量价配合</td></tr>
 * <tr><td>close_trend</td><td>价格动量摘要</td>
 * <td>rising_5=5连涨(强动量)，falling_5=5连跌(弱动量)，mostly_up/down=多数上涨/下跌</td></tr>
 * <tr><td>close_trend_recent_3_closed</td><td>近3根已收盘趋势摘要</td>
 * <td>去掉当前未收盘 tick 的 close_trend，避免临时噪音污染判断</td></tr>
 * <tr><td>volume_ratio_recent_5_closed</td><td>近5根已收盘量比序列</td>
 * <td>每根 bar 独立算量比，用于判断量能持续性（放量突破 / 量能衰减）</td></tr>
 * </table>
 */
public class CryptoIndicatorCalculator {

    /** 部分 BigDecimal 乘法使用的计算精度：10 位有效数字 + HALF_UP 四舍五入。 */
    private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);

    /** 多数内部序列的默认小数位；最终输出会按字段语义单独收敛精度。 */
    private static final int SCALE = 8;

    // ==================== 1. 综合计算入口 ====================

    /**
     * 根据 K 线序列一次性计算全套技术指标。
     *
     * <p>这是本类最常用的入口：下游只关心"给我一份指标快照"而不关心单个计算细节。
     * 所有指标都组装到同一个 {@link LinkedHashMap}，按 put 顺序迭代，便于日志和序列化。</p>
     *
     * @param klines K 线列表，每行是 {@code [high, low, close, volume]}，按时间升序；
     *               至少需要 30 根才能计算，少于 30 根直接返回 {@code {"error": "K线数据不足"}}
     * @return 指标字段 Map（字段定义见类级 Javadoc 表格）；输入不足时只含一个 {@code error} 字段
     */
    public static Map<String, Object> calcAll(List<BigDecimal[]> klines) {
        if (klines == null || klines.size() < 30) return Map.of("error", "K线数据不足");

        // 把 K 线拆成四条并列序列，后续每个指标只挑自己需要的那几条。
        List<BigDecimal> closes = new ArrayList<>(klines.size());
        List<BigDecimal> highs = new ArrayList<>(klines.size());
        List<BigDecimal> lows = new ArrayList<>(klines.size());
        List<BigDecimal> volumes = new ArrayList<>(klines.size());
        for (BigDecimal[] k : klines) {
            highs.add(k[0]);
            lows.add(k[1]);
            closes.add(k[2]);
            volumes.add(k[3]);
        }

        int size = closes.size();
        Map<String, Object> r = new LinkedHashMap<>();

        // --- 均线 (MA / EMA) ---
        r.put("ma7", ma(closes, 7));
        r.put("ma25", ma(closes, 25));
        if (size >= 99) r.put("ma99", ma(closes, 99));
        r.put("ema12", ema(closes, 12));
        r.put("ema20", ema(closes, 20));
        r.put("ema26", ema(closes, 26));

        // 均线排列: 1=多头(ma7>ma25>ma99), -1=空头, 0=纠缠
        r.put("ma_alignment", maAlignment(closes));

        // --- RSI ---
        // 一次计算完整序列，既可取末值（rsi14），也可做趋势摘要（rsi14_trend）
        List<BigDecimal> rsiSeries = rsiSeries(closes, 14);
        if (!rsiSeries.isEmpty()) {
            r.put("rsi14", rsiSeries.getLast());
            r.put("rsi14_trend", trendSummary(rsiSeries, 5));
        }

        // --- MACD (含 hist 趋势、金叉/死叉) ---
        Map<String, Object> macdResult = macdFull(closes, 12, 26, 9);
        r.putAll(macdResult);

        // --- 布林带 (上下轨 + %B + 带宽) ---
        Map<String, BigDecimal> boll = boll(closes, 20, 2);
        if (!boll.isEmpty()) {
            r.put("boll_upper", boll.get("upper"));
            r.put("boll_mid", boll.get("mid"));
            r.put("boll_lower", boll.get("lower"));
            BigDecimal curClose = closes.getLast();
            r.put("boll_pb", bollPercentB(curClose, boll.get("upper"), boll.get("lower")));
            r.put("boll_bandwidth", bollBandwidth(boll.get("upper"), boll.get("lower"), boll.get("mid")));
        }

        // --- ATR (Wilder/RMA 平滑) ---
        r.put("atr14", atr(highs, lows, closes, 14));

        // --- KDJ 随机指标 ---
        Map<String, BigDecimal> kdj = kdj(highs, lows, closes, 9, 3, 3);
        if (!kdj.isEmpty()) {
            r.put("kdj_k", kdj.get("k"));
            r.put("kdj_d", kdj.get("d"));
            r.put("kdj_j", kdj.get("j"));
        }

        // --- ADX (趋势强度 + 方向线) ---
        Map<String, BigDecimal> adxResult = adx(highs, lows, closes, 14);
        if (!adxResult.isEmpty()) {
            r.put("adx", adxResult.get("adx"));
            r.put("plus_di", adxResult.get("plus_di"));
            r.put("minus_di", adxResult.get("minus_di"));
        }

        // --- OBV (能量潮) ---
        List<BigDecimal> obvSeries = obv(closes, volumes);
        if (!obvSeries.isEmpty()) {
            r.put("obv", obvSeries.getLast());
            // 当 OBV 序列不足 20 时退化为全序列均值，避免丢字段
            BigDecimal obvMa = ma(obvSeries, Math.min(20, obvSeries.size()));
            r.put("obv_ma20", obvMa);
            r.put("obv_trend", trendSummary(obvSeries, 5));
        }

        // --- 成交量 ---
        r.put("volume_ma20", ma(volumes, Math.min(20, size)));
        r.put("volume_ratio", volumeRatio(volumes, 20));
        // 最近 5 根"已收盘"的量比序列，下游用于判断量能持续性（放量突破 / 量能衰减）
        r.put("volume_ratio_recent_5_closed", closedVolumeRatioSeries(volumes, 20, 5));

        // --- 价格动量摘要 ---
        r.put("close_trend", trendSummary(closes, 5));
        // 最近 3 根"已收盘"的趋势，避免当前未收盘 bar 噪音影响判断
        r.put("close_trend_recent_3_closed", closedTrendSummary(closes, 3));

        return r;
    }

    // ==================== 2. 价格变化 ====================

    /**
     * 百分比变化：{@code (to - from) / from × 100}。
     *
     * <p>结果按 2 位小数四舍五入。例 from=100, to=105 → 5.00；from=100, to=95 → -5.00。</p>
     *
     * @return 百分比变化（已放大 100 倍）；{@code from} 为 null 或 0 时返回 null
     */
    public static BigDecimal pctChange(BigDecimal from, BigDecimal to) {
        if (from == null || from.signum() == 0) return null;
        return to.subtract(from).divide(from, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    // ==================== 3. 趋势摘要 ====================

    /**
     * 把一段时间序列的方向压缩成一个字符串标签。
     *
     * <p>统计最近 lookback 期相邻点的 up / down 次数，按比例归类：</p>
     * <ul>
     *   <li>{@code rising_n} —— n 期全部上涨（最强连续上涨）</li>
     *   <li>{@code falling_n} —— n 期全部下跌</li>
     *   <li>{@code mostly_up} / {@code mostly_down} —— 多数方向但未全部</li>
     *   <li>{@code sideways} —— up / down 次数相同（或都为 0）</li>
     *   <li>{@code unknown} —— 数据不足 2 期</li>
     * </ul>
     *
     * <p>同一个函数被 close / RSI / OBV / MACD hist 等多种序列复用。</p>
     *
     * @param series   任意时间序列（按时间升序）
     * @param lookback 回看期数；实际使用 {@code min(lookback, series.size()-1)}
     */
    static String trendSummary(List<BigDecimal> series, int lookback) {
        if (series.size() < 2) return "unknown";
        int n = Math.min(lookback, series.size() - 1);
        int up = 0, down = 0;
        for (int i = series.size() - n; i < series.size(); i++) {
            int cmp = series.get(i).compareTo(series.get(i - 1));
            if (cmp > 0) up++;
            else if (cmp < 0) down++;
        }
        if (up == n) return "rising_" + n;
        if (down == n) return "falling_" + n;
        if (up > down) return "mostly_up";
        if (down > up) return "mostly_down";
        return "sideways";
    }

    /**
     * 只看"已收盘"部分的趋势摘要。
     *
     * <p>最后一根 bar 可能还没走完（当前 tick），直接拿它判断容易被噪音误导。
     * 这里先去掉末尾一根再调 {@link #trendSummary}，保证结果只基于完整收盘数据。</p>
     *
     * @return 输入为 null 或长度不足 {@code lookback + 2}（1 根未收盘 + lookback+1 根已收盘才够比较）时返回 {@code "unknown"}
     */
    static String closedTrendSummary(List<BigDecimal> series, int lookback) {
        if (series == null || series.size() < lookback + 2) {
            return "unknown";
        }
        return trendSummary(series.subList(0, series.size() - 1), lookback);
    }

    // ==================== 4. 均线排列 ====================

    /**
     * 判断 MA7 / MA25 / MA99 的多空排列。
     *
     * <p>样本不足 99 时降级只看 MA7 与 MA25 的大小关系。</p>
     *
     * @return 样本足够时 1 = MA7 &gt; MA25 &gt; MA99，-1 = 反向排列；
     *         样本不足 99 时按 MA7 / MA25 降级判断；0 = 纠缠或数据缺失
     */
    static int maAlignment(List<BigDecimal> closes) {
        BigDecimal m7 = ma(closes, 7);
        BigDecimal m25 = ma(closes, 25);
        if (m7 == null || m25 == null) return 0;
        BigDecimal m99 = closes.size() >= 99 ? ma(closes, 99) : null;
        if (m99 != null) {
            // 三均线严格有序才算完整多/空头排列
            if (m7.compareTo(m25) > 0 && m25.compareTo(m99) > 0) return 1;
            if (m7.compareTo(m25) < 0 && m25.compareTo(m99) < 0) return -1;
        } else {
            // 样本不足：只能用 MA7 vs MA25 的方向近似判断
            if (m7.compareTo(m25) > 0) return 1;
            if (m7.compareTo(m25) < 0) return -1;
        }
        return 0;
    }

    // ==================== 5. 均线基础工具 (MA / EMA / RMA) ====================

    /**
     * 简单移动平均（SMA）：最近 {@code period} 期算术均值。
     *
     * @return 样本不足 period 返回 null
     */
    static BigDecimal ma(List<BigDecimal> data, int period) {
        if (data.size() < period) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = data.size() - period; i < data.size(); i++) sum = sum.add(data.get(i));
        return sum.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 指数移动平均（EMA），只返回最新一点的值。
     *
     * <p>实现：</p>
     * <ol>
     *   <li>种子用前 period 期 SMA</li>
     *   <li>后续递推 {@code EMA(t) = close(t) × k + EMA(t-1) × (1-k)}，其中 {@code k = 2 / (period + 1)}</li>
     * </ol>
     *
     * @return 样本不足 period 返回 null
     */
    static BigDecimal ema(List<BigDecimal> data, int period) {
        if (data.size() < period) return null;
        BigDecimal k = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(period + 1), SCALE, RoundingMode.HALF_UP);
        BigDecimal oneMinusK = BigDecimal.ONE.subtract(k);
        // 前 period 期 SMA 作为种子
        BigDecimal emaVal = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) emaVal = emaVal.add(data.get(i));
        emaVal = emaVal.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
        // 从第 period 个样本开始，逐点向后递推到最新
        for (int i = period; i < data.size(); i++) {
            emaVal = data.get(i).multiply(k, MC).add(emaVal.multiply(oneMinusK, MC));
        }
        return emaVal.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 和 {@link #ema} 同算法，但返回整条 EMA 序列（每步一个值）。
     *
     * <p>MACD 需要 EMA12 和 EMA26 的整条序列来逐点相减得到 DIF，单点 EMA 满足不了。</p>
     *
     * @return 第 0 个元素对应原始数据第 period-1 个点；长度为 {@code data.size() - period + 1}
     */
    static List<BigDecimal> emaSeries(List<BigDecimal> data, int period) {
        if (data.size() < period) return List.of();
        BigDecimal k = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(period + 1), SCALE, RoundingMode.HALF_UP);
        BigDecimal oneMinusK = BigDecimal.ONE.subtract(k);
        BigDecimal seed = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) seed = seed.add(data.get(i));
        seed = seed.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
        List<BigDecimal> result = new ArrayList<>();
        result.add(seed);
        BigDecimal current = seed;
        for (int i = period; i < data.size(); i++) {
            current = data.get(i).multiply(k, MC).add(current.multiply(oneMinusK, MC));
            result.add(current.setScale(SCALE, RoundingMode.HALF_UP));
        }
        return result;
    }

    /**
     * Wilder / RMA 平滑（Running Moving Average）。
     *
     * <p>公式：{@code RMA(t) = RMA(t-1) × (period-1)/period + cur/period}；
     * 种子同样用前 period 期的 SMA。</p>
     *
     * <p>RSI、ATR、ADX 的原始定义都用 RMA 而不是 EMA，两者权重不同
     * （RMA 更"懒"，比同 period 的 EMA 反应慢）。</p>
     */
    static List<BigDecimal> rmaSeries(List<BigDecimal> data, int period) {
        if (data.size() < period) return List.of();
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) sum = sum.add(data.get(i));
        BigDecimal rma = sum.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
        List<BigDecimal> result = new ArrayList<>();
        result.add(rma);
        BigDecimal pMinus1 = BigDecimal.valueOf(period - 1);
        BigDecimal p = BigDecimal.valueOf(period);
        for (int i = period; i < data.size(); i++) {
            rma = rma.multiply(pMinus1).add(data.get(i)).divide(p, SCALE, RoundingMode.HALF_UP);
            result.add(rma);
        }
        return result;
    }

    // ==================== 6. RSI ====================

    /**
     * 计算 RSI 完整序列（而非只返回最新值）。
     *
     * <p>实现按 Wilder 原始定义：</p>
     * <ol>
     *   <li>第一个值用前 period 期涨幅 / 跌幅的简单均值做种子</li>
     *   <li>后续值用 RMA 平滑：{@code avg(t) = avg(t-1) × (p-1)/p + cur/p}</li>
     *   <li>最终 {@code RSI = 100 - 100 / (1 + avgGain/avgLoss)}</li>
     * </ol>
     *
     * <p>下游的 {@code rsi14_trend} 需要整条序列做 trendSummary，所以这里返回 List。</p>
     *
     * @return 样本不足 period+1 返回空 List
     */
    static List<BigDecimal> rsiSeries(List<BigDecimal> closes, int period) {
        if (closes.size() < period + 1) return List.of();
        // 种子段：前 period 期的涨幅累加 / 跌幅累加
        BigDecimal avgGain = BigDecimal.ZERO, avgLoss = BigDecimal.ZERO;
        for (int i = 1; i <= period; i++) {
            BigDecimal diff = closes.get(i).subtract(closes.get(i - 1));
            if (diff.signum() > 0) avgGain = avgGain.add(diff);
            else avgLoss = avgLoss.add(diff.abs());
        }
        avgGain = avgGain.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
        avgLoss = avgLoss.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);

        List<BigDecimal> result = new ArrayList<>();
        result.add(calcRsiValue(avgGain, avgLoss));

        // 后续逐点 RMA 递推：涨时只更新 avgGain，跌时只更新 avgLoss
        for (int i = period + 1; i < closes.size(); i++) {
            BigDecimal diff = closes.get(i).subtract(closes.get(i - 1));
            BigDecimal pMinus1 = BigDecimal.valueOf(period - 1);
            BigDecimal p = BigDecimal.valueOf(period);
            if (diff.signum() > 0) {
                avgGain = avgGain.multiply(pMinus1).add(diff).divide(p, SCALE, RoundingMode.HALF_UP);
                avgLoss = avgLoss.multiply(pMinus1).divide(p, SCALE, RoundingMode.HALF_UP);
            } else {
                avgGain = avgGain.multiply(pMinus1).divide(p, SCALE, RoundingMode.HALF_UP);
                avgLoss = avgLoss.multiply(pMinus1).add(diff.abs()).divide(p, SCALE, RoundingMode.HALF_UP);
            }
            result.add(calcRsiValue(avgGain, avgLoss));
        }
        return result;
    }

    /**
     * 用已平滑的 avgGain / avgLoss 求 RSI 单点值。
     *
     * <p>公式：{@code RSI = 100 - 100 / (1 + RS)}，其中 {@code RS = avgGain / avgLoss}。</p>
     *
     * <p>特殊：当前实现对 {@code avgLoss == 0} 统一返回 100，避免除零；
     * 横盘导致 {@code avgGain == 0 && avgLoss == 0} 时也会走这个边界。</p>
     */
    private static BigDecimal calcRsiValue(BigDecimal avgGain, BigDecimal avgLoss) {
        if (avgLoss.signum() == 0) return BigDecimal.valueOf(100);
        BigDecimal rs = avgGain.divide(avgLoss, SCALE, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(100).subtract(
                BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), 2, RoundingMode.HALF_UP));
    }

    // ==================== 7. MACD ====================

    /**
     * 计算 MACD 及其衍生信号，一次返回多个字段。
     *
     * <p>输出字段（全部 null-safe，样本不足时把 dif/dea/hist 兜底为 0 返回）：</p>
     * <ul>
     *   <li>{@code macd_dif} = EMA(fast) − EMA(slow)</li>
     *   <li>{@code macd_dea} = EMA(DIF, signal)</li>
     *   <li>{@code macd_hist} = (DIF − DEA) × 2
     *       （国内常见画法，放大 2 倍让视觉差异更明显）</li>
     *   <li>{@code macd_hist_trend} = 近 5 期 hist 数值趋势（看动能改善 / 恶化）</li>
     *   <li>{@code macd_cross} = {@code "golden"} / {@code "death"}，仅当最新一根 bar 恰好发生交叉时出现</li>
     * </ul>
     */
    static Map<String, Object> macdFull(List<BigDecimal> closes, int fast, int slow, int signal) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (closes.size() < slow + signal) {
            // 样本不够就兜底全 0，让下游字段始终存在，不必判空
            r.put("macd_dif", BigDecimal.ZERO);
            r.put("macd_dea", BigDecimal.ZERO);
            r.put("macd_hist", BigDecimal.ZERO);
            return r;
        }
        List<BigDecimal> emaFastSeries = emaSeries(closes, fast);
        List<BigDecimal> emaSlowSeries = emaSeries(closes, slow);
        // 两条 EMA 序列的尾部本来就对齐（都到最新 bar），但头部不齐：
        //   emaFastSeries[0] 对应 time = fast-1
        //   emaSlowSeries[0] 对应 time = slow-1
        // 这里 start = slow - fast，用来跳过 fast 序列前面那几个
        // "还没有 slow 能配对"的早期点，让 fast[i+start] 与 slow[i] 落在同一时间。
        int start = emaFastSeries.size() - emaSlowSeries.size();
        List<BigDecimal> difSeries = new ArrayList<>(emaSlowSeries.size());
        for (int i = 0; i < emaSlowSeries.size(); i++) {
            difSeries.add(emaFastSeries.get(i + start).subtract(emaSlowSeries.get(i)));
        }
        List<BigDecimal> deaSeries = emaSeries(difSeries, signal);

        BigDecimal dif = difSeries.getLast().setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal dea = deaSeries.getLast().setScale(SCALE, RoundingMode.HALF_UP);
        // × 2 是国内画法惯例；柱状图只用来看方向和强度，绝对值对策略无意义
        BigDecimal hist = dif.subtract(dea).multiply(BigDecimal.valueOf(2)).setScale(SCALE, RoundingMode.HALF_UP);

        r.put("macd_dif", dif);
        r.put("macd_dea", dea);
        r.put("macd_hist", hist);

        // MACD 柱状图趋势：看连续几期 hist 是在放大还是收缩（动能变化方向，不是视觉长度）
        int histLen = Math.min(deaSeries.size(), difSeries.size());
        if (histLen >= 3) {
            // 再手工算一条 hist 序列，因为 difSeries 和 deaSeries 长度不一定同步
            List<BigDecimal> histSeries = new ArrayList<>();
            int offset = difSeries.size() - histLen;
            int deaOffset = deaSeries.size() - histLen;
            for (int i = 0; i < histLen; i++) {
                histSeries.add(difSeries.get(i + offset).subtract(deaSeries.get(i + deaOffset))
                        .multiply(BigDecimal.valueOf(2)));
            }
            r.put("macd_hist_trend", trendSummary(histSeries, 5));

            // 金叉 / 死叉检测：对比最后两期 dif 与 dea 的相对位置是否翻转
            if (difSeries.size() >= 2 && deaSeries.size() >= 2) {
                BigDecimal prevDif = difSeries.get(difSeries.size() - 2);
                BigDecimal prevDea = deaSeries.get(deaSeries.size() - 2);
                boolean wasBelow = prevDif.compareTo(prevDea) <= 0;
                boolean nowAbove = dif.compareTo(dea) > 0;
                boolean wasAbove = prevDif.compareTo(prevDea) >= 0;
                boolean nowBelow = dif.compareTo(dea) < 0;
                if (wasBelow && nowAbove) r.put("macd_cross", "golden");
                else if (wasAbove && nowBelow) r.put("macd_cross", "death");
            }
        }
        return r;
    }

    // ==================== 8. 布林带 ====================

    /**
     * 布林带 (Bollinger Bands) 的上 / 中 / 下轨。
     *
     * <p>中轨 = period 期 SMA；上下轨 = 中轨 ± mult × period 期标准差。
     * 标准差算法用总体标准差（除 n，不是 n-1），和大多数交易软件一致。</p>
     *
     * @return 含 {@code upper / mid / lower} 三个 key 的 Map；样本不足返回空 Map
     */
    static Map<String, BigDecimal> boll(List<BigDecimal> closes, int period, int mult) {
        BigDecimal mid = ma(closes, period);
        if (mid == null) return Map.of();
        BigDecimal sumSq = BigDecimal.ZERO;
        for (int i = closes.size() - period; i < closes.size(); i++) {
            BigDecimal diff = closes.get(i).subtract(mid);
            sumSq = sumSq.add(diff.multiply(diff));
        }
        BigDecimal variance = sumSq.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
        // 开方走 double 足够：收盘价本身已是有限精度，平方根误差远小于价格 tick
        BigDecimal std = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
        BigDecimal band = std.multiply(BigDecimal.valueOf(mult));
        return Map.of("upper", mid.add(band).setScale(SCALE, RoundingMode.HALF_UP),
                "mid", mid,
                "lower", mid.subtract(band).setScale(SCALE, RoundingMode.HALF_UP));
    }

    /**
     * 布林带 %B：把收盘价在上下轨之间的位置归一到 0..100。
     *
     * <ul>
     *   <li>0 = 贴下轨（可能超卖）</li>
     *   <li>50 = 中轨附近</li>
     *   <li>100 = 贴上轨（可能超买）</li>
     *   <li>&gt;100 / &lt;0 = 价格突破上 / 下轨外（罕见的强势 / 弱势）</li>
     * </ul>
     *
     * <p>上下轨相等（带宽收敛至 0）时返回 50 避免除零。</p>
     */
    static BigDecimal bollPercentB(BigDecimal close, BigDecimal upper, BigDecimal lower) {
        BigDecimal range = upper.subtract(lower);
        if (range.signum() == 0) return BigDecimal.valueOf(50);
        return close.subtract(lower).divide(range, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 布林带带宽：{@code (upper − lower) / mid × 100}。
     *
     * <p>数值大 = 波动率高、趋势展开；数值小 = 收敛、可能即将变盘。
     * 中轨为 0 时返回 0 避免除零。</p>
     */
    static BigDecimal bollBandwidth(BigDecimal upper, BigDecimal lower, BigDecimal mid) {
        if (mid.signum() == 0) return BigDecimal.ZERO;
        return upper.subtract(lower).divide(mid, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    // ==================== 9. ATR (Wilder/RMA) ====================

    /**
     * ATR (Average True Range) 平均真实波幅，用 Wilder / RMA 平滑。
     *
     * <p>True Range (TR) 取以下三者最大值：</p>
     * <ul>
     *   <li>当前 bar {@code high − low}</li>
     *   <li>{@code |当前 high − 前一根 close|}（覆盖跨 bar 向上跳变）</li>
     *   <li>{@code |当前 low − 前一根 close|}（覆盖跨 bar 向下跳变）</li>
     * </ul>
     *
     * <p>ATR 代表"正常波动幅度"，常用于计算止损距离（SL = 入场价 ± N×ATR）。</p>
     *
     * @return 数据不足 period+1 返回 null
     */
    static BigDecimal atr(List<BigDecimal> highs, List<BigDecimal> lows, List<BigDecimal> closes, int period) {
        if (closes.size() < period + 1) return null;
        // 先算每根 bar 的 TR
        List<BigDecimal> trs = new ArrayList<>();
        for (int i = 1; i < closes.size(); i++) {
            BigDecimal hl = highs.get(i).subtract(lows.get(i)).abs();
            BigDecimal hc = highs.get(i).subtract(closes.get(i - 1)).abs();
            BigDecimal lc = lows.get(i).subtract(closes.get(i - 1)).abs();
            trs.add(hl.max(hc).max(lc));
        }
        // 再用 RMA 平滑 TR 序列，取最后一个值
        List<BigDecimal> atrSeries = rmaSeries(trs, period);
        return atrSeries.isEmpty() ? null : atrSeries.getLast();
    }

    // ==================== 10. KDJ (随机指标) ====================

    /**
     * KDJ 随机指标（采用国内股票软件的 KDJ 平滑约定）。
     *
     * <p>计算步骤：</p>
     * <ol>
     *   <li>RSV = {@code (close − n 期最低) / (n 期最高 − n 期最低) × 100}</li>
     *   <li>K = KDJ_SMA(RSV, m1)：{@code K(t) = (RSV + (m1 − 1) × K(t − 1)) / m1}</li>
     *   <li>D = KDJ_SMA(K, m2) （对 K 再平滑一次）</li>
     *   <li>J = 3K − 2D（放大 K / D 差距，作为超买超卖早期信号）</li>
     * </ol>
     *
     * <p>⚠️ 这里的 KDJ_SMA 是国内通达信 / 同花顺 KDJ 惯例的"SMA"，
     * 和国际上 SMA = 简单算术平均的含义不同；上面的递推公式本质等价于
     * 一阶 Wilder / RMA 平滑（权重 {@code 1/m1}），不是算术均值。</p>
     *
     * <p>K / D 的初始值都设为 50（传统做法，避免初值偏置影响前面几期结果）。</p>
     *
     * @return 含 {@code k / d / j} 三个键；样本不足 n 返回空 Map
     */
    static Map<String, BigDecimal> kdj(List<BigDecimal> highs, List<BigDecimal> lows,
                                       List<BigDecimal> closes, int n, int m1, int m2) {
        if (closes.size() < n) return Map.of();

        // --- Step 1: 构建 RSV 序列 ---
        List<BigDecimal> rsvList = new ArrayList<>();
        for (int i = n - 1; i < closes.size(); i++) {
            // 找 n 期内的最高价 hh / 最低价 ll
            BigDecimal hh = highs.get(i), ll = lows.get(i);
            for (int j = i - n + 1; j <= i; j++) {
                if (highs.get(j).compareTo(hh) > 0) hh = highs.get(j);
                if (lows.get(j).compareTo(ll) < 0) ll = lows.get(j);
            }
            BigDecimal range = hh.subtract(ll);
            // range == 0 表示 n 期内完全没波动，RSV 约定为 50
            BigDecimal rsv = range.signum() == 0 ? BigDecimal.valueOf(50) :
                    closes.get(i).subtract(ll).divide(range, SCALE, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
            rsvList.add(rsv);
        }

        // --- Step 2: 逐点迭代 K / D（J 只是最终用 3K-2D 派生） ---
        BigDecimal k = BigDecimal.valueOf(50);
        BigDecimal d = BigDecimal.valueOf(50);
        BigDecimal m1d = BigDecimal.valueOf(m1);
        BigDecimal m2d = BigDecimal.valueOf(m2);
        BigDecimal m1m1 = BigDecimal.valueOf(m1 - 1);
        BigDecimal m2m1 = BigDecimal.valueOf(m2 - 1);

        for (BigDecimal rsv : rsvList) {
            k = rsv.add(m1m1.multiply(k)).divide(m1d, SCALE, RoundingMode.HALF_UP);
            d = k.add(m2m1.multiply(d)).divide(m2d, SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal j = k.multiply(BigDecimal.valueOf(3)).subtract(d.multiply(BigDecimal.valueOf(2)));

        return Map.of("k", k.setScale(2, RoundingMode.HALF_UP),
                "d", d.setScale(2, RoundingMode.HALF_UP),
                "j", j.setScale(2, RoundingMode.HALF_UP));
    }

    // ==================== 11. ADX (+DI / -DI) ====================

    /**
     * ADX 趋势强度 + 方向线 +DI / -DI。
     *
     * <p>计算流程：</p>
     * <ol>
     *   <li>+DM / -DM：相对昨日的"方向性移动"（涨了多少 vs 跌了多少，只算占优的那一方）</li>
     *   <li>TR：真实波幅（公式同 ATR）</li>
     *   <li>对 +DM、-DM、TR 各做 RMA 平滑</li>
     *   <li>+DI = 平滑+DM / 平滑TR × 100；-DI 同理</li>
     *   <li>DX = {@code |+DI − -DI| / (+DI + -DI) × 100}（方向差异强度）</li>
     *   <li>ADX = RMA(DX, period)（对 DX 再平滑一次，就是最终"趋势强度"）</li>
     * </ol>
     *
     * <p>实战参考：</p>
     * <ul>
     *   <li>ADX &gt; 25：有明显趋势，适合追势</li>
     *   <li>ADX &lt; 15：震荡市，适合高抛低吸</li>
     *   <li>+DI &gt; -DI 看多，反之看空；差距越大趋势越强</li>
     * </ul>
     *
     * @return 含 {@code adx / plus_di / minus_di} 三个键；样本不足 {@code period*2+1} 返回空 Map
     */
    static Map<String, BigDecimal> adx(List<BigDecimal> highs, List<BigDecimal> lows,
                                       List<BigDecimal> closes, int period) {
        if (closes.size() < period * 2 + 1) return Map.of();

        // --- Step 1: 构建 +DM / -DM / TR 三条并行序列 ---
        List<BigDecimal> plusDM = new ArrayList<>();
        List<BigDecimal> minusDM = new ArrayList<>();
        List<BigDecimal> tr = new ArrayList<>();

        for (int i = 1; i < closes.size(); i++) {
            BigDecimal upMove = highs.get(i).subtract(highs.get(i - 1));
            BigDecimal downMove = lows.get(i - 1).subtract(lows.get(i));

            // 标准 Wilder 规则：谁大谁才"算数"，另一边归 0；负数方向直接归 0
            plusDM.add(upMove.compareTo(downMove) > 0 && upMove.signum() > 0 ? upMove : BigDecimal.ZERO);
            minusDM.add(downMove.compareTo(upMove) > 0 && downMove.signum() > 0 ? downMove : BigDecimal.ZERO);

            BigDecimal hl = highs.get(i).subtract(lows.get(i)).abs();
            BigDecimal hc = highs.get(i).subtract(closes.get(i - 1)).abs();
            BigDecimal lc = lows.get(i).subtract(closes.get(i - 1)).abs();
            tr.add(hl.max(hc).max(lc));
        }

        // --- Step 2: 三条序列分别做 RMA 平滑 ---
        List<BigDecimal> atrSeries = rmaSeries(tr, period);
        List<BigDecimal> plusDmSmooth = rmaSeries(plusDM, period);
        List<BigDecimal> minusDmSmooth = rmaSeries(minusDM, period);

        int len = Math.min(atrSeries.size(), Math.min(plusDmSmooth.size(), minusDmSmooth.size()));
        if (len < period) return Map.of();

        // --- Step 3: 逐点算 +DI / -DI / DX，同时记录最后一期 DI 作为输出 ---
        List<BigDecimal> dxList = new ArrayList<>();
        BigDecimal lastPlusDi = BigDecimal.ZERO, lastMinusDi = BigDecimal.ZERO;

        for (int i = 0; i < len; i++) {
            BigDecimal atrVal = atrSeries.get(i);
            if (atrVal.signum() == 0) {
                // ATR=0 意味着完全没波动，DI 无意义，DX 归 0
                dxList.add(BigDecimal.ZERO);
                continue;
            }
            BigDecimal pdi = plusDmSmooth.get(i).divide(atrVal, SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            BigDecimal mdi = minusDmSmooth.get(i).divide(atrVal, SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            BigDecimal diSum = pdi.add(mdi);
            BigDecimal dx = diSum.signum() == 0 ? BigDecimal.ZERO :
                    pdi.subtract(mdi).abs().divide(diSum, SCALE, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
            dxList.add(dx);
            lastPlusDi = pdi;
            lastMinusDi = mdi;
        }

        // --- Step 4: 对 DX 再做一次 RMA 得到 ADX ---
        List<BigDecimal> adxSeries = rmaSeries(dxList, period);
        if (adxSeries.isEmpty()) return Map.of();

        return Map.of(
                "adx", adxSeries.getLast().setScale(2, RoundingMode.HALF_UP),
                "plus_di", lastPlusDi.setScale(2, RoundingMode.HALF_UP),
                "minus_di", lastMinusDi.setScale(2, RoundingMode.HALF_UP));
    }

    // ==================== 12. OBV (能量潮) ====================

    /**
     * OBV (On Balance Volume) 能量潮，返回完整序列。
     *
     * <p>规则（当前 bar 对比前一根收盘）：</p>
     * <ul>
     *   <li>当前收盘 &gt; 前一根收盘：OBV += 当前 volume</li>
     *   <li>当前收盘 &lt; 前一根收盘：OBV -= 当前 volume</li>
     *   <li>相等：OBV 不变</li>
     * </ul>
     *
     * <p>OBV 的绝对值无意义，重点看方向和"量价背离"——价格创新高但 OBV 没跟上，
     * 通常是趋势疲弱的信号。</p>
     *
     * @return 首元素为 0（第一天无从判断涨跌）；closes 不足 2 期返回空 List
     */
    static List<BigDecimal> obv(List<BigDecimal> closes, List<BigDecimal> volumes) {
        if (closes.size() < 2) return List.of();
        List<BigDecimal> result = new ArrayList<>();
        BigDecimal obvVal = BigDecimal.ZERO;
        result.add(obvVal);
        for (int i = 1; i < closes.size(); i++) {
            int cmp = closes.get(i).compareTo(closes.get(i - 1));
            if (cmp > 0) obvVal = obvVal.add(volumes.get(i));
            else if (cmp < 0) obvVal = obvVal.subtract(volumes.get(i));
            result.add(obvVal);
        }
        return result;
    }

    // ==================== 13. 成交量 ====================

    /**
     * 量比：{@code 最新一期 volume / 最近 period 期平均 volume}。
     *
     * <p>经验阈值：</p>
     * <ul>
     *   <li>&gt; 1.5 ：放量</li>
     *   <li>0.8 ~ 1.2 ：常规</li>
     *   <li>&lt; 0.5 ：缩量</li>
     * </ul>
     *
     * <p>样本数约束：当前实现要求 {@code volumes.size() >= period + 1}，比 {@link #ma}
     * 更保守；实际分母仍只使用最近 {@code period} 期均量。</p>
     *
     * @return 样本不足或均量为 0 时返回 null
     */
    static BigDecimal volumeRatio(List<BigDecimal> volumes, int period) {
        if (volumes.size() < period + 1) return null;
        BigDecimal avg = ma(volumes, period);
        if (avg == null || avg.signum() == 0) return null;
        return volumes.getLast().divide(avg, 2, RoundingMode.HALF_UP);
    }

    /**
     * 最近 {@code recent} 根"已收盘" bar 的量比序列。
     *
     * <p>与 {@link #volumeRatio} 的关键区别：</p>
     * <ol>
     *   <li>排除最后一根（通常还未收盘）的污染</li>
     *   <li>对每根已收盘 bar 独立计算量比 —— 分母是它当时的 period 期均量，窗口随 bar 滚动</li>
     *   <li>返回序列按时间升序</li>
     * </ol>
     *
     * <p>下游使用场景：判断"最近几根已收盘 bar 的量能结构"，
     * 例如突破时量能持续放大 / 趋势中量能持续衰减。</p>
     *
     * @param volumes 原始成交量序列
     * @param period  每根 bar 算量比时的均量窗口（通常 20）
     * @param recent  想看最近多少根已收盘 bar
     * @return 参数非法、样本不足、或所有 bar 的均量都为 0 时返回空 List
     */
    static List<BigDecimal> closedVolumeRatioSeries(List<BigDecimal> volumes, int period, int recent) {
        if (volumes == null || period <= 0 || recent <= 0 || volumes.size() <= period) {
            return List.of();
        }
        // 去掉最后一根未收盘 bar；closedSize 是"已收盘 bar 的个数"
        int closedSize = volumes.size() - 1;
        if (closedSize < period) {
            return List.of();
        }
        // 起点：保证窗口内有足够 period 个 bar，且最多只回看 recent 根
        int start = Math.max(period - 1, closedSize - recent);
        List<BigDecimal> result = new ArrayList<>();
        for (int i = start; i < closedSize; i++) {
            // 对 [i-period+1, i] 累加求均量
            BigDecimal sum = BigDecimal.ZERO;
            for (int j = i - period + 1; j <= i; j++) {
                BigDecimal volume = volumes.get(j);
                if (volume != null) {
                    sum = sum.add(volume);
                }
            }
            BigDecimal avg = sum.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
            if (avg.signum() == 0 || volumes.get(i) == null) {
                // 均量为 0 或当期 volume 缺失则跳过，不硬塞一个伪造值
                continue;
            }
            result.add(volumes.get(i).divide(avg, 2, RoundingMode.HALF_UP));
        }
        return result;
    }

    // ==================== 14. K 线解析 ====================

    /**
     * 解析 Binance 现货 / 合约 K 线 JSON。
     *
     * <p>Binance 原始数组字段顺序：</p>
     * <pre>
     * [0] openTime   [1] open    [2] high    [3] low     [4] close
     * [5] volume     [6] closeTime [7] quoteVol [8] trades
     * [9] takerBuyBaseVol   [10] takerBuyQuoteVol   [11] ignore
     * </pre>
     *
     * <p>本方法只抽取下游会用到的 5 个字段，按以下顺序组成 BigDecimal 数组：</p>
     * <pre>
     * [0] High  [1] Low  [2] Close  [3] Volume  [4] TakerBuyBaseAssetVolume
     * </pre>
     *
     * <p>注意：{@link #calcAll} 只用前 4 个字段（H/L/C/V），
     * TakerBuy 字段由 {@link #takerBuyRatio} 单独消费。</p>
     */
    public static List<BigDecimal[]> parseKlines(String json) {
        if (json == null || json.isBlank()) return List.of();
        JSONArray arr = JSON.parseArray(json);
        List<BigDecimal[]> result = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JSONArray k = arr.getJSONArray(i);
            // 老版本接口可能没有 index 9（takerBuyBaseVol），做一次防御
            BigDecimal takerBuyVol = k.size() > 9 ? new BigDecimal(k.getString(9)) : BigDecimal.ZERO;
            result.add(new BigDecimal[]{
                    new BigDecimal(k.getString(2)),  // [0] High
                    new BigDecimal(k.getString(3)),  // [1] Low
                    new BigDecimal(k.getString(4)),  // [2] Close
                    new BigDecimal(k.getString(5)),  // [3] Volume
                    takerBuyVol                      // [4] TakerBuyBaseAssetVolume
            });
        }
        return result;
    }

    // ==================== 15. 主动买卖占比 ====================

    /**
     * 最近 {@code bars} 根 K 线的主动买占总成交量比例（Taker Buy Ratio）。
     *
     * <p>"Taker" = 吃对手挂单的一方：主动买单吃卖盘 → taker buy，主动卖单吃买盘 → taker sell。
     * 比值反映主动买盘的强度：</p>
     * <ul>
     *   <li>&gt; 0.55 ：主动买偏多，短线偏多头</li>
     *   <li>0.45 ~ 0.55 ：均衡</li>
     *   <li>&lt; 0.45 ：主动卖偏多，短线偏空头</li>
     * </ul>
     *
     * @param klines 由 {@link #parseKlines} 解析的 K 线（每行需 ≥ 5 个字段）
     * @param bars   回看根数
     * @return 比例 [0, 1]；样本不足、字段缺失或总量为 0 时返回中性值 0.5
     */
    public static double takerBuyRatio(List<BigDecimal[]> klines, int bars) {
        if (klines == null || klines.size() < bars) return 0.5;
        BigDecimal totalVol = BigDecimal.ZERO;
        BigDecimal totalTakerBuy = BigDecimal.ZERO;
        for (int i = klines.size() - bars; i < klines.size(); i++) {
            BigDecimal[] k = klines.get(i);
            // 防御：某些历史数据可能没 takerBuyVol 字段（只有 4 列），返回中性
            if (k.length < 5) return 0.5;
            totalVol = totalVol.add(k[3]);
            totalTakerBuy = totalTakerBuy.add(k[4]);
        }
        // 无成交量视为静止市场，返回中性
        if (totalVol.signum() == 0) return 0.5;
        return totalTakerBuy.divide(totalVol, SCALE, RoundingMode.HALF_UP).doubleValue();
    }
}
