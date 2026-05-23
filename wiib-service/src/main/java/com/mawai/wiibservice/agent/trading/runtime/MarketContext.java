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

    // 当前市场状态，用来区分趋势、震荡、挤压和冲击环境。
    public final String regime;
    // 市场状态切换信息，用于后续识别 regime 变化风险。
    public final String regimeTransition;
    // 主决策周期 ATR，作为 SL/TP 距离、盈利进度和低波动判断的基础。
    public final BigDecimal atr;
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
    // 主决策周期成交量相对均量倍数，用于突破确认和共振评分。
    public final Double volumeRatio;
    // 近 5 根已闭合主决策周期 K 线量比，时间从早到晚；突破衰竭只看闭合窗口。
    public final List<Double> volumeRatioClosedSeries;
    // 主决策周期收盘价趋势，用于持仓期间判断价格是否反向推进。
    public final String closeTrend;
    // 近 3 根已闭合主决策周期 K 线收盘趋势；MR 失效只看闭合窗口。
    public final String closeTrendClosed3;
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
    // 多空比极值，用于识别单边拥挤和共振扣分风险。
    public final Double lsrExtreme;
    // 数据质量标记，如 LOW_CONFIDENCE、STALE_AGG_TRADE，会影响开仓和退出。
    public final List<String> qualityFlags;

    private MarketContext(String regime, String regimeTransition,
                          BigDecimal atr, BigDecimal rsi, BigDecimal price,
                          Integer maAlignment1h, Integer maAlignment15m, Integer maAlignment,
                          String macdCross, String macdHistTrend,
                          BigDecimal macdDif, BigDecimal macdDea, BigDecimal ema20,
                          Double bollPb, Double bollBandwidth, boolean bollSqueeze,
                          Double volumeRatio, List<Double> volumeRatioClosedSeries,
                          String closeTrend, String closeTrendClosed3, Double rsi15m,
                          Double bidAskImbalance, Double takerPressure, Double oiChangeRate,
                          Double fundingDeviation, Double lsrExtreme, List<String> qualityFlags) {
        this.regime = regime;
        this.regimeTransition = regimeTransition;
        this.atr = atr;
        this.rsi = rsi;
        this.price = price;
        this.maAlignment1h = maAlignment1h;
        this.maAlignment15m = maAlignment15m;
        this.maAlignment = maAlignment;
        this.macdCross = macdCross;
        this.macdHistTrend = macdHistTrend;
        this.macdDif = macdDif;
        this.macdDea = macdDea;
        this.ema20 = ema20;
        this.bollPb = bollPb;
        this.bollBandwidth = bollBandwidth;
        this.bollSqueeze = bollSqueeze;
        this.volumeRatio = volumeRatio;
        this.volumeRatioClosedSeries = volumeRatioClosedSeries != null
                ? volumeRatioClosedSeries : List.of();
        this.closeTrend = closeTrend;
        this.closeTrendClosed3 = closeTrendClosed3;
        this.rsi15m = rsi15m;
        this.bidAskImbalance = bidAskImbalance;
        this.takerPressure = takerPressure;
        this.oiChangeRate = oiChangeRate;
        this.fundingDeviation = fundingDeviation;
        this.lsrExtreme = lsrExtreme;
        this.qualityFlags = qualityFlags;
    }

    public static MarketContext parse(QuantForecastCycle forecast, BigDecimal price, KlineInterval decisionInterval) {
        String regime = "RANGE";
        String transition = null;
        BigDecimal atr = null, rsi = null;
        Integer maAlignment1h = null, maAlignment15m = null, maAlignment = null;
        String macdCross = null, macdHistTrend = null;
        BigDecimal macdDif = null, macdDea = null, ema20 = null;
        Double bollPb = null, bollBandwidth = null, volumeRatio = null;
        List<Double> volumeRatioClosedSeries = new ArrayList<>();
        String closeTrend = null, closeTrendClosed3 = null;
        Double rsi15m = null;
        Double bidAskImbalance = null, takerPressure = null, oiChangeRate = null;
        Double fundingDeviation = null, lsrExtreme = null;
        boolean bollSqueeze = false;
        List<String> qualityFlags = new ArrayList<>();

        if (forecast != null && forecast.getSnapshotJson() != null) {
            try {
                JSONObject snap = JSON.parseObject(forecast.getSnapshotJson());
                regime = snap.getString("regime");
                if (regime == null || regime.isBlank()) regime = "RANGE";
                transition = snap.getString("regimeTransition");
                atr = snap.getBigDecimal("atr");
                bollSqueeze = Boolean.TRUE.equals(snap.getBoolean("bollSqueeze"));

                bidAskImbalance = snap.getDouble("bidAskImbalance");
                takerPressure = snap.getDouble("takerBuySellPressure");
                oiChangeRate = snap.getDouble("oiChangeRate");
                fundingDeviation = snap.getDouble("fundingDeviation");
                lsrExtreme = snap.getDouble("lsrExtreme");

                JSONArray flagsArr = snap.getJSONArray("qualityFlags");
                if (flagsArr != null) {
                    for (int i = 0; i < flagsArr.size(); i++) {
                        String flag = flagsArr.getString(i);
                        if (flag != null && !flag.isBlank()) qualityFlags.add(flag);
                    }
                }

                JSONObject indicators = snap.getJSONObject("indicatorsByTimeframe");
                if (indicators != null) {
                    JSONObject primary = indicators.getJSONObject(decisionInterval.getCode());
                    if (primary != null) {
                        rsi = primary.getBigDecimal("rsi14");
                        macdCross = primary.getString("macd_cross");
                        macdHistTrend = primary.getString("macd_hist_trend");
                        BigDecimal macdDifVal = primary.getBigDecimal("macd_dif");
                        BigDecimal macdDeaVal = primary.getBigDecimal("macd_dea");
                        if (macdDifVal != null) macdDif = macdDifVal;
                        if (macdDeaVal != null) macdDea = macdDeaVal;
                        BigDecimal ema20Val = primary.getBigDecimal("ema20");
                        if (ema20Val != null) ema20 = ema20Val;
                        maAlignment = primary.getInteger("ma_alignment");
                        closeTrend = primary.getString("close_trend");
                        closeTrendClosed3 = primary.getString("close_trend_recent_3_closed");
                        bollPb = primary.getDouble("boll_pb");
                        bollBandwidth = primary.getDouble("boll_bandwidth");
                        volumeRatio = primary.getDouble("volume_ratio");
                        JSONArray closedVolumeRatios = primary.getJSONArray("volume_ratio_recent_5_closed");
                        if (closedVolumeRatios != null) {
                            for (int i = 0; i < closedVolumeRatios.size(); i++) {
                                Double value = closedVolumeRatios.getDouble(i);
                                if (value != null) volumeRatioClosedSeries.add(value);
                            }
                        }
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

        if (atr == null || atr.signum() <= 0) {
            atr = price.multiply(new BigDecimal("0.003"));
            log.info("[MarketContext] ATR缺失，使用价格0.3%估算: {}", atr);
        }

        return new MarketContext(regime, transition, atr, rsi, price,
                maAlignment1h, maAlignment15m, maAlignment,
                macdCross, macdHistTrend, macdDif, macdDea, ema20,
                bollPb, bollBandwidth, bollSqueeze,
                volumeRatio, volumeRatioClosedSeries, closeTrend, closeTrendClosed3, rsi15m,
                bidAskImbalance, takerPressure, oiChangeRate,
                fundingDeviation, lsrExtreme, qualityFlags);
    }
}
