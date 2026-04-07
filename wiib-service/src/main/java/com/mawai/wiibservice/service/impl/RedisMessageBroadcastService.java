package com.mawai.wiibservice.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

/**
 * Redis消息广播服务
 * 用于WebSocket集群消息同步
 *
 * <p>工作原理：</p>
 * <ol>
 *   <li>发送消息时，先发布到Redis频道</li>
 *   <li>所有实例订阅该频道，收到消息后推送给本地WebSocket连接</li>
 *   <li>实现集群环境下的消息广播</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisMessageBroadcastService implements MessageListener {

    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final RedisMessageListenerContainer redisMessageListenerContainer;

    /** Redis频道前缀 */
    private static final String CHANNEL_PREFIX = "ws:broadcast:";
    private static final String STOCK_CHANNEL = CHANNEL_PREFIX + "stock";
    private static final String CRYPTO_CHANNEL = CHANNEL_PREFIX + "crypto";
    private static final String PREDICTION_CHANNEL = CHANNEL_PREFIX + "prediction";
    private static final String QUANT_CHANNEL = CHANNEL_PREFIX + "quant";
    private static final String FUTURES_CHANNEL = CHANNEL_PREFIX + "futures";

    @PostConstruct
    public void init() {
        redisMessageListenerContainer.addMessageListener(this, new ChannelTopic(STOCK_CHANNEL));
        redisMessageListenerContainer.addMessageListener(this, new ChannelTopic(CRYPTO_CHANNEL));
        redisMessageListenerContainer.addMessageListener(this, new ChannelTopic(FUTURES_CHANNEL));
        redisMessageListenerContainer.addMessageListener(this, new ChannelTopic(PREDICTION_CHANNEL));
        redisMessageListenerContainer.addMessageListener(this, new ChannelTopic(QUANT_CHANNEL));
        log.info("已订阅Redis广播频道: {}, {}, {}, {}", STOCK_CHANNEL, CRYPTO_CHANNEL, PREDICTION_CHANNEL, QUANT_CHANNEL);
    }

    /**
     * 广播股票行情消息
     * 发布到Redis，所有实例都会收到并推送给本地WebSocket连接
     *
     * @param stockCode 股票代码
     * @param message   JSON消息内容
     */
    public void broadcastStockQuote(String stockCode, String message) {
        try {
            String payload = stockCode + "|" + message;
            redisTemplate.convertAndSend(STOCK_CHANNEL, payload);
            log.debug("广播股票行情: {}", stockCode);
        } catch (Exception e) {
            log.error("广播股票行情失败: {}", stockCode, e);
        }
    }

    /**
     * 广播加密货币行情消息
     */
    public void broadcastCryptoQuote(String symbol, String message) {
        try {
            String payload = symbol + "|" + message;
            redisTemplate.convertAndSend(CRYPTO_CHANNEL, payload);
        } catch (Exception e) {
            log.error("广播加密货币行情失败: {}", symbol, e);
        }
    }

    /**
     * 广播合约行情消息（标记价 + 最新价）
     */
    public void broadcastFuturesQuote(String symbol, String message) {
        try {
            String payload = symbol + "|" + message;
            redisTemplate.convertAndSend(FUTURES_CHANNEL, payload);
        } catch (Exception e) {
            log.error("广播合约行情失败: {}", symbol, e);
        }
    }

    /**
     * 广播预测市场消息
     * @param topic price/round/activity
     */
    public void broadcastPrediction(String topic, String message) {
        try {
            String payload = topic + "|" + message;
            redisTemplate.convertAndSend(PREDICTION_CHANNEL, payload);
        } catch (Exception e) {
            log.error("广播预测市场消息失败: {}", topic, e);
        }
    }

    /**
     * 广播量化预测信号
     * @param symbol 交易对
     * @param message JSON消息
     */
    public void broadcastQuantSignal(String symbol, String message) {
        try {
            String payload = symbol + "|" + message;
            redisTemplate.convertAndSend(QUANT_CHANNEL, payload);
        } catch (Exception e) {
            log.error("广播量化信号失败: {}", symbol, e);
        }
    }

    /**
     * 接收Redis消息并推送到本地WebSocket连接
     */
    @Override
    public void onMessage(@NonNull Message message, byte[] pattern) {
        try {
            String channel = new String(message.getChannel(), java.nio.charset.StandardCharsets.UTF_8);
            String payload = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            int separatorIndex = payload.indexOf('|');
            if (separatorIndex <= 0) {
                log.warn("无效的广播消息格式: {}", payload);
                return;
            }

            String code = payload.substring(0, separatorIndex);
            String jsonMessage = payload.substring(separatorIndex + 1);

            switch (channel) {
                case CRYPTO_CHANNEL -> messagingTemplate.convertAndSend("/topic/crypto/" + code, jsonMessage);
                case FUTURES_CHANNEL -> messagingTemplate.convertAndSend("/topic/futures/" + code, jsonMessage);
                case PREDICTION_CHANNEL -> messagingTemplate.convertAndSend("/topic/prediction/" + code, jsonMessage);
                case QUANT_CHANNEL -> messagingTemplate.convertAndSend("/topic/quant/" + code, jsonMessage);
                default -> {
                    messagingTemplate.convertAndSend("/topic/quote/" + code, jsonMessage);
                    log.debug("本地推送股票行情: {}", code);
                }
            }
        } catch (Exception e) {
            log.error("处理Redis广播消息失败", e);
        }
    }
}

