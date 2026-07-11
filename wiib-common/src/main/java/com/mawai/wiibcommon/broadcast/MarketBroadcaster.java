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
    public static final String FUTURES_CHANNEL = CHANNEL_PREFIX + "futures";
    public static final String KLINE_CHANNEL = CHANNEL_PREFIX + "kline";
    // feed WS 流健康：连/断/重连状态变化时事件驱动推送（非定时），载荷是全量快照 JSON
    public static final String STREAM_HEALTH_CHANNEL = CHANNEL_PREFIX + "stream-health";
    // 进程 JVM 监控：feed/quant 定时采样发布，sim 中继到 /topic/monitor/{进程}（sim 自身直推不走此频道）
    public static final String MONITOR_CHANNEL = CHANNEL_PREFIX + "monitor";

    public void broadcastStockQuote(String stockCode, String message) {
        publish(STOCK_CHANNEL, stockCode, message);
    }

    public void broadcastCryptoQuote(String symbol, String message) {
        publish(CRYPTO_CHANNEL, symbol, message);
    }

    public void broadcastFuturesQuote(String symbol, String message) {
        publish(FUTURES_CHANNEL, symbol, message);
    }

    /** 实时 K 线（含未收盘的当前根）：o/h/l/c/v/q/i/x，前端蜡烛图 + 成交量柱状图用。 */
    public void broadcastKline(String symbol, String message) {
        publish(KLINE_CHANNEL, symbol, message);
    }

    /** @param topic price/round/activity */
    public void broadcastPrediction(String topic, String message) {
        publish(PREDICTION_CHANNEL, topic, message);
    }

    /** feed WS 流健康全量快照（JSON 数组）：某条流状态一变就推，前端整表替换。code 固定 "streams"，复用 code|json 中继。 */
    public void broadcastStreamHealth(String snapshotJson) {
        publish(STREAM_HEALTH_CHANNEL, "streams", snapshotJson);
    }

    /** 进程 JVM 快照：code=进程名（feed/quant），sim 中继到 /topic/monitor/{进程}。 */
    public void broadcastMonitor(String process, String snapshotJson) {
        publish(MONITOR_CHANNEL, process, snapshotJson);
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
