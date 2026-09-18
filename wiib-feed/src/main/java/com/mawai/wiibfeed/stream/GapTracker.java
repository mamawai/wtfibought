package com.mawai.wiibfeed.stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 一条价格连接的空窗记录：每帧记"最后一帧时间"，连上时把 [最后一帧, 现在] 发成 gap 事件，
 * sim 侧按这段空窗拉 1m K 线补漏。
 * <p>内存值管运行中断连，Redis 值管进程重启；重连后不清内存值——连上又没来帧就断，下次 from 仍正确。
 */
@Slf4j
final class GapTracker {

    private static final String LAST_MSG_KEY_PREFIX = "feed:last-msg:";
    private static final long REDIS_WRITE_INTERVAL_MS = 5_000L;

    private final String name;
    private final String kind;
    private final StringRedisTemplate redisTemplate;
    private final MatchPricePublisher publisher;

    private volatile long lastMs;
    private volatile long lastRedisWriteMs;

    GapTracker(String name, String kind, StringRedisTemplate redisTemplate, MatchPricePublisher publisher) {
        this.name = name;
        this.kind = kind;
        this.redisTemplate = redisTemplate;
        this.publisher = publisher;
    }

    /** 每帧调用。Redis 节流 5s 写一次 */
    void touch() {
        long now = System.currentTimeMillis();
        lastMs = now;
        if (now - lastRedisWriteMs < REDIS_WRITE_INTERVAL_MS) return;
        lastRedisWriteMs = now;
        redisTemplate.opsForValue().set(LAST_MSG_KEY_PREFIX + name, String.valueOf(now));
    }

    /** 连上时调用。from 取最后一帧时间，内存没有（进程刚起）读 Redis，都没有不发 */
    void publishGap() {
        long from = lastMs;
        if (from == 0L) from = readLastMs();
        if (from == 0L) return;
        long to = System.currentTimeMillis();
        publisher.publish("{\"type\":\"gap\",\"kind\":\"" + kind + "\",\"from\":" + from + ",\"to\":" + to + "}");
        log.info("{} 空窗 {}ms，发 gap 事件补漏", name, to - from);
    }

    private long readLastMs() {
        String v = redisTemplate.opsForValue().get(LAST_MSG_KEY_PREFIX + name);
        return v == null ? 0L : Long.parseLong(v);
    }
}
