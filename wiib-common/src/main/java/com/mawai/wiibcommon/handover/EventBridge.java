package com.mawai.wiibcommon.handover;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 事件桥：标了 {@link Clustered} 的 Spring 事件，事务提交后（不在事务里就当场）发到 Redis 频道 wiib:events:<服务>，
 * 同一服务的另一个实例收到后在本地重新发布。监听方还是普通的事件监听，不知道事件来自哪个实例。
 * 消息里带来源槽位，自己发的不重复处理；只反序列化标了 {@link Clustered} 的类型；转来的事件不再往回转。
 * 没分槽位时什么都不做
 */
@Slf4j
public class EventBridge {

    /** 正在本地重新发布转来的事件：这时发出的不再转出去 */
    private static final ThreadLocal<Boolean> RELAYING = new ThreadLocal<>();

    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer container;
    private final ApplicationEventPublisher events;
    private final String channel;
    private final String slot;

    public EventBridge(StringRedisTemplate redis, RedisMessageListenerContainer container,
                       ApplicationEventPublisher events, String service, String slot) {
        this.redis = redis;
        this.container = container;
        this.events = events;
        this.channel = "wiib:events:" + service;
        this.slot = slot;
    }

    @PostConstruct
    public void subscribe() {
        if (!slot.isEmpty()) {
            container.addMessageListener(this::onMessage, new ChannelTopic(channel));
        }
    }

    /** 本地发的每个事件都过一下：标了 Clustered 的转出去 */
    @TransactionalEventListener(fallbackExecution = true)
    public void forward(Object event) {
        if (slot.isEmpty() || RELAYING.get() != null || !event.getClass().isAnnotationPresent(Clustered.class)) {
            return;
        }
        try {
            ObjectNode msg = MAPPER.createObjectNode()
                    .put("from", slot)
                    .put("type", event.getClass().getName());
            msg.set("payload", MAPPER.valueToTree(event));
            redis.convertAndSend(channel, MAPPER.writeValueAsString(msg));
        } catch (Exception e) {
            log.warn("[EventBridge] 转出失败 {} msg={}", event.getClass().getSimpleName(), e.toString());
        }
    }

    void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode msg = MAPPER.readTree(message.getBody());
            if (slot.equals(msg.path("from").asString(""))) {
                return;
            }
            Class<?> type = Class.forName(msg.path("type").asString());
            if (!type.isAnnotationPresent(Clustered.class)) {
                log.warn("[EventBridge] 不转没标 Clustered 的类型 {}", type.getName());
                return;
            }
            Object event = MAPPER.treeToValue(msg.get("payload"), type);
            RELAYING.set(Boolean.TRUE);
            try {
                events.publishEvent(event);
            } finally {
                RELAYING.remove();
            }
        } catch (Exception e) {
            log.warn("[EventBridge] 收到的事件处理失败 msg={}", e.toString());
        }
    }
}
