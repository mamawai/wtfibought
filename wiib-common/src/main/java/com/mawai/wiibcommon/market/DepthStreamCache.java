package com.mawai.wiibcommon.market;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * depth20@100ms WS 快照缓存（合约、现货各一份）。比 REST 轮询更新鲜（100ms 级），合约那份供 CollectDataNode 优先使用。
 *
 * <p>存储后端为 Redis KV：feed 进程写、agent 进程 {@link #getFreshDepth} 读，
 * 天然跨进程——两边各跑一个实例、共享同一 Redis，无需额外消费者。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DepthStreamCache {

    private static final String KEY_PREFIX = "market:depth:";
    /** 现货单独一份：币名跟合约撞（都叫 BTCUSDT） */
    private static final String SPOT_KEY_PREFIX = "market:spot-depth:";
    // Redis 兜底 TTL（防 symbol 下线后键残留）；真正的新鲜度判定在读侧按收到时刻 + maxAgeMs
    private static final Duration REDIS_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;

    /** 合约 WS depth 回调，存「receivedAt|原始JSON」：receivedAt 是 feed 收到这一帧的时刻，交易所事件时间 E 在 JSON 里 */
    public void onDepthUpdate(String symbol, String rawJson, long receivedAtMs) {
        write(KEY_PREFIX, symbol, rawJson, receivedAtMs);
    }

    /** 现货 WS depth 回调，存「receivedAt|原始JSON」：现货这条推送不带事件时间 */
    public void onSpotDepthUpdate(String symbol, String rawJson, long receivedAtMs) {
        write(SPOT_KEY_PREFIX, symbol, rawJson, receivedAtMs);
    }

    /** 写失败降级，不拖垮 WS 线程 */
    private void write(String prefix, String symbol, String rawJson, long atMs) {
        try {
            redisTemplate.opsForValue().set(prefix + symbol, atMs + "|" + rawJson, REDIS_TTL);
        } catch (Exception e) {
            log.warn("[DepthStream] 写 Redis 失败 key={}{}: {}", prefix, symbol, e.toString());
        }
    }

    /**
     * 获取缓存的合约深度快照。
     * @param maxAgeMs 最大允许年龄（毫秒，按 feed 收到的时刻算），超龄返回 null
     * @return 原始 JSON 或 null（Redis 异常/脏数据/无数据时一律 null，调用方有 REST 兜底）
     */
    public String getFreshDepth(String symbol, long maxAgeMs) {
        Snapshot s = snapshot(symbol);
        if (s == null || System.currentTimeMillis() - s.atMs() > maxAgeMs) return null;
        return s.json();
    }

    /** 一份快照：feed 收到它的时刻 + 原始 JSON */
    public record Snapshot(long atMs, String json) {}

    /** 合约快照，不判新鲜；Redis 异常/脏数据/无数据返回 null */
    public Snapshot snapshot(String symbol) {
        return read(KEY_PREFIX, symbol);
    }

    /** 现货快照，不判新鲜；Redis 异常/脏数据/无数据返回 null */
    public Snapshot spotSnapshot(String symbol) {
        return read(SPOT_KEY_PREFIX, symbol);
    }

    private Snapshot read(String prefix, String symbol) {
        try {
            String value = redisTemplate.opsForValue().get(prefix + symbol);
            if (value == null) return null;
            int sep = value.indexOf('|');
            if (sep < 0) return null;
            return new Snapshot(Long.parseLong(value.substring(0, sep)), value.substring(sep + 1));
        } catch (Exception e) {
            return null;
        }
    }
}
