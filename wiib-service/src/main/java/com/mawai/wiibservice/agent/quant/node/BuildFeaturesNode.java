package com.mawai.wiibservice.agent.quant.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibservice.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibservice.agent.quant.domain.MarketRegime;
import com.mawai.wiibservice.agent.quant.domain.NewsItem;
import com.mawai.wiibservice.agent.tool.CryptoIndicatorCalculator;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 特征工程节点：从原始市场数据构建FeatureSnapshot。
 * 复用CryptoIndicatorCalculator.calcAll()，额外计算盘口微结构和波动率特征。
 */
@Slf4j
public class BuildFeaturesNode implements NodeAction {

    private static final String[][] PRICE_CHANGE_SOURCES = {
            {"5m", "1m", "5"}, {"15m", "5m", "3"}, {"30m", "5m", "6"},
            {"1h", "15m", "4"}, {"4h", "1h", "4"}, {"24h", "1h", "24"},
    };

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) {
        long startMs = System.currentTimeMillis();
        String symbol = (String) state.value("target_symbol").orElse("BTCUSDT");
        var klineMap = (Map<String, Map<String, String>>) state.value("kline_map").orElse(Map.of());
        var tickerMap = (Map<String, String>) state.value("ticker_map").orElse(Map.of());
        var fundingRateMap = (Map<String, String>) state.value("funding_rate_map").orElse(Map.of());
        var fundingRateHistMap = (Map<String, String>) state.value("funding_rate_hist_map").orElse(Map.of());
        var orderbookMap = (Map<String, String>) state.value("orderbook_map").orElse(Map.of());
        var oiHistMap = (Map<String, String>) state.value("oi_hist_map").orElse(Map.of());
        var longShortRatioMap = (Map<String, String>) state.value("long_short_ratio_map").orElse(Map.of());
        var forceOrdersMap = (Map<String, String>) state.value("force_orders_map").orElse(Map.of());
        var topTraderPositionMap = (Map<String, String>) state.value("top_trader_position_map").orElse(Map.of());
        var takerLongShortMap = (Map<String, String>) state.value("taker_long_short_map").orElse(Map.of());
        String fearGreedData = (String) state.value("fear_greed_data").orElse("{}");
        String newsData = (String) state.value("news_data").orElse("{}");

        Map<String, String> rawKlines = klineMap.getOrDefault(symbol, Map.of());
        log.info("[Q2.0] build_features开始 symbol={} K线周期={}", symbol, rawKlines.keySet());

        // 1. 技术指标计算（多周期）
        Map<String, Map<String, Object>> indicatorsByTf = new HashMap<>();
        Map<String, List<BigDecimal>> closesByInterval = new HashMap<>();
        Map<String, List<BigDecimal[]>> parsedKlines = new HashMap<>();  // 保留原始解析数据

        for (var entry : rawKlines.entrySet()) {
            String interval = entry.getKey();
            List<BigDecimal[]> klines = CryptoIndicatorCalculator.parseKlines(entry.getValue());
            if (klines.size() < 30) continue;
            indicatorsByTf.put(interval, CryptoIndicatorCalculator.calcAll(klines));
            parsedKlines.put(interval, klines);
            List<BigDecimal> closes = new ArrayList<>(klines.size());
            for (BigDecimal[] k : klines) closes.add(k[2]);
            closesByInterval.put(interval, closes);
        }
        log.info("[Q2.1] 指标计算完成 有效周期={}", indicatorsByTf.keySet());

        // 2. 价格变化
        Map<String, BigDecimal> priceChanges = new LinkedHashMap<>();
        for (String[] src : PRICE_CHANGE_SOURCES) {
            List<BigDecimal> closes = closesByInterval.get(src[1]);
            int bars = Integer.parseInt(src[2]);
            if (closes == null || closes.size() <= bars) continue;
            BigDecimal change = CryptoIndicatorCalculator.pctChange(
                    closes.get(closes.size() - 1 - bars), closes.getLast());
            if (change != null) priceChanges.put(src[0], change);
        }

        // 3. 最新价格
        BigDecimal lastPrice = extractLastPrice(tickerMap.get(symbol), closesByInterval);

        // 4. 盘口微结构
        double bidAskImbalance = calcBidAskImbalance(orderbookMap.get(symbol));
        double tradeDelta = calcTradeDelta(parsedKlines);
        double oiChangeRate = calcOiChangeRate(oiHistMap.get(symbol));
        double fundingDeviation = calcFundingDeviation(fundingRateMap.get(symbol));
        double lsrExtreme = calcLsrExtreme(longShortRatioMap.get(symbol));

        // 4.5 资金费率趋势（历史）
        double[] fundingTrend = calcFundingTrend(fundingRateHistMap.get(symbol));
        double fundingRateTrend = fundingTrend[0];
        double fundingRateExtreme = fundingTrend[1];

        // 4.6 爆仓压力
        double[] liqResult = calcLiquidationPressure(forceOrdersMap.get(symbol));
        double liquidationPressure = liqResult[0];
        double liquidationVolumeUsdt = liqResult[1];

        // 4.7 大户持仓趋势
        double topTraderBias = calcTrendBias(topTraderPositionMap.get(symbol), "longShortRatio", 0.5);

        // 4.8 主动买卖量比
        double takerBuySellPressure = calcTrendBias(takerLongShortMap.get(symbol), "buySellRatio", 0.3);

        // 4.9 恐惧贪婪指数
        int fearGreedIndex = calcFearGreed(fearGreedData);
        String fearGreedLabel = mapFearGreedLabel(fearGreedIndex);

        log.info("[Q2.2] 微结构 bia={} td={} oi={} funding={} fundTrend={} fundExtreme={} lsr={} liqPressure={} liqVol={} topTrader={} takerPressure={} fearGreed={}/{}",
                String.format("%.3f", bidAskImbalance), String.format("%.3f", tradeDelta),
                String.format("%.3f", oiChangeRate), String.format("%.3f", fundingDeviation),
                String.format("%.3f", fundingRateTrend), String.format("%.3f", fundingRateExtreme),
                String.format("%.3f", lsrExtreme),
                String.format("%.3f", liquidationPressure), String.format("%.0f", liquidationVolumeUsdt),
                String.format("%.3f", topTraderBias), String.format("%.3f", takerBuySellPressure),
                fearGreedIndex, fearGreedLabel);

        // 5. 波动率特征
        BigDecimal atr1m = extractAtr(indicatorsByTf, "1m");
        BigDecimal atr5m = extractAtr(indicatorsByTf, "5m");
        BigDecimal bollBw = extractBollBandwidth(indicatorsByTf, "5m");
        boolean bollSqueeze = bollBw != null && bollBw.doubleValue() < 1.5;

        // 6. Regime
        MarketRegime regime = detectRegime(indicatorsByTf, bollBw, parsedKlines);
        log.info("[Q2.3] 波动率 atr1m={} atr5m={} bollBw={} squeeze={} regime={}",
                atr1m, atr5m, bollBw, bollSqueeze, regime);

        // 7. 数据质量标记
        List<String> qualityFlags = new ArrayList<>();
        if (indicatorsByTf.isEmpty()) qualityFlags.add("NO_INDICATORS");
        if (lastPrice == null) qualityFlags.add("NO_PRICE");
        addMissingTimeframeFlags(qualityFlags, rawKlines, indicatorsByTf);
        if (!orderbookMap.containsKey(symbol)) qualityFlags.add("NO_ORDERBOOK");
        if (!fundingRateMap.containsKey(symbol)) qualityFlags.add("NO_FUNDING");
        if (!fundingRateHistMap.containsKey(symbol)) qualityFlags.add("NO_FUNDING_HIST");
        if (!longShortRatioMap.containsKey(symbol)) qualityFlags.add("NO_LONG_SHORT_RATIO");
        if (!forceOrdersMap.containsKey(symbol)) qualityFlags.add("NO_FORCE_ORDERS");
        if (!topTraderPositionMap.containsKey(symbol)) qualityFlags.add("NO_TOP_TRADER");
        if (!takerLongShortMap.containsKey(symbol)) qualityFlags.add("NO_TAKER_LSR");
        if (fearGreedIndex < 0) qualityFlags.add("NO_FEAR_GREED");
        if (!oiHistMap.containsKey(symbol)) qualityFlags.add("NO_OI_HISTORY");
        if (atr5m == null) qualityFlags.add("NO_ATR_5M");
        if (bollBw == null) qualityFlags.add("NO_BOLL_5M");
        if (qualityFlags.stream().anyMatch(flag -> flag.startsWith("MISSING_TF_"))) {
            qualityFlags.add("PARTIAL_KLINE_DATA");
        }

        // 8. 新闻解析
        List<NewsItem> newsItems = parseNewsItems(newsData);
        if (newsItems.isEmpty()) qualityFlags.add("NO_NEWS");

        FeatureSnapshot snapshot = new FeatureSnapshot(symbol, LocalDateTime.now(), lastPrice,
                indicatorsByTf, priceChanges, bidAskImbalance, tradeDelta, oiChangeRate,
                fundingDeviation, fundingRateTrend, fundingRateExtreme, lsrExtreme,
                liquidationPressure, liquidationVolumeUsdt,
                topTraderBias, takerBuySellPressure,
                fearGreedIndex, fearGreedLabel,
                atr1m, atr5m, bollBw, bollSqueeze,
                regime, newsItems, qualityFlags);
        log.info("[Q2.4] build_features完成 price={} news={}条 qualityFlags={} 耗时{}ms",
                lastPrice, newsItems.size(), qualityFlags, System.currentTimeMillis() - startMs);

        return Map.of("feature_snapshot", snapshot, "indicator_map", indicatorsByTf,
                "price_change_map", Map.of(symbol, priceChanges));
    }

    private BigDecimal extractLastPrice(String tickerJson, Map<String, List<BigDecimal>> closes) {
        if (tickerJson != null) {
            try {
                JSONObject t = JSON.parseObject(tickerJson);
                String p = t.getString("lastPrice");
                if (p != null) return new BigDecimal(p);
            } catch (Exception ignored) {}
        }
        // fallback: 最新1m close
        List<BigDecimal> c1m = closes.get("1m");
        return (c1m != null && !c1m.isEmpty()) ? c1m.getLast() : null;
    }

    private double calcBidAskImbalance(String obJson) {
        if (obJson == null) return 0;
        try {
            JSONObject ob = JSON.parseObject(obJson);
            double bidVol = sumDepth(ob.getJSONArray("bids"), 5);
            double askVol = sumDepth(ob.getJSONArray("asks"), 5);
            double total = bidVol + askVol;
            return total > 0 ? (bidVol - askVol) / total : 0;
        } catch (Exception e) { return 0; }
    }

    private double sumDepth(JSONArray levels, int depth) {
        if (levels == null) return 0;
        double sum = 0;
        for (int i = 0; i < Math.min(levels.size(), depth); i++) {
            JSONArray lv = levels.getJSONArray(i);
            sum += lv.getDoubleValue(1);
        }
        return sum;
    }

    /**
     * 用taker buy volume计算主动买卖差。
     * takerBuyRatio > 0.5 表示主动买多，转换为[-1,1]的tradeDelta。
     * 优先用1m近10根，fallback到5m近3根。
     */
    private double calcTradeDelta(Map<String, List<BigDecimal[]>> parsedKlines) {
        List<BigDecimal[]> k1m = parsedKlines.get("1m");
        if (k1m != null && k1m.size() >= 10) {
            double ratio = CryptoIndicatorCalculator.takerBuyRatio(k1m, 10);
            return (ratio - 0.5) * 2.0; // [0,1] → [-1,1]
        }
        List<BigDecimal[]> k5m = parsedKlines.get("5m");
        if (k5m != null && k5m.size() >= 3) {
            double ratio = CryptoIndicatorCalculator.takerBuyRatio(k5m, 3);
            return (ratio - 0.5) * 2.0;
        }
        return 0;
    }


    /**
     * 基于OI历史数据计算变化率。
     * 使用/futures/data/openInterestHist返回的数组，取最新与最早的OI对比。
     * 正值=OI增长（资金流入），负值=OI萎缩（资金流出）。
     */
    private double calcOiChangeRate(String oiHistJson) {
        if (oiHistJson == null || oiHistJson.isBlank()) return 0;
        try {
            JSONArray arr = JSON.parseArray(oiHistJson);
            if (arr == null || arr.size() < 2) return 0;
            JSONObject oldest = arr.getJSONObject(0);
            JSONObject latest = arr.getJSONObject(arr.size() - 1);
            double oldOi = oldest.getDoubleValue("sumOpenInterest");
            double newOi = latest.getDoubleValue("sumOpenInterest");
            if (oldOi <= 0) return 0;
            return (newOi - oldOi) / oldOi; // 正=增长，负=萎缩
        } catch (Exception e) { return 0; }
    }

    private double calcFundingDeviation(String fundingJson) {
        if (fundingJson == null) return 0;
        try {
            JSONObject f = JSON.parseObject(fundingJson);
            double rate = f.getDoubleValue("lastFundingRate");
            // 标准费率0.0001 (0.01%), 偏离度
            return (rate - 0.0001) / 0.0003; // 归一化到约[-1,1]
        } catch (Exception e) { return 0; }
    }

    private double calcLsrExtreme(String lsrJson) {
        if (lsrJson == null) return 0;
        try {
            JSONArray arr = JSON.parseArray(lsrJson);
            if (arr == null || arr.isEmpty()) return 0;
            // 取最新一条
            JSONObject latest = arr.getJSONObject(arr.size() - 1);
            double ratio = latest.getDoubleValue("longShortRatio");
            // ratio>2 极端多头, <0.5 极端空头
            if (ratio > 2.0) return 1.0;
            if (ratio < 0.5) return -1.0;
            return (ratio - 1.0); // 归一化
        } catch (Exception e) { return 0; }
    }

    private BigDecimal extractAtr(Map<String, Map<String, Object>> indicators, String tf) {
        Map<String, Object> ind = indicators.get(tf);
        if (ind == null) return null;
        Object v = ind.get("atr14");
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }

    private BigDecimal extractBollBandwidth(Map<String, Map<String, Object>> indicators, String tf) {
        Map<String, Object> ind = indicators.get(tf);
        if (ind == null) return null;
        Object v = ind.get("boll_bandwidth");
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }

    /**
     * 市场状态检测。按文档6.4：
     * SHOCK: ATR突增 > 2倍历史均值
     * SQUEEZE: 布林带宽极低 + ADX<15
     * TREND_UP/DOWN: ADX>25 + DI方向
     * RANGE: ADX<20
     */
    private MarketRegime detectRegime(Map<String, Map<String, Object>> indicators,
                                      BigDecimal bollBw,
                                      Map<String, List<BigDecimal[]>> parsedKlines) {
        Map<String, Object> ind = indicators.getOrDefault("15m", indicators.get("5m"));
        if (ind == null) return MarketRegime.RANGE;

        BigDecimal adx = toBd(ind.get("adx"));
        BigDecimal plusDi = toBd(ind.get("plus_di"));
        BigDecimal minusDi = toBd(ind.get("minus_di"));
        double adxVal = adx != null ? adx.doubleValue() : 15;

        // SHOCK: ATR突增 > 2倍历史均值（用5m K线计算近期ATR vs 长期ATR）
        if (isAtrSpike(parsedKlines)) {
            return MarketRegime.SHOCK;
        }
        // SQUEEZE: 布林带收敛 + ADX<15
        if (adxVal < 15 && bollBw != null && bollBw.doubleValue() < 1.5) {
            return MarketRegime.SQUEEZE;
        }
        // TREND: ADX>25 + DI方向
        if (adxVal > 25 && plusDi != null && minusDi != null) {
            return plusDi.compareTo(minusDi) > 0 ? MarketRegime.TREND_UP : MarketRegime.TREND_DOWN;
        }
        // RANGE: ADX<20
        if (adxVal < 20) {
            return MarketRegime.RANGE;
        }
        // ADX在20-25之间：弱趋势，归类为RANGE
        return MarketRegime.RANGE;
    }

    /**
     * ATR突增检测：比较近6根K线平均真实波幅 vs 前60根平均真实波幅。
     * 如果近期 > 2倍长期，判定为SHOCK。
     */
    private boolean isAtrSpike(Map<String, List<BigDecimal[]>> parsedKlines) {
        List<BigDecimal[]> k5m = parsedKlines.get("5m");
        if (k5m == null || k5m.size() < 66) return false;

        // 近6根(30min)平均真实波幅
        double recentAtr = avgTrueRange(k5m, k5m.size() - 6, k5m.size());
        // 前60根(5h)平均真实波幅
        double histAtr = avgTrueRange(k5m, k5m.size() - 66, k5m.size() - 6);

        return histAtr > 0 && recentAtr > 2.0 * histAtr;
    }

    private double avgTrueRange(List<BigDecimal[]> klines, int from, int to) {
        double sum = 0;
        int count = 0;
        for (int i = Math.max(1, from); i < to; i++) {
            double high = klines.get(i)[0].doubleValue();
            double low = klines.get(i)[1].doubleValue();
            double prevClose = klines.get(i - 1)[2].doubleValue();
            double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            sum += tr;
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

    private static BigDecimal toBd(Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }

    private void addMissingTimeframeFlags(List<String> qualityFlags,
                                          Map<String, String> rawKlines,
                                          Map<String, Map<String, Object>> indicatorsByTf) {
        for (String timeframe : List.of("1m", "5m", "15m", "1h", "4h", "1d")) {
            if (!rawKlines.containsKey(timeframe)) {
                qualityFlags.add("MISSING_TF_" + timeframe.toUpperCase());
                continue;
            }
            if (!indicatorsByTf.containsKey(timeframe)) {
                qualityFlags.add("INSUFFICIENT_BARS_" + timeframe.toUpperCase());
            }
        }
    }

    /**
     * 爆仓压力: 统计近期强平单，多头爆仓多=空头力量(正)，空头爆仓多=多头力量(负)。
     * 返回 [pressure归一化, 总爆仓额USDT]
     * Binance forceOrders格式: [{side:"SELL"=多头爆仓, price, origQty, time, ...}]
     */
    private double[] calcLiquidationPressure(String forceOrdersJson) {
        if (forceOrdersJson == null || forceOrdersJson.isBlank()) return new double[]{0, 0};
        try {
            JSONArray arr = JSON.parseArray(forceOrdersJson);
            if (arr == null || arr.isEmpty()) return new double[]{0, 0};
            double longLiqVol = 0, shortLiqVol = 0;
            for (int i = 0; i < arr.size(); i++) {
                JSONObject order = arr.getJSONObject(i);
                double price = order.getDoubleValue("price");
                double qty = order.getDoubleValue("origQty");
                double vol = price * qty;
                // SELL=多头被强平, BUY=空头被强平
                if ("SELL".equals(order.getString("side"))) {
                    longLiqVol += vol;
                } else {
                    shortLiqVol += vol;
                }
            }
            double total = longLiqVol + shortLiqVol;
            if (total < 1) return new double[]{0, 0};
            // 正=多头爆仓多(利空), 负=空头爆仓多(利多)
            double pressure = Math.clamp((longLiqVol - shortLiqVol) / total, -1, 1);
            return new double[]{pressure, total};
        } catch (Exception e) {
            log.warn("[Q2] 爆仓数据解析失败: {}", e.getMessage());
            return new double[]{0, 0};
        }
    }

    /**
     * 通用趋势偏离: 对比JSON数组前半段 vs 后半段某字段的均值变化，归一化到[-1,1]。
     */
    private double calcTrendBias(String json, String fieldName, double divisor) {
        if (json == null || json.isBlank()) return 0;
        try {
            JSONArray arr = JSON.parseArray(json);
            if (arr == null || arr.size() < 4) return 0;
            int mid = arr.size() / 2;
            double olderAvg = 0, recentAvg = 0;
            for (int i = 0; i < mid; i++) {
                olderAvg += arr.getJSONObject(i).getDoubleValue(fieldName);
            }
            olderAvg /= mid;
            for (int i = mid; i < arr.size(); i++) {
                recentAvg += arr.getJSONObject(i).getDoubleValue(fieldName);
            }
            recentAvg /= (arr.size() - mid);
            return Math.clamp((recentAvg - olderAvg) / divisor, -1, 1);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 恐惧贪婪指数解析。返回当前值(0-100)，失败返回-1。
     */
    private int calcFearGreed(String fearGreedJson) {
        if (fearGreedJson == null || fearGreedJson.isBlank() || "{}".equals(fearGreedJson)) return -1;
        try {
            JSONObject root = JSON.parseObject(fearGreedJson);
            JSONArray data = root.getJSONArray("data");
            if (data == null || data.isEmpty()) return -1;
            return data.getJSONObject(0).getIntValue("value");
        } catch (Exception e) {
            log.warn("[Q2] 恐惧贪婪指数解析失败: {}", e.getMessage());
            return -1;
        }
    }

    private String mapFearGreedLabel(int index) {
        if (index < 0) return "UNKNOWN";
        if (index <= 24) return "EXTREME_FEAR";
        if (index <= 44) return "FEAR";
        if (index <= 55) return "NEUTRAL";
        if (index <= 74) return "GREED";
        return "EXTREME_GREED";
    }

    /**
     * 从资金费率历史计算趋势和极端度。
     * 返回 [trend, extreme]，trend: 近期均值 vs 远期均值的变化方向，extreme: 最新值偏离均值的程度。
     */
    private double[] calcFundingTrend(String fundingHistJson) {
        if (fundingHistJson == null || fundingHistJson.isBlank()) return new double[]{0, 0};
        try {
            JSONArray arr = JSON.parseArray(fundingHistJson);
            if (arr == null || arr.size() < 3) return new double[]{0, 0};

            List<Double> rates = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                JSONObject item = arr.getJSONObject(i);
                rates.add(item.getDoubleValue("fundingRate"));
            }

            int mid = rates.size() / 2;
            double olderAvg = rates.subList(0, mid).stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double recentAvg = rates.subList(mid, rates.size()).stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double trend = Math.clamp((recentAvg - olderAvg) / 0.0003, -1, 1);

            double totalAvg = rates.stream().mapToDouble(Double::doubleValue).average().orElse(0.0001);
            double latest = rates.getLast();
            double extreme = Math.clamp((latest - totalAvg) / 0.0003, -1, 1);

            return new double[]{trend, extreme};
        } catch (Exception e) {
            log.warn("[Q2] 资金费率趋势计算失败: {}", e.getMessage());
            return new double[]{0, 0};
        }
    }

    private List<NewsItem> parseNewsItems(String newsData) {
        if (newsData == null || newsData.isBlank() || "{}".equals(newsData)) return List.of();
        try {
            JSONObject root = JSON.parseObject(newsData);
            JSONArray items = root.getJSONArray("Data");
            if (items == null || items.isEmpty()) return List.of();
            List<NewsItem> result = new ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.getJSONObject(i);
                if (item == null) continue;
                result.add(new NewsItem(
                        item.getString("TITLE"),
                        item.getString("BODY"),
                        item.getString("SOURCE_DATA_SOURCE_KEY"),
                        item.getString("GUID")
                ));
            }
            return result;
        } catch (Exception e) {
            log.warn("[Q2] 新闻解析失败: {}", e.getMessage());
            return List.of();
        }
    }
}
