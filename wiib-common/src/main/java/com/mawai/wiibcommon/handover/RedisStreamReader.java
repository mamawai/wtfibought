package com.mawai.wiibcommon.handover;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 消费组读一条 Stream 的通用件，同时是一个口：开口建组（组已在就跳过）并开始读，关口停读并等读循环退出。
 * 自动 ACK；每个实例一个消费者名（前缀加启动时刻）。子类只管处理一条记录，处理时不许抛异常。
 * <p>
 * 停读的等法：cancel 不打断正在阻塞的那次读，它读到的照样交给 {@link #onRecord}，交完循环才退出、订阅才变成不活跃，
 * 所以等到不活跃，最后读到的那条就已经处理完了
 */
@Slf4j
public abstract class RedisStreamReader implements Intake {

    /** 关口时最多等读循环退出多久：盖过一次轮询，再给处理最后那批留点时间 */
    private static final long LOOP_EXIT_WAIT_MS = 5_000;

    private final StringRedisTemplate redisTemplate;
    private final RedisConnectionFactory connectionFactory;
    private final String stream;
    private final String group;
    /** 第一次建组时从哪读：latest 是只读之后的，0 是从头读 */
    private final ReadOffset groupStart;
    private final String consumerName;
    /** 一次阻塞读最多等多久，也是关口时最多等它返回多久 */
    private final Duration pollTimeout;
    /** 日志前缀 */
    private final String tag;

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private Subscription subscription;
    // 关口之后轮询再报错（连接已关之类）是预期时序，errorHandler 据此降噪
    private volatile boolean closed;

    protected RedisStreamReader(StringRedisTemplate redisTemplate, RedisConnectionFactory connectionFactory,
                                String stream, String group, ReadOffset groupStart, String consumerPrefix,
                                Duration pollTimeout, String tag) {
        this.redisTemplate = redisTemplate;
        this.connectionFactory = connectionFactory;
        this.stream = stream;
        this.group = group;
        this.groupStart = groupStart;
        this.consumerName = consumerPrefix + "-" + Long.toHexString(System.nanoTime());
        this.pollTimeout = pollTimeout;
        this.tag = tag;
    }

    /** 处理一条记录，不许抛异常（抛了这条就丢了） */
    protected abstract void onRecord(MapRecord<String, String, String> record);

    @Override
    public void open(Start how) {
        closed = false;
        ensureGroup();
        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(pollTimeout)
                        .errorHandler(this::handlePollError)
                        .build();
        container = StreamMessageListenerContainer.create(connectionFactory, options);
        StreamReadRequest<String> request = StreamReadRequest
                .builder(StreamOffset.create(stream, ReadOffset.lastConsumed()))
                .consumer(Consumer.from(group, consumerName))
                .autoAcknowledge(true)
                .cancelOnError(t -> false)   // 出错不退订，接着轮询
                .build();
        subscription = container.register(request, this::onRecord);
        container.start();
        log.info("[{}] 开始读 stream={} group={} consumer={}", tag, stream, group, consumerName);
    }

    /** 建消费组：mkstream 兼容 Stream 还没被写过；组已存在则跳过。组的消费位点持久化，换实例从 lastConsumed 接着读 */
    private void ensureGroup() {
        try {
            redisTemplate.execute((RedisConnection conn) -> {
                try {
                    conn.streamCommands().xGroupCreate(stream.getBytes(StandardCharsets.UTF_8), group, groupStart, true);
                } catch (Exception e) {
                    log.debug("[{}] 消费组已存在/建组跳过: {}", tag, e.getMessage());
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("[{}] 建组失败: {}", tag, e.toString());
        }
    }

    /** 轮询异常：关口之后的连接中断是预期时序，降 debug；运行期记 ERROR，歇 1 秒再接着轮询 */
    private void handlePollError(Throwable e) {
        if (closed) {
            log.debug("[{}] 关口后轮询中断（预期）: {}", tag, e.toString());
            return;
        }
        log.error("[{}] Stream 轮询异常", tag, e);
        // 跑在轮询线程上，歇 1 秒，Redis 停机时不空转刷日志
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** 关口：停读并等读循环退出（最多 5 秒）。销毁时也会调一次，重复调、没开过口都没关系 */
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
                log.warn("[{}] {}ms 内读循环没退出，不再等", tag, LOOP_EXIT_WAIT_MS);
            }
            container.stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            subscription = null;
        }
    }
}
