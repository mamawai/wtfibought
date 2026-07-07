package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.broadcast.MarketBroadcaster;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibfeed.WsConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.http.WebSocket;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 合约流（重 handler）：markPrice@1s 走本 handler 主连接，miniTicker 走 FuturesMiniTick 副连接，
 * 两者共用 {@link #onMessage} 解析（靠 stream 名/字段区分 markPrice vs miniTicker）。
 * 断线启 REST 兜底；重连后补漏合约限价单 + 强平检查。
 * <p>Binance 2026-04-23 起期货 WS 端点拆分，markPrice 与 miniTicker 必须分两条物理连接，故有副连接。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FuturesStreamHandler implements StreamHandler {

    private static final String REDIS_MARK_PRICE_KEY_PREFIX = "market:markprice:";
    private static final String REDIS_FUTURES_PRICE_KEY_PREFIX = "market:futures-price:";
    // 指数价，值格式 <price>|<写入ms>：quant RedisLiqSideData 算 premium=(最新价-指数价)/指数价 用，
    // 带时间戳让读方自判时效（超龄按缺数据 NaN），feed 不硬编码策略的时效参数
    private static final String REDIS_INDEX_PRICE_KEY_PREFIX = "market:indexprice:";

    private final BinanceProperties props;
    private final StringRedisTemplate redisTemplate;
    private final CacheService cacheService;
    private final MarketBroadcaster broadcastService;
    private final BinanceRestClient restClient;
    private final MatchPricePublisher matchPricePublisher;

    private WsConnection conn;
    private RestFallbackPoller fallback;

    @Override public String name() { return "Futures"; }
    @Override public long maxIdleSeconds() { return 90; }

    @Override
    public void bind(WsConnection conn, ScheduledExecutorService scheduler) {
        this.conn = conn;
        this.fallback = new RestFallbackPoller(scheduler, props.getFallbackPollInterval(), "Futures ", this::pollOnce);
    }

    private boolean isFuturesConnected() {
        return conn != null && conn.isConnected();
    }

    // Binance 2026-04-23起: markPrice走/market端点
    @Override
    public String buildUrl() {
        return StreamUrls.combinedUrl(props.getFuturesWsUrl(), "market", props.getSymbols(), "markPrice@1s");
    }

    @Override
    public void onConnected(WebSocket ws) {
        fallback.stop();
        Thread.startVirtualThread(() -> {
            recoverMissedFuturesLimitOrders();
            recoverMissedLiquidations();
        });
    }

    @Override
    public void onDisconnected() {
        fallback.start();
    }

    @Override
    public void onMessage(String raw) {
        // 真实WS组合流: {"stream":"btcusdt@markPrice@1s","data":{...,"s":"BTCUSDT","p":"..."}} / {"stream":"btcusdt@miniTicker","data":{...,"s":"BTCUSDT","c":"..."}}
        // REST兜底伪消息: {"s":"BTCUSDT","p":"..."} / {"s":"BTCUSDT","c":"..."}
        int streamIdx = raw.indexOf("\"stream\":\"");
        boolean isMarkPrice;
        if (streamIdx >= 0) {
            int streamStart = streamIdx + 10;
            int streamEnd = raw.indexOf('"', streamStart);
            if (streamEnd < 0) return;
            isMarkPrice = raw.regionMatches(streamEnd - 12, "markPrice@1s", 0, 12);
        } else {
            int pIdx = raw.indexOf("\"p\":\"");
            int cIdx = raw.indexOf("\"c\":\"");
            if (pIdx < 0 && cIdx < 0) return;
            isMarkPrice = pIdx >= 0 && (cIdx < 0 || pIdx < cIdx);
        }

        int sIdx = raw.indexOf("\"s\":\"");
        if (sIdx < 0) return;
        String symbol = StreamParse.extractQuoted(raw, sIdx + 5);

        if (isMarkPrice) {
            int pIdx = raw.indexOf("\"p\":\"", sIdx);
            if (pIdx < 0) return;
            String markPrice = StreamParse.extractQuoted(raw, pIdx + 5);
            redisTemplate.opsForValue().set(REDIS_MARK_PRICE_KEY_PREFIX + symbol, markPrice);
            cacheService.putMarkPrice(symbol, new BigDecimal(markPrice));

            // 指数价 i（markPrice 报文自带；REST 兜底伪消息也补了 i）——缺字段就不写，读方自然 NaN 降级
            int iIdx = raw.indexOf("\"i\":\"", sIdx);
            if (iIdx >= 0) {
                String indexPrice = StreamParse.extractQuoted(raw, iIdx + 5);
                redisTemplate.opsForValue().set(REDIS_INDEX_PRICE_KEY_PREFIX + symbol,
                        indexPrice + "|" + System.currentTimeMillis());
            }

            broadcastService.broadcastFuturesQuote(symbol, "{\"mp\":\"" + markPrice + "\",\"fws\":" + isFuturesConnected() + "}");

            // markPrice 事件发 Redis：sim 侧消费做强平、quant 侧消费喂哨兵（解耦：currentPrice 由 sim 自己读 KV）
            matchPricePublisher.publish("{\"symbol\":\"" + symbol + "\",\"type\":\"markprice\",\"price\":\"" + markPrice + "\"}");
        } else {
            // miniTicker: "c" 是最新价
            int cIdx = raw.indexOf("\"c\":\"", sIdx);
            if (cIdx < 0) return;
            String price = StreamParse.extractQuoted(raw, cIdx + 5);
            redisTemplate.opsForValue().set(REDIS_FUTURES_PRICE_KEY_PREFIX + symbol, price);
            cacheService.putFuturesPrice(symbol, new BigDecimal(price));

            broadcastService.broadcastFuturesQuote(symbol, "{\"fp\":\"" + price + "\",\"fws\":" + isFuturesConnected() + "}");

            // futures 价格事件发 Redis：sim 侧消费做合约限价单结算（解耦：不再进程内直调）
            matchPricePublisher.publish("{\"symbol\":\"" + symbol + "\",\"type\":\"futures\",\"price\":\"" + price + "\"}");
        }
    }

    // ── REST兜底：WS断开期间切REST轮询保证价格不中断 ──

    private void pollOnce() {
        for (String symbol : props.getSymbols()) {
            try {
                String json = restClient.getMarkPrice(symbol);
                if (json != null) {
                    int idx = json.indexOf("\"markPrice\":\"");
                    if (idx >= 0) {
                        String markPrice = StreamParse.extractQuoted(json, idx + 13);
                        // premiumIndex 响应自带 indexPrice，透传进伪消息，WS 断线期 premium 采样不中断
                        int idxPriceIdx = json.indexOf("\"indexPrice\":\"");
                        String indexPart = idxPriceIdx >= 0
                                ? ",\"i\":\"" + StreamParse.extractQuoted(json, idxPriceIdx + 14) + "\"" : "";
                        onMessage("{\"s\":\"" + symbol + "\",\"p\":\"" + markPrice + "\"" + indexPart + "}");
                    }
                }
                String tickerJson = restClient.getFutures24hTicker(symbol);
                if (tickerJson != null) {
                    int idx = tickerJson.indexOf("\"lastPrice\":\"");
                    if (idx >= 0) {
                        String lastPrice = StreamParse.extractQuoted(tickerJson, idx + 13);
                        onMessage("{\"s\":\"" + symbol + "\",\"c\":\"" + lastPrice + "\"}");
                    }
                }
            } catch (Exception e) {
                log.warn("REST轮询Futures {}失败: {}", symbol, e.getMessage());
            }
        }
    }

    private void recoverMissedFuturesLimitOrders() {
        try {
            for (String symbol : props.getSymbols()) {
                BigDecimal[] futuresLowHigh = restClient.getRecentFuturesHighLow(symbol);
                if (futuresLowHigh != null) {
                    matchPricePublisher.publish("{\"symbol\":\"" + symbol + "\",\"type\":\"futures-recover\",\"low\":\""
                            + futuresLowHigh[0].toPlainString() + "\",\"high\":\"" + futuresLowHigh[1].toPlainString() + "\"}");
                }
            }
        } catch (Exception e) {
            log.error("恢复合约限价单失败", e);
        }
    }

    private void recoverMissedLiquidations() {
        try {
            for (String symbol : props.getSymbols()) {
                BigDecimal[] markLowHigh = restClient.getRecentMarkPriceHighLow(symbol);
                BigDecimal[] futuresLowHigh = restClient.getRecentFuturesHighLow(symbol);
                if (markLowHigh != null) {
                    BigDecimal futuresLow = futuresLowHigh != null ? futuresLowHigh[0] : markLowHigh[0];
                    BigDecimal futuresHigh = futuresLowHigh != null ? futuresLowHigh[1] : markLowHigh[1];
                    matchPricePublisher.publish("{\"symbol\":\"" + symbol + "\",\"type\":\"liq-recover\",\"markLow\":\""
                            + markLowHigh[0].toPlainString() + "\",\"markHigh\":\"" + markLowHigh[1].toPlainString()
                            + "\",\"futLow\":\"" + futuresLow.toPlainString() + "\",\"futHigh\":\"" + futuresHigh.toPlainString() + "\"}");
                }
            }
        } catch (Exception e) {
            log.error("恢复强平检查失败", e);
        }
    }
}
