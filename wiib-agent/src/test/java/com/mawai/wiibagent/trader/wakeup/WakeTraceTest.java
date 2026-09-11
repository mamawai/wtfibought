package com.mawai.wiibagent.trader.wakeup;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.math.BigDecimal;
import java.util.List;

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

        JSONObject o = JSON.parseObject(t.toJson());
        assertThat(o.getIntValue("v")).isEqualTo(1);
        assertThat(o.getString("kind")).isEqualTo("TRADE");
        assertThat(o.getLongValue("wakeTime")).isEqualTo(1000L);
        assertThat(o.getLongValue("budgetSeconds")).isEqualTo(595L);
        assertThat(o.getBigDecimal("equity")).isEqualByComparingTo("10000");
        assertThat(o.getIntValue("positions")).isEqualTo(1);
        assertThat(o.getIntValue("pendingOrders")).isEqualTo(2);
        assertThat(o.getLong("startedAt")).isPositive();
        assertThat(o.getJSONObject("prompt").getString("system")).isEqualTo("SYS");
        assertThat(o.getJSONObject("prompt").getString("instruction")).isEqualTo("INS");

        JSONArray calls = o.getJSONArray("calls");
        assertThat(calls).hasSize(2);
        JSONObject first = calls.getJSONObject(0);
        assertThat(first.getIntValue("n")).isEqualTo(1);
        // callEnd 的整段正文覆盖攒的增量
        assertThat(first.getString("text")).isEqualTo("先看K线");
        assertThat(first.getLong("startedAt")).isPositive();
        assertThat(first.getLong("endedAt")).isPositive();
        JSONArray toolCalls = first.getJSONArray("toolCalls");
        assertThat(toolCalls.getJSONObject(0).getString("id")).isEqualTo("c1");
        assertThat(toolCalls.getJSONObject(0).getString("name")).isEqualTo("klines");
        assertThat(toolCalls.getJSONObject(0).getJSONObject("args").getString("symbol")).isEqualTo("BTCUSDT");
        // 参数不是合法 JSON：原字符串
        assertThat(toolCalls.getJSONObject(1).get("args")).isEqualTo("not json");
        JSONArray results = first.getJSONArray("results");
        assertThat(results.getJSONObject(0).getString("status")).isEqualTo("ok");
        assertThat(results.getJSONObject(0).getString("preview")).isEqualTo("OK data");
        assertThat(results.getJSONObject(1).getString("status")).isEqualTo("rejected");
        assertThat(calls.getJSONObject(1).getString("text")).isEqualTo("结论");

        JSONObject end = o.getJSONObject("end");
        assertThat(end.getString("status")).isEqualTo("OK");
        assertThat(end.containsKey("error")).isFalse();
        assertThat(end.getBigDecimal("equity")).isEqualByComparingTo("10012.3");
        assertThat(end.getIntValue("latencyMs")).isEqualTo(42000);
        assertThat(end.getIntValue("modelCalls")).isEqualTo(2);
        assertThat(end.getLongValue("totalTokens")).isEqualTo(18000L);
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
        assertThat(ok.data().getIntValue("call")).isEqualTo(1);
        assertThat(ok.data().getString("id")).isEqualTo("c1");
        assertThat(ok.data().getString("name")).isEqualTo("klines");
        assertThat(ok.data().getString("status")).isEqualTo("ok");
        assertThat(ok.data().getString("preview")).hasSize(WakeTrace.PREVIEW_CHARS);
        assertThat(error.data().getString("status")).isEqualTo("error");
        assertThat(rejected.data().getString("status")).isEqualTo("rejected");
        assertThat(rejected.data().getString("preview")).isEqualTo("REJECTED: 杠杆越界");
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
        assertThat(end.data().getString("status")).isEqualTo("OK");
        assertThat(end.data().getString("error")).isEqualTo("上限");
        // 没报的项内存里是 null，线上（toJSONString）缺席
        assertThat(end.data().get("totalTokens")).isNull();
        assertThat(end.data().toJSONString()).doesNotContain("totalTokens");
        assertThat(JSON.parseObject(t.toJson()).getJSONArray("calls")).hasSize(1);

        // 尾 call 有半截正文（超时打断）：保留，endedAt 缺席
        WakeTrace half = trace();
        half.callStart();
        half.token("半截");
        half.end("ERROR", "超时", new BigDecimal("10000"), 600000, 1, null);
        JSONArray calls = JSON.parseObject(half.toJson()).getJSONArray("calls");
        assertThat(calls).hasSize(1);
        assertThat(calls.getJSONObject(0).getString("text")).isEqualTo("半截");
        assertThat(calls.getJSONObject(0).containsKey("endedAt")).isFalse();
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
        assertThat(owner.get(0).data().getString("kind")).isEqualTo("TRADE");
        assertThat(owner.get(0).data().getLongValue("wakeTime")).isEqualTo(1000L);
        assertThat(owner.get(1).data().getString("system")).isEqualTo("SYS");
        assertThat(owner.get(2).data().getIntValue("call")).isEqualTo(1);
        assertThat(owner.get(2).data().getJSONArray("toolCalls")).hasSize(1);
        assertThat(owner.get(3).data().getString("id")).isEqualTo("c1");
        assertThat(owner.get(4).data().getIntValue("call")).isEqualTo(2);
        assertThat(owner.get(5).data().getIntValue("call")).isEqualTo(2);
        assertThat(owner.get(5).data().getString("text")).isEqualTo("前后");
        // 提示词还没到：回放里也没有 prompt 帧
        assertThat(trace().replay()).extracting(WakeTrace.Frame::event).containsExactly("run_start");
    }

}
