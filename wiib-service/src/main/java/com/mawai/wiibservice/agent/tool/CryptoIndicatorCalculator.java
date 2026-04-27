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
 * <h2>指标说明</h2>
 * <table border="1">
 * <tr><th>字段</th><th>名称</th><th>说明</th></tr>
 * <tr><td>ma7 / ma25 / ma99</td><td>简单移动平均线</td>
 * <td>MA7最敏感，MA25中期，MA99长期。MA7&gt;MA25&gt;MA99多头排列看涨，反之空头排列</td></tr>
 * <tr><td>ema12 / ema26</td><td>指数移动平均线</td>
 * <td>比MA更灵敏，EMA12&gt;EMA26金叉看涨，死叉看跌</td></tr>
 * <tr><td>ma_alignment</td><td>均线排列</td>
 * <td>1=多头排列(上涨趋势)，-1=空头排列(下跌趋势)，0=纠缠(震荡)</td></tr>
 * <tr><td>rsi14</td><td>相对强弱指数</td>
 * <td>0-100。&gt;70超买可能回落，&lt;30超卖可能反弹。需配合trend判断动量方向</td></tr>
 * <tr><td>rsi14_trend</td><td>RSI趋势摘要</td>
 * <td>rising_n=连续n期上涨，falling_n=连续n期下跌，mostly_up/down=多数上涨/下跌，sideways=震荡</td></tr>
 * <tr><td>macd_dif / macd_dea</td><td>MACD快线/慢线</td>
 * <td>DIF&gt;DEA金叉看涨，DIF&lt;DEA死叉看跌</td></tr>
 * <tr><td>macd_hist</td><td>MACD柱状图</td>
 * <td>正值柱看涨，负值柱看跌。HIST上升=动能改善，下降=动能恶化</td></tr>
 * <tr><td>macd_cross</td><td>MACD交叉信号</td>
 * <td>golden=刚发生金叉，death=刚发生死叉</td></tr>
 * <tr><td>macd_hist_trend</td><td>MACD柱状图趋势</td>
 * <td>rising_5=连续5期动能改善，falling_5=连续5期动能恶化</td></tr>
 * <tr><td>boll_upper / boll_mid / boll_lower</td><td>布林带上/中/下轨</td>
 * <td>上轨阻力，下轨支撑，中轨=动态均线。价格触上轨回落概率大</td></tr>
 * <tr><td>boll_pb</td><td>布林带百分比</td>
 * <td>0-100。0=价格贴下轨，100=贴上轨，50=在中轨附近。判断价格相对位置</td></tr>
 * <tr><td>boll_bandwidth</td><td>布林带宽度</td>
 * <td>数值小=收敛(即将变盘)，数值大=扩张(趋势延续)</td></tr>
 * <tr><td>atr14</td><td>平均真实波幅</td>
 * <td>衡量波动率大小。用于计算合理止损距离（入场价±2ATR）</td></tr>
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
 * </table>
 */
public class CryptoIndicatorCalculator {

    private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);
    private static final int SCALE = 8;

    // ==================== 综合计算 ====================

    public static Map<String, Object> calcAll(List<BigDecimal[]> klines) {
        if (klines == null || klines.size() < 30) return Map.of("error", "K线数据不足");

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

        // --- 均线 ---
        r.put("ma7", ma(closes, 7));
        r.put("ma25", ma(closes, 25));
        if (size >= 99) r.put("ma99", ma(closes, 99));
        r.put("ema12", ema(closes, 12));
        r.put("ema20", ema(closes, 20));
        r.put("ema26", ema(closes, 26));

        // 均线排列: 1=多头(ma7>ma25>ma99), -1=空头, 0=纠缠
        r.put("ma_alignment", maAlignment(closes));

        // --- RSI ---
        List<BigDecimal> rsiSeries = rsiSeries(closes, 14);
        if (!rsiSeries.isEmpty()) {
            r.put("rsi14", rsiSeries.getLast());
            r.put("rsi14_trend", trendSummary(rsiSeries, 5));
        }

        // --- MACD ---
        Map<String, Object> macdResult = macdFull(closes, 12, 26, 9);
        r.putAll(macdResult);

        // --- 布林带 ---
        Map<String, BigDecimal> boll = boll(closes, 20, 2);
        if (!boll.isEmpty()) {
            r.put("boll_upper", boll.get("upper"));
            r.put("boll_mid", boll.get("mid"));
            r.put("boll_lower", boll.get("lower"));
            BigDecimal curClose = closes.getLast();
            r.put("boll_pb", bollPercentB(curClose, boll.get("upper"), boll.get("lower")));
            r.put("boll_bandwidth", bollBandwidth(boll.get("upper"), boll.get("lower"), boll.get("mid")));
        }

        // --- ATR (Wilder/RMA) ---
        r.put("atr14", atr(highs, lows, closes, 14));

        // --- KDJ ---
        Map<String, BigDecimal> kdj = kdj(highs, lows, closes, 9, 3, 3);
        if (!kdj.isEmpty()) {
            r.put("kdj_k", kdj.get("k"));
            r.put("kdj_d", kdj.get("d"));
            r.put("kdj_j", kdj.get("j"));
        }

        // --- ADX ---
        Map<String, BigDecimal> adxResult = adx(highs, lows, closes, 14);
        if (!adxResult.isEmpty()) {
            r.put("adx", adxResult.get("adx"));
            r.put("plus_di", adxResult.get("plus_di"));
            r.put("minus_di", adxResult.get("minus_di"));
        }

        // --- OBV ---
        List<BigDecimal> obvSeries = obv(closes, volumes);
        if (!obvSeries.isEmpty()) {
            r.put("obv", obvSeries.getLast());
            BigDecimal obvMa = ma(obvSeries, Math.min(20, obvSeries.size()));
            r.put("obv_ma20", obvMa);
            r.put("obv_trend", trendSummary(obvSeries, 5));
        }

        // --- 成交量 ---
        r.put("volume_ma20", ma(volumes, Math.min(20, size)));
        r.put("volume_ratio", volumeRatio(volumes, 20));

        // --- 价格动量摘要 ---
        r.put("close_trend", trendSummary(closes, 5));

        return r;
    }

    // ==================== 价格变化 (从Node层调用) ====================

    public static BigDecimal pctChange(BigDecimal from, BigDecimal to) {
        if (from == null || from.signum() == 0) return null;
        return to.subtract(from).divide(from, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    // ==================== 趋势摘要 ====================

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

    // ==================== 均线排列 ====================

    static int maAlignment(List<BigDecimal> closes) {
        BigDecimal m7 = ma(closes, 7);
        BigDecimal m25 = ma(closes, 25);
        if (m7 == null || m25 == null) return 0;
        BigDecimal m99 = closes.size() >= 99 ? ma(closes, 99) : null;
        if (m99 != null) {
            if (m7.compareTo(m25) > 0 && m25.compareTo(m99) > 0) return 1;
            if (m7.compareTo(m25) < 0 && m25.compareTo(m99) < 0) return -1;
        } else {
            if (m7.compareTo(m25) > 0) return 1;
            if (m7.compareTo(m25) < 0) return -1;
        }
        return 0;
    }

    // ==================== MA / EMA ====================

    static BigDecimal ma(List<BigDecimal> data, int period) {
        if (data.size() < period) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = data.size() - period; i < data.size(); i++) sum = sum.add(data.get(i));
        return sum.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
    }

    static BigDecimal ema(List<BigDecimal> data, int period) {
        if (data.size() < period) return null;
        BigDecimal k = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(period + 1), SCALE, RoundingMode.HALF_UP);
        BigDecimal oneMinusK = BigDecimal.ONE.subtract(k);
        BigDecimal emaVal = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) emaVal = emaVal.add(data.get(i));
        emaVal = emaVal.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
        for (int i = period; i < data.size(); i++) {
            emaVal = data.get(i).multiply(k, MC).add(emaVal.multiply(oneMinusK, MC));
        }
        return emaVal.setScale(SCALE, RoundingMode.HALF_UP);
    }

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

    // Wilder/RMA smoothing: rma = prev * (period-1)/period + cur/period
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

    // ==================== RSI ====================

    static List<BigDecimal> rsiSeries(List<BigDecimal> closes, int period) {
        if (closes.size() < period + 1) return List.of();
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

    private static BigDecimal calcRsiValue(BigDecimal avgGain, BigDecimal avgLoss) {
        if (avgLoss.signum() == 0) return BigDecimal.valueOf(100);
        BigDecimal rs = avgGain.divide(avgLoss, SCALE, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(100).subtract(
                BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), 2, RoundingMode.HALF_UP));
    }

    // ==================== MACD (含趋势) ====================

    static Map<String, Object> macdFull(List<BigDecimal> closes, int fast, int slow, int signal) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (closes.size() < slow + signal) {
            r.put("macd_dif", BigDecimal.ZERO);
            r.put("macd_dea", BigDecimal.ZERO);
            r.put("macd_hist", BigDecimal.ZERO);
            return r;
        }
        List<BigDecimal> emaFastSeries = emaSeries(closes, fast);
        List<BigDecimal> emaSlowSeries = emaSeries(closes, slow);
        int start = emaFastSeries.size() - emaSlowSeries.size();
        List<BigDecimal> difSeries = new ArrayList<>(emaSlowSeries.size());
        for (int i = 0; i < emaSlowSeries.size(); i++) {
            difSeries.add(emaFastSeries.get(i + start).subtract(emaSlowSeries.get(i)));
        }
        List<BigDecimal> deaSeries = emaSeries(difSeries, signal);

        BigDecimal dif = difSeries.getLast().setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal dea = deaSeries.getLast().setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal hist = dif.subtract(dea).multiply(BigDecimal.valueOf(2)).setScale(SCALE, RoundingMode.HALF_UP);

        r.put("macd_dif", dif);
        r.put("macd_dea", dea);
        r.put("macd_hist", hist);

        // MACD柱状图趋势: 连续动能改善or恶化（数值变化方向，非视觉长度）
        int histLen = Math.min(deaSeries.size(), difSeries.size());
        if (histLen >= 3) {
            List<BigDecimal> histSeries = new ArrayList<>();
            int offset = difSeries.size() - histLen;
            int deaOffset = deaSeries.size() - histLen;
            for (int i = 0; i < histLen; i++) {
                histSeries.add(difSeries.get(i + offset).subtract(deaSeries.get(i + deaOffset))
                        .multiply(BigDecimal.valueOf(2)));
            }
            r.put("macd_hist_trend", trendSummary(histSeries, 5));

            // 金叉/死叉检测: 看最后2期dif与dea的交叉
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

    // ==================== 布林带 ====================

    static Map<String, BigDecimal> boll(List<BigDecimal> closes, int period, int mult) {
        BigDecimal mid = ma(closes, period);
        if (mid == null) return Map.of();
        BigDecimal sumSq = BigDecimal.ZERO;
        for (int i = closes.size() - period; i < closes.size(); i++) {
            BigDecimal diff = closes.get(i).subtract(mid);
            sumSq = sumSq.add(diff.multiply(diff));
        }
        BigDecimal variance = sumSq.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
        BigDecimal std = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
        BigDecimal band = std.multiply(BigDecimal.valueOf(mult));
        return Map.of("upper", mid.add(band).setScale(SCALE, RoundingMode.HALF_UP),
                "mid", mid,
                "lower", mid.subtract(band).setScale(SCALE, RoundingMode.HALF_UP));
    }

    static BigDecimal bollPercentB(BigDecimal close, BigDecimal upper, BigDecimal lower) {
        BigDecimal range = upper.subtract(lower);
        if (range.signum() == 0) return BigDecimal.valueOf(50);
        return close.subtract(lower).divide(range, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal bollBandwidth(BigDecimal upper, BigDecimal lower, BigDecimal mid) {
        if (mid.signum() == 0) return BigDecimal.ZERO;
        return upper.subtract(lower).divide(mid, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    // ==================== ATR (Wilder/RMA) ====================

    static BigDecimal atr(List<BigDecimal> highs, List<BigDecimal> lows, List<BigDecimal> closes, int period) {
        if (closes.size() < period + 1) return null;
        List<BigDecimal> trs = new ArrayList<>();
        for (int i = 1; i < closes.size(); i++) {
            BigDecimal hl = highs.get(i).subtract(lows.get(i)).abs();
            BigDecimal hc = highs.get(i).subtract(closes.get(i - 1)).abs();
            BigDecimal lc = lows.get(i).subtract(closes.get(i - 1)).abs();
            trs.add(hl.max(hc).max(lc));
        }
        List<BigDecimal> atrSeries = rmaSeries(trs, period);
        return atrSeries.isEmpty() ? null : atrSeries.getLast();
    }

    // ==================== KDJ (随机指标) ====================

    static Map<String, BigDecimal> kdj(List<BigDecimal> highs, List<BigDecimal> lows,
                                       List<BigDecimal> closes, int n, int m1, int m2) {
        if (closes.size() < n) return Map.of();

        // RSV序列
        List<BigDecimal> rsvList = new ArrayList<>();
        for (int i = n - 1; i < closes.size(); i++) {
            BigDecimal hh = highs.get(i), ll = lows.get(i);
            for (int j = i - n + 1; j <= i; j++) {
                if (highs.get(j).compareTo(hh) > 0) hh = highs.get(j);
                if (lows.get(j).compareTo(ll) < 0) ll = lows.get(j);
            }
            BigDecimal range = hh.subtract(ll);
            BigDecimal rsv = range.signum() == 0 ? BigDecimal.valueOf(50) :
                    closes.get(i).subtract(ll).divide(range, SCALE, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
            rsvList.add(rsv);
        }

        // K = SMA(RSV, m1), D = SMA(K, m2), J = 3K - 2D
        // SMA平滑: K(t) = (RSV + (m1-1)*K(t-1)) / m1
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

    // ==================== ADX (+DI, -DI) ====================

    static Map<String, BigDecimal> adx(List<BigDecimal> highs, List<BigDecimal> lows,
                                       List<BigDecimal> closes, int period) {
        if (closes.size() < period * 2 + 1) return Map.of();

        List<BigDecimal> plusDM = new ArrayList<>();
        List<BigDecimal> minusDM = new ArrayList<>();
        List<BigDecimal> tr = new ArrayList<>();

        for (int i = 1; i < closes.size(); i++) {
            BigDecimal upMove = highs.get(i).subtract(highs.get(i - 1));
            BigDecimal downMove = lows.get(i - 1).subtract(lows.get(i));

            plusDM.add(upMove.compareTo(downMove) > 0 && upMove.signum() > 0 ? upMove : BigDecimal.ZERO);
            minusDM.add(downMove.compareTo(upMove) > 0 && downMove.signum() > 0 ? downMove : BigDecimal.ZERO);

            BigDecimal hl = highs.get(i).subtract(lows.get(i)).abs();
            BigDecimal hc = highs.get(i).subtract(closes.get(i - 1)).abs();
            BigDecimal lc = lows.get(i).subtract(closes.get(i - 1)).abs();
            tr.add(hl.max(hc).max(lc));
        }

        List<BigDecimal> atrSeries = rmaSeries(tr, period);
        List<BigDecimal> plusDmSmooth = rmaSeries(plusDM, period);
        List<BigDecimal> minusDmSmooth = rmaSeries(minusDM, period);

        int len = Math.min(atrSeries.size(), Math.min(plusDmSmooth.size(), minusDmSmooth.size()));
        if (len < period) return Map.of();

        List<BigDecimal> dxList = new ArrayList<>();
        BigDecimal lastPlusDi = BigDecimal.ZERO, lastMinusDi = BigDecimal.ZERO;

        for (int i = 0; i < len; i++) {
            BigDecimal atrVal = atrSeries.get(i);
            if (atrVal.signum() == 0) {
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

        List<BigDecimal> adxSeries = rmaSeries(dxList, period);
        if (adxSeries.isEmpty()) return Map.of();

        return Map.of(
                "adx", adxSeries.getLast().setScale(2, RoundingMode.HALF_UP),
                "plus_di", lastPlusDi.setScale(2, RoundingMode.HALF_UP),
                "minus_di", lastMinusDi.setScale(2, RoundingMode.HALF_UP));
    }

    // ==================== OBV ====================

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

    // ==================== 成交量比率 ====================

    static BigDecimal volumeRatio(List<BigDecimal> volumes, int period) {
        if (volumes.size() < period + 1) return null;
        BigDecimal avg = ma(volumes, period);
        if (avg == null || avg.signum() == 0) return null;
        return volumes.getLast().divide(avg, 2, RoundingMode.HALF_UP);
    }

    // ==================== K线解析 ====================

    /**
     * 解析Binance K线JSON。
     * 返回 BigDecimal[5]: [High, Low, Close, Volume, TakerBuyVolume]
     * Binance原始字段: [0]openTime [1]open [2]high [3]low [4]close [5]volume
     *                  [6]closeTime [7]quoteVol [8]trades [9]takerBuyBaseVol ...
     */
    public static List<BigDecimal[]> parseKlines(String json) {
        if (json == null || json.isBlank()) return List.of();
        JSONArray arr = JSON.parseArray(json);
        List<BigDecimal[]> result = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JSONArray k = arr.getJSONArray(i);
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

    /**
     * 计算近N根K线的taker buy ratio（主动买占比）。
     * ratio > 0.5 表示主动买多于主动卖，偏多头。
     *
     * @param klines 解析后的K线（需包含index 3=Volume, 4=TakerBuyVol）
     * @param bars   回看根数
     * @return taker buy ratio [0,1]，0.5为中性；数据不足返回0.5
     */
    public static double takerBuyRatio(List<BigDecimal[]> klines, int bars) {
        if (klines == null || klines.size() < bars) return 0.5;
        BigDecimal totalVol = BigDecimal.ZERO;
        BigDecimal totalTakerBuy = BigDecimal.ZERO;
        for (int i = klines.size() - bars; i < klines.size(); i++) {
            BigDecimal[] k = klines.get(i);
            if (k.length < 5) return 0.5;
            totalVol = totalVol.add(k[3]);
            totalTakerBuy = totalTakerBuy.add(k[4]);
        }
        if (totalVol.signum() == 0) return 0.5;
        return totalTakerBuy.divide(totalVol, SCALE, RoundingMode.HALF_UP).doubleValue();
    }
}
