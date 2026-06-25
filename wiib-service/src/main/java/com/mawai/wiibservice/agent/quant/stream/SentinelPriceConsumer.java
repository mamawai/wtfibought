package com.mawai.wiibservice.agent.quant.stream;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibservice.agent.quant.PriceVolatilitySentinel;
import com.mawai.wiibservice.config.MarketStreamChannels;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/**
 * markPrice 行情订阅者（quant 侧）。feed 把 markPrice 发到 {@link MarketStreamChannels#PRICE}，本消费者
 * 订阅后喂 {@link PriceVolatilitySentinel} 价格异动哨兵——替代原 feed 进程内直调，使 quant 能独立取价。
 *
 * <p>同一通道也承载现货/合约撮合价（sim 侧消费），本消费者只取 {@code type=markprice}，其余忽略。
 * onPriceTick 低频（markPrice@1s），同步执行即可，与原 BinanceWsClient 行为一致。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SentinelPriceConsumer implements MessageListener {

    private final RedisMessageListenerContainer listenerContainer;
    private final PriceVolatilitySentinel sentinel;

    @PostConstruct
    public void init() {
        listenerContainer.addMessageListener(this, new ChannelTopic(MarketStreamChannels.PRICE));
        log.info("[SentinelPrice] 订阅 {} 启动", MarketStreamChannels.PRICE);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JSONObject obj = JSON.parseObject(new String(message.getBody(), StandardCharsets.UTF_8));
            if (!"markprice".equals(obj.getString("type"))) return;
            sentinel.onPriceTick(obj.getString("symbol"), new BigDecimal(obj.getString("price")));
        } catch (Exception e) {
            log.warn("[SentinelPrice] 处理失败: {}", e.toString());
        }
    }
}
