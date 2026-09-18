package com.mawai.wiibfeed.stream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 空窗记录。要害是 from 取"最后一帧时间"而不是"发现断开那一刻"——
 * WsConnection 连上时会把自己的 lastMessageAt 重置成 now，所以这个时间必须 handler 自己记。
 */
class GapTrackerTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final MatchPricePublisher publisher = mock(MatchPricePublisher.class);

    private GapTracker tracker() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        return new GapTracker("Spot", "spot", redisTemplate, publisher);
    }

    private String publishedJson() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(publisher).publish(captor.capture());
        return captor.getValue();
    }

    @Test
    void touch过用内存值当from() {
        GapTracker t = tracker();
        long before = System.currentTimeMillis();
        t.touch();
        t.publishGap();

        String json = publishedJson();
        assertThat(json).contains("\"type\":\"gap\"").contains("\"kind\":\"spot\"");
        long from = Long.parseLong(json.replaceAll(".*\"from\":(\\d+).*", "$1"));
        assertThat(from).isGreaterThanOrEqualTo(before);
    }

    @Test
    void 没touch过读Redis值当from() {
        when(valueOps.get("feed:last-msg:Spot")).thenReturn("1700000000000");

        tracker().publishGap();

        assertThat(publishedJson()).contains("\"from\":1700000000000");
    }

    @Test
    void 内存和Redis都没有不发() {
        when(valueOps.get("feed:last-msg:Spot")).thenReturn(null);

        tracker().publishGap();

        verify(publisher, never()).publish(anyString());
    }

    /** 每帧都写 Redis 纯浪费：5s 内多次 touch 只写一次 */
    @Test
    void 五秒内多次touch只写一次Redis() {
        GapTracker t = tracker();
        t.touch();
        t.touch();
        t.touch();

        verify(valueOps, times(1)).set(eq("feed:last-msg:Spot"), anyString());
    }
}
