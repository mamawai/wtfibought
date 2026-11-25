package com.mawai.wiibquant.market.stream;

import com.mawai.wiibcommon.handover.Intake;
import com.mawai.wiibcommon.handover.Start;
import com.mawai.wiibquant.market.domain.KlineClosedEvent;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.MarketStreamChannels;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamReadRequest;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * K线收盘 Stream 消费者（代码在 quant 库，跑在 agent 进程里），消费 KlineStreamCache的KLINE_CLOSED_STREAM
 *
 * <p>feed 把收盘写进 Stream，本消费者重建 {@link KlineBar} 并 republish 本地 {@link KlineClosedEvent}，
 * 驱动三个监听者：策略信号（StrategyRuntime）、交易员唤醒（TraderScheduler）、叙事对账（VerificationTask）。
 * 这是本进程跨进程获取实时收盘的<b>唯一</b>入口——拆成独立进程后，本消费者随服务启动即自动从 Redis 取数，
 * 监听者一行不用改。</p>
 *
 * <p>进活的口：开口是开始读，关口是停读并等读循环退出。</p>
 */
@Slf4j
@Component
@Order(20)
public class KlineStreamConsumer implements Intake {

    private static final String GROUP = "quant";
    /** 关口时最多等读循环退出多久：盖过一次轮询（2 秒），再给监听者处理最后那批留点时间 */
    private static final long LOOP_EXIT_WAIT_MS = 5_000;

    private final StringRedisTemplate redisTemplate;
    private final RedisConnectionFactory connectionFactory;
    private final ApplicationEventPublisher eventPublisher;
    // 消费者名需唯一（拆进程后每实例一个）；启动时刻纳秒足够区分单机多实例
    private final String consumerName = "quant-" + Long.toHexString(System.nanoTime());

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private Subscription subscription;
    // 关口标志：关口之后轮询再报错（连接已关之类）是预期时序而非故障，errorHandler 据此降噪
    private volatile boolean closed;

    public KlineStreamConsumer(StringRedisTemplate redisTemplate,
                               RedisConnectionFactory connectionFactory,
                               ApplicationEventPublisher eventPublisher) {
        this.redisTemplate = redisTemplate;
        this.connectionFactory = connectionFactory;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void open(Start how) {
        ensureGroup();
        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(2))
                        .errorHandler(this::handlePollError)
                        .build();
        container = StreamMessageListenerContainer.create(connectionFactory, options);
        StreamReadRequest<String> request = StreamReadRequest
                .builder(StreamOffset.create(MarketStreamChannels.KLINE_CLOSED_STREAM, ReadOffset.lastConsumed()))
                .consumer(Consumer.from(GROUP, consumerName))
                .autoAcknowledge(true)
                .cancelOnError(t -> false)   // 出错不退订，接着轮询
                .build();
        subscription = container.register(request, this::onMessage);
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

    /** 轮询异常：关口之后的连接中断是预期时序，降 debug；运行期记 ERROR，歇 1 秒再接着轮询。 */
    private void handlePollError(Throwable e) {
        if (closed) {
            log.debug("[KlineStream] 关口后轮询中断（预期）: {}", e.toString());
            return;
        }
        log.error("[KlineStream] Stream 轮询异常", e);
        // 跑在轮询线程上，歇 1 秒，Redis 停机时不空转刷日志
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 关口：停读并等读循环退出。cancel 不打断正在阻塞的那次读，它读到的照样交给 onMessage，
     * 交完循环才退出、订阅才变成不活跃，所以等到不活跃，最后进来的那根收盘就已经发出去了。
     * 销毁时也会调一次，重复调没关系
     */
    @Override
    @PreDestroy
    public void close() {
        if (subscription == null) {
            return;
        }
        closed = true;
        try {
            subscription.cancel();
            long deadline = System.currentTimeMillis() + LOOP_EXIT_WAIT_MS;
            while (subscription.isActive() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            if (subscription.isActive()) {
                log.warn("[KlineStream] {}ms 内读循环没退出，不再等", LOOP_EXIT_WAIT_MS);
            }
            container.stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
