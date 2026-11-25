package com.mawai.wiibcommon.handover;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 事件桥：标了 Clustered 的转给对面、对面本地重新发布；自己发的不处理；转来的不再转回去；没标的不过线 */
class EventBridgeTest {

    @Clustered
    record Pinged(long id) {
    }

    record LocalOnly(long id) {
    }

    private final List<Object> receivedByB = new ArrayList<>();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private EventBridge a;
    private EventBridge b;

    /** 两个实例的桥接在同一个"频道"上：发出去的两边都收到（Redis Pub/Sub 发的人自己也收得到） */
    private void wire() {
        ApplicationEventPublisher publishOnB = event -> {
            receivedByB.add(event);
            // 真的 Spring 里，本地重新发布的事件也会经过桥自己的监听
            b.forward(event);
        };
        a = new EventBridge(redis, mock(RedisMessageListenerContainer.class), _ -> {
        }, "test", "a");
        b = new EventBridge(redis, mock(RedisMessageListenerContainer.class), publishOnB, "test", "b");
        when(redis.convertAndSend(anyString(), any())).thenAnswer(inv -> {
            DefaultMessage msg = new DefaultMessage(inv.getArgument(0, String.class).getBytes(StandardCharsets.UTF_8),
                    inv.getArgument(1, String.class).getBytes(StandardCharsets.UTF_8));
            a.onMessage(msg, null);
            b.onMessage(msg, null);
            return 2L;
        });
    }

    @Test
    void 标了Clustered的转到对面_对面重新发布_不再转回去() {
        wire();

        a.forward(new Pinged(7));

        assertThat(receivedByB).containsExactly(new Pinged(7));
    }

    @Test
    void 没标的不过线() {
        wire();

        a.forward(new LocalOnly(7));

        assertThat(receivedByB).isEmpty();
    }

    @Test
    void 没分槽位什么都不做() {
        EventBridge local = new EventBridge(redis, mock(RedisMessageListenerContainer.class), _ -> {
        }, "test", "");

        local.forward(new Pinged(7));

        verifyNoInteractions(redis);
    }
}
