package com.mawai.wiibagent.trader.wakeup;

import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.entity.AiTrader;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发布订阅这一层：中途接入的回放、finish 之后的晚到帧、Sink 返回 false 就被摘掉。
 */
class TraderLiveHubTest {

    /** 收帧的出口 */
    private static final class Frames implements TraderLiveHub.Sink {
        final List<String> events = new ArrayList<>();
        final List<JSONObject> data = new ArrayList<>();

        @Override
        public boolean send(String event, JSONObject d) {
            events.add(event);
            data.add(d);
            return true;
        }

        JSONObject last(String event) {
            return data.get(events.lastIndexOf(event));
        }
    }

    private final TraderLiveHub hub = new TraderLiveHub();

    private static AiTrader trader(long id) {
        AiTrader t = new AiTrader();
        t.setId(id);
        t.setUserId(3L);
        return t;
    }

    private TraderLiveHub.Run begin(long traderId) {
        return hub.begin(trader(traderId), "TRADE", 1000L, 595, new BigDecimal("10000"), 0, 0);
    }

    private static AssistantMessage.ToolCall call(String id, String name, String args) {
        return new AssistantMessage.ToolCall(id, "function", name, args);
    }

    @Test
    void 中途接入拿到回放_之后在途帧继续到() {
        TraderLiveHub.Run run = begin(1L);
        run.prompt("SYS", "INS");
        run.callStart();
        run.callEnd("", List.of(call("c1", "klines", "{}")));
        run.toolResult("c1", "klines", "d");
        run.callStart();
        run.token("半截");

        Frames owner = new Frames();
        hub.subscribeTrader(1L, owner);

        assertThat(owner.events).containsExactly("run_start", "prompt", "model_end", "tool_result", "model_start", "token");
        assertThat(owner.last("prompt").getString("system")).isEqualTo("SYS");
        assertThat(owner.last("token").getString("text")).isEqualTo("半截");

        // 之后的在途帧继续到
        run.token("后半");
        assertThat(owner.last("token").getString("text")).isEqualTo("后半");
        run.finish("OK", null, BigDecimal.TEN, 1, 2, null);
        run.end(5L);
        assertThat(owner.last("run_end").getLongValue("decisionId")).isEqualTo(5L);
        assertThat(owner.last("run_end").getString("status")).isEqualTo("OK");

        // 空闲 trader 连上什么都不发
        Frames idle = new Frames();
        hub.subscribeTrader(2L, idle);
        assertThat(idle.events).isEmpty();
        // 但下一轮开跑就收到 run_start
        begin(2L);
        assertThat(idle.events).containsExactly("run_start");
    }

    @Test
    void finish之后的晚到帧全部丢掉() {
        Frames owner = new Frames();
        hub.subscribeTrader(1L, owner);
        TraderLiveHub.Run run = begin(1L);
        run.callStart();
        run.finish("ERROR", "超时", BigDecimal.TEN, 1, 1, null);
        // 超时后循环线程还在推
        run.token("晚到");
        run.callEnd("晚到", List.of());
        run.callStart();
        run.end(9L);

        assertThat(owner.events).containsExactly("run_start", "model_start", "run_end");
        assertThat(owner.last("run_end").getString("status")).isEqualTo("ERROR");
    }

    @Test
    void Sink返回false就被摘掉() {
        AtomicInteger detail = new AtomicInteger();
        hub.subscribeTrader(1L, (event, data) -> {
            detail.incrementAndGet();
            return false;
        });

        TraderLiveHub.Run run = begin(1L); // run_start 这一帧就返回 false，之后不再收
        run.callStart();
        run.callStart();

        assertThat(detail).hasValue(1);
    }
}
