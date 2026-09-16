package com.mawai.wiibagent.llm;

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
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
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
                    String type = MAPPER.readTree(line).path("type").asString(null);
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

    private static JsonNode body() {
        return MAPPER.readTree(lastRequestBody);
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
        JsonNode body = body();
        assertThat(body.path("model").asString(null)).isEqualTo("claude-test");
        assertThat(body.path("max_tokens").asInt(0)).isEqualTo(AnthropicChatModel.MAX_TOKENS);
        // system 是块数组，首块挂 1h 缓存断点；末条消息末块挂默认（5 分钟）断点
        JsonNode systemBlock = body.get("system").get(0);
        assertThat(systemBlock.path("text").asString(null)).isEqualTo("你是助手");
        assertThat(systemBlock.get(AnthropicChatModel.CACHE_CONTROL).path("ttl").asString(null)).isEqualTo("1h");
        JsonNode tail = body.get("messages").get(0).get("content").get(0)
                .get(AnthropicChatModel.CACHE_CONTROL);
        assertThat(tail.path("type").asString(null)).isEqualTo("ephemeral");
        assertThat(tail.has("ttl")).isFalse();
        assertThat(body.get("thinking").path("type").asString(null)).isEqualTo("adaptive");
        assertThat(body.get("output_config").path("effort").asString(null)).isEqualTo("high");
        assertThat(body.get("tools")).extracting(t -> t.path("type").asString(null))
                .containsExactly(AnthropicChatModel.SEARCH_TOOL_TYPE);
        assertThat(body.get("tools").get(0).path("name").asString(null)).isEqualTo("web_search");
        assertThat(body.get("messages").get(0).path("role").asString(null)).isEqualTo("user");
    }

    @Test
    void 请求侧_多条系统消息各成一块_只有首块挂1h断点() {
        events = PLAIN;
        // 压缩后的形状：基础提示词 + 首问 + 摘要（SystemMessage）+ 最近消息
        model().call(new Prompt(List.of(
                new SystemMessage("你是助手"), new UserMessage("首问"),
                new SystemMessage("【摘要】第1段"), new UserMessage("新问题"))));

        JsonNode system = body().get("system");
        assertThat(system).extracting(b -> b.path("text").asString(null)).containsExactly("你是助手", "【摘要】第1段");
        assertThat(system.get(0).get(AnthropicChatModel.CACHE_CONTROL).path("ttl").asString(null)).isEqualTo("1h");
        // 摘要块不挂断点：它压一次变一次，挂上去会把基础提示词的 1h 缓存一起冲掉
        assertThat(system.get(1).has(AnthropicChatModel.CACHE_CONTROL)).isFalse();
        // 摘要没混进 messages
        JsonNode messages = body().get("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("content")).extracting(b -> b.path("text").asString(null))
                .containsExactly("首问", "新问题");
    }

    @Test
    void 请求侧_档位none为disabled_留空不传() {
        events = PLAIN;
        model("none", false, mock(ToolCallingManager.class)).call(new Prompt("q"));
        assertThat(body().get("thinking").path("type").asString(null)).isEqualTo("disabled");
        assertThat(body().has("output_config")).isFalse();

        model().call(new Prompt("q"));
        assertThat(body().has("thinking")).isFalse();
    }

    @Test
    void 请求侧_未捎许可或端点未开启都不声明搜索() {
        events = PLAIN;
        AnthropicChatModel on = model(null, true, mock(ToolCallingManager.class));
        on.call(new Prompt("q", on.getOptions()));
        assertThat(body().has("tools")).isFalse();

        AnthropicChatModel off = model(null, false, mock(ToolCallingManager.class));
        off.call(new Prompt("q", allowWebSearch(off)));
        assertThat(body().has("tools")).isFalse();
    }

    @Test
    void 请求侧_function工具与tool_choice映射() {
        events = PLAIN;
        AnthropicChatModel m = model(null, false, oneTool());
        m.call(new Prompt("q", ToolChoice.apply(m.getOptions(), ToolChoice.REQUIRED)));
        JsonNode tool = body().get("tools").get(0);
        assertThat(tool.path("name").asString(null)).isEqualTo("get_price");
        assertThat(tool.get("input_schema").path("type").asString(null)).isEqualTo("object");
        assertThat(body().get("tool_choice").path("type").asString(null)).isEqualTo("any");

        m.call(new Prompt("q", ToolChoice.apply(m.getOptions(), "get_price")));
        assertThat(body().get("tool_choice").path("type").asString(null)).isEqualTo("tool");
        assertThat(body().get("tool_choice").path("name").asString(null)).isEqualTo("get_price");

        m.call(new Prompt("q", m.getOptions()));
        assertThat(body().has("tool_choice")).isFalse();
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

        JsonNode messages = body().get("messages");
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).path("role").asString(null)).isEqualTo("user");
        assertThat(messages.get(0).get("content")).extracting(b -> b.path("text").asString(null))
                .containsExactly("问题", "补充");
        // 没有原始块的 assistant 按 tool_use 拼；空文本不发空 text 块
        JsonNode assistantBlocks = messages.get(1).get("content");
        assertThat(assistantBlocks).hasSize(1);
        assertThat(assistantBlocks.get(0).path("type").asString(null)).isEqualTo("tool_use");
        assertThat(assistantBlocks.get(0).get("input").path("symbol").asString(null)).isEqualTo("BTC");
        // 工具回执与紧随的用户消息并成一条，tool_result 在前
        JsonNode userBlocks = messages.get(2).get("content");
        assertThat(userBlocks).extracting(b -> b.path("type").asString(null)).containsExactly("tool_result", "text");
        assertThat(userBlocks.get(0).path("tool_use_id").asString(null)).isEqualTo("toolu_1");
        assertThat(userBlocks.get(0).path("content").asString(null)).isEqualTo("60000");
        // 缓存断点只在末条消息的末块
        assertThat(userBlocks.get(0).has(AnthropicChatModel.CACHE_CONTROL)).isFalse();
        assertThat(userBlocks.get(1).has(AnthropicChatModel.CACHE_CONTROL)).isTrue();
        assertThat(messages.get(0).get("content").get(1)
                .has(AnthropicChatModel.CACHE_CONTROL)).isFalse();
    }

    /**
     * 请求体整串钉死：温度/档位、工具定义与 schema 里的小数、搜索声明、tool_choice、系统块与断点、
     * 没原始块的按文本+tool_use 拼、工具循环里的原始块原样回放（签名、密文、小数、null 字段）、转义与 emoji
     */
    @Test
    void 请求侧_请求体整串金标准() {
        events = PLAIN;
        ToolCallingManager tcm = mock(ToolCallingManager.class);
        when(tcm.resolveToolDefinitions(any())).thenReturn(List.of(ToolDefinition.builder()
                .name("get_price").description("查价格 <b>&\"引号\"")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"qty\":{\"type\":\"number\",\"minimum\":0.010}},"
                        + "\"required\":[\"qty\"],\"additionalProperties\":false}").build()));
        AnthropicChatModel m = new AnthropicChatModel("sk-ant", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "claude-test", 0.30, "high", tcm, true);
        String blocks = """
                [{"type":"thinking","thinking":"先查\\n再说","signature":"sig/+="},
                 {"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{"query":"btc"}},
                 {"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[
                     {"type":"web_search_result","url":"https://a.com/x?q=1&b=2","title":"A","encrypted_content":"enc==","page_age":null}]},
                 {"type":"text","text":"答","citations":[{"type":"web_search_result_location","url":"https://a.com/x","start_index":12,"score":0.50}]},
                 {"type":"tool_use","id":"toolu_1","name":"get_price","input":{"qty":0.010,"n":3}}]""";
        AssistantMessage plain = AssistantMessage.builder().content("旧答\n第二行").toolCalls(List.of(
                new AssistantMessage.ToolCall("toolu_0", "function", "get_price", "{\"qty\":1.50}"))).build();
        AssistantMessage inLoop = AssistantMessage.builder().content("答").toolCalls(List.of(
                        new AssistantMessage.ToolCall("toolu_1", "function", "get_price", "{\"qty\":0.010,\"n\":3}")))
                .properties(Map.of(AnthropicChatModel.BLOCKS_KEY, blocks)).build();
        ToolResponseMessage result0 = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("toolu_0", "get_price", "{\"price\":60000.10}"))).build();
        ToolResponseMessage result1 = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("toolu_1", "get_price", "ERROR: 超时\t重试"))).build();

        m.call(new Prompt(List.of(new SystemMessage("你是助手"), new UserMessage("问题 📈 \"引号\" \\ /"),
                plain, result0, new UserMessage("再问"), new SystemMessage("【摘要】第1段"), inLoop, result1),
                ToolChoice.apply(allowWebSearch(m), "get_price")));

        assertThat(lastRequestBody).isEqualTo("{\"model\":\"claude-test\",\"max_tokens\":64000,\"stream\":true,\"temperature\":0.3,\"thinking\":{\"type\":\"adaptive\"},"
                + "\"output_config\":{\"effort\":\"high\"},\"tools\":[{\"name\":\"get_price\",\"description\":\"查价格 <b>&\\\"引号\\\"\","
                + "\"input_schema\":{\"type\":\"object\",\"properties\":{\"qty\":{\"type\":\"number\",\"minimum\":0.010}},\"required\":[\"qty\"],"
                + "\"additionalProperties\":false}},{\"type\":\"web_search_20250305\",\"name\":\"web_search\"}],\"tool_choice\":{\"type\":\"tool\","
                + "\"name\":\"get_price\"},\"system\":[{\"type\":\"text\",\"text\":\"你是助手\",\"cache_control\":{\"type\":\"ephemeral\","
                + "\"ttl\":\"1h\"}},{\"type\":\"text\",\"text\":\"【摘要】第1段\"}],\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\","
                + "\"text\":\"问题 📈 \\\"引号\\\" \\\\ /\"}]},{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"旧答\\n第二行\"},"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_0\",\"name\":\"get_price\",\"input\":{\"qty\":1.50}}]},{\"role\":\"user\","
                + "\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_0\",\"content\":\"{\\\"price\\\":60000.10}\"},"
                + "{\"type\":\"text\",\"text\":\"再问\"}]},{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"先查\\n再说\","
                + "\"signature\":\"sig/+=\"},{\"type\":\"server_tool_use\",\"id\":\"srvtoolu_1\",\"name\":\"web_search\",\"input\":{\"query\":\"btc\"}},"
                + "{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"srvtoolu_1\",\"content\":[{\"type\":\"web_search_result\","
                + "\"url\":\"https://a.com/x?q=1&b=2\",\"title\":\"A\",\"encrypted_content\":\"enc==\"}]},{\"type\":\"text\","
                + "\"text\":\"答\",\"citations\":[{\"type\":\"web_search_result_location\",\"url\":\"https://a.com/x\",\"start_index\":12,"
                + "\"score\":0.50}]},{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_price\",\"input\":{\"qty\":0.010,"
                + "\"n\":3}}]},{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_1\",\"content\":\"ERROR: 超时\\t重试\","
                + "\"cache_control\":{\"type\":\"ephemeral\"}}]}]}");
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
        assertThat(MAPPER.readTree(assistant.getToolCalls().getFirst().arguments()).path("symbol").asString(null)).isEqualTo("BTC");
        assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
        // 原始块挂在消息 metadata 上，阻塞路径的帧合并不能把它丢了
        assertThat(assistant.getMetadata()).containsKey(AnthropicChatModel.BLOCKS_KEY);

        // 回传工具结果：assistant 消息按原始块回放，思考块与 signature 原样、空 text 块不回放
        events = PLAIN;
        ToolResponseMessage toolResult = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("toolu_9", "get_price", "60000"))).build();
        m.call(new Prompt(List.of(new UserMessage("BTC 多少钱"), assistant, toolResult)));

        JsonNode messages = body().get("messages");
        JsonNode replayed = messages.get(1).get("content");
        assertThat(replayed).extracting(b -> b.path("type").asString(null)).containsExactly("thinking", "tool_use");
        assertThat(replayed.get(0).path("signature").asString(null)).isEqualTo("sig-1");
        assertThat(replayed.get(0).path("thinking").asString(null)).isEqualTo("先查价");
        assertThat(replayed.get(1).get("input").path("symbol").asString(null)).isEqualTo("BTC");
        assertThat(messages.get(2).get("content").get(0).path("type").asString(null))
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
        JsonNode ended = body().get("messages").get(1).get("content");
        assertThat(ended).extracting(b -> b.path("type").asString(null)).containsExactly("text", "tool_use");
        assertThat(ended.get(0).has("citations")).isFalse();

        // 工具循环里：后面紧跟工具回执
        ToolResponseMessage toolResult = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("toolu_1", "get_price", "60000"))).build();
        m.call(new Prompt(List.of(new UserMessage("q"), assistant, toolResult), allowWebSearch(m)));
        JsonNode inLoop = body().get("messages").get(1).get("content");
        assertThat(inLoop).extracting(b -> b.path("type").asString(null))
                .containsExactly("thinking", "server_tool_use", "web_search_tool_result", "text", "tool_use");
        assertThat(inLoop.get(0).path("signature").asString(null)).isEqualTo("sig-1");
        assertThat(inLoop.get(2).get("content").get(0).path("encrypted_content").asString(null))
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
        JsonNode blocks = MAPPER.readTree((String) last.getResult().getOutput().getMetadata().get(AnthropicChatModel.BLOCKS_KEY));
        assertThat(blocks).extracting(b -> b.path("type").asString(null))
                .containsExactly("server_tool_use", "web_search_tool_result", "text");
        assertThat(blocks.get(0).get("input").path("query").asString(null)).isEqualTo("BTC news");
        assertThat(blocks.get(2).get("citations")).hasSize(1);
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
