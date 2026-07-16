package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.market.PredictionStreamChannels;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 预测回合事件消费者（sim 侧），消费 {@link PredictionStreamChannels#ROUND_STREAM}。
 *
 * <p>feed 的 PolymarketWsClient 侦测回合轮换/开收盘价就绪后发事件，本消费者触发 sim 的
 * {@link PredictionService} 账本操作（建/锁/结算回合、回填开盘价）。回合顺序敏感
 * (lock 旧→create 新→settle 旧)，Stream 天然 FIFO 保序；方法本身幂等/CAS，autoAck 偶发重投无害。</p>
 */
@Slf4j
@Component
public class PredictionRoundConsumer {

    private final StringRedisTemplate redisTemplate;
    private final RedisConnectionFactory connectionFactory;
    private final PredictionService predictionService;
    // 消费者名需唯一（拆进程后每实例一个）；启动时刻纳秒足够区分单机多实例
    private final String consumerName = "sim-" + Long.toHexString(System.nanoTime());

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private Subscription subscription;
    // 停机标志：cancel() 不会打断正阻塞的 XREADGROUP，连接工厂随后关闭必然掐死最后一次读，
    // 该"Connection closed"是预期时序而非故障，errorHandler 据此降噪。
    // 注意必须在 ContextClosedEvent 时就置位——Lettuce 连接工厂在 lifecycle stop 阶段关连接，
    // 早于 @PreDestroy 销毁阶段，只靠 @PreDestroy 置位赶不上最后一拍轮询报错。
    private volatile boolean shuttingDown;

    public PredictionRoundConsumer(StringRedisTemplate redisTemplate,
                                   RedisConnectionFactory connectionFactory,
                                   PredictionService predictionService) {
        this.redisTemplate = redisTemplate;
        this.connectionFactory = connectionFactory;
        this.predictionService = predictionService;
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
                Consumer.from(PredictionStreamChannels.GROUP, consumerName),
                StreamOffset.create(PredictionStreamChannels.ROUND_STREAM, ReadOffset.lastConsumed()),
                this::onMessage);
        container.start();
        log.info("[PredictionRound] 消费者启动 group={} consumer={} stream={}",
                PredictionStreamChannels.GROUP, consumerName, PredictionStreamChannels.ROUND_STREAM);
    }

    /**
     * 建消费组：mkstream=true 兼容 Stream 尚未被 feed 创建的启动时序；组已存在则忽略。
     * 起点 latest——上线不重放历史；组的位点持久化，consumer 重启从 lastConsumed 续，不丢。
     */
    private void ensureGroup() {
        try {
            redisTemplate.execute((RedisConnection conn) -> {
                try {
                    conn.streamCommands().xGroupCreate(
                            PredictionStreamChannels.ROUND_STREAM.getBytes(StandardCharsets.UTF_8),
                            PredictionStreamChannels.GROUP, ReadOffset.latest(), true);
                } catch (Exception e) {
                    log.debug("[PredictionRound] 消费组已存在/建组跳过: {}", e.getMessage());
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("[PredictionRound] 建组失败: {}", e.toString());
        }
    }

    /** 按 type 触发账本。不抛异常以保证 autoAck 不堆 pending。包级可见供测试直接调。 */
    void onMessage(MapRecord<String, String, String> record) {
        Map<String, String> v = record.getValue();
        String type = v.get("type");
        try {
            switch (type) {
                // lock 锁定指定旧回合，windowStart 来自事件（不能用消费时刻的当前窗口）
                case "lock" -> predictionService.lockRound(Long.parseLong(v.get("windowStart")));
                // create/syncopen/settle 内部按当前/上一窗口算，同机消费亚毫秒延迟、仍在原窗口内，语义一致
                case "create" -> predictionService.createNewRound();
                case "syncopen" -> predictionService.syncOpenPrice();
                case "settle" -> predictionService.settlePreviousRound();
                default -> log.warn("[PredictionRound] 未知事件 type={} fields={}", type, v);
            }
        } catch (Exception e) {
            log.error("[PredictionRound] 处理失败 id={} fields={} msg={}", record.getId(), v, e.toString());
        }
    }

    /** 轮询异常：停机期的连接中断是预期时序，降 debug；运行期异常保持 ERROR 可见。 */
    private void handlePollError(Throwable e) {
        if (shuttingDown) {
            log.debug("[PredictionRound] 停机期轮询中断（预期）: {}", e.toString());
        } else {
            log.error("[PredictionRound] Stream 轮询异常", e);
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
            log.debug("[PredictionRound] 关闭异常: {}", e.toString());
        }
    }
}
