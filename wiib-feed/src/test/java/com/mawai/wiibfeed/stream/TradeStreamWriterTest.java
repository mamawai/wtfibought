package com.mawai.wiibfeed.stream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 成交 Stream 的字段契约 ts/p/q/bm/rt，价量原样；写的时候顺带近似裁剪 */
class TradeStreamWriterTest {

    @Test
    @SuppressWarnings("unchecked")
    void 一笔成交写成一条记录_带近似裁剪() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> ops = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(ops);

        new TradeStreamWriter(redis).append("market:spot-trade:", "BTCUSDT", "37000.10", "0.005", true, 123L, 150L);

        ArgumentCaptor<MapRecord<String, String, String>> record = ArgumentCaptor.forClass(MapRecord.class);
        ArgumentCaptor<XAddOptions> options = ArgumentCaptor.forClass(XAddOptions.class);
        verify(ops).add(record.capture(), options.capture());
        assertThat(record.getValue().getStream()).isEqualTo("market:spot-trade:BTCUSDT");
        assertThat(record.getValue().getValue())
                .isEqualTo(Map.of("ts", "123", "p", "37000.10", "q", "0.005", "bm", "1", "rt", "150"));
        assertThat(options.getValue().getMaxlen()).isEqualTo(30_000L);
        assertThat(options.getValue().isApproximateTrimming()).isTrue();
    }
}
