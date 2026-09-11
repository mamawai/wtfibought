package com.mawai.wiibagent.llm;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Anthropic Messages 协议的形状钉子：请求头/思考档位/搜索声明/tool_choice 映射、同角色合并、
 * 内容块原样回放（思考块 signature 必须原样回去）、流式事件→帧（文本、工具、搜索过程）、错误归类。
 * 手法同 ResponsesChatModelTest：JDK HttpServer 伪造 SSE 服务端。
 */
class AnthropicChatModelTest {

    private static HttpServer server;
    /** 各用例各自设定要回放的事件序列（data 里的 JSON；event: 行按 type 补） */
    private static volatile String[] events = new String[0];
    /** 非 0 时不回放事件，直接回这个状态码与 body */
    private static volatile int httpStatus = 0;
    private static volatile String httpBody = "";
    private static volatile String lastRequestBody = "";
    private static volatile Map<String, List<String>> lastHeaders = Map.of();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastHeaders = exchange.getRequestHeaders();
            if (httpStatus != 0) {
                byte[] bytes = httpBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(httpStatus, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                for (String event : events) {
                    String line = event.replaceAll("\\s*\\R\\s*", "");
                    String type = JSON.parseObject(line).getString("type");
                    os.write(("event: " + type + "\ndata: " + line + "\n\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            }
        });
        server.createContext("/v1/models", exchange -> {
            lastHeaders = exchange.getRequestHeaders();
            byte[] bytes = "{\"data\":[{\"id\":\"claude-b\"},{\"id\":\"claude-a\"}],\"has_more\":false}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    @Test
    void 模型清单_GET_v1_models_取data的id() {
        List<String> ids = AnthropicChatModel.listModels(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "sk-ant");
        assertThat(ids).containsExactly("claude-a", "claude-b");
        assertThat(lastHeaders.get("X-api-key")).containsExactly("sk-ant");
        assertThat(lastHeaders.get("Anthropic-version")).containsExactly("2023-06-01");
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static AnthropicChatModel model(String effort, boolean webSearch, ToolCallingManager tcm) {
        httpStatus = 0;
        return new AnthropicChatModel("sk-ant", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "claude-test", null, effort, tcm, webSearch);
    }

    private static AnthropicChatModel model() {
        return model(null, false, mock(ToolCallingManager.class));
    }

    private static ChatOptions allowWebSearch(AnthropicChatModel m) {
        return ((ToolCallingChatOptions) m.getOptions()).mutate()
                .toolContext(Map.of(SseChatModel.WEB_SEARCH_KEY, true)).build();
    }

    /** 挂一个 function 工具 */
    private static ToolCallingManager oneTool() {
        ToolCallingManager tcm = mock(ToolCallingManager.class);
        when(tcm.resolveToolDefinitions(any())).thenReturn(List.of(ToolDefinition.builder()
                .name("get_price").description("查价格").inputSchema("{\"type\":\"object\",\"properties\":{}}").build()));
        return tcm;
    }

    private static JSONObject body() {
        return JSON.parseObject(lastRequestBody);
    }

    private static final String[] PLAIN = new String[]{
            """
            {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":12}}}""",
            """
            {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """
            {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"答"}}""",
            """
            {"type":"content_block_stop","index":0}""",
            """
            {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}""",
            """
            {"type":"message_stop"}"""
    };

    // ========== 请求侧 ==========

    @Test
    void 请求侧_头_档位_系统提示_搜索声明() {
        events = PLAIN;
        AnthropicChatModel m = model("high", true, mock(ToolCallingManager.class));
        m.call(new Prompt(List.of(new SystemMessage("你是助手"), new UserMessage("新闻")), allowWebSearch(m)));

        assertThat(lastHeaders.get("X-api-key")).containsExactly("sk-ant");
        assertThat(lastHeaders.get("Anthropic-version")).containsExactly("2023-06-01");
        JSONObject body = body();
        assertThat(body.getString("model")).isEqualTo("claude-test");
        assertThat(body.getIntValue("max_tokens")).isEqualTo(AnthropicChatModel.MAX_TOKENS);
        // system 是块数组，首块挂 1h 缓存断点；末条消息末块挂默认（5 分钟）断点
        JSONObject systemBlock = body.getJSONArray("system").getJSONObject(0);
        assertThat(systemBlock.getString("text")).isEqualTo("你是助手");
        assertThat(systemBlock.getJSONObject(AnthropicChatModel.CACHE_CONTROL).getString("ttl")).isEqualTo("1h");
        JSONObject tail = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0)
                .getJSONObject(AnthropicChatModel.CACHE_CONTROL);
        assertThat(tail.getString("type")).isEqualTo("ephemeral");
        assertThat(tail.containsKey("ttl")).isFalse();
        assertThat(body.getJSONObject("thinking").getString("type")).isEqualTo("adaptive");
        assertThat(body.getJSONObject("output_config").getString("effort")).isEqualTo("high");
        assertThat(body.getJSONArray("tools")).extracting(t -> ((JSONObject) t).getString("type"))
                .containsExactly(AnthropicChatModel.SEARCH_TOOL_TYPE);
        assertThat(body.getJSONArray("tools").getJSONObject(0).getString("name")).isEqualTo("web_search");
        assertThat(body.getJSONArray("messages").getJSONObject(0).getString("role")).isEqualTo("user");
    }

    @Test
    void 请求侧_多条系统消息各成一块_只有首块挂1h断点() {
        events = PLAIN;
        // 压缩后的形状：基础提示词 + 首问 + 摘要（SystemMessage）+ 最近消息
        model().call(new Prompt(List.of(
                new SystemMessage("你是助手"), new UserMessage("首问"),
                new SystemMessage("【摘要】第1段"), new UserMessage("新问题"))));

        JSONArray system = body().getJSONArray("system");
        assertThat(system).extracting(b -> ((JSONObject) b).getString("text")).containsExactly("你是助手", "【摘要】第1段");
        assertThat(system.getJSONObject(0).getJSONObject(AnthropicChatModel.CACHE_CONTROL).getString("ttl")).isEqualTo("1h");
        // 摘要块不挂断点：它压一次变一次，挂上去会把基础提示词的 1h 缓存一起冲掉
        assertThat(system.getJSONObject(1).containsKey(AnthropicChatModel.CACHE_CONTROL)).isFalse();
        // 摘要没混进 messages
        JSONArray messages = body().getJSONArray("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.getJSONObject(0).getJSONArray("content")).extracting(b -> ((JSONObject) b).getString("text"))
                .containsExactly("首问", "新问题");
    }

    @Test
    void 请求侧_档位none为disabled_留空不传() {
        events = PLAIN;
        model("none", false, mock(ToolCallingManager.class)).call(new Prompt("q"));
        assertThat(body().getJSONObject("thinking").getString("type")).isEqualTo("disabled");
        assertThat(body().containsKey("output_config")).isFalse();

        model().call(new Prompt("q"));
        assertThat(body().containsKey("thinking")).isFalse();
    }

    @Test
    void 请求侧_未捎许可或端点未开启都不声明搜索() {
        events = PLAIN;
        AnthropicChatModel on = model(null, true, mock(ToolCallingManager.class));
        on.call(new Prompt("q", on.getOptions()));
        assertThat(body().containsKey("tools")).isFalse();

        AnthropicChatModel off = model(null, false, mock(ToolCallingManager.class));
        off.call(new Prompt("q", allowWebSearch(off)));
        assertThat(body().containsKey("tools")).isFalse();
    }

    @Test
    void 请求侧_function工具与tool_choice映射() {
        events = PLAIN;
        AnthropicChatModel m = model(null, false, oneTool());
        m.call(new Prompt("q", ToolChoice.apply(m.getOptions(), ToolChoice.REQUIRED)));
        JSONObject tool = body().getJSONArray("tools").getJSONObject(0);
        assertThat(tool.getString("name")).isEqualTo("get_price");
        assertThat(tool.getJSONObject("input_schema").getString("type")).isEqualTo("object");
        assertThat(body().getJSONObject("tool_choice").getString("type")).isEqualTo("any");

        m.call(new Prompt("q", ToolChoice.apply(m.getOptions(), "get_price")));
        assertThat(body().getJSONObject("tool_choice").getString("type")).isEqualTo("tool");
        assertThat(body().getJSONObject("tool_choice").getString("name")).isEqualTo("get_price");

        m.call(new Prompt("q", m.getOptions()));
        assertThat(body().containsKey("tool_choice")).isFalse();
    }

    @Test
    void 请求侧_同角色连续消息合成一条多块消息() {
        events = PLAIN;
        AssistantMessage assistant = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("toolu_1", "function", "get_price", "{\"symbol\":\"BTC\"}"))).build();
        ToolResponseMessage toolResult = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("toolu_1", "get_price", "60000"))).build();
        model().call(new Prompt(List.of(new UserMessage("问题"), new UserMessage("补充"),
                assistant, toolResult, new UserMessage("专家结论"))));

        JSONArray messages = body().getJSONArray("messages");
        assertThat(messages).hasSize(3);
        assertThat(messages.getJSONObject(0).getString("role")).isEqualTo("user");
        assertThat(messages.getJSONObject(0).getJSONArray("content")).extracting(b -> ((JSONObject) b).getString("text"))
                .containsExactly("问题", "补充");
        // 没有原始块的 assistant 按 tool_use 拼；空文本不发空 text 块
        JSONArray assistantBlocks = messages.getJSONObject(1).getJSONArray("content");
        assertThat(assistantBlocks).hasSize(1);
        assertThat(assistantBlocks.getJSONObject(0).getString("type")).isEqualTo("tool_use");
        assertThat(assistantBlocks.getJSONObject(0).getJSONObject("input").getString("symbol")).isEqualTo("BTC");
        // 工具回执与紧随的用户消息并成一条，tool_result 在前
        JSONArray userBlocks = messages.getJSONObject(2).getJSONArray("content");
        assertThat(userBlocks).extracting(b -> ((JSONObject) b).getString("type")).containsExactly("tool_result", "text");
        assertThat(userBlocks.getJSONObject(0).getString("tool_use_id")).isEqualTo("toolu_1");
        assertThat(userBlocks.getJSONObject(0).getString("content")).isEqualTo("60000");
        // 缓存断点只在末条消息的末块
        assertThat(userBlocks.getJSONObject(0).containsKey(AnthropicChatModel.CACHE_CONTROL)).isFalse();
        assertThat(userBlocks.getJSONObject(1).containsKey(AnthropicChatModel.CACHE_CONTROL)).isTrue();
        assertThat(messages.getJSONObject(0).getJSONArray("content").getJSONObject(1)
                .containsKey(AnthropicChatModel.CACHE_CONTROL)).isFalse();
    }

    // ========== 阻塞路径 ==========

    @Test
    void 阻塞路径_文本流拼接并带usage() {
        events = new String[]{
                PLAIN[0], PLAIN[1], PLAIN[2],
                """
                {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"案"}}""",
                PLAIN[3], PLAIN[4], PLAIN[5]
        };
        ChatResponse response = model().call(new Prompt("问题"));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("答案");
        assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
        assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(12);
        assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(3);
        assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(15);
        assertThat(response.getMetadata().getId()).isEqualTo("msg_1");
    }

    /** 开了缓存 input_tokens 只算未命中的；命中与写入两项要一起算进 prompt，否则用量少记一大截 */
    @Test
    void 阻塞路径_usage把缓存命中与写入算进prompt() {
        events = new String[]{
                """
                {"type":"message_start","message":{"id":"msg_c","usage":{"input_tokens":3,
                 "cache_creation_input_tokens":20,"cache_read_input_tokens":100}}}""",
                PLAIN[1], PLAIN[2], PLAIN[3], PLAIN[4], PLAIN[5]
        };
        ChatResponse response = model().call(new Prompt("问题"));

        assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(123);
        assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(3);
        assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(126);
    }

    private static final String[] THINK_THEN_TOOL = new String[]{
            """
            {"type":"message_start","message":{"id":"msg_2","usage":{"input_tokens":20}}}""",
            """
            {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            """
            {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"先查价"}}""",
            """
            {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-1"}}""",
            """
            {"type":"content_block_stop","index":0}""",
            """
            {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """
            {"type":"content_block_stop","index":1}""",
            """
            {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_9","name":"get_price","input":{}}}""",
            """
            {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\\"sym"}}""",
            """
            {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"bol\\":\\"BTC\\"}"}}""",
            """
            {"type":"content_block_stop","index":2}""",
            """
            {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":9}}""",
            """
            {"type":"message_stop"}"""
    };

    @Test
    void 阻塞路径_工具调用往返_思考块原样回放() {
        events = THINK_THEN_TOOL;
        AnthropicChatModel m = model(null, false, oneTool());
        ChatResponse response = m.call(new Prompt("BTC 多少钱"));

        AssistantMessage assistant = response.getResult().getOutput();
        assertThat(assistant.getToolCalls()).hasSize(1);
        assertThat(assistant.getToolCalls().getFirst().id()).isEqualTo("toolu_9");
        assertThat(assistant.getToolCalls().getFirst().name()).isEqualTo("get_price");
        assertThat(JSON.parseObject(assistant.getToolCalls().getFirst().arguments()).getString("symbol")).isEqualTo("BTC");
        assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
        // 原始块挂在消息 metadata 上，阻塞路径的帧合并不能把它丢了
        assertThat(assistant.getMetadata()).containsKey(AnthropicChatModel.BLOCKS_KEY);

        // 回传工具结果：assistant 消息按原始块回放，思考块与 signature 原样、空 text 块不回放
        events = PLAIN;
        ToolResponseMessage toolResult = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("toolu_9", "get_price", "60000"))).build();
        m.call(new Prompt(List.of(new UserMessage("BTC 多少钱"), assistant, toolResult)));

        JSONArray messages = body().getJSONArray("messages");
        JSONArray replayed = messages.getJSONObject(1).getJSONArray("content");
        assertThat(replayed).extracting(b -> ((JSONObject) b).getString("type")).containsExactly("thinking", "tool_use");
        assertThat(replayed.getJSONObject(0).getString("signature")).isEqualTo("sig-1");
        assertThat(replayed.getJSONObject(0).getString("thinking")).isEqualTo("先查价");
        assertThat(replayed.getJSONObject(1).getJSONObject("input").getString("symbol")).isEqualTo("BTC");
        assertThat(messages.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("type"))
                .isEqualTo("tool_result");
    }

    /** 原始块只在工具循环里回放（后面紧跟工具回执）；已结束的轮次按文本拼，思考块/搜索密文/引用都不带 */
    @Test
    void 请求侧_原始块只在工具循环里回放() {
        events = PLAIN;
        String blocks = """
                [{"type":"thinking","thinking":"先查","signature":"sig-1"},
                 {"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{"query":"btc"}},
                 {"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[
                     {"type":"web_search_result","url":"https://a.com/x","title":"A","encrypted_content":"enc"}]},
                 {"type":"text","text":"答","citations":[{"type":"web_search_result_location","url":"https://a.com/x"}]},
                 {"type":"tool_use","id":"toolu_1","name":"get_price","input":{"symbol":"BTC"}}]""";
        AssistantMessage assistant = AssistantMessage.builder().content("答").toolCalls(List.of(
                        new AssistantMessage.ToolCall("toolu_1", "function", "get_price", "{\"symbol\":\"BTC\"}")))
                .properties(Map.of(AnthropicChatModel.BLOCKS_KEY, blocks)).build();
        AnthropicChatModel m = model(null, true, mock(ToolCallingManager.class));

        // 已结束的轮次：后面是新提问
        m.call(new Prompt(List.of(new UserMessage("q"), assistant, new UserMessage("再问")), allowWebSearch(m)));
        JSONArray ended = body().getJSONArray("messages").getJSONObject(1).getJSONArray("content");
        assertThat(ended).extracting(b -> ((JSONObject) b).getString("type")).containsExactly("text", "tool_use");
        assertThat(ended.getJSONObject(0).containsKey("citations")).isFalse();

        // 工具循环里：后面紧跟工具回执
        ToolResponseMessage toolResult = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("toolu_1", "get_price", "60000"))).build();
        m.call(new Prompt(List.of(new UserMessage("q"), assistant, toolResult), allowWebSearch(m)));
        JSONArray inLoop = body().getJSONArray("messages").getJSONObject(1).getJSONArray("content");
        assertThat(inLoop).extracting(b -> ((JSONObject) b).getString("type"))
                .containsExactly("thinking", "server_tool_use", "web_search_tool_result", "text", "tool_use");
        assertThat(inLoop.getJSONObject(0).getString("signature")).isEqualTo("sig-1");
        assertThat(inLoop.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("encrypted_content"))
                .isEqualTo("enc");
    }

    // ========== 搜索过程帧 ==========

    @Test
    void 流式_搜索事件帧_searching_searched_cited() {
        events = new String[]{
                """
                {"type":"message_start","message":{"id":"msg_3","usage":{"input_tokens":5}}}""",
                """
                {"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search"}}""",
                """
                {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"query\\":\\"BTC news\\"}"}}""",
                """
                {"type":"content_block_stop","index":0}""",
                """
                {"type":"content_block_start","index":1,"content_block":{"type":"web_search_tool_result","tool_use_id":"srvtoolu_1",
                 "content":[{"type":"web_search_result","url":"https://a.com/1","title":"A1","encrypted_content":"e1"},
                            {"type":"web_search_result","url":"https://b.com/2","title":"B2","encrypted_content":"e2"}]}}""",
                """
                {"type":"content_block_stop","index":1}""",
                """
                {"type":"content_block_start","index":2,"content_block":{"type":"text","text":""}}""",
                """
                {"type":"content_block_delta","index":2,"delta":{"type":"text_delta","text":"据 A 报道"}}""",
                """
                {"type":"content_block_delta","index":2,"delta":{"type":"citations_delta","citation":
                 {"type":"web_search_result_location","url":"https://a.com/1","title":"A1","cited_text":"..","encrypted_index":"i1"}}}""",
                """
                {"type":"content_block_stop","index":2}""",
                """
                {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7,"server_tool_use":{"web_search_requests":1}}}""",
                """
                {"type":"message_stop"}"""
        };
        AnthropicChatModel m = model(null, true, mock(ToolCallingManager.class));
        List<ChatResponse> frames = m.stream(new Prompt("BTC 新闻", allowWebSearch(m))).collectList().block();

        List<SearchEvent> search = frames.stream()
                .filter(f -> f.getMetadata().<String>get(SearchEvent.KEY) != null)
                .map(f -> SearchEvent.parse(f.getMetadata().get(SearchEvent.KEY))).toList();
        assertThat(search).extracting(SearchEvent::phase)
                .containsExactly(SearchEvent.SEARCHING, SearchEvent.SEARCHED, SearchEvent.CITED);
        assertThat(search.get(0).query()).isEqualTo("BTC news");
        assertThat(search.get(1).query()).isEqualTo("BTC news");
        assertThat(search.get(1).sources()).extracting(SearchEvent.Source::url)
                .containsExactly("https://a.com/1", "https://b.com/2");
        assertThat(search.get(2).sources()).extracting(SearchEvent.Source::title).containsExactly("A1");
        // 搜索帧是空文本帧，正文照常
        assertThat(frames.stream().map(f -> f.getResult().getOutput().getText()).reduce("", String::concat))
                .isEqualTo("据 A 报道");
        ChatResponse last = frames.getLast();
        assertThat(last.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
        assertThat(last.getMetadata().<Integer>get("web_search_requests")).isEqualTo(1);
        // 原始块含搜索结果（encrypted_content 回传要用）与引用
        JSONArray blocks = JSON.parseArray((String) last.getResult().getOutput().getMetadata().get(AnthropicChatModel.BLOCKS_KEY));
        assertThat(blocks).extracting(b -> ((JSONObject) b).getString("type"))
                .containsExactly("server_tool_use", "web_search_tool_result", "text");
        assertThat(blocks.getJSONObject(0).getJSONObject("input").getString("query")).isEqualTo("BTC news");
        assertThat(blocks.getJSONObject(2).getJSONArray("citations")).hasSize(1);
    }

    // ========== 错误归类 ==========

    @Test
    void 拒收400判非瞬时_流式overloaded判瞬时() {
        AnthropicChatModel m = model();
        httpStatus = 400;
        httpBody = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"tools.0: web_search_20250305 not supported\"}}";
        assertThatThrownBy(() -> m.call(new Prompt("q")))
                .isInstanceOf(NonTransientAiException.class)
                .hasMessageContaining("400").hasMessageContaining("web_search");

        httpStatus = 0;
        events = new String[]{
                PLAIN[0],
                """
                {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""
        };
        assertThatThrownBy(() -> model().call(new Prompt("q")))
                .isInstanceOf(TransientAiException.class)
                .hasMessageContaining("Overloaded");
    }

    /**
     * refusal 是 HTTP 200 + stop_reason，零输出或半截；max_tokens 是撞顶。
     * 按 STOP 收会把空回答或半截[本轮结论]以 status=OK 落库，必须显式失败且不重试
     */
    @Test
    void 非正常收尾_refusal与max_tokens判非瞬时() {
        events = new String[]{
                PLAIN[0],
                """
                {"type":"message_delta","delta":{"stop_reason":"refusal",
                 "stop_details":{"type":"refusal","category":"cyber"}},"usage":{"output_tokens":0}}""",
                PLAIN[5]
        };
        assertThatThrownBy(() -> model().call(new Prompt("q")))
                .isInstanceOf(NonTransientAiException.class)
                .hasMessageContaining("拒绝生成").hasMessageContaining("cyber");

        events = new String[]{
                PLAIN[0], PLAIN[1], PLAIN[2], PLAIN[3],
                """
                {"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":3}}""",
                PLAIN[5]
        };
        assertThatThrownBy(() -> model().call(new Prompt("q")))
                .isInstanceOf(NonTransientAiException.class)
                .hasMessageContaining("max_tokens");
    }

    @Test
    void 断流无收尾_判瞬时可重试() {
        events = new String[]{PLAIN[0], PLAIN[1], PLAIN[2]};
        assertThatThrownBy(() -> model().call(new Prompt("q")))
                .isInstanceOf(TransientAiException.class)
                .hasMessageContaining("断流");
    }
}
