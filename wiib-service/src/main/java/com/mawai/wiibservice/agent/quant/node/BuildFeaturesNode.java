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
import com.mawai.wiibservice.service.OrderFlowAggregator;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

/**
 * 特征工程节点：从原始市场数据构建FeatureSnapshot。
 * 复用CryptoIndicatorCalculator.calcAll()，额外计算盘口微结构和波动率特征。
 */
@Slf4j
public class BuildFeaturesNode implements NodeAction {

    private final OrderFlowAggregator orderFlowAggregator;

    public BuildFeaturesNode(OrderFlowAggregator orderFlowAggregator) {
        this.orderFlowAggregator = orderFlowAggregator;
    }

    private static final java.time.format.DateTimeFormatter DERIBIT_EXPIRY_FMT =
            new DateTimeFormatterBuilder().parseCaseInsensitive()
                    .appendPattern("dMMMuu").toFormatter(java.util.Locale.ENGLISH);

    private static final String[][] PRICE_CHANGE_SOURCES = {
            {"5m", "1m", "5"}, {"15m", "5m", "3"}, {"30m", "5m", "6"},
            {"1h", "15m", "4"}, {"4h", "1h", "4"}, {"24h", "1h", "24"},
    };

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String symbol = (String) state.value("target_symbol").orElse("BTCUSDT");
        Map<String, Object> rawData = new HashMap<>();
        rawData.put("kline_map", state.value("kline_map").orElse(Map.of()));
        rawData.put("spot_kline_map", state.value("spot_kline_map").orElse(Map.of()));
        rawData.put("ticker_map", state.value("ticker_map").orElse(Map.of()));
        rawData.put("spot_ticker_map", state.value("spot_ticker_map").orElse(Map.of()));
        rawData.put("funding_rate_map", state.value("funding_rate_map").orElse(Map.of()));
        rawData.put("funding_rate_hist_map", state.value("funding_rate_hist_map").orElse(Map.of()));
        rawData.put("orderbook_map", state.value("orderbook_map").orElse(Map.of()));
        rawData.put("spot_orderbook_map", state.value("spot_orderbook_map").orElse(Map.of()));
        rawData.put("oi_hist_map", state.value("oi_hist_map").orElse(Map.of()));
        rawData.put("long_short_ratio_map", state.value("long_short_ratio_map").orElse(Map.of()));
        rawData.put("force_orders_map", state.value("force_orders_map").orElse(Map.of()));
        rawData.put("top_trader_position_map", state.value("top_trader_position_map").orElse(Map.of()));
        rawData.put("taker_long_short_map", state.value("taker_long_short_map").orElse(Map.of()));
        rawData.put("fear_greed_data", state.value("fear_greed_data").orElse("{}"));
        rawData.put("news_data", state.value("news_data").orElse("{}"));
        rawData.put("dvol_data", state.value("dvol_data").orElse(null));
        rawData.put("option_book_summary", state.value("option_book_summary").orElse(null));
        return buildFeatures(symbol, rawData);
    }

    /** 独立于 StateGraph 的特征构建入口，轻周期可直接调用 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> buildFeatures(String symbol, Map<String, Object> rawData) {
        long startMs = System.currentTimeMillis();
        var klineMap = (Map<String, Map<String, String>>) rawData.getOrDefault("kline_map", Map.of());
        var spotKlineMap = (Map<String, Map<String, String>>) rawData.getOrDefault("spot_kline_map", Map.of());
        var tickerMap = (Map<String, String>) rawData.getOrDefault("ticker_map", Map.of());
        var spotTickerMap = (Map<String, String>) rawData.getOrDefault("spot_ticker_map", Map.of());
        var fundingRateMap = (Map<String, String>) rawData.getOrDefault("funding_rate_map", Map.of());
        var fundingRateHistMap = (Map<String, String>) rawData.getOrDefault("funding_rate_hist_map", Map.of());
        var orderbookMap = (Map<String, String>) rawData.getOrDefault("orderbook_map", Map.of());
        var spotOrderbookMap = (Map<String, String>) rawData.getOrDefault("spot_orderbook_map", Map.of());
        var oiHistMap = (Map<String, String>) rawData.getOrDefault("oi_hist_map", Map.of());
        var longShortRatioMap = (Map<String, String>) rawData.getOrDefault("long_short_ratio_map", Map.of());
        var forceOrdersMap = (Map<String, String>) rawData.getOrDefault("force_orders_map", Map.of());
        var topTraderPositionMap = (Map<String, String>) rawData.getOrDefault("top_trader_position_map", Map.of());
        var takerLongShortMap = (Map<String, String>) rawData.getOrDefault("taker_long_short_map", Map.of());
        String fearGreedData = (String) rawData.getOrDefault("fear_greed_data", "{}");
        String newsData = (String) rawData.getOrDefault("news_data", "{}");
        String dvolData = (String) rawData.getOrDefault("dvol_data", null);
        String bookSummaryData = (String) rawData.getOrDefault("option_book_summary", null);

        Map<String, String> rawKlines = klineMap.getOrDefault(symbol, Map.of());
        Map<String, String> rawSpotKlines = spotKlineMap.getOrDefault(symbol, Map.of());
        log.info("[Q2.0] build_features开始 symbol={} K线周期={}", symbol, rawKlines.keySet());

        // 1. 技术指标计算（多周期）
        Map<String, Map<String, Object>> indicatorsByTf = new HashMap<>();
        Map<String, List<BigDecimal>> closesByInterval = new HashMap<>();
        Map<String, List<BigDecimal[]>> parsedKlines = new HashMap<>();  // 保留原始解析数据
        Map<String, List<BigDecimal>> spotClosesByInterval = new HashMap<>();
        Map<String, List<BigDecimal[]>> parsedSpotKlines = new HashMap<>();

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
        for (var entry : rawSpotKlines.entrySet()) {
            List<BigDecimal[]> klines = CryptoIndicatorCalculator.parseKlines(entry.getValue());
            if (klines.isEmpty()) continue;
            parsedSpotKlines.put(entry.getKey(), klines);
            List<BigDecimal> closes = new ArrayList<>(klines.size());
            for (BigDecimal[] k : klines) closes.add(k[2]);
            spotClosesByInterval.put(entry.getKey(), closes);
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
        BigDecimal barHigh = extractLatestKlineField(parsedKlines, "5m", 0);
        BigDecimal barLow = extractLatestKlineField(parsedKlines, "5m", 1);
        BigDecimal spotLastPrice = extractLastPrice(spotTickerMap.get(symbol), spotClosesByInterval);

        // 3.5 现货-合约联动
        double spotBidAskImbalance = calcBidAskImbalance(spotOrderbookMap.get(symbol));
        BigDecimal spotPriceChange5m = calcRecentPctChange(spotClosesByInterval.get("1m"), 5);
        double spotPerpBasisBps = calcBasisBps(lastPrice, spotLastPrice);
        double spotLeadLagScore = calcSpotLeadLagScore(parsedSpotKlines, parsedKlines);

        // 4. 盘口微结构
        double bidAskImbalance = calcBidAskImbalance(orderbookMap.get(symbol));

        // tradeDelta: 优先用 aggTrade 实时数据，fallback K线近似
        double tradeDelta;
        double tradeIntensity = 0;
        double largeTradeBias = 0;
        boolean aggTradeAvailable = false;
        if (orderFlowAggregator != null && orderFlowAggregator.hasData(symbol)) {
            OrderFlowAggregator.Metrics m3 = orderFlowAggregator.getMetrics(symbol, 180);
            if (m3 != null && m3.tradeCount() >= 10) {
                tradeDelta = Math.clamp(m3.tradeDelta(), -1, 1);
                largeTradeBias = Math.clamp(m3.largeTradeBias(), -1, 1);
                // 强度归一化：BTC 正常约 5-15 笔/秒，除以10 后 clamp
                OrderFlowAggregator.Metrics m60 = orderFlowAggregator.getMetrics(symbol, 60);
                if (m60 != null && m60.tradeCount() > 0) {
                    tradeIntensity = Math.clamp(m60.tradeIntensity() / 10.0, 0, 3);
                }
                aggTradeAvailable = true;
                log.info("[Q2.flow] aggTrade可用 symbol={} delta={} intensity={} largeBias={} trades3m={}",
                        symbol, String.format("%.3f", tradeDelta),
                        String.format("%.2f", tradeIntensity),
                        String.format("%.3f", largeTradeBias), m3.tradeCount());
            } else {
                tradeDelta = calcTradeDelta(parsedKlines);
            }
        } else {
            tradeDelta = calcTradeDelta(parsedKlines);
        }
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

        log.info("[Q2.2] 微结构 futBia={} spotBia={} td={}(agg={}) intensity={} largeBias={} oi={} funding={} fundTrend={} fundExtreme={} lsr={} basis={}bps spotLead={} liqPressure={} liqVol={} topTrader={} takerPressure={} fearGreed={}/{}",
                String.format("%.3f", bidAskImbalance), String.format("%.3f", spotBidAskImbalance),
                String.format("%.3f", tradeDelta), aggTradeAvailable,
                String.format("%.2f", tradeIntensity), String.format("%.3f", largeTradeBias),
                String.format("%.3f", oiChangeRate), String.format("%.3f", fundingDeviation),
                String.format("%.3f", fundingRateTrend), String.format("%.3f", fundingRateExtreme),
                String.format("%.3f", lsrExtreme),
                String.format("%.2f", spotPerpBasisBps), String.format("%.3f", spotLeadLagScore),
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

        // 6.5 期权 IV 特征（Deribit）
        double dvolIndex = parseDvol(dvolData);
        double[] ivFeatures = parseOptionIv(bookSummaryData, lastPrice);
        double atmIv = ivFeatures[0];
        double ivSkew25d = ivFeatures[1];
        double ivTermSlope = ivFeatures[2];
        if (dvolIndex > 0 || atmIv > 0) {
            log.info("[Q2.3.iv] dvol={} atmIv={} skew25d={} termSlope={}",
                    String.format("%.1f", dvolIndex), String.format("%.1f", atmIv),
                    String.format("%.2f", ivSkew25d), String.format("%.2f", ivTermSlope));
        }

        // 7. 数据质量标记
        List<String> qualityFlags = new ArrayList<>();
        if (indicatorsByTf.isEmpty()) qualityFlags.add("NO_INDICATORS");
        if (lastPrice == null) qualityFlags.add("NO_PRICE");
        addMissingTimeframeFlags(qualityFlags, rawKlines, indicatorsByTf);
        if (!orderbookMap.containsKey(symbol)) qualityFlags.add("NO_ORDERBOOK");
        if (!spotTickerMap.containsKey(symbol)) qualityFlags.add("NO_SPOT_TICKER");
        if (!spotOrderbookMap.containsKey(symbol)) qualityFlags.add("NO_SPOT_ORDERBOOK");
        if (!spotClosesByInterval.containsKey("1m")) qualityFlags.add("NO_SPOT_KLINE_1M");
        if (!fundingRateMap.containsKey(symbol)) qualityFlags.add("NO_FUNDING");
        if (!fundingRateHistMap.containsKey(symbol)) qualityFlags.add("NO_FUNDING_HIST");
        if (!longShortRatioMap.containsKey(symbol)) qualityFlags.add("NO_LONG_SHORT_RATIO");
        if (!forceOrdersMap.containsKey(symbol)) qualityFlags.add("NO_FORCE_ORDERS");
        if (!topTraderPositionMap.containsKey(symbol)) qualityFlags.add("NO_TOP_TRADER");
        if (!takerLongShortMap.containsKey(symbol)) qualityFlags.add("NO_TAKER_LSR");
        if (fearGreedIndex < 0) qualityFlags.add("NO_FEAR_GREED");
        if (!oiHistMap.containsKey(symbol)) qualityFlags.add("NO_OI_HISTORY");
        if (!aggTradeAvailable) qualityFlags.add("NO_AGG_TRADE");
        // STALE_AGG_TRADE: deque 内有旧数据但 WS >30s 未更新（断流/卡顿），下游 executor 弃权
        if (aggTradeAvailable) {
            long lastUpdate = orderFlowAggregator.getLastUpdateMs(symbol);
            if (lastUpdate > 0 && System.currentTimeMillis() - lastUpdate > 30_000) {
                qualityFlags.add("STALE_AGG_TRADE");
            }
        }
        if (dvolIndex <= 0 && atmIv <= 0) qualityFlags.add("NO_OPTION_IV");
        if (atr5m == null) qualityFlags.add("NO_ATR_5M");
        if (bollBw == null) qualityFlags.add("NO_BOLL_5M");
        if (qualityFlags.stream().anyMatch(flag -> flag.startsWith("MISSING_TF_"))) {
            qualityFlags.add("PARTIAL_KLINE_DATA");
        }

        // 8. 新闻解析
        List<NewsItem> newsItems = parseNewsItems(newsData);
        if (newsItems.isEmpty()) qualityFlags.add("NO_NEWS");

        FeatureSnapshot snapshot = new FeatureSnapshot(symbol, LocalDateTime.now(), lastPrice, barHigh, barLow, spotLastPrice,
                indicatorsByTf, priceChanges,
                spotBidAskImbalance, spotPriceChange5m, spotPerpBasisBps, spotLeadLagScore,
                bidAskImbalance, tradeDelta, tradeIntensity, largeTradeBias, oiChangeRate,
                fundingDeviation, fundingRateTrend, fundingRateExtreme, lsrExtreme,
                liquidationPressure, liquidationVolumeUsdt,
                topTraderBias, takerBuySellPressure,
                fearGreedIndex, fearGreedLabel,
                atr1m, atr5m, bollBw, bollSqueeze,
                dvolIndex, atmIv, ivSkew25d, ivTermSlope,
                regime, newsItems, qualityFlags,
                0.5, "NONE");
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
                if (p == null || p.isBlank()) {
                    p = t.getString("price");
                }
                if (p != null) return new BigDecimal(p);
            } catch (Exception ignored) {}
        }
        // fallback: 最新1m close
        List<BigDecimal> c1m = closes.get("1m");
        return (c1m != null && !c1m.isEmpty()) ? c1m.getLast() : null;
    }

    private BigDecimal extractLatestKlineField(Map<String, List<BigDecimal[]>> parsedKlines,
                                               String interval, int index) {
        List<BigDecimal[]> klines = parsedKlines.get(interval);
        if ((klines == null || klines.isEmpty()) && !"1m".equals(interval)) {
            klines = parsedKlines.get("1m");
        }
        if (klines == null || klines.isEmpty()) return null;
        BigDecimal[] latest = klines.getLast();
        return latest.length > index ? latest[index] : null;
    }

    private BigDecimal calcRecentPctChange(List<BigDecimal> closes, int bars) {
        if (closes == null || closes.size() <= bars) return null;
        return CryptoIndicatorCalculator.pctChange(closes.get(closes.size() - 1 - bars), closes.getLast());
    }

    private double calcBasisBps(BigDecimal futuresPrice, BigDecimal spotPrice) {
        if (futuresPrice == null || spotPrice == null || spotPrice.signum() <= 0) return 0;
        return futuresPrice.subtract(spotPrice)
                .multiply(BigDecimal.valueOf(10000))
                .divide(spotPrice, 2, java.math.RoundingMode.HALF_UP)
                .doubleValue();
    }

    /**
     * 现货领先代理：比较近几根现货与合约的短时收益差。
     * 正值表示现货更强，负值表示合约更强。
     */
    private double calcSpotLeadLagScore(Map<String, List<BigDecimal[]>> spotKlines,
                                        Map<String, List<BigDecimal[]>> futuresKlines) {
        Double fast = calcLeadLagWindowScore(spotKlines.get("1m"), futuresKlines.get("1m"), 3, 0.08);
        if (fast != null) return fast;
        Double slow = calcLeadLagWindowScore(spotKlines.get("5m"), futuresKlines.get("5m"), 1, 0.12);
        return slow != null ? slow : 0;
    }

    private Double calcLeadLagWindowScore(List<BigDecimal[]> spot, List<BigDecimal[]> futures,
                                          int bars, double scalePct) {
        if (spot == null || futures == null || spot.size() <= bars || futures.size() <= bars) return null;
        BigDecimal spotChange = CryptoIndicatorCalculator.pctChange(
                spot.get(spot.size() - 1 - bars)[2], spot.getLast()[2]);
        BigDecimal futuresChange = CryptoIndicatorCalculator.pctChange(
                futures.get(futures.size() - 1 - bars)[2], futures.getLast()[2]);
        if (spotChange == null || futuresChange == null || scalePct <= 0) return null;
        return Math.clamp((spotChange.subtract(futuresChange)).doubleValue() / scalePct, -1, 1);
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

            List<Double> ratios = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                JSONObject item = arr.getJSONObject(i);
                if (item == null) continue;
                Double ratio = item.getDouble("longShortRatio");
                if (ratio != null && Double.isFinite(ratio) && ratio > 0) {
                    ratios.add(ratio);
                }
            }
            if (ratios.size() < 2) return 0;

            // 多空比各币种长期中枢不同，用窗口内百分位判断拥挤，避免固定阈值误判。
            double latestRatio = ratios.get(ratios.size() - 1);
            int less = 0;
            int equal = 0;
            for (double ratio : ratios) {
                int cmp = Double.compare(ratio, latestRatio);
                if (cmp < 0) less++;
                if (cmp == 0) equal++;
            }
            double percentile = (less + equal * 0.5) / ratios.size();
            return Math.clamp((percentile - 0.5) * 2.0, -1.0, 1.0);
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
            // byte模式解析，绕开fastjson2 char buffer扩容bug
            JSONObject root = JSON.parseObject(newsData.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            JSONArray items = root.getJSONArray("Data");
            if (items == null || items.isEmpty()) return List.of();
            List<NewsItem> result = new ArrayList<>(items.size());
            int batchSize = 10;
            for (int batch = 0; batch * batchSize < items.size(); batch++) {
                int start = batch * batchSize;
                int end = Math.min(start + batchSize, items.size());
                for (int i = start; i < end; i++) {
                    JSONObject item = items.getJSONObject(i);
                    if (item == null) continue;
                    result.add(new NewsItem(
                            item.getString("TITLE"),
                            item.getString("BODY"),
                            item.getString("SOURCE_DATA_SOURCE_KEY"),
                            item.getString("GUID"),
                            item.getLongValue("PUBLISHED_ON")
                    ));
                }
                log.info("[Q2] 新闻第{}批解析完成 [{}-{})", batch + 1, start, end);
            }
            return result;
        } catch (Exception e) {
            log.warn("[Q2] 新闻解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 解析 Deribit DVOL（BTC波动率指数）。
     * 响应: {"result":{"data":[[ts,open,high,low,close], ...]}}
     * 取最新一根 close。
     */
    private double parseDvol(String dvolJson) {
        if (dvolJson == null || dvolJson.isBlank()) return 0;
        try {
            JSONObject root = JSON.parseObject(dvolJson);
            JSONArray data = root.getJSONObject("result").getJSONArray("data");
            if (data == null || data.isEmpty()) return 0;
            JSONArray last = data.getJSONArray(data.size() - 1);
            return last.getDoubleValue(4); // close
        } catch (Exception e) {
            log.warn("[Q2.iv] DVOL解析失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 从 Deribit book_summary_by_currency 计算 ATM IV、25d skew、term structure slope。
     * 响应: {"result":[{instrument_name:"BTC-28MAR25-90000-C", mark_iv:55.2, underlying_price:84000, ...}]}
     * <p>
     * 策略：
     * - 按到期日分组，找最近到期日（>1天）
     * - ATM: strike 最接近 underlying_price 的 call 的 mark_iv
     * - 25d skew 近似: OTM 5-8% call IV - OTM 5-8% put IV（正=call贵=看涨偏好）
     * - term slope: 次近到期 ATM IV - 最近到期 ATM IV（正=远期IV更高=contango）
     *
     * @return [atmIv, skew25d, termSlope]，失败全部返回0
     */
    private double[] parseOptionIv(String bookSummaryJson, BigDecimal lastPrice) {
        double[] empty = {0, 0, 0};
        if (bookSummaryJson == null || bookSummaryJson.isBlank() || lastPrice == null) return empty;
        try {
            JSONObject root = JSON.parseObject(bookSummaryJson);
            JSONArray results = root.getJSONArray("result");
            if (results == null || results.isEmpty()) return empty;

            double spot = lastPrice.doubleValue();
            if (spot <= 0) return empty;

            // 按到期日分组: expiry -> list of {strike, type(C/P), mark_iv}
            record OptionInfo(String expiry, double strike, String type, double markIv) {}
            Map<String, List<OptionInfo>> byExpiry = new java.util.TreeMap<>(
                    Comparator.comparing(s -> LocalDate.parse(s, DERIBIT_EXPIRY_FMT)));

            for (int i = 0; i < results.size(); i++) {
                JSONObject item = results.getJSONObject(i);
                double markIv = item.getDoubleValue("mark_iv");
                if (markIv <= 0) continue;
                String name = item.getString("instrument_name");
                if (name == null) continue;
                // BTC-28MAR25-90000-C
                String[] parts = name.split("-");
                if (parts.length < 4) continue;
                String expiry = parts[1];
                double strike;
                try { strike = Double.parseDouble(parts[2]); } catch (NumberFormatException e) { continue; }
                String type = parts[3]; // C or P
                byExpiry.computeIfAbsent(expiry, k -> new ArrayList<>())
                        .add(new OptionInfo(expiry, strike, type, markIv));
            }

            if (byExpiry.isEmpty()) return empty;

            // 找最近到期日（至少有几个合约的）和次近到期日
            List<String> expiries = byExpiry.entrySet().stream()
                    .filter(e -> e.getValue().size() >= 4)
                    .map(Map.Entry::getKey)
                    .toList();
            if (expiries.isEmpty()) return empty;

            String nearExpiry = expiries.getFirst();
            List<OptionInfo> nearOptions = byExpiry.get(nearExpiry);

            // ATM IV: strike 最接近 spot 的 call
            double atmIv = nearOptions.stream()
                    .filter(o -> "C".equals(o.type()))
                    .min(java.util.Comparator.comparingDouble(o -> Math.abs(o.strike() - spot)))
                    .map(OptionInfo::markIv)
                    .orElse(0.0);

            // 25d skew 近似: OTM 5-8% 区间
            double otmLow = spot * 1.05, otmHigh = spot * 1.08;
            double otmPutLow = spot * 0.92, otmPutHigh = spot * 0.95;
            double callIv = nearOptions.stream()
                    .filter(o -> "C".equals(o.type()) && o.strike() >= otmLow && o.strike() <= otmHigh)
                    .mapToDouble(OptionInfo::markIv).average().orElse(0);
            double putIv = nearOptions.stream()
                    .filter(o -> "P".equals(o.type()) && o.strike() >= otmPutLow && o.strike() <= otmPutHigh)
                    .mapToDouble(OptionInfo::markIv).average().orElse(0);
            double skew25d = (callIv > 0 && putIv > 0) ? callIv - putIv : 0;

            // term structure slope
            double termSlope = 0;
            if (expiries.size() >= 2) {
                String farExpiry = expiries.get(1);
                List<OptionInfo> farOptions = byExpiry.get(farExpiry);
                double farAtmIv = farOptions.stream()
                        .filter(o -> "C".equals(o.type()))
                        .min(java.util.Comparator.comparingDouble(o -> Math.abs(o.strike() - spot)))
                        .map(OptionInfo::markIv)
                        .orElse(0.0);
                if (atmIv > 0 && farAtmIv > 0) {
                    termSlope = farAtmIv - atmIv;
                }
            }

            return new double[]{atmIv, skew25d, termSlope};
        } catch (Exception e) {
            log.warn("[Q2.iv] 期权IV解析失败: {}", e.getMessage());
            return empty;
        }
    }
}
