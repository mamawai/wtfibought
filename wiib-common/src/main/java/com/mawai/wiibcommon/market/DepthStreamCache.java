package com.mawai.wiibcommon.market;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 合约 depth20@100ms WS 快照缓存。比 REST 轮询更新鲜（100ms 级），供 CollectDataNode 优先使用。
 *
 * <p>存储后端为 Redis KV：feed 进程 {@link #onDepthUpdate} 写、quant 进程 {@link #getFreshDepth} 读，
 * 天然跨进程——拆服务后 feed 写 quant 读各跑一个实例、共享同一 Redis，无需额外消费者。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DepthStreamCache {

    private static final String KEY_PREFIX = "market:depth:";
    // Redis 兜底 TTL（防 symbol 下线后键残留）；真正的新鲜度判定在 getFreshDepth 按 eventTime + maxAgeMs
    private static final Duration REDIS_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;

    /** WS depth 回调，存「eventTime|原始JSON」；写失败降级，不拖垮 WS 线程。 */
    public void onDepthUpdate(String symbol, String rawJson, long eventTimeMs) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + symbol, eventTimeMs + "|" + rawJson, REDIS_TTL);
        } catch (Exception e) {
            log.warn("[DepthStream] 写 Redis 失败 symbol={}: {}", symbol, e.toString());
        }
    }

    /**
     * 获取缓存的深度快照。
     * @param maxAgeMs 最大允许年龄（毫秒，按交易所 eventTime 算），超龄返回 null
     * @return 原始 JSON 或 null（Redis 异常/脏数据/无数据时一律 null，调用方有 REST 兜底）
     */
    public String getFreshDepth(String symbol, long maxAgeMs) {
        try {
            String value = redisTemplate.opsForValue().get(KEY_PREFIX + symbol);
            if (value == null) return null;
            int sep = value.indexOf('|');
            if (sep < 0) return null;
            long eventTime = Long.parseLong(value.substring(0, sep));
            if (System.currentTimeMillis() - eventTime > maxAgeMs) return null;
            return value.substring(sep + 1);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean hasFreshData(String symbol) {
        return getFreshDepth(symbol, 2000) != null;
    }
}
