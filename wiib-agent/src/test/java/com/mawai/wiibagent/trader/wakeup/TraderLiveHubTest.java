package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibcommon.entity.AiTrader;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

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
        final List<JsonNode> data = new ArrayList<>();

        @Override
        public boolean send(String event, ObjectNode d) {
            events.add(event);
            data.add(d);
            return true;
        }

        JsonNode last(String event) {
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
        return hub.begin(trader(traderId), "TRADE", null, 1000L, 595, new BigDecimal("10000"), 0, 0);
    }

    /** 同一个 trader 两轮同时在跑：每帧带着是哪一轮的，订阅者按它分开；中途接入两轮都回放 */
    @Test
    void 两轮同时在跑_帧带轮次编号_中途接入各自回放() {
        Frames owner = new Frames();
        hub.subscribeTrader(1L, owner);
        TraderLiveHub.Run book = begin(1L);
        TraderLiveHub.Run sol = hub.begin(trader(1L), "ALERT", "SOLUSDT", 2000L, 595, new BigDecimal("10000"), 1, 0);
        book.callStart();
        sol.callStart();
        sol.token("只看 SOL");

        assertThat(owner.events).containsExactly("run_start", "run_start", "model_start", "model_start", "token");
        String bookId = owner.data.get(0).path("run").asString(null);
        String solId = owner.data.get(1).path("run").asString(null);
        assertThat(bookId).isNotBlank().isNotEqualTo(solId);
        assertThat(owner.data.get(0).path("symbol").isNull()).isTrue();
        assertThat(owner.data.get(1).path("symbol").asString(null)).isEqualTo("SOLUSDT");
        assertThat(owner.data.get(2).path("run").asString(null)).isEqualTo(bookId);
        assertThat(owner.last("token").path("run").asString(null)).isEqualTo(solId);

        // 后来的订阅者：两轮各回放一遍，都从 run_start 起
        Frames late = new Frames();
        hub.subscribeTrader(1L, late);
        assertThat(late.events.stream().filter("run_start"::equals)).hasSize(2);
        assertThat(late.data.stream().map(d -> d.path("run").asString(null)).distinct())
                .containsExactlyInAnyOrder(bookId, solId);

        // 一轮结束只摘它自己，另一轮接着推
        sol.finish("OK", null, BigDecimal.TEN, 1, 1, null);
        sol.end(8L);
        book.token("整盘还在想");
        assertThat(owner.last("run_end").path("run").asString(null)).isEqualTo(solId);
        assertThat(owner.last("token").path("run").asString(null)).isEqualTo(bookId);
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
        assertThat(owner.last("prompt").path("system").asString(null)).isEqualTo("SYS");
        assertThat(owner.last("token").path("text").asString(null)).isEqualTo("半截");

        // 之后的在途帧继续到
        run.token("后半");
        assertThat(owner.last("token").path("text").asString(null)).isEqualTo("后半");
        run.finish("OK", null, BigDecimal.TEN, 1, 2, null);
        run.end(5L);
        assertThat(owner.last("run_end").path("decisionId").asLong(0)).isEqualTo(5L);
        assertThat(owner.last("run_end").path("status").asString(null)).isEqualTo("OK");

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
        assertThat(owner.last("run_end").path("status").asString(null)).isEqualTo("ERROR");
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
