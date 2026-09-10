package com.mawai.wiibagent.llm;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.ToolCallback;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 钉死与框架的契约边界（这些曾被别处单测 mock 掉，真跑才暴露）：
 * Spring AI 2.0 的契约方法是 getOptions()，覆写成旧名 getDefaultOptions() 会命中接口默认实现
 * 返回普通 ChatOptions → ResilientChatService 挂工具的 instanceof 恒假 → 专家请求 tools=[]。
 * <p>
 * 阻塞路径用例走真 HTTP + SSE（JDK HttpServer 假服务器，零新依赖）：
 * call() 与 stream() 共用 streamOnce 一条协议解析路，这些用例钉住的是各类 BYOK 渠道的
 * 事件形态兼容性——工具只在 output_item.done 发、无增量服务端只发 completed、半途断流等。
 */
class ResponsesChatModelTest {

    /** 假 SSE 服务器：每个请求都按 events 当前值回放事件后关流（关流即 SSE 正常终止信号） */
    private static HttpServer server;
    /** 各用例各自设定要回放的事件序列；handler 无状态，重试的每次请求拿到同样的流 */
    private static volatile String[] events = new String[0];
    /** 挂死模拟：发完 events 后挂住这么久再关流（0=立即关）。心跳挂死＝发心跳后既不出结果也不断流 */
    private static volatile long holdMs = 0;
    /** 最近一次请求体：请求侧断言（服务端工具注入等）从这取 */
    private static volatile String lastRequestBody = "";

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                for (String event : events) {
                    // SSE 的 data 不允许裸换行，text block 里排版用的换行必须压平
                    String line = event.replaceAll("\\s*\\R\\s*", "");
                    os.write(("data: " + line + "\n\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                if (holdMs > 0) {
                    try {
                        Thread.sleep(holdMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });
        // 挂死用例的重试会并发压着多个 hold 中的请求，默认单 dispatcher 线程会让后续用例排队
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void resetHold() {
        holdMs = 0;
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private ResponsesChatModel model() {
        return model(false);
    }

    /** webSearch=端点配置层的开关（建模时烤死）；调用层的许可另经 toolContext 捎带 */
    private ResponsesChatModel model(boolean webSearch) {
        return new ResponsesChatModel("key", "http://127.0.0.1:" + server.getAddress().getPort(),
                "grok-test", null, null, mock(ToolCallingManager.class), webSearch);
    }

    /** 汇总者那样的调用方：本次调用允许服务端搜索（经 toolContext 捎给模型） */
    private ChatOptions allowWebSearch(ResponsesChatModel model) {
        return ((ToolCallingChatOptions) model.getOptions()).mutate()
                .toolContext(Map.of(ResponsesChatModel.WEB_SEARCH_KEY, true)).build();
    }

    private static final String[] PLAIN_COMPLETED = new String[]{
            """
            {"type":"response.completed","response":{"id":"resp_ws","status":"completed",
             "model":"grok-test","output":[{"type":"message","content":[
                 {"type":"output_text","text":"答"}]}]}}"""
    };

    // ========== 服务端搜索（web_search）：端点配置 ∧ 调用许可 双闸门 ==========

    @Test
    void 请求侧_端点开启且调用允许_注入web_search服务端工具() {
        events = PLAIN_COMPLETED;
        ResponsesChatModel m = model(true);
        m.call(new Prompt("过去24小时BTC新闻", allowWebSearch(m)));

        JSONObject body = JSON.parseObject(lastRequestBody);
        assertThat(body.getJSONArray("tools"))
                .extracting(t -> ((JSONObject) t).getString("type"))
                .contains("web_search");
    }

    @Test
    void 请求侧_调用未捎许可_端点开了也不注入() {
        // 专家/trader 链路不捎许可键：同一个端点开了搜索，它们的请求里也不许出现服务端工具
        events = PLAIN_COMPLETED;
        ResponsesChatModel m = model(true);
        m.call(new Prompt("查行情", m.getOptions()));

        assertThat(JSON.parseObject(lastRequestBody).containsKey("tools")).isFalse();
    }

    @Test
    void 请求侧_端点未开启_调用允许也不注入() {
        events = PLAIN_COMPLETED;
        ResponsesChatModel m = model(false);
        m.call(new Prompt("过去24小时BTC新闻", allowWebSearch(m)));

        assertThat(JSON.parseObject(lastRequestBody).containsKey("tools")).isFalse();
    }

    @Test
    void 响应侧_usage带服务端搜索计数_落进响应metadata() {
        // 真实上游（xAI）usage 形态：搜没搜要有据可查
        events = new String[]{
                """
                {"type":"response.completed","response":{"id":"resp_ws2","status":"completed",
                 "model":"grok-test","output":[],
                 "usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15,
                     "num_server_side_tools_used":7,
                     "server_side_tool_usage_details":{"web_search_calls":7,"x_search_calls":0}}}}"""
        };
        ChatResponse response = model().call(new Prompt("问题"));

        assertThat(response.getMetadata().<Integer>get("num_server_side_tools_used")).isEqualTo(7);
        assertThat(response.getMetadata().<String>get("server_side_tool_usage_details"))
                .contains("web_search_calls");
    }

    /** 搜索项 added=开搜、done=搜完（query/sources 在 action 里），url_citation 注解=引用；都是空文本帧不进正文 */
    @Test
    void 流式_搜索事件帧_searching_searched_cited() {
        events = new String[]{
                """
                {"type":"response.output_item.added","output_index":0,"item":{"type":"web_search_call","id":"ws_1","status":"in_progress"}}""",
                """
                {"type":"response.output_item.done","output_index":0,"item":{"type":"web_search_call","id":"ws_1","status":"completed",
                 "action":{"type":"search","query":"BTC news","sources":[{"type":"url","url":"https://a.com/1","title":"A1"}]}}}""",
                """
                {"type":"response.output_text.delta","delta":"据 A 报道"}""",
                """
                {"type":"response.output_text.annotation.added","annotation":{"type":"url_citation","url":"https://a.com/1","title":"A1"}}""",
                """
                {"type":"response.completed","response":{"id":"resp_s","status":"completed","model":"grok-test","output":[]}}"""
        };
        ResponsesChatModel m = model(true);
        List<ChatResponse> frames = m.stream(new Prompt("BTC 新闻", allowWebSearch(m))).collectList().block();

        List<SearchEvent> search = frames.stream()
                .filter(f -> f.getMetadata().<String>get(SearchEvent.KEY) != null)
                .map(f -> SearchEvent.parse(f.getMetadata().get(SearchEvent.KEY))).toList();
        assertThat(search).extracting(SearchEvent::phase)
                .containsExactly(SearchEvent.SEARCHING, SearchEvent.SEARCHED, SearchEvent.CITED);
        assertThat(search.get(0).query()).isNull();      // added 时上游还没给 query
        assertThat(search.get(1).query()).isEqualTo("BTC news");
        assertThat(search.get(1).sources()).extracting(SearchEvent.Source::url).containsExactly("https://a.com/1");
        assertThat(search.get(2).sources()).extracting(SearchEvent.Source::title).containsExactly("A1");
        assertThat(frames.stream().map(f -> f.getResult().getOutput().getText()).reduce("", String::concat))
                .isEqualTo("据 A 报道");
        assertThat(frames.getLast().getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
    }

    @Test
    void getOptions必须给ToolCallingChatOptions() {
        // 真实实例、不 mock：ResilientChatService 靠 instanceof 这个类型决定挂不挂工具
        assertThat(model().getOptions()).isInstanceOf(ToolCallingChatOptions.class);
        assertThat(model().getOptions().getModel()).isEqualTo("grok-test");
    }

    @Test
    void 与ResilientChatService组合时工具挂得上() {
        // 复刻专家叶子的装配路径：真实模型 + 真实工具表，工具必须进 chatOptions
        ResilientChatService service = ResilientChatService.builder()
                .model(model())
                .systemPrompt("你是专家")
                .tools(List.of(mock(ToolCallback.class)))
                .forceFirstToolChoice("required")
                .build();

        ToolCallingChatOptions options = (ToolCallingChatOptions) service.chatOptions().orElseThrow();
        assertThat(options.getToolCallbacks()).hasSize(1);
        // 强制信号是逐次调用时才捎的（ToolChoice.apply），底稿里没有；本模型读的就是这个键
        assertThat(ToolChoice.of(ToolChoice.apply(options, ToolChoice.REQUIRED))).isEqualTo("required");
        assertThat(ToolChoice.of(options)).isEqualTo(ToolChoice.AUTO);
    }

    // ========== 阻塞路径（call → streamOnce 帧合并） ==========

    @Test
    void 阻塞路径_正常SSE流_拼接增量并带usage收尾() {
        events = new String[]{
                """
                {"type":"response.output_text.delta","delta":"[本轮结论]"}""",
                """
                {"type":"response.output_text.delta","delta":"HOLD，等待突破确认。"}""",
                """
                {"type":"response.completed","response":{"id":"resp_1","status":"completed",
                 "model":"grok-test","output":[],
                 "usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}}"""
        };
        ChatResponse response = model().call(new Prompt("问题"));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("[本轮结论]HOLD，等待突破确认。");
        assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
        assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(15);
    }

    @Test
    void 阻塞路径_工具帧_收齐并标TOOL_CALLS() {
        // 有的渠道 completed 里 output 不全，工具调用只能从 output_item.done 收——
        // 帧合并必须靠工具帧而不是 completed 的 output（cpa 对 grok 专门打过 output 补丁，上游真有此形态）
        events = new String[]{
                """
                {"type":"response.output_item.done","item":{"type":"function_call",
                 "call_id":"c1","name":"get_account","arguments":"{}"}}""",
                """
                {"type":"response.completed","response":{"id":"resp_2","status":"completed",
                 "model":"grok-test","output":[]}}"""
        };
        ChatResponse response = model().call(new Prompt("查账户"));

        List<AssistantMessage.ToolCall> toolCalls = response.getResult().getOutput().getToolCalls();
        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.getFirst().name()).isEqualTo("get_account");
        assertThat(toolCalls.getFirst().id()).isEqualTo("c1");
        assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
    }

    @Test
    void 阻塞路径_无增量服务端_completed里的工具调用也要收() {
        // 简易网关把非流式响应原样包成一个 completed 事件：工具调用只在 output 里，
        // 没有 output_item.done——兜底丢工具的话，"模型要调工具"会被当成"模型答了空话"收场
        events = new String[]{
                """
                {"type":"response.completed","response":{"id":"resp_5","status":"completed",
                 "model":"grok-test","output":[{"type":"function_call",
                     "call_id":"c9","name":"get_account","arguments":"{}"}]}}"""
        };
        ChatResponse response = model().call(new Prompt("查账户"));

        List<AssistantMessage.ToolCall> toolCalls = response.getResult().getOutput().getToolCalls();
        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.getFirst().name()).isEqualTo("get_account");
        assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
    }

    @Test
    void 阻塞路径_completed事件带incomplete状态_以payload为准判失败() {
        // 同一类简易网关的另一面：上游返回 status=incomplete 的 JSON 被不加判断地
        // 包成 completed 事件——事件类型说完成、payload 说截断时，信 payload
        events = new String[]{
                """
                {"type":"response.completed","response":{"id":"resp_6","status":"incomplete",
                 "model":"grok-test","incomplete_details":{"reason":"max_output_tokens"},
                 "output":[{"type":"message","content":[
                     {"type":"output_text","text":"[本轮结论]方向：做多 BTC，止损放在"}]}]}}"""
        };
        assertThatThrownBy(() -> model().call(new Prompt("问题")))
                .isInstanceOf(NonTransientAiException.class)
                .hasMessageContaining("max_output_tokens");
    }

    @Test
    void 阻塞路径_无增量服务端_从completed兜底全文() {
        // 不发 delta 的服务端：全文只在 completed 的 output 里，靠 toFrames 的兜底帧补出
        events = new String[]{
                """
                {"type":"response.completed","response":{"id":"resp_3","status":"completed",
                 "model":"grok-test","output":[{"type":"message","content":[
                     {"type":"output_text","text":"[本轮结论]HOLD，等待突破确认。"}]}]}}"""
        };
        assertThat(model().call(new Prompt("问题")).getResult().getOutput().getText())
                .isEqualTo("[本轮结论]HOLD，等待突破确认。");
    }

    /**
     * Responses 除了 failed 还有 incomplete（含 max_output_tokens 截断），它照常带着半截 output。
     * 阻塞路径当正常收尾发 STOP 的话，被截断的[本轮结论]会以 status=OK 落库，
     * 下一轮还被当"上一轮的承诺"回注给模型做检验基准。
     */
    @Test
    void 阻塞路径_incomplete事件_判截断失败() {
        events = new String[]{
                """
                {"type":"response.output_text.delta","delta":"[本轮结论]方向：做多 BTC，止损放在"}""",
                """
                {"type":"response.incomplete","response":{"status":"incomplete",
                 "incomplete_details":{"reason":"max_output_tokens"}}}"""
        };
        assertThatThrownBy(() -> model().call(new Prompt("问题")))
                .isInstanceOf(NonTransientAiException.class)
                .hasMessageContaining("max_output_tokens");
    }

    /**
     * 半途断流（cpa 侧对应 408 stream disconnected）：连接级偶发故障，换个连接大概率就好，
     * 必须判 Transient 进 callWithRetry 重试通道——判 NonTransient 会让 trader 整轮唤醒白跑。
     */
    @Test
    void 阻塞路径_断流无收尾_判瞬时可重试() {
        events = new String[]{
                """
                {"type":"response.output_text.delta","delta":"说到一半"}"""
                // 没有 completed，服务器随即关流
        };
        assertThatThrownBy(() -> model().call(new Prompt("问题")))
                .isInstanceOf(TransientAiException.class)
                .hasMessageContaining("断流");
    }

    @Test
    void 阻塞路径_心跳不断但不完成_整体超时判瞬时挂死() {
        // 生产实测的挂死真身：流上 keepalive 心跳不断（首帧/帧间超时全被骗过），但结果永远不来。
        // 唯一有效判据是整体时长；必须判瞬时才能进重试换连接（实测掐线重发 3s 内即成功）
        events = new String[]{"""
                {"type":"response.in_progress"}"""};
        holdMs = 10_000;
        ChatOptions options = ResponsesChatModel.withCallTimeout(model().getOptions(), Duration.ofSeconds(2));
        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> model().call(new Prompt("问题", options)))
                .isInstanceOf(TransientAiException.class)
                .hasMessageContaining("挂死");
        // 3 次重试＋退避应在 10s 内收场；上限断言同时钉住"捎带的超时真被读到了"（否则等满默认 10 分钟）
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void 阻塞路径_陌生事件类型_跳过不炸流() {
        // 协议随时会添新事件类型；陌生类型只记日志跳过，不许把整条流炸掉
        events = new String[]{
                """
                {"type":"response.brand_new_event","foo":"bar"}""",
                """
                {"type":"response.output_text.delta","delta":"正文"}""",
                """
                {"type":"response.completed","response":{"id":"resp_u","status":"completed",
                 "model":"grok-test","output":[]}}"""
        };
        ChatResponse response = model().call(new Prompt("问题"));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("正文");
        assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
    }
}
