package com.mawai.wiibservice.agent.quant.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.fastjson2.JSON;
import com.mawai.wiibcommon.entity.ForceOrder;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.config.DeribitClient;
import com.mawai.wiibservice.service.DepthStreamCache;
import com.mawai.wiibservice.service.ForceOrderService;
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
public class CollectDataNode implements NodeAction {

    private static final long COLLECT_TIMEOUT_SECONDS = 10L;

    private final BinanceRestClient binanceRestClient;
    private final ForceOrderService forceOrderService;
    private final DepthStreamCache depthStreamCache;
    private final DeribitClient deribitClient;

    public CollectDataNode(BinanceRestClient binanceRestClient, ForceOrderService forceOrderService,
                           DepthStreamCache depthStreamCache, DeribitClient deribitClient) {
        this.binanceRestClient = binanceRestClient;
        this.forceOrderService = forceOrderService;
        this.depthStreamCache = depthStreamCache;
        this.deribitClient = deribitClient;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String symbol = (String) state.value("target_symbol").orElse("BTCUSDT");
        if (symbol.isBlank()) symbol = "BTCUSDT";
        String preFetchedFearGreed = (String) state.value("fear_greed_data").orElse(null);
        return collect(symbol, preFetchedFearGreed);
    }

    /** 独立于 StateGraph 的采集入口，轻周期可直接调用 */
    public Map<String, Object> collect(String symbol, String preFetchedFearGreed) {
        long startMs = System.currentTimeMillis();
        log.info("[Q1.0] collect_data开始 symbol={}", symbol);

        Map<String, Map<String, String>> klineMap = new HashMap<>();
        Map<String, Map<String, String>> spotKlineMap = new HashMap<>();
        Map<String, String> tickerMap = new HashMap<>();
        Map<String, String> spotTickerMap = new HashMap<>();
        Map<String, String> fundingRateMap = new HashMap<>();
        Map<String, String> fundingRateHistMap = new HashMap<>();
        Map<String, String> orderbookMap = new HashMap<>();
        Map<String, String> spotOrderbookMap = new HashMap<>();
        Map<String, String> openInterestMap = new HashMap<>();
        Map<String, String> openInterestHistMap = new HashMap<>();
        Map<String, String> longShortRatioMap = new HashMap<>();
        Map<String, String> forceOrdersMap = new HashMap<>();
        Map<String, String> topTraderPositionMap = new HashMap<>();
        Map<String, String> takerLongShortMap = new HashMap<>();

        // FGI：全市场通用，调度器可能已预取
        boolean skipFearGreed = preFetchedFearGreed != null
                && !preFetchedFearGreed.isBlank() && !"{}".equals(preFetchedFearGreed);
        String fearGreedData = skipFearGreed ? preFetchedFearGreed : "{}";
        String newsData = "{}";
        String dvolData = null;
        String bookSummaryData = null;
        String[] intervals = {"1m", "5m", "15m", "1h", "4h", "1d"};
        int[] limits = {120, 288, 192, 168, 180, 90};
        String[] spotIntervals = {"1m", "5m"};
        int[] spotLimits = {120, 288};

        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(COLLECT_TIMEOUT_SECONDS);
        // 不能用 try-with-resources：ExecutorService.close() 会等待任务结束，可能抵消整轮 deadline。
        //noinspection resource
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {

            // 并行采集全部数据（合约API）
            @SuppressWarnings("unchecked")
            Future<String>[] klineFutures = new Future[6];
            for (int i = 0; i < 6; i++) {
                int idx = i;
                klineFutures[i] = executor.submit(
                        () -> binanceRestClient.getFuturesKlines(symbol, intervals[idx], limits[idx], null));
            }
            @SuppressWarnings("unchecked")
            Future<String>[] spotKlineFutures = new Future[2];
            for (int i = 0; i < 2; i++) {
                int idx = i;
                spotKlineFutures[i] = executor.submit(
                        () -> binanceRestClient.getKlines(symbol, spotIntervals[idx], spotLimits[idx], null));
            }
            var tickerF = executor.submit(() -> binanceRestClient.getFutures24hTicker(symbol));
            var spotTickerF = executor.submit(() -> binanceRestClient.get24hTicker(symbol));
            var fundingF = executor.submit(() -> binanceRestClient.getFundingRate(symbol));
            var fundingHistF = executor.submit(() -> binanceRestClient.getFundingRateHistory(symbol, 48));
            var obF = executor.submit(() -> {
                if (depthStreamCache != null) {
                    String wsDepth = depthStreamCache.getFreshDepth(symbol, 2000);
                    if (wsDepth != null) return wsDepth;
                }
                return binanceRestClient.getFuturesOrderbook(symbol, 20);
            });
            var spotObF = executor.submit(() -> binanceRestClient.getOrderbook(symbol, 20));
            var oiF = executor.submit(() -> binanceRestClient.getOpenInterest(symbol));
            var oiHistF = executor.submit(() -> binanceRestClient.getOpenInterestHist(symbol, "5m", 48));
            var lsrF = executor.submit(() -> binanceRestClient.getLongShortRatio(symbol));
            var forceOrdersF = executor.submit(() -> buildForceOrdersJson(symbol));
            var topTraderF = executor.submit(() -> binanceRestClient.getTopTraderPositionRatio(symbol, "5m", 24));
            var takerLsrF = executor.submit(() -> binanceRestClient.getTakerLongShortRatio(symbol, "5m", 24));
            var fearGreedF = skipFearGreed ? null
                    : executor.submit(() -> binanceRestClient.getFearGreedIndex(2));
            String coin = symbol.replace("USDT", "").replace("USDC", "");
            var newsF = executor.submit(() -> binanceRestClient.getCryptoNews(coin, 30, "EN"));

            // Deribit 期权 IV 数据（并行采集，失败不影响主流程）
            Future<String> dvolF = deribitClient != null
                    ? executor.submit(() -> deribitClient.getDvolIndex(coin, 3600)) : null;
            Future<String> bookSummaryF = deribitClient != null
                    ? executor.submit(() -> deribitClient.getBookSummaryByCurrency(coin)) : null;

            // 收集K线
            Map<String, String> klines = new HashMap<>();
            for (int i = 0; i < 6; i++) {
                String data = safeGet(klineFutures[i], "K线" + intervals[i], deadlineNanos);
                if (data != null) klines.put(intervals[i], data);
            }
            klineMap.put(symbol, klines);

            Map<String, String> spotKlines = new HashMap<>();
            for (int i = 0; i < 2; i++) {
                String data = safeGet(spotKlineFutures[i], "现货K线" + spotIntervals[i], deadlineNanos);
                if (data != null) spotKlines.put(spotIntervals[i], data);
            }
            spotKlineMap.put(symbol, spotKlines);

            // 收集其他数据
            tickerMap.put(symbol, safeGet(tickerF, "ticker", deadlineNanos));
            putIfNotNull(spotTickerMap, symbol, safeGet(spotTickerF, "spotTicker", deadlineNanos));
            putIfNotNull(fundingRateMap, symbol, safeGet(fundingF, "funding", deadlineNanos));
            putIfNotNull(fundingRateHistMap, symbol, safeGet(fundingHistF, "fundingRateHist", deadlineNanos));
            putIfNotNull(orderbookMap, symbol, safeGet(obF, "orderbook", deadlineNanos));
            putIfNotNull(spotOrderbookMap, symbol, safeGet(spotObF, "spotOrderbook", deadlineNanos));
            putIfNotNull(openInterestMap, symbol, safeGet(oiF, "openInterest", deadlineNanos));
            putIfNotNull(openInterestHistMap, symbol, safeGet(oiHistF, "openInterestHist", deadlineNanos));
            putIfNotNull(longShortRatioMap, symbol, safeGet(lsrF, "longShortRatio", deadlineNanos));
            putIfNotNull(forceOrdersMap, symbol, safeGet(forceOrdersF, "forceOrders", deadlineNanos));
            putIfNotNull(topTraderPositionMap, symbol, safeGet(topTraderF, "topTraderPosition", deadlineNanos));
            putIfNotNull(takerLongShortMap, symbol, safeGet(takerLsrF, "takerLongShort", deadlineNanos));

            // 恐惧贪婪
            if (!skipFearGreed) {
                String rawFearGreed = safeGet(fearGreedF, "fearGreed", deadlineNanos);
                if (rawFearGreed != null && !rawFearGreed.isBlank()) {
                    fearGreedData = rawFearGreed;
                }
            }

            // 新闻
            String rawNews = safeGet(newsF, "news", deadlineNanos);
            if (rawNews != null && !rawNews.isBlank()) {
                newsData = rawNews;
            }

            // Deribit IV
            dvolData = dvolF != null ? safeGet(dvolF, "dvol", deadlineNanos) : null;
            bookSummaryData = bookSummaryF != null ? safeGet(bookSummaryF, "bookSummary", deadlineNanos) : null;
        } catch (Exception e) {
            log.warn("[Q1] collect_data采集异常: {}", e.getMessage());
        } finally {
            executor.shutdownNow();
        }

        // 判断数据可用性
        Map<String, String> kline = klineMap.get(symbol);
        String ticker = tickerMap.get(symbol);
        boolean klineOk = kline != null && kline.values().stream().anyMatch(v -> v != null && !v.isBlank());
        boolean dataAvailable = klineOk && ticker != null && !ticker.isBlank();
        log.info("[Q1.1] collect_data完成 symbol={} futuresKlines={} spotKlines={} dataAvailable={} ticker={} spotTicker={} funding={} fundingHist={} ob={} spotOb={} oi={} oiHist={} lsr={} forceOrders={} topTrader={} takerLsr={} fearGreed={}chars news={}chars 耗时{}ms",
                symbol, kline != null ? kline.size() : 0,
                spotKlineMap.getOrDefault(symbol, Map.of()).size(),
                dataAvailable,
                ticker != null, spotTickerMap.containsKey(symbol),
                fundingRateMap.containsKey(symbol), fundingRateHistMap.containsKey(symbol),
                orderbookMap.containsKey(symbol), spotOrderbookMap.containsKey(symbol),
                openInterestMap.containsKey(symbol),
                openInterestHistMap.containsKey(symbol), longShortRatioMap.containsKey(symbol),
                forceOrdersMap.containsKey(symbol), topTraderPositionMap.containsKey(symbol),
                takerLongShortMap.containsKey(symbol), fearGreedData.length(), newsData.length(),
                System.currentTimeMillis() - startMs);

        Map<String, Object> result = new HashMap<>();
        result.put("kline_map", klineMap);
        result.put("spot_kline_map", spotKlineMap);
        result.put("ticker_map", tickerMap);
        result.put("spot_ticker_map", spotTickerMap);
        result.put("funding_rate_map", fundingRateMap);
        result.put("funding_rate_hist_map", fundingRateHistMap);
        result.put("orderbook_map", orderbookMap);
        result.put("spot_orderbook_map", spotOrderbookMap);
        result.put("open_interest_map", openInterestMap);
        result.put("oi_hist_map", openInterestHistMap);
        result.put("long_short_ratio_map", longShortRatioMap);
        result.put("force_orders_map", forceOrdersMap);
        result.put("top_trader_position_map", topTraderPositionMap);
        result.put("taker_long_short_map", takerLongShortMap);
        result.put("fear_greed_data", fearGreedData);
        result.put("news_data", newsData);
        result.put("data_available", dataAvailable);
        if (dvolData != null) result.put("dvol_data", dvolData);
        if (bookSummaryData != null) result.put("option_book_summary", bookSummaryData);
        return result;
    }

    private String safeGet(Future<String> f, String label, long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0 && !f.isDone()) {
            f.cancel(true);
            log.warn("[Q1] {}采集超时，已取消", label);
            return null;
        }
        try {
            return f.get(Math.max(remainingNanos, 0), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            log.warn("[Q1] {}采集超时，已取消", label);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            log.warn("[Q1] {}采集被中断，已取消", label);
            return null;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("[Q1] {}采集失败: {}", label, cause.toString());
            return null;
        } catch (CancellationException e) {
            log.warn("[Q1] {}采集已取消", label);
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
