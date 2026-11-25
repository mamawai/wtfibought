package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.handover.RedisStreamReader;
import com.mawai.wiibcommon.market.PredictionStreamChannels;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * 预测回合事件消费者（sim 侧），消费 {@link PredictionStreamChannels#ROUND_STREAM}。
 *
 * <p>feed 的 PolymarketWsClient 侦测回合轮换/开收盘价就绪后发事件，本消费者触发 sim 的
 * {@link PredictionService} 账本操作（建/锁/结算回合、回填开盘价）。回合顺序敏感
 * (lock 旧→create 新→settle 旧)，Stream 天然 FIFO 保序；方法本身幂等/CAS，autoAck 偶发重投无害。</p>
 *
 * <p>进活的口：开口是开始读，关口是停读并等读循环退出（{@link RedisStreamReader}）。消费组起点 latest：上线不重放历史。</p>
 */
@Slf4j
@Component
@Order(30)
public class PredictionRoundConsumer extends RedisStreamReader {

    private final PredictionService predictionService;

    public PredictionRoundConsumer(StringRedisTemplate redisTemplate,
                                   RedisConnectionFactory connectionFactory,
                                   PredictionService predictionService) {
        // 轮询 500 毫秒
        super(redisTemplate, connectionFactory, PredictionStreamChannels.ROUND_STREAM, PredictionStreamChannels.GROUP,
                ReadOffset.latest(), "sim", Duration.ofMillis(500), "PredictionRound");
        this.predictionService = predictionService;
    }

    /** 按 type 触发账本。不抛异常以保证 autoAck 不堆 pending */
    @Override
    protected void onRecord(MapRecord<String, String, String> record) {
        Map<String, String> v = record.getValue();
        String type = v.get("type");
        try {
            switch (type) {
                // lock/settle 处理的是旧回合，windowStart 必须取事件里的（不能用消费时刻的当前窗口）
                case "lock" -> predictionService.lockRound(Long.parseLong(v.get("windowStart")));
                // create/syncopen 内部按当前窗口算，同机消费亚毫秒延迟、仍在原窗口内，语义一致
                case "create" -> predictionService.createNewRound();
                case "syncopen" -> predictionService.syncOpenPrice();
                case "settle" -> predictionService.settleRound(Long.parseLong(v.get("windowStart")));
                default -> log.warn("[PredictionRound] 未知事件 type={} fields={}", type, v);
            }
        } catch (Exception e) {
            log.error("[PredictionRound] 处理失败 id={} fields={} msg={}", record.getId(), v, e.toString());
        }
    }
}
