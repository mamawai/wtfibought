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
 * 空窗记录。要害是 from 取"最后一帧收到的时刻"而不是"发现断开那一刻"——
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

    /** from 是最后一帧收到的时刻：积压时它比处理时刻早，按处理时刻算会把积压那段漏在空窗外 */
    @Test
    void touch过用最后一帧收到的时刻当from() {
        GapTracker t = tracker();
        t.touch(1700000000000L);
        t.publishGap();

        String json = publishedJson();
        assertThat(json).contains("\"type\":\"gap\"").contains("\"kind\":\"spot\"").contains("\"from\":1700000000000");
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
        t.touch(1700000000000L);
        t.touch(1700000001000L);
        t.touch(1700000002000L);

        verify(valueOps, times(1)).set(eq("feed:last-msg:Spot"), anyString());
    }
}
