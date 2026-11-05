package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.market.TradeEvent;
import com.mawai.wiibsim.util.AfterCommit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 持仓事件发给 agent（Redis Pub/Sub，见 {@link TradeEvent}）。
 * 在事务里调就等提交后再发：先发出去 agent 立刻醒来对账，库里还是旧状态。
 * 发失败只记日志，平仓 / 成交本身不受影响。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeEventPublisher {

    private final StringRedisTemplate redisTemplate;

    public void publishAfterCommit(TradeEvent event) {
        AfterCommit.run(() -> publish(event));
    }

    private void publish(TradeEvent event) {
        try {
            redisTemplate.convertAndSend(TradeEvent.CHANNEL, MAPPER.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("[TradeEvent] 发布失败 type={} userId={} msg={}", event.type(), event.userId(), e.toString());
        }
    }
}
