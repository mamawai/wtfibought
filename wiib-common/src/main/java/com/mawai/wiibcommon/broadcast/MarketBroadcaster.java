package com.mawai.wiibcommon.broadcast;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 行情/业务消息广播发布器（共享层）。
 * <p>三域（feed/sim/quant）都用它把消息发到 Redis 频道；订阅 + 推前端在 sim 的 {@code WsBroadcastRelay}。
 * 发布与订阅分离——feed/quant 进程无 WS 网关，只发布不订阅，故不依赖 SimpMessagingTemplate。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketBroadcaster {

    private final StringRedisTemplate redisTemplate;

    public static final String CHANNEL_PREFIX = "ws:broadcast:";
    public static final String STOCK_CHANNEL = CHANNEL_PREFIX + "stock";
    public static final String CRYPTO_CHANNEL = CHANNEL_PREFIX + "crypto";
    public static final String PREDICTION_CHANNEL = CHANNEL_PREFIX + "prediction";
    public static final String QUANT_CHANNEL = CHANNEL_PREFIX + "quant";
    public static final String FUTURES_CHANNEL = CHANNEL_PREFIX + "futures";

    public void broadcastStockQuote(String stockCode, String message) {
        publish(STOCK_CHANNEL, stockCode, message);
    }

    public void broadcastCryptoQuote(String symbol, String message) {
        publish(CRYPTO_CHANNEL, symbol, message);
    }

    public void broadcastFuturesQuote(String symbol, String message) {
        publish(FUTURES_CHANNEL, symbol, message);
    }

    /** @param topic price/round/activity */
    public void broadcastPrediction(String topic, String message) {
        publish(PREDICTION_CHANNEL, topic, message);
    }

    public void broadcastQuantSignal(String symbol, String message) {
        publish(QUANT_CHANNEL, symbol, message);
    }

    /** payload 统一 code|json，订阅端 WsBroadcastRelay 按 | 拆分。失败仅 log 不阻断业务。 */
    private void publish(String channel, String code, String message) {
        try {
            redisTemplate.convertAndSend(channel, code + "|" + message);
        } catch (Exception e) {
            log.error("广播失败 channel={} code={}", channel, code, e);
        }
    }
}
