package com.mawai.wiibservice.agent.quant.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.fastjson2.JSON;
import com.mawai.wiibcommon.entity.ForceOrder;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.service.ForceOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 数据采集节点：合并CollectMarketNode + CollectNewsNode。
 * 内部虚拟线程并行采集K线(6周期)、ticker、funding、orderbook、OI、LSR、新闻。
 */
@Slf4j
@RequiredArgsConstructor
public class CollectDataNode implements NodeAction {

    private final BinanceRestClient binanceRestClient;
    private final ForceOrderService forceOrderService;

    @Override
    public Map<String, Object> apply(OverAllState state) {
        long startMs = System.currentTimeMillis();
        String symbol = (String) state.value("target_symbol").orElse("BTCUSDT");
        if (symbol.isBlank()) symbol = "BTCUSDT";
        log.info("[Q1.0] collect_data开始 symbol={}", symbol);

        Map<String, Map<String, String>> klineMap = new HashMap<>();
        Map<String, String> tickerMap = new HashMap<>();
        Map<String, String> fundingRateMap = new HashMap<>();
        Map<String, String> fundingRateHistMap = new HashMap<>();
        Map<String, String> orderbookMap = new HashMap<>();
        Map<String, String> openInterestMap = new HashMap<>();
        Map<String, String> openInterestHistMap = new HashMap<>();
        Map<String, String> longShortRatioMap = new HashMap<>();
        Map<String, String> forceOrdersMap = new HashMap<>();
        Map<String, String> topTraderPositionMap = new HashMap<>();
        Map<String, String> takerLongShortMap = new HashMap<>();

        // FGI：全市场通用，调度器可能已预取
        String preFetchedFearGreed = (String) state.value("fear_greed_data").orElse(null);
        boolean skipFearGreed = preFetchedFearGreed != null
                && !preFetchedFearGreed.isBlank() && !"{}".equals(preFetchedFearGreed);
        String fearGreedData = skipFearGreed ? preFetchedFearGreed : "{}";
        String newsData = "{}";

        String[] intervals = {"1m", "5m", "15m", "1h", "4h", "1d"};
        int[] limits = {120, 288, 192, 168, 180, 90};

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            String sym = symbol;

            // 并行采集全部数据（合约API）
            @SuppressWarnings("unchecked")
            Future<String>[] klineFutures = new Future[6];
            for (int i = 0; i < 6; i++) {
                int idx = i;
                klineFutures[i] = executor.submit(
                        () -> binanceRestClient.getFuturesKlines(sym, intervals[idx], limits[idx], null));
            }
            var tickerF = executor.submit(() -> binanceRestClient.getFutures24hTicker(sym));
            var fundingF = executor.submit(() -> binanceRestClient.getFundingRate(sym));
            var fundingHistF = executor.submit(() -> binanceRestClient.getFundingRateHistory(sym, 48));
            var obF = executor.submit(() -> binanceRestClient.getFuturesOrderbook(sym, 20));
            var oiF = executor.submit(() -> binanceRestClient.getOpenInterest(sym));
            var oiHistF = executor.submit(() -> binanceRestClient.getOpenInterestHist(sym, "5m", 48));
            var lsrF = executor.submit(() -> binanceRestClient.getLongShortRatio(sym));
            var forceOrdersF = executor.submit(() -> buildForceOrdersJson(sym));
            var topTraderF = executor.submit(() -> binanceRestClient.getTopTraderPositionRatio(sym, "5m", 24));
            var takerLsrF = executor.submit(() -> binanceRestClient.getTakerLongShortRatio(sym, "5m", 24));
            var fearGreedF = skipFearGreed ? null
                    : executor.submit(() -> binanceRestClient.getFearGreedIndex(2));
            String coin = sym.replace("USDT", "").replace("USDC", "");
            var newsF = executor.submit(() -> binanceRestClient.getCryptoNews(coin, 30, "EN"));

            // 收集K线
            Map<String, String> klines = new HashMap<>();
            for (int i = 0; i < 6; i++) {
                try {
                    String data = klineFutures[i].get(10, TimeUnit.SECONDS);
                    if (data != null) klines.put(intervals[i], data);
                } catch (Exception e) {
            log.warn("[Q1] K线{}采集失败: {}", intervals[i], e.getMessage());
                }
            }
            klineMap.put(sym, klines);

            // 收集其他数据
            tickerMap.put(sym, safeGet(tickerF, "ticker"));
            putIfNotNull(fundingRateMap, sym, safeGet(fundingF, "funding"));
            putIfNotNull(fundingRateHistMap, sym, safeGet(fundingHistF, "fundingRateHist"));
            putIfNotNull(orderbookMap, sym, safeGet(obF, "orderbook"));
            putIfNotNull(openInterestMap, sym, safeGet(oiF, "openInterest"));
            putIfNotNull(openInterestHistMap, sym, safeGet(oiHistF, "openInterestHist"));
            putIfNotNull(longShortRatioMap, sym, safeGet(lsrF, "longShortRatio"));
            putIfNotNull(forceOrdersMap, sym, safeGet(forceOrdersF, "forceOrders"));
            putIfNotNull(topTraderPositionMap, sym, safeGet(topTraderF, "topTraderPosition"));
            putIfNotNull(takerLongShortMap, sym, safeGet(takerLsrF, "takerLongShort"));

            // 恐惧贪婪
            if (!skipFearGreed) {
                String rawFearGreed = safeGet(fearGreedF, "fearGreed");
                if (rawFearGreed != null && !rawFearGreed.isBlank()) {
                    fearGreedData = rawFearGreed;
                }
            }

            // 新闻
            String rawNews = safeGet(newsF, "news");
            if (rawNews != null && !rawNews.isBlank()) {
                newsData = rawNews;
            }
        } catch (Exception e) {
            log.warn("[Q1] collect_data采集异常: {}", e.getMessage());
        }

        // 判断数据可用性
        Map<String, String> kline = klineMap.get(symbol);
        String ticker = tickerMap.get(symbol);
        boolean klineOk = kline != null && kline.values().stream().anyMatch(v -> v != null && !v.isBlank());
        boolean dataAvailable = klineOk && ticker != null && !ticker.isBlank();
        log.info("[Q1.1] collect_data完成 symbol={} klines={}周期 dataAvailable={} ticker={} funding={} fundingHist={} ob={} oi={} oiHist={} lsr={} forceOrders={} topTrader={} takerLsr={} fearGreed={}chars news={}chars 耗时{}ms",
                symbol, kline != null ? kline.size() : 0, dataAvailable,
                ticker != null, fundingRateMap.containsKey(symbol), fundingRateHistMap.containsKey(symbol),
                orderbookMap.containsKey(symbol), openInterestMap.containsKey(symbol),
                openInterestHistMap.containsKey(symbol), longShortRatioMap.containsKey(symbol),
                forceOrdersMap.containsKey(symbol), topTraderPositionMap.containsKey(symbol),
                takerLongShortMap.containsKey(symbol), fearGreedData.length(), newsData.length(),
                System.currentTimeMillis() - startMs);

        Map<String, Object> result = new HashMap<>();
        result.put("kline_map", klineMap);
        result.put("ticker_map", tickerMap);
        result.put("funding_rate_map", fundingRateMap);
        result.put("funding_rate_hist_map", fundingRateHistMap);
        result.put("orderbook_map", orderbookMap);
        result.put("open_interest_map", openInterestMap);
        result.put("oi_hist_map", openInterestHistMap);
        result.put("long_short_ratio_map", longShortRatioMap);
        result.put("force_orders_map", forceOrdersMap);
        result.put("top_trader_position_map", topTraderPositionMap);
        result.put("taker_long_short_map", takerLongShortMap);
        result.put("fear_greed_data", fearGreedData);
        result.put("news_data", newsData);
        result.put("data_available", dataAvailable);
        return result;
    }

    private String safeGet(Future<String> f, String label) {
        try {
            return f.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[Q1] {}采集超时/失败: {}", label, e.getMessage());
            return null;
        }
    }

    private void putIfNotNull(Map<String, String> map, String key, String value) {
        if (value != null) map.put(key, value);
    }

    private String buildForceOrdersJson(String symbol) {
        List<ForceOrder> orders = forceOrderService.getRecent(symbol, 30);
        if (orders == null || orders.isEmpty()) return null;
        // 转成 BuildFeaturesNode.calcLiquidationPressure 期望的格式: [{side, price, origQty}]
        List<Map<String, Object>> list = orders.stream().map(o -> Map.<String, Object>of(
                "side", o.getSide(),
                "price", o.getAvgPrice(),
                "origQty", o.getQuantity()
        )).toList();
        return JSON.toJSONString(list);
    }
}
