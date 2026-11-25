package com.mawai.wiibquant.market.stream;

import com.mawai.wiibcommon.handover.RedisStreamReader;
import com.mawai.wiibquant.market.domain.KlineClosedEvent;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.MarketStreamChannels;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * K线收盘 Stream 消费者（代码在 quant 库，跑在 agent 进程里），消费 KlineStreamCache的KLINE_CLOSED_STREAM
 *
 * <p>feed 把收盘写进 Stream，本消费者重建 {@link KlineBar} 并 republish 本地 {@link KlineClosedEvent}，
 * 驱动三个监听者：策略信号（StrategyRuntime）、交易员唤醒（TraderScheduler）、叙事对账（VerificationTask）。
 * 这是本进程跨进程获取实时收盘的<b>唯一</b>入口——拆成独立进程后，本消费者随服务启动即自动从 Redis 取数，
 * 监听者一行不用改。</p>
 *
 * <p>进活的口：开口是开始读，关口是停读并等读循环退出（{@link RedisStreamReader}）。消费组起点 latest：上线不重放历史堆积。</p>
 */
@Slf4j
@Component
@Order(20)
public class KlineStreamConsumer extends RedisStreamReader {

    private final ApplicationEventPublisher eventPublisher;

    public KlineStreamConsumer(StringRedisTemplate redisTemplate,
                               RedisConnectionFactory connectionFactory,
                               ApplicationEventPublisher eventPublisher) {
        super(redisTemplate, connectionFactory, MarketStreamChannels.KLINE_CLOSED_STREAM, "quant", ReadOffset.latest(),
                "quant", "KlineStream");
        this.eventPublisher = eventPublisher;
    }

    /** 重建 KlineBar 并 republish 本地事件，触发 quant 三监听者。不抛异常以保证 autoAck，不堆 pending */
    @Override
    protected void onRecord(MapRecord<String, String, String> record) {
        Map<String, String> v = record.getValue();
        try {
            KlineBar bar = new KlineBar(
                    Long.parseLong(v.get("openTime")),
                    Long.parseLong(v.get("closeTime")),
                    new BigDecimal(v.get("open")),
                    new BigDecimal(v.get("high")),
                    new BigDecimal(v.get("low")),
                    new BigDecimal(v.get("close")),
                    new BigDecimal(v.get("volume")));
            eventPublisher.publishEvent(
                    new KlineClosedEvent(this, v.get("symbol"), v.get("interval"), bar.closeTime(), bar));
        } catch (Exception e) {
            // 落库已兜底历史数据，丢的仅本次触发；不抛出，避免堆 pending
            log.error("[KlineStream] 处理收盘失败 id={} fields={} msg={}", record.getId(), v, e.toString());
        }
    }
}
