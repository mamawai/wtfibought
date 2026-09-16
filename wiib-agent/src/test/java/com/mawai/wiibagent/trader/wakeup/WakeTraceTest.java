package com.mawai.wiibagent.trader.wakeup;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 过程轨迹的纯数据契约：落库形状、回执预览截断与 status 前缀判定、收尾丢空尾 call、中途接入的回放合成、列表状态对象。
 * 字段名与帧协议表一一对应，前端按它建类型。
 */
class WakeTraceTest {

    private static WakeTrace trace() {
        return new WakeTrace(7L, "TRADE", 1000L, 595, new BigDecimal("10000"), 1, 2);
    }

    private static AssistantMessage.ToolCall call(String id, String name, String args) {
        return new AssistantMessage.ToolCall(id, "function", name, args);
    }

    @Test
    void toJson是落库形状() {
        WakeTrace t = trace();
        t.prompt("SYS", "INS");
        t.callStart();
        t.token("先看");
        t.callEnd("先看K线", List.of(call("c1", "klines", "{\"symbol\":\"BTCUSDT\"}"), call("c2", "x", "not json")));
        t.toolResult("c1", "klines", "OK data");
        t.toolResult("c2", "x", "REJECTED: 不行");
        t.callStart();
        t.callEnd("结论", List.of());
        t.end("OK", null, new BigDecimal("10012.3"), 42000, 2, 18000L);

        JsonNode o = MAPPER.readTree(t.toJson());
        assertThat(o.path("v").asInt(0)).isEqualTo(1);
        assertThat(o.path("kind").asString(null)).isEqualTo("TRADE");
        assertThat(o.path("wakeTime").asLong(0)).isEqualTo(1000L);
        assertThat(o.path("budgetSeconds").asLong(0)).isEqualTo(595L);
        assertThat(o.path("equity").asDecimal(null)).isEqualByComparingTo("10000");
        assertThat(o.path("positions").asInt(0)).isEqualTo(1);
        assertThat(o.path("pendingOrders").asInt(0)).isEqualTo(2);
        assertThat(o.path("startedAt").asLong()).isPositive();
        assertThat(o.get("prompt").path("system").asString(null)).isEqualTo("SYS");
        assertThat(o.get("prompt").path("instruction").asString(null)).isEqualTo("INS");

        JsonNode calls = o.get("calls");
        assertThat(calls).hasSize(2);
        JsonNode first = calls.get(0);
        assertThat(first.path("n").asInt(0)).isEqualTo(1);
        // callEnd 的整段正文覆盖攒的增量
        assertThat(first.path("text").asString(null)).isEqualTo("先看K线");
        assertThat(first.path("startedAt").asLong()).isPositive();
        assertThat(first.path("endedAt").asLong()).isPositive();
        JsonNode toolCalls = first.get("toolCalls");
        assertThat(toolCalls.get(0).path("id").asString(null)).isEqualTo("c1");
        assertThat(toolCalls.get(0).path("name").asString(null)).isEqualTo("klines");
        assertThat(toolCalls.get(0).get("args").path("symbol").asString(null)).isEqualTo("BTCUSDT");
        // 参数不是合法 JSON：原字符串
        assertThat(toolCalls.get(1).get("args").asString()).isEqualTo("not json");
        JsonNode results = first.get("results");
        assertThat(results.get(0).path("status").asString(null)).isEqualTo("ok");
        assertThat(results.get(0).path("preview").asString(null)).isEqualTo("OK data");
        assertThat(results.get(1).path("status").asString(null)).isEqualTo("rejected");
        assertThat(calls.get(1).path("text").asString(null)).isEqualTo("结论");

        JsonNode end = o.get("end");
        assertThat(end.path("status").asString(null)).isEqualTo("OK");
        assertThat(end.has("error")).isFalse();
        assertThat(end.path("equity").asDecimal(null)).isEqualByComparingTo("10012.3");
        assertThat(end.path("latencyMs").asInt(0)).isEqualTo(42000);
        assertThat(end.path("modelCalls").asInt(0)).isEqualTo(2);
        assertThat(end.path("totalTokens").asLong(0)).isEqualTo(18000L);
    }

    @Test
    void 回执预览截2000字且status按前缀判() {
        WakeTrace t = trace();
        t.callStart();
        t.callEnd("", List.of(call("c1", "klines", "{}"), call("c2", "a", "{}"), call("c3", "b", "{}")));

        WakeTrace.Frame ok = t.toolResult("c1", "klines", "x".repeat(WakeTrace.PREVIEW_CHARS + 500));
        WakeTrace.Frame error = t.toolResult("c2", "a", "ERROR: 上游超时");
        WakeTrace.Frame rejected = t.toolResult("c3", "b", "REJECTED: 杠杆越界");

        assertThat(ok.event()).isEqualTo("tool_result");
        assertThat(ok.data().path("call").asInt(0)).isEqualTo(1);
        assertThat(ok.data().path("id").asString(null)).isEqualTo("c1");
        assertThat(ok.data().path("name").asString(null)).isEqualTo("klines");
        assertThat(ok.data().path("status").asString(null)).isEqualTo("ok");
        assertThat(ok.data().path("preview").asString(null)).hasSize(WakeTrace.PREVIEW_CHARS);
        assertThat(error.data().path("status").asString(null)).isEqualTo("error");
        assertThat(rejected.data().path("status").asString(null)).isEqualTo("rejected");
        assertThat(rejected.data().path("preview").asString(null)).isEqualTo("REJECTED: 杠杆越界");
    }

    @Test
    void 收尾丢掉末尾没正文也没toolCalls的空call() {
        // 保险丝跳 END：占位回执之后又起了一个 call
        WakeTrace t = trace();
        t.callStart();
        t.callEnd("", List.of(call("c1", "get_account", "{}")));
        t.toolResult("c1", "get_account", "未执行");
        t.callStart();

        WakeTrace.Frame end = t.end("OK", "上限", new BigDecimal("9990"), 1000, 12, null);

        assertThat(end.event()).isEqualTo("run_end");
        assertThat(end.data().path("status").asString(null)).isEqualTo("OK");
        assertThat(end.data().path("error").asString(null)).isEqualTo("上限");
        // 没报的项内存里是 null，线上（toJSONString）缺席
        assertThat(end.data().hasNonNull("totalTokens")).isFalse();
        assertThat(MAPPER.writeValueAsString(end.data())).doesNotContain("totalTokens");
        assertThat(MAPPER.readTree(t.toJson()).get("calls")).hasSize(1);

        // 尾 call 有半截正文（超时打断）：保留，endedAt 缺席
        WakeTrace half = trace();
        half.callStart();
        half.token("半截");
        half.end("ERROR", "超时", new BigDecimal("10000"), 600000, 1, null);
        JsonNode calls = MAPPER.readTree(half.toJson()).get("calls");
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).path("text").asString(null)).isEqualTo("半截");
        assertThat(calls.get(0).has("endedAt")).isFalse();
    }

    @Test
    void 回放按当前状态合成() {
        WakeTrace t = trace();
        t.prompt("SYS", "INS");
        t.callStart();
        t.callEnd("", List.of(call("c1", "klines", "{}")));
        t.toolResult("c1", "klines", "d");
        t.callStart();
        t.token("前");
        t.token("后");

        // 已结束的 call 合成 model_end + tool_result，进行中的合成 model_start + 一帧累计 token
        List<WakeTrace.Frame> owner = t.replay();
        assertThat(owner).extracting(WakeTrace.Frame::event)
                .containsExactly("run_start", "prompt", "model_end", "tool_result", "model_start", "token");
        assertThat(owner.get(0).data().path("kind").asString(null)).isEqualTo("TRADE");
        assertThat(owner.get(0).data().path("wakeTime").asLong(0)).isEqualTo(1000L);
        assertThat(owner.get(1).data().path("system").asString(null)).isEqualTo("SYS");
        assertThat(owner.get(2).data().path("call").asInt(0)).isEqualTo(1);
        assertThat(owner.get(2).data().get("toolCalls")).hasSize(1);
        assertThat(owner.get(3).data().path("id").asString(null)).isEqualTo("c1");
        assertThat(owner.get(4).data().path("call").asInt(0)).isEqualTo(2);
        assertThat(owner.get(5).data().path("call").asInt(0)).isEqualTo(2);
        assertThat(owner.get(5).data().path("text").asString(null)).isEqualTo("前后");
        // 提示词还没到：回放里也没有 prompt 帧
        assertThat(trace().replay()).extracting(WakeTrace.Frame::event).containsExactly("run_start");
    }

}
