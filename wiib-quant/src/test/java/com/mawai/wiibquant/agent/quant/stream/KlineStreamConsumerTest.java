package com.mawai.wiibquant.agent.quant.stream;

import com.mawai.wiibcommon.market.MarketStreamChannels;
import com.mawai.wiibquant.agent.quant.domain.KlineClosedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KlineStreamConsumerTest {

    @Test
    void republishesEventFromStreamRecord() {
        List<Object> events = new ArrayList<>();
        ApplicationEventPublisher publisher = events::add;
        KlineStreamConsumer consumer = new KlineStreamConsumer(
                mock(StringRedisTemplate.class), mock(RedisConnectionFactory.class), publisher);

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("symbol", "BTCUSDT");
        fields.put("interval", "5m");
        fields.put("openTime", "1");
        fields.put("closeTime", "2");
        fields.put("open", "1");
        fields.put("high", "10");
        fields.put("low", "0");
        fields.put("close", "5");
        fields.put("volume", "10");
        MapRecord<String, String, String> record =
                StreamRecords.newRecord().in(MarketStreamChannels.KLINE_CLOSED_STREAM).ofMap(fields);

        consumer.onMessage(record);

        // Stream 9 字段 → 重建 KlineBar → republish 正确事件（字段契约对齐校验）
        assertThat(events).singleElement().isInstanceOf(KlineClosedEvent.class);
        KlineClosedEvent e = (KlineClosedEvent) events.getFirst();
        assertThat(e.symbol()).isEqualTo("BTCUSDT");
        assertThat(e.interval()).isEqualTo("5m");
        assertThat(e.closeTime()).isEqualTo(2L);
        assertThat(e.bar().openTime()).isEqualTo(1L);
        assertThat(e.bar().open()).isEqualByComparingTo("1");
        assertThat(e.bar().high()).isEqualByComparingTo("10");
        assertThat(e.bar().low()).isEqualByComparingTo("0");
        assertThat(e.bar().close()).isEqualByComparingTo("5");
        assertThat(e.bar().volume()).isEqualByComparingTo("10");
    }
}
