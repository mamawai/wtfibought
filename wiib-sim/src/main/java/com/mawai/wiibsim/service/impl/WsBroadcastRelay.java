package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.broadcast.MarketBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;

/**
 * Redis 广播 → 本地 WebSocket 中继（sim 侧，仅有 WS 网关的进程需要）。
 * <p>订阅 {@link MarketBroadcaster} 发布的频道，收到后按 channel 推到对应 STOMP /topic。
 * 与发布器分离：feed/quant 进程只发布不订阅，无需本类。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WsBroadcastRelay implements MessageListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final RedisMessageListenerContainer redisMessageListenerContainer;

    @PostConstruct
    public void init() {
        for (String ch : new String[]{
                MarketBroadcaster.STOCK_CHANNEL, MarketBroadcaster.CRYPTO_CHANNEL,
                MarketBroadcaster.FUTURES_CHANNEL, MarketBroadcaster.PREDICTION_CHANNEL,
                MarketBroadcaster.QUANT_CHANNEL, MarketBroadcaster.KLINE_CHANNEL}) {
            redisMessageListenerContainer.addMessageListener(this, new ChannelTopic(ch));
        }
        log.info("已订阅 Redis 广播频道，中继到本地 WebSocket");
    }

    @Override
    public void onMessage(@NonNull Message message, byte[] pattern) {
        // 回调线程内同步解码成不可变值再交棒（别跨线程读容器 Message）。
        // convertAndSend 可能因慢客户端阻塞，甩虚拟线程跑，避免占住共享的 redis-sub- 池线程。
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        Thread.startVirtualThread(() -> relay(channel, payload));
    }

    // try/catch 必须在虚拟线程体内：throw 与 catch 同线程，log.error 才抓得到 convertAndSend 的异常。
    private void relay(String channel, String payload) {
        try {
            int sep = payload.indexOf('|');
            if (sep <= 0) {
                log.warn("无效广播消息格式: {}", payload);
                return;
            }
            String code = payload.substring(0, sep);
            String json = payload.substring(sep + 1);
            switch (channel) {
                case MarketBroadcaster.CRYPTO_CHANNEL -> messagingTemplate.convertAndSend("/topic/crypto/" + code, json);
                case MarketBroadcaster.FUTURES_CHANNEL -> messagingTemplate.convertAndSend("/topic/futures/" + code, json);
                case MarketBroadcaster.KLINE_CHANNEL -> messagingTemplate.convertAndSend("/topic/kline/" + code, json);
                case MarketBroadcaster.PREDICTION_CHANNEL -> messagingTemplate.convertAndSend("/topic/prediction/" + code, json);
                case MarketBroadcaster.QUANT_CHANNEL -> messagingTemplate.convertAndSend("/topic/quant/" + code, json);
                default -> messagingTemplate.convertAndSend("/topic/quote/" + code, json);
            }
        } catch (Exception e) {
            log.error("处理 Redis 广播消息失败", e);
        }
    }
}
