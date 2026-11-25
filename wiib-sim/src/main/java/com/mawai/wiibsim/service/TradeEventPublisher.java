package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.market.TradeEvent;
import com.mawai.wiibsim.util.AfterCommit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 持仓事件发给 agent：写进 Stream {@link TradeEvent#STREAM}，字段 json 是事件的 JSON，近似裁剪到 1 万条。
 * 在事务里调就等提交后再写：先写出去 agent 立刻醒来对账，库里还是旧状态。
 * 写失败只记日志，平仓 / 成交本身不受影响。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeEventPublisher {

    private static final XAddOptions TRIM = XAddOptions.maxlen(10_000).approximateTrimming(true);

    private final StringRedisTemplate redisTemplate;

    public void publishAfterCommit(TradeEvent event) {
        AfterCommit.run(() -> publish(event));
    }

    private void publish(TradeEvent event) {
        try {
            redisTemplate.opsForStream().add(TradeEvent.STREAM, Map.of("json", MAPPER.writeValueAsString(event)), TRIM);
        } catch (Exception e) {
            log.warn("[TradeEvent] 发布失败 type={} userId={} msg={}", event.type(), event.userId(), e.toString());
        }
    }
}
