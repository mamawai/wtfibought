package com.mawai.wiibservice.agent.trading.runtime;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import com.mawai.wiibcommon.enums.KlineInterval;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 单轮交易判断使用的市场快照。
 * 只在执行器入口解析一次，开仓/平仓引擎共享同一份上下文。
 */
@Slf4j
public final class MarketContext {

    // 本轮使用的主决策周期，MaSlope 等自治策略需要据此选择阈值。
    public final KlineInterval decisionInterval;
    // 当前市场状态，用来区分趋势、震荡、挤压和冲击环境。
    public final String regime;
    // 市场状态切换信息，用于后续识别 regime 变化风险。
    public final String regimeTransition;
    // 主决策周期 ATR，作为 SL/TP 距离、盈利进度和低波动判断的基础。
    public final BigDecimal atr;
    // 主决策周期已闭合 ATR，MaSlope 用它和闭合 MA 序列保持同一口径。
    public final BigDecimal atrClosed;
    // true 表示 atr 来自 price*0.3% 兜底估算，只能给老保护逻辑兜底，新策略不能拿它开仓。
    public final boolean atrFromFallback;
    // 主决策周期 RSI，用于共振评分和均值回归反转确认。
    public final BigDecimal rsi;
    // 当前标记/成交参考价，用于计算止损、止盈、盈亏和仓位数量。
    public final BigDecimal price;
    // 1h 均线排列方向，用于大级别趋势过滤和持仓风险投票。
    public final Integer maAlignment1h;
    // 15m 均线排列方向，用于入场共振和中级别反向风险投票。
    public final Integer maAlignment15m;
    // 主决策周期均线排列方向，用于短线持仓反向风险投票。
    public final Integer maAlignment;
    // 主决策周期 MA7/MA25 当前值与闭合序列，用于 MaSlope 斜率状态机。
    public final BigDecimal ma7;
    public final BigDecimal ma25;
    public final List<BigDecimal> ma7SeriesClosed;
    public final List<BigDecimal> ma25SeriesClosed;
    public final List<BigDecimal> atrSeriesClosed;
    // 主周期最近一根闭合 K 线标识，用于退出端按 bar 计数，避免同一根 K 线内重复轮询累加。
    public final String lastClosedBarKey;
    // 确认周期 MA7/MA25 闭合序列。M3/M5 看 15m，M15 看 1h。
    public final BigDecimal confirmMa7;
    public final BigDecimal confirmMa25;
    public final List<BigDecimal> confirmMa7SeriesClosed;
    public final List<BigDecimal> confirmMa25SeriesClosed;
    public final BigDecimal confirmAtr;
    // 主决策周期 ADX/DI 与 ATR 突增指标，用于 MaSlope 硬闸门。
    public final Double adx;
    public final Double plusDi;
    public final Double minusDi;
    public final BigDecimal atrMean30Closed;
    public final Double atrSpikeRatio;
    // 主决策周期 MACD 金叉/死叉状态，用于方向确认和反向风险识别。
    public final String macdCross;
    // 主决策周期 MACD 柱趋势，用于判断动能是否支持当前方向。
    public final String macdHistTrend;
    // 主决策周期 MACD DIF 值，用于无交叉信号时补充判断多空动能。
    public final BigDecimal macdDif;
    // 主决策周期 MACD DEA 值，用于和 DIF 比较判断动能方向。
    public final BigDecimal macdDea;
    // 主决策周期 EMA20，用于共振评分里判断价格是否站在短期均线同侧。
    public final BigDecimal ema20;
    // 主决策周期布林 %B，用于识别突破位置和均值回归极值区。
    public final Double bollPb;
    // 主决策周期布林带宽，用于估算均值回归到中轨的目标空间。
    public final Double bollBandwidth;
    // 是否处于布林挤压状态，是突破路径的前置条件。
    public final boolean bollSqueeze;
    // 最近 5 根闭合 K 线布林带是否扩张，用于 MaSlope 软加分。
    public final boolean bollExpanding5;
    // 主决策周期成交量相对均量倍数，用于突破确认和共振评分。
    public final Double volumeRatio;
    // 近 5 根已闭合主决策周期 K 线量比，时间从早到晚；突破衰竭只看闭合窗口。
    public final List<Double> volumeRatioClosedSeries;
    // 主决策周期收盘价趋势，用于持仓期间判断价格是否反向推进。
    public final String closeTrend;
    // 近 3 根已闭合主决策周期 K 线收盘趋势；MR 失效只看闭合窗口。
    public final String closeTrendClosed3;
    // 最近闭合收盘价序列，用于判断是否回踩 MA7/MA25 后重新站回趋势侧。
    public final List<BigDecimal> closeSeriesClosed;
    // 最近闭合 K 线的收盘位置和波动结构；MaSlope 用来区分真突破和假斜率。
    public final Double closePositionClosed;
    public final Double rangeAtrClosed;
    public final boolean closeBreakoutHigh10Closed;
    public final boolean closeBreakdownLow10Closed;
    // 15m RSI，当前作为中周期指标保留，便于后续风控扩展。
    public final Double rsi15m;
    // 买卖盘不平衡度，用于微结构方向判断。
    public final Double bidAskImbalance;
    // 主动买卖压力，用于和买卖盘不平衡一起形成微结构票。
    public final Double takerPressure;
    // 持仓量变化率，当前解析保留，后续可用于杠杆拥挤风险。
    public final Double oiChangeRate;
    // 资金费率偏离度，当前解析保留，后续可用于拥挤和反转风险。
    public final Double fundingDeviation;
    // 资金费率相对历史极端度，MaSlope 用它做拥挤降权。
    public final Double fundingRateExtreme;
    // 多空比极值，用于识别单边拥挤和共振扣分风险。
    public final Double lsrExtreme;
    // 数据质量标记，如 LOW_CONFIDENCE、STALE_AGG_TRADE，会影响开仓和退出。
    public final List<String> qualityFlags;

    private MarketContext(KlineInterval decisionInterval,
                          String regime, String regimeTransition,
                          BigDecimal atr, BigDecimal atrClosed, boolean atrFromFallback,
                          BigDecimal rsi, BigDecimal price,
                          Integer maAlignment1h, Integer maAlignment15m, Integer maAlignment,
                          BigDecimal ma7, BigDecimal ma25,
                          List<BigDecimal> ma7SeriesClosed, List<BigDecimal> ma25SeriesClosed,
                          List<BigDecimal> atrSeriesClosed, String lastClosedBarKey,
                          BigDecimal confirmMa7, BigDecimal confirmMa25,
                          List<BigDecimal> confirmMa7SeriesClosed, List<BigDecimal> confirmMa25SeriesClosed,
                          BigDecimal confirmAtr,
                          Double adx, Double plusDi, Double minusDi,
                          BigDecimal atrMean30Closed, Double atrSpikeRatio,
                          String macdCross, String macdHistTrend,
                          BigDecimal macdDif, BigDecimal macdDea, BigDecimal ema20,
                          Double bollPb, Double bollBandwidth, boolean bollSqueeze,
                          boolean bollExpanding5,
                          Double volumeRatio, List<Double> volumeRatioClosedSeries,
                          String closeTrend, String closeTrendClosed3,
                          List<BigDecimal> closeSeriesClosed,
                          Double closePositionClosed, Double rangeAtrClosed,
                          boolean closeBreakoutHigh10Closed, boolean closeBreakdownLow10Closed,
                          Double rsi15m,
                          Double bidAskImbalance, Double takerPressure, Double oiChangeRate,
                          Double fundingDeviation, Double fundingRateExtreme,
                          Double lsrExtreme, List<String> qualityFlags) {
        this.decisionInterval = decisionInterval != null ? decisionInterval : KlineInterval.M5;
        this.regime = regime;
        this.regimeTransition = regimeTransition;
        this.atr = atr;
        this.atrClosed = atrClosed;
        this.atrFromFallback = atrFromFallback;
        this.rsi = rsi;
        this.price = price;
        this.maAlignment1h = maAlignment1h;
        this.maAlignment15m = maAlignment15m;
        this.maAlignment = maAlignment;
        this.ma7 = ma7;
        this.ma25 = ma25;
        this.ma7SeriesClosed = ma7SeriesClosed != null ? ma7SeriesClosed : List.of();
        this.ma25SeriesClosed = ma25SeriesClosed != null ? ma25SeriesClosed : List.of();
        this.atrSeriesClosed = atrSeriesClosed != null ? atrSeriesClosed : List.of();
        this.lastClosedBarKey = lastClosedBarKey;
        this.confirmMa7 = confirmMa7;
        this.confirmMa25 = confirmMa25;
        this.confirmMa7SeriesClosed = confirmMa7SeriesClosed != null ? confirmMa7SeriesClosed : List.of();
        this.confirmMa25SeriesClosed = confirmMa25SeriesClosed != null ? confirmMa25SeriesClosed : List.of();
        this.confirmAtr = confirmAtr;
        this.adx = adx;
        this.plusDi = plusDi;
        this.minusDi = minusDi;
        this.atrMean30Closed = atrMean30Closed;
        this.atrSpikeRatio = atrSpikeRatio;
        this.macdCross = macdCross;
        this.macdHistTrend = macdHistTrend;
        this.macdDif = macdDif;
        this.macdDea = macdDea;
        this.ema20 = ema20;
        this.bollPb = bollPb;
        this.bollBandwidth = bollBandwidth;
        this.bollSqueeze = bollSqueeze;
        this.bollExpanding5 = bollExpanding5;
        this.volumeRatio = volumeRatio;
        this.volumeRatioClosedSeries = volumeRatioClosedSeries != null
                ? volumeRatioClosedSeries : List.of();
        this.closeTrend = closeTrend;
        this.closeTrendClosed3 = closeTrendClosed3;
        this.closeSeriesClosed = closeSeriesClosed != null ? closeSeriesClosed : List.of();
        this.closePositionClosed = closePositionClosed;
        this.rangeAtrClosed = rangeAtrClosed;
        this.closeBreakoutHigh10Closed = closeBreakoutHigh10Closed;
        this.closeBreakdownLow10Closed = closeBreakdownLow10Closed;
        this.rsi15m = rsi15m;
        this.bidAskImbalance = bidAskImbalance;
        this.takerPressure = takerPressure;
        this.oiChangeRate = oiChangeRate;
        this.fundingDeviation = fundingDeviation;
        this.fundingRateExtreme = fundingRateExtreme;
        this.lsrExtreme = lsrExtreme;
        this.qualityFlags = qualityFlags != null ? qualityFlags : List.of();
    }

    public static MarketContext parse(QuantForecastCycle forecast, BigDecimal price, KlineInterval decisionInterval) {
        KlineInterval interval = decisionInterval != null ? decisionInterval : KlineInterval.M5;
        String regime = "RANGE";
        String transition = null;
        BigDecimal atr = null, atrClosed = null, rsi = null;
        boolean atrFromFallback = false;
        Integer maAlignment1h = null, maAlignment15m = null, maAlignment = null;
        BigDecimal ma7 = null, ma25 = null, confirmMa7 = null, confirmMa25 = null, confirmAtr = null;
        List<BigDecimal> ma7SeriesClosed = List.of();
        List<BigDecimal> ma25SeriesClosed = List.of();
        List<BigDecimal> atrSeriesClosed = List.of();
        String lastClosedBarKey = null;
        List<BigDecimal> confirmMa7SeriesClosed = List.of();
        List<BigDecimal> confirmMa25SeriesClosed = List.of();
        Double adx = null, plusDi = null, minusDi = null, atrSpikeRatio = null;
        BigDecimal atrMean30Closed = null;
        String macdCross = null, macdHistTrend = null;
        BigDecimal macdDif = null, macdDea = null, ema20 = null;
        Double bollPb = null, bollBandwidth = null, volumeRatio = null;
        Double closePositionClosed = null, rangeAtrClosed = null;
        List<Double> volumeRatioClosedSeries = new ArrayList<>();
        List<BigDecimal> closeSeriesClosed = List.of();
        String closeTrend = null, closeTrendClosed3 = null;
        Double rsi15m = null;
        Double bidAskImbalance = null, takerPressure = null, oiChangeRate = null;
        Double fundingDeviation = null, fundingRateExtreme = null, lsrExtreme = null;
        boolean bollSqueeze = false, bollExpanding5 = false;
        boolean closeBreakoutHigh10Closed = false, closeBreakdownLow10Closed = false;
        List<String> qualityFlags = new ArrayList<>();

        if (forecast != null && forecast.getSnapshotJson() != null) {
            try {
                JSONObject snap = JSON.parseObject(forecast.getSnapshotJson());
                regime = snap.getString("regime");
                if (regime == null || regime.isBlank()) regime = "RANGE";
                transition = snap.getString("regimeTransition");
                atr = snap.getBigDecimal("atr");
                bollSqueeze = Boolean.TRUE.equals(snap.getBoolean("bollSqueeze"));

                bidAskImbalance = finiteDouble(snap, "bidAskImbalance");
                takerPressure = finiteDouble(snap, "takerBuySellPressure");
                oiChangeRate = finiteDouble(snap, "oiChangeRate");
                fundingDeviation = finiteDouble(snap, "fundingDeviation");
                fundingRateExtreme = finiteDouble(snap, "fundingRateExtreme");
                lsrExtreme = finiteDouble(snap, "lsrExtreme");

                JSONArray flagsArr = snap.getJSONArray("qualityFlags");
                if (flagsArr != null) {
                    for (int i = 0; i < flagsArr.size(); i++) {
                        String flag = flagsArr.getString(i);
                        if (flag != null && !flag.isBlank()) qualityFlags.add(flag);
                    }
                }

                JSONObject indicators = snap.getJSONObject("indicatorsByTimeframe");
                if (indicators != null) {
                    JSONObject primary = indicators.getJSONObject(interval.getCode());
                    if (primary != null) {
                        rsi = primary.getBigDecimal("rsi14");
                        macdCross = primary.getString("macd_cross");
                        macdHistTrend = primary.getString("macd_hist_trend");
                        macdDif = primary.getBigDecimal("macd_dif");
                        macdDea = primary.getBigDecimal("macd_dea");
                        ema20 = primary.getBigDecimal("ema20");
                        maAlignment = primary.getInteger("ma_alignment");
                        ma7 = primary.getBigDecimal("ma7");
                        ma25 = primary.getBigDecimal("ma25");
                        atrClosed = primary.getBigDecimal("atr14_closed");
                        BigDecimal primaryAtr = primary.getBigDecimal("atr14");
                        if ((atr == null || atr.signum() <= 0)
                                && primaryAtr != null && primaryAtr.signum() > 0) {
                            atr = primaryAtr;
                        }
                        ma7SeriesClosed = readBigDecimalList(primary, "ma7_series_closed");
                        ma25SeriesClosed = readBigDecimalList(primary, "ma25_series_closed");
                        atrSeriesClosed = readBigDecimalList(primary, "atr_series_closed");
                        lastClosedBarKey = primary.getString("last_closed_bar_key");
                        adx = finiteDouble(primary, "adx");
                        plusDi = finiteDouble(primary, "plus_di");
                        minusDi = finiteDouble(primary, "minus_di");
                        atrMean30Closed = primary.getBigDecimal("atr_mean_30_closed");
                        atrSpikeRatio = finiteDouble(primary, "atr_spike_ratio");
                        closeTrend = primary.getString("close_trend");
                        closeTrendClosed3 = primary.getString("close_trend_recent_3_closed");
                        closeSeriesClosed = readBigDecimalList(primary, "close_series_closed");
                        closePositionClosed = finiteDouble(primary, "close_position_closed");
                        rangeAtrClosed = finiteDouble(primary, "range_atr_closed");
                        closeBreakoutHigh10Closed = Boolean.TRUE.equals(
                                primary.getBoolean("close_breakout_high_10_closed"));
                        closeBreakdownLow10Closed = Boolean.TRUE.equals(
                                primary.getBoolean("close_breakdown_low_10_closed"));
                        bollPb = finiteDouble(primary, "boll_pb");
                        bollBandwidth = finiteDouble(primary, "boll_bandwidth");
                        bollExpanding5 = Boolean.TRUE.equals(primary.getBoolean("boll_expanding_5"));
                        volumeRatio = finiteDouble(primary, "volume_ratio");
                        JSONArray closedVolumeRatios = primary.getJSONArray("volume_ratio_recent_5_closed");
                        if (closedVolumeRatios != null) {
                            for (int i = 0; i < closedVolumeRatios.size(); i++) {
                                Double value = closedVolumeRatios.getDouble(i);
                                if (value != null) volumeRatioClosedSeries.add(value);
                            }
                        }
                    }

                    JSONObject confirmTf = indicators.getJSONObject(confirmTimeframeCode(interval));
                    if (confirmTf != null) {
                        confirmMa7 = confirmTf.getBigDecimal("ma7");
                        confirmMa25 = confirmTf.getBigDecimal("ma25");
                        confirmMa7SeriesClosed = readBigDecimalList(confirmTf, "ma7_series_closed");
                        confirmMa25SeriesClosed = readBigDecimalList(confirmTf, "ma25_series_closed");
                        confirmAtr = confirmTf.getBigDecimal("atr14_closed");
                    }

                    JSONObject tf15m = indicators.getJSONObject("15m");
                    if (tf15m != null) {
                        maAlignment15m = tf15m.getInteger("ma_alignment");
                        BigDecimal rsi15mBd = tf15m.getBigDecimal("rsi14");
                        if (rsi15mBd != null) rsi15m = rsi15mBd.doubleValue();
                    }
                    JSONObject tf1h = indicators.getJSONObject("1h");
                    if (tf1h != null) {
                        maAlignment1h = tf1h.getInteger("ma_alignment");
                    }
                }
            } catch (Exception e) {
                log.warn("[MarketContext] snapshotJson解析失败: {}", e.getMessage());
            }
        }

        if ((atr == null || atr.signum() <= 0) && atrClosed != null && atrClosed.signum() > 0) {
            atr = atrClosed;
        }
        if ((atr == null || atr.signum() <= 0) && price != null && price.signum() > 0) {
            atr = price.multiply(new BigDecimal("0.003"));
            atrFromFallback = true;
            log.info("[MarketContext] ATR缺失，使用价格0.3%估算: {}", atr);
        }

        return new MarketContext(interval, regime, transition, atr, atrClosed, atrFromFallback, rsi, price,
                maAlignment1h, maAlignment15m, maAlignment,
                ma7, ma25, ma7SeriesClosed, ma25SeriesClosed, atrSeriesClosed, lastClosedBarKey,
                confirmMa7, confirmMa25, confirmMa7SeriesClosed, confirmMa25SeriesClosed, confirmAtr,
                adx, plusDi, minusDi, atrMean30Closed, atrSpikeRatio,
                macdCross, macdHistTrend, macdDif, macdDea, ema20,
                bollPb, bollBandwidth, bollSqueeze, bollExpanding5,
                volumeRatio, volumeRatioClosedSeries, closeTrend, closeTrendClosed3,
                closeSeriesClosed,
                closePositionClosed, rangeAtrClosed,
                closeBreakoutHigh10Closed, closeBreakdownLow10Closed, rsi15m,
                bidAskImbalance, takerPressure, oiChangeRate,
                fundingDeviation, fundingRateExtreme, lsrExtreme, qualityFlags);
    }

    private static String confirmTimeframeCode(KlineInterval decisionInterval) {
        return decisionInterval == KlineInterval.M15 ? KlineInterval.H1.getCode() : KlineInterval.M15.getCode();
    }

    private static Double finiteDouble(JSONObject obj, String key) {
        if (obj == null) return null;
        Double value = obj.getDouble(key);
        return value != null && Double.isFinite(value) ? value : null;
    }

    private static List<BigDecimal> readBigDecimalList(JSONObject obj, String key) {
        JSONArray arr = obj != null ? obj.getJSONArray(key) : null;
        if (arr == null || arr.isEmpty()) {
            return List.of();
        }
        List<BigDecimal> result = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            BigDecimal value = arr.getBigDecimal(i);
            if (value != null) {
                result.add(value);
            }
        }
        return result;
    }
}
