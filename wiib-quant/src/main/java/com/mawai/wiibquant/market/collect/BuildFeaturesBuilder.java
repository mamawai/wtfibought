package com.mawai.wiibquant.market.collect;

import com.mawai.wiibcommon.enums.KlineInterval;
import com.mawai.wiibquant.market.domain.FeatureSnapshot;
import com.mawai.wiibquant.market.service.DeribitOptionBook;
import com.mawai.wiibquant.market.indicator.CryptoIndicatorCalculator;
import com.mawai.wiibcommon.market.OrderFlowAggregator;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 特征工程：从原始市场数据构建 FeatureSnapshot（多周期指标、价格变化、基差、期权 IV、恐贪指数），
 * 衍生品与微结构那部分不打分，原样读数由 {@link MarketReadings} 给。
 */
@Slf4j
public final class BuildFeaturesBuilder {

    private final OrderFlowAggregator orderFlowAggregator;
    private final Supplier<KlineInterval> decisionIntervalSupplier;
    /** 墙钟注入点：末根走没走完要测得了 */
    LongSupplier nowMs = System::currentTimeMillis;

    public BuildFeaturesBuilder(OrderFlowAggregator orderFlowAggregator, KlineInterval decisionInterval) {
        this(orderFlowAggregator, () -> decisionInterval);
    }

    public BuildFeaturesBuilder(OrderFlowAggregator orderFlowAggregator, Supplier<KlineInterval> decisionIntervalSupplier) {
        this.orderFlowAggregator = orderFlowAggregator;
        this.decisionIntervalSupplier = decisionIntervalSupplier;
    }

    private static final String[][] PRICE_CHANGE_SOURCES = {
            {"5m", "1m", "5"}, {"15m", "5m", "3"}, {"30m", "5m", "6"},
            {"1h", "15m", "4"}, {"4h", "1h", "4"}, {"24h", "1h", "24"},
    };

    /**
     * 特征构建入口；rawData 缺键按空值兜底，缺什么打什么质量标记。
     * 回包四样：feature_snapshot、indicator_map、price_change_map、readings（{@link MarketReadings}）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> buildFeatures(String symbol, Map<String, Object> rawData) {
        long startMs = System.currentTimeMillis();
        var klineMap = (Map<String, Map<String, String>>) rawData.getOrDefault("kline_map", Map.of());
        var tickerMap = (Map<String, String>) rawData.getOrDefault("ticker_map", Map.of());
        var spotTickerMap = (Map<String, String>) rawData.getOrDefault("spot_ticker_map", Map.of());
        var fundingRateMap = (Map<String, String>) rawData.getOrDefault("funding_rate_map", Map.of());
        var orderbookMap = (Map<String, String>) rawData.getOrDefault("orderbook_map", Map.of());
        var spotOrderbookMap = (Map<String, String>) rawData.getOrDefault("spot_orderbook_map", Map.of());
        var oiHistMap = (Map<String, String>) rawData.getOrDefault("oi_hist_map", Map.of());
        var longShortRatioMap = (Map<String, String>) rawData.getOrDefault("long_short_ratio_map", Map.of());
        var topTraderPositionMap = (Map<String, String>) rawData.getOrDefault("top_trader_position_map", Map.of());
        var takerLongShortMap = (Map<String, String>) rawData.getOrDefault("taker_long_short_map", Map.of());
        String fearGreedData = (String) rawData.getOrDefault("fear_greed_data", "{}");
        String dvolData = (String) rawData.getOrDefault("dvol_data", null);
        String bookSummaryData = (String) rawData.getOrDefault("option_book_summary", null);

        Map<String, String> rawKlines = klineMap.getOrDefault(symbol, Map.of());
        log.info("[Q2.0] build_features开始 symbol={} K线周期={}", symbol, rawKlines.keySet());

        // 1. 技术指标计算（多周期）
        Map<String, Map<String, Object>> indicatorsByTf = new HashMap<>();
        Map<String, List<BigDecimal>> closesByInterval = new HashMap<>();

        long nowMs = this.nowMs.getAsLong();
        for (var entry : rawKlines.entrySet()) {
            String interval = entry.getKey();
            List<BigDecimal[]> klines = CryptoIndicatorCalculator.parseKlines(entry.getValue());
            if (klines.size() < 30) continue;
            // 指标只按已收盘的算；收盘价序列留着在走的末根，最新价要用它
            indicatorsByTf.put(interval, CryptoIndicatorCalculator.calcAll(closedRows(klines, nowMs)));
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

        // 3. 最新价格与现货-合约基差
        BigDecimal lastPrice = extractLastPrice(tickerMap.get(symbol), closesByInterval);
        BigDecimal spotLastPrice = extractLastPrice(spotTickerMap.get(symbol), Map.of());
        double spotPerpBasisBps = calcBasisBps(lastPrice, spotLastPrice);

        // 4. 衍生品与微结构的原始读数
        ObjectNode readings = MarketReadings.build(symbol, rawData, orderFlowAggregator, nowMs);

        // 5. 恐惧贪婪指数
        int fearGreedIndex = calcFearGreed(fearGreedData);
        String fearGreedLabel = mapFearGreedLabel(fearGreedIndex);

        // 6. 决策周期的 ATR
        BigDecimal atr = extractAtr(indicatorsByTf, decisionIntervalSupplier.get().getCode());

        // 7. 期权 IV 特征（Deribit）
        double dvolIndex = parseDvol(dvolData);
        double[] ivFeatures = parseOptionIv(bookSummaryData, lastPrice, Instant.now());
        double atmIv = ivFeatures[0];
        double ivSkew25d = ivFeatures[1];
        double ivTermSlope = ivFeatures[2];
        if (dvolIndex > 0 || atmIv > 0) {
            log.info("[Q2.3.iv] dvol={} atmIv={} skew25d={} termSlope={}",
                    String.format("%.1f", dvolIndex), String.format("%.1f", atmIv),
                    String.format("%.2f", ivSkew25d), String.format("%.2f", ivTermSlope));
        }

        // 8. 数据质量标记
        List<String> qualityFlags = new ArrayList<>();
        if (indicatorsByTf.isEmpty()) qualityFlags.add("NO_INDICATORS");
        if (lastPrice == null) qualityFlags.add("NO_PRICE");
        addMissingTimeframeFlags(qualityFlags, rawKlines, indicatorsByTf);
        if (!orderbookMap.containsKey(symbol)) qualityFlags.add("NO_ORDERBOOK");
        if (!spotTickerMap.containsKey(symbol)) qualityFlags.add("NO_SPOT_TICKER");
        if (!spotOrderbookMap.containsKey(symbol)) qualityFlags.add("NO_SPOT_ORDERBOOK");
        if (!fundingRateMap.containsKey(symbol)) qualityFlags.add("NO_FUNDING");
        if (!longShortRatioMap.containsKey(symbol)) qualityFlags.add("NO_LONG_SHORT_RATIO");
        if (!topTraderPositionMap.containsKey(symbol)) qualityFlags.add("NO_TOP_TRADER");
        if (!takerLongShortMap.containsKey(symbol)) qualityFlags.add("NO_TAKER_LSR");
        if (fearGreedIndex < 0) qualityFlags.add("NO_FEAR_GREED");
        if (!oiHistMap.containsKey(symbol)) qualityFlags.add("NO_OI_HISTORY");
        if (!readings.has("trades3m")) {
            qualityFlags.add("NO_AGG_TRADE");
        } else {
            long lastTrade = orderFlowAggregator.getLastUpdateMs(symbol);
            // 成交 Stream 里有旧数据，但 30 秒没来新成交（断流或卡顿）；0 是这次没读到时间，不算
            if (lastTrade > 0 && System.currentTimeMillis() - lastTrade > 30_000) {
                qualityFlags.add("STALE_AGG_TRADE");
            }
        }
        if (dvolIndex <= 0 && atmIv <= 0) qualityFlags.add("NO_OPTION_IV");
        if (atr == null) qualityFlags.add("NO_ATR");
        if (qualityFlags.stream().anyMatch(flag -> flag.startsWith("MISSING_TF_"))) {
            qualityFlags.add("PARTIAL_KLINE_DATA");
        }

        FeatureSnapshot snapshot = new FeatureSnapshot(symbol, LocalDateTime.now(), lastPrice,
                indicatorsByTf, priceChanges, spotPerpBasisBps,
                fearGreedIndex, fearGreedLabel, atr,
                dvolIndex, atmIv, ivSkew25d, ivTermSlope, qualityFlags);
        log.info("[Q2.4] build_features完成 price={} qualityFlags={} 耗时{}ms",
                lastPrice, qualityFlags, System.currentTimeMillis() - startMs);

        return Map.of("feature_snapshot", snapshot, "indicator_map", indicatorsByTf,
                "price_change_map", priceChanges, "readings", readings);
    }

    private BigDecimal extractLastPrice(String tickerJson, Map<String, List<BigDecimal>> closes) {
        if (tickerJson != null) {
            try {
                ObjectNode t = MAPPER.readValue(tickerJson, ObjectNode.class);
                String p = t.path("lastPrice").asString(null);
                if (p == null || p.isBlank()) {
                    p = t.path("price").asString(null);
                }
                if (p != null) return new BigDecimal(p);
            } catch (Exception ignored) {}
        }
        // fallback: 最新1m close
        List<BigDecimal> c1m = closes.get("1m");
        return (c1m != null && !c1m.isEmpty()) ? c1m.getLast() : null;
    }

    /** 去掉末尾还在走的那根（[6] 是 closeTime）；null 原样回 */
    private static List<BigDecimal[]> closedRows(List<BigDecimal[]> klines, long nowMs) {
        return klines != null && !klines.isEmpty() && klines.getLast()[6].longValue() >= nowMs
                ? klines.subList(0, klines.size() - 1) : klines;
    }

    static double calcBasisBps(BigDecimal futuresPrice, BigDecimal spotPrice) {
        if (futuresPrice == null || spotPrice == null || spotPrice.signum() <= 0) return 0;
        return futuresPrice.subtract(spotPrice)
                .multiply(BigDecimal.valueOf(10000))
                .divide(spotPrice, 2, java.math.RoundingMode.HALF_UP)
                .doubleValue();
    }

    private BigDecimal extractAtr(Map<String, Map<String, Object>> indicators, String tf) {
        Map<String, Object> ind = indicators.get(tf);
        if (ind == null) return null;
        Object v = ind.get("atr14");
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }

    private void addMissingTimeframeFlags(List<String> qualityFlags,
                                          Map<String, String> rawKlines,
                                          Map<String, Map<String, Object>> indicatorsByTf) {
        // 周期表必须与 CollectDataNode 的采集列表一致：没去采的周期缺席不是故障，不该打质量标记
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
     * 恐惧贪婪指数解析。返回当前值(0-100)，失败返回-1。
     */
    static int calcFearGreed(String fearGreedJson) {
        if (fearGreedJson == null || fearGreedJson.isBlank() || "{}".equals(fearGreedJson)) return -1;
        try {
            JsonNode data = MAPPER.readValue(fearGreedJson, ObjectNode.class).path("data");
            if (data.isEmpty()) return -1;
            return data.get(0).path("value").asInt(0);
        } catch (Exception e) {
            log.warn("[Q2] 恐惧贪婪指数解析失败: {}", e.getMessage());
            return -1;
        }
    }

    static String mapFearGreedLabel(int index) {
        if (index < 0) return "UNKNOWN";
        if (index <= 24) return "EXTREME_FEAR";
        if (index <= 44) return "FEAR";
        if (index <= 55) return "NEUTRAL";
        if (index <= 74) return "GREED";
        return "EXTREME_GREED";
    }

    /**
     * 解析 Deribit DVOL（BTC波动率指数）。
     * 响应: {"result":{"data":[[ts,open,high,low,close], ...]}}
     * 取最新一根 close。
     */
    private double parseDvol(String dvolJson) {
        if (dvolJson == null || dvolJson.isBlank()) return 0;
        try {
            JsonNode data = MAPPER.readValue(dvolJson, ObjectNode.class).get("result").path("data");
            if (data.isEmpty()) return 0;
            return data.get(data.size() - 1).path(4).asDouble(0); // close
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
     * @param now 当前时刻，距交割不足一天的到期日按它剔掉
     * @return [atmIv, skew25d, termSlope]，失败全部返回0
     */
    static double[] parseOptionIv(String bookSummaryJson, BigDecimal lastPrice, Instant now) {
        double[] empty = {0, 0, 0};
        if (bookSummaryJson == null || bookSummaryJson.isBlank() || lastPrice == null) return empty;
        try {
            double spot = lastPrice.doubleValue();
            if (spot <= 0) return empty;

            // 解析统一走 DeribitOptionBook(日期格式坑见其注释)；
            // 一天内交割的不要：临近交割 IV 不稳，每天 08:00 UTC 换到下一个到期日时读数还会突跳。
            // 再只留合约数足够的到期日，取近月/次近月
            List<List<DeribitOptionBook.Quote>> groups = DeribitOptionBook.parse(bookSummaryJson)
                    .expiringAfter(now.plus(Duration.ofDays(1))).values().stream()
                    .filter(g -> g.size() >= 4)
                    .toList();
            if (groups.isEmpty()) return empty;

            List<DeribitOptionBook.Quote> nearOptions = groups.getFirst();

            // ATM IV: strike 最接近 spot 的 call
            double atmIv = DeribitOptionBook.atmCallIv(nearOptions, spot);

            // 25d skew 近似: OTM 5-8% 区间
            double otmLow = spot * 1.05, otmHigh = spot * 1.08;
            double otmPutLow = spot * 0.92, otmPutHigh = spot * 0.95;
            double callIv = nearOptions.stream()
                    .filter(o -> o.call() && o.strike() >= otmLow && o.strike() <= otmHigh)
                    .mapToDouble(DeribitOptionBook.Quote::markIv).average().orElse(0);
            double putIv = nearOptions.stream()
                    .filter(o -> !o.call() && o.strike() >= otmPutLow && o.strike() <= otmPutHigh)
                    .mapToDouble(DeribitOptionBook.Quote::markIv).average().orElse(0);
            double skew25d = (callIv > 0 && putIv > 0) ? callIv - putIv : 0;

            // term structure slope
            double termSlope = 0;
            if (groups.size() >= 2) {
                double farAtmIv = DeribitOptionBook.atmCallIv(groups.get(1), spot);
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
