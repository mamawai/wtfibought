package com.mawai.wiibfeed.stream;

import com.mawai.wiibcommon.market.MarketStreamChannels;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 撮合价格事件发布：feed 把价格 tick 发 Redis 频道，sim 侧 MatchPriceConsumer 订阅消费触发撮合。
 * 发送失败降级（仅告警），不阻断行情主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchPricePublisher {

    private final StringRedisTemplate redisTemplate;

    public void publish(String json) {
        try {
            redisTemplate.convertAndSend(MarketStreamChannels.PRICE, json);
        } catch (Exception e) {
            log.warn("[MatchPrice] 发送失败: {}", e.toString());
        }
    }
}
