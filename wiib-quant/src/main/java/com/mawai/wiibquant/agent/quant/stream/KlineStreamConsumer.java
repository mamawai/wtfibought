package com.mawai.wiibquant.agent.quant.stream;

import com.mawai.wiibquant.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.MarketStreamChannels;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * K线收盘 Stream 消费者（quant 侧），消费 {@link KlineStreamCache#CLOSED_STREAM_KEY}。
 *
 * <p>feed 把收盘写进 Stream，本消费者重建 {@link KlineBar} 并 republish 本地 {@link KlineClosedEvent}，
 * 驱动 quant 域的预测/策略/MacroContext 三个监听者。这是 quant 跨进程获取实时收盘的<b>唯一</b>入口——
 * 拆成独立进程后，本消费者随 quant 服务启动即自动从 Redis 取数，监听者一行不用改。</p>
 */
@Slf4j
@Component
public class KlineStreamConsumer {

    private static final String GROUP = "quant";

    private final StringRedisTemplate redisTemplate;
    private final RedisConnectionFactory connectionFactory;
    private final ApplicationEventPublisher eventPublisher;
    // 消费者名需唯一（拆进程后每实例一个）；启动时刻纳秒足够区分单机多实例
    private final String consumerName = "quant-" + Long.toHexString(System.nanoTime());

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private Subscription subscription;
    // 停机标志：cancel() 不会打断正阻塞的 XREADGROUP，连接工厂随后关闭必然掐死最后一次读，
    // 该"Connection closed"是预期时序而非故障，errorHandler 据此降噪。
    // 注意必须在 ContextClosedEvent 时就置位——Lettuce 连接工厂在 lifecycle stop 阶段关连接，
    // 早于 @PreDestroy 销毁阶段，只靠 @PreDestroy 置位赶不上最后一拍轮询报错。
    private volatile boolean shuttingDown;

    public KlineStreamConsumer(StringRedisTemplate redisTemplate,
                               RedisConnectionFactory connectionFactory,
                               ApplicationEventPublisher eventPublisher) {
        this.redisTemplate = redisTemplate;
        this.connectionFactory = connectionFactory;
        this.eventPublisher = eventPublisher;
    }

    @PostConstruct
    public void start() {
        ensureGroup();
        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(2))
                        .errorHandler(this::handlePollError)
                        .build();
        container = StreamMessageListenerContainer.create(connectionFactory, options);
        subscription = container.receiveAutoAck(
                Consumer.from(GROUP, consumerName),
                StreamOffset.create(MarketStreamChannels.KLINE_CLOSED_STREAM, ReadOffset.lastConsumed()),
                this::onMessage);
        container.start();
        log.info("[KlineStream] 消费者启动 group={} consumer={} stream={}",
                GROUP, consumerName, MarketStreamChannels.KLINE_CLOSED_STREAM);
    }

    /**
     * 建消费组：mkstream=true 兼容 Stream 尚未被 feed 创建的启动时序；组已存在则忽略。
     * 起点用 latest——上线不重放 Stream 历史堆积；组的消费位点持久化，consumer 重启从 lastConsumed 续，不丢。
     */
    private void ensureGroup() {
        try {
            redisTemplate.execute((RedisConnection conn) -> {
                try {
                    conn.streamCommands().xGroupCreate(
                            MarketStreamChannels.KLINE_CLOSED_STREAM.getBytes(StandardCharsets.UTF_8),
                            GROUP, ReadOffset.latest(), true);
                } catch (Exception e) {
                    log.debug("[KlineStream] 消费组已存在/建组跳过: {}", e.getMessage());
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("[KlineStream] 建组失败: {}", e.toString());
        }
    }

    /** 重建 KlineBar 并 republish 本地事件，触发 quant 三监听者。不抛异常以保证 autoAck，不堆 pending。包级可见供测试直接调。 */
    void onMessage(MapRecord<String, String, String> record) {
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

    /** 轮询异常：停机期的连接中断是预期时序，降 debug；运行期异常保持 ERROR 可见。 */
    private void handlePollError(Throwable e) {
        if (shuttingDown) {
            log.debug("[KlineStream] 停机期轮询中断（预期）: {}", e.toString());
        } else {
            log.error("[KlineStream] Stream 轮询异常", e);
        }
    }

    /** ContextClosedEvent 先于 lifecycle stop（Lettuce 关连接）与销毁阶段发布——在这里提前撤订阅。 */
    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        stop();
    }

    @PreDestroy
    public void stop() {
        if (shuttingDown) return;   // onContextClosed 已处理，销毁阶段幂等跳过
        shuttingDown = true;
        try {
            if (subscription != null) subscription.cancel();
            if (container != null) container.stop();
        } catch (Exception e) {
            log.debug("[KlineStream] 关闭异常: {}", e.toString());
        }
    }
}
