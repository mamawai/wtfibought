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
 * Gemini generateContent 协议的形状钉子：路径/头/thinkingConfig/搜索声明/toolConfig 映射、同角色合并、
 * functionCall 无 id 时的合成与回传、parts 原样回放（thoughtSignature）、chunk→帧（文本、工具、grounding）、错误归类。
 * 手法同 ResponsesChatModelTest：JDK HttpServer 伪造 SSE 服务端。
 */
class GeminiChatModelTest {

    private static HttpServer server;
    private static volatile String[] chunks = new String[0];
    private static volatile int httpStatus = 0;
    private static volatile String httpBody = "";
    private static volatile String lastRequestBody = "";
    private static volatile String lastRequestUri = "";
    private static volatile Map<String, List<String>> lastHeaders = Map.of();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1beta/models/", exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastRequestUri = exchange.getRequestURI().toString();
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
                for (String chunk : chunks) {
                    String line = chunk.replaceAll("\\s*\\R\\s*", "");
                    os.write(("data: " + line + "\n\n").getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            }
        });
        // 无尾斜杠的 /v1beta/models 是清单接口；HttpServer 按最长前缀匹配，流式请求仍落到上面那个
        server.createContext("/v1beta/models", exchange -> {
            lastRequestUri = exchange.getRequestURI().toString();
            lastHeaders = exchange.getRequestHeaders();
            byte[] bytes = "{\"models\":[{\"name\":\"models/gemini-b\"},{\"name\":\"models/gemini-a\"}]}"
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
    void 模型清单_GET_v1beta_models_去掉models前缀() {
        List<String> names = GeminiChatModel.listModels(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta", "AIza-test");
        assertThat(names).containsExactly("gemini-a", "gemini-b");
        assertThat(lastRequestUri).isEqualTo("/v1beta/models?pageSize=1000");
        assertThat(lastHeaders.get("X-goog-api-key")).containsExactly("AIza-test");
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static GeminiChatModel model(String effort, boolean webSearch, ToolCallingManager tcm) {
        httpStatus = 0;
        // 用户手滑带上的 /v1beta 要被剥掉，否则拼成 /v1beta/v1beta/models
        return new GeminiChatModel("AIza-test", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/",
                "gemini-test", null, effort, tcm, webSearch);
    }

    private static GeminiChatModel model() {
        return model(null, false, mock(ToolCallingManager.class));
    }

    private static ChatOptions allowWebSearch(GeminiChatModel m) {
        return ((ToolCallingChatOptions) m.getOptions()).mutate()
                .toolContext(Map.of(SseChatModel.WEB_SEARCH_KEY, true)).build();
    }

    private static ToolCallingManager oneTool() {
        ToolCallingManager tcm = mock(ToolCallingManager.class);
        when(tcm.resolveToolDefinitions(any())).thenReturn(List.of(ToolDefinition.builder()
                .name("get_price").description("查价格")
                .inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}").build()));
        return tcm;
    }

    private static JSONObject body() {
        return JSON.parseObject(lastRequestBody);
    }

    private static final String[] PLAIN = new String[]{
            """
            {"candidates":[{"content":{"role":"model","parts":[{"text":"答"}]}}],"responseId":"r1"}""",
            """
            {"candidates":[{"content":{"role":"model","parts":[{"text":"案"}]},"finishReason":"STOP"}],
             "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":4,"totalTokenCount":14},"responseId":"r1"}"""
    };

    // ========== 请求侧 ==========

    @Test
    void 请求侧_路径_头_系统指令_档位_搜索声明() {
        chunks = PLAIN;
        GeminiChatModel m = model("high", true, mock(ToolCallingManager.class));
        m.call(new Prompt(List.of(new SystemMessage("你是助手"), new UserMessage("新闻")), allowWebSearch(m)));

        assertThat(lastRequestUri).isEqualTo("/v1beta/models/gemini-test:streamGenerateContent?alt=sse");
        assertThat(lastHeaders.get("X-goog-api-key")).containsExactly("AIza-test");
        JSONObject body = body();
        assertThat(body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
                .isEqualTo("你是助手");
        assertThat(body.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getString("thinkingLevel"))
                .isEqualTo("HIGH");
        assertThat(body.getJSONArray("tools")).hasSize(1);
        assertThat(body.getJSONArray("tools").getJSONObject(0).containsKey("google_search")).isTrue();
        assertThat(body.getJSONArray("contents").getJSONObject(0).getString("role")).isEqualTo("user");
    }

    @Test
    void 请求侧_档位none为预算0_留空不传() {
        chunks = PLAIN;
        model("none", false, mock(ToolCallingManager.class)).call(new Prompt("q"));
        assertThat(body().getJSONObject("generationConfig").getJSONObject("thinkingConfig").getIntValue("thinkingBudget"))
                .isZero();

        model().call(new Prompt("q"));
        assertThat(body().containsKey("generationConfig")).isFalse();
    }

    @Test
    void 请求侧_function声明与toolConfig映射_未捎许可不声明搜索() {
        chunks = PLAIN;
        GeminiChatModel m = model(null, true, oneTool());
        m.call(new Prompt("q", ToolChoice.apply(m.getOptions(), ToolChoice.REQUIRED)));
        JSONObject body = body();
        JSONArray tools = body.getJSONArray("tools");
        assertThat(tools).hasSize(1);   // 端点开了搜索但本次没捎许可
        JSONObject decl = tools.getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0);
        assertThat(decl.getString("name")).isEqualTo("get_price");
        assertThat(decl.getJSONObject("parametersJsonSchema").getBooleanValue("additionalProperties")).isFalse();
        assertThat(body.getJSONObject("toolConfig").getJSONObject("functionCallingConfig").getString("mode")).isEqualTo("ANY");

        m.call(new Prompt("q", ToolChoice.apply(m.getOptions(), "get_price")));
        JSONObject config = body().getJSONObject("toolConfig").getJSONObject("functionCallingConfig");
        assertThat(config.getString("mode")).isEqualTo("ANY");
        assertThat(config.getJSONArray("allowedFunctionNames")).containsExactly("get_price");

        m.call(new Prompt("q", allowWebSearch(m)));
        assertThat(body().getJSONArray("tools")).hasSize(2);
        assertThat(body().containsKey("toolConfig")).isFalse();
    }

    @Test
    void 请求侧_同角色合并_合成id不回传_真实id回传() {
        chunks = PLAIN;
        AssistantMessage synthetic = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall(GeminiChatModel.SYNTHETIC_ID_PREFIX + "1_get_price", "function",
                        "get_price", "{\"symbol\":\"BTC\"}"))).build();
        ToolResponseMessage syntheticResult = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse(GeminiChatModel.SYNTHETIC_ID_PREFIX + "1_get_price",
                        "get_price", "{\"price\":60000}"))).build();
        model().call(new Prompt(List.of(new UserMessage("问题"), new UserMessage("补充"),
                synthetic, syntheticResult, new UserMessage("专家结论"))));

        JSONArray contents = body().getJSONArray("contents");
        assertThat(contents).hasSize(3);
        assertThat(contents.getJSONObject(0).getJSONArray("parts")).extracting(p -> ((JSONObject) p).getString("text"))
                .containsExactly("问题", "补充");
        JSONObject call = contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getJSONObject("functionCall");
        assertThat(contents.getJSONObject(1).getString("role")).isEqualTo("model");
        assertThat(call.getString("name")).isEqualTo("get_price");
        assertThat(call.getJSONObject("args").getString("symbol")).isEqualTo("BTC");
        assertThat(call.containsKey("id")).isFalse();
        JSONArray userParts = contents.getJSONObject(2).getJSONArray("parts");
        assertThat(contents.getJSONObject(2).getString("role")).isEqualTo("user");
        JSONObject response = userParts.getJSONObject(0).getJSONObject("functionResponse");
        assertThat(response.getString("name")).isEqualTo("get_price");
        assertThat(response.containsKey("id")).isFalse();
        // 回执本身是 JSON 对象就直接用
        assertThat(response.getJSONObject("response").getIntValue("price")).isEqualTo(60000);
        assertThat(userParts.getJSONObject(1).getString("text")).isEqualTo("专家结论");

        // 上游给了 id 的原样带回；文本回执包一层
        AssistantMessage real = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("fc-abc", "function", "get_price", "{}"))).build();
        ToolResponseMessage realResult = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("fc-abc", "get_price", "六万"))).build();
        model().call(new Prompt(List.of(new UserMessage("问题"), real, realResult)));
        contents = body().getJSONArray("contents");
        assertThat(contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getJSONObject("functionCall")
                .getString("id")).isEqualTo("fc-abc");
        JSONObject wrapped = contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse");
        assertThat(wrapped.getString("id")).isEqualTo("fc-abc");
        assertThat(wrapped.getJSONObject("response").getString("output")).isEqualTo("六万");
    }

    // ========== 阻塞路径 ==========

    @Test
    void 阻塞路径_文本chunk拼接并带usage() {
        chunks = PLAIN;
        ChatResponse response = model().call(new Prompt("问题"));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("答案");
        assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
        assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(10);
        assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(14);
        assertThat(response.getMetadata().getId()).isEqualTo("r1");
    }

    @Test
    void 阻塞路径_无id的functionCall合成id_原始parts带签名回放() {
        chunks = new String[]{
                """
                {"candidates":[{"content":{"role":"model","parts":[
                    {"text":"先查","thoughtSignature":"sig-x"},
                    {"functionCall":{"name":"get_price","args":{"symbol":"BTC"}},"thoughtSignature":"sig-y"}]},
                  "finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":8,"candidatesTokenCount":6,"totalTokenCount":14}}"""
        };
        GeminiChatModel m = model(null, false, oneTool());
        ChatResponse response = m.call(new Prompt("BTC 多少钱"));

        AssistantMessage assistant = response.getResult().getOutput();
        assertThat(assistant.getToolCalls()).hasSize(1);
        assertThat(assistant.getToolCalls().getFirst().id()).startsWith(GeminiChatModel.SYNTHETIC_ID_PREFIX);
        assertThat(assistant.getToolCalls().getFirst().name()).isEqualTo("get_price");
        assertThat(JSON.parseObject(assistant.getToolCalls().getFirst().arguments()).getString("symbol")).isEqualTo("BTC");
        assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
        assertThat(assistant.getText()).isEqualTo("先查");

        chunks = PLAIN;
        ToolResponseMessage toolResult = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse(assistant.getToolCalls().getFirst().id(), "get_price", "60000"))).build();
        m.call(new Prompt(List.of(new UserMessage("BTC 多少钱"), assistant, toolResult)));

        JSONArray parts = body().getJSONArray("contents").getJSONObject(1).getJSONArray("parts");
        assertThat(parts).hasSize(2);
        assertThat(parts.getJSONObject(0).getString("thoughtSignature")).isEqualTo("sig-x");
        assertThat(parts.getJSONObject(1).getString("thoughtSignature")).isEqualTo("sig-y");
        assertThat(parts.getJSONObject(1).getJSONObject("functionCall").containsKey("id")).isFalse();
        JSONObject fr = body().getJSONArray("contents").getJSONObject(2).getJSONArray("parts").getJSONObject(0)
                .getJSONObject("functionResponse");
        assertThat(fr.getString("name")).isEqualTo("get_price");
        assertThat(fr.containsKey("id")).isFalse();

        // 已结束的轮次（后面是新提问）不回放原始 parts：签名不带，按文本 + functionCall 拼
        m.call(new Prompt(List.of(new UserMessage("BTC 多少钱"), assistant, toolResult,
                AssistantMessage.builder().content("六万").properties(Map.of(GeminiChatModel.PARTS_KEY,
                        "[{\"text\":\"六万\",\"thoughtSignature\":\"sig-z\"}]")).build(),
                new UserMessage("再问"))));
        JSONObject ended = body().getJSONArray("contents").getJSONObject(3);
        assertThat(ended.getString("role")).isEqualTo("model");
        assertThat(ended.getJSONArray("parts")).hasSize(1);
        assertThat(ended.getJSONArray("parts").getJSONObject(0).getString("text")).isEqualTo("六万");
        assertThat(ended.getJSONArray("parts").getJSONObject(0).containsKey("thoughtSignature")).isFalse();
    }

    // ========== 搜索过程帧 ==========

    @Test
    void 流式_grounding变成searched事件_思考part不进正文() {
        chunks = new String[]{
                """
                {"candidates":[{"content":{"role":"model","parts":[{"text":"想想","thought":true},{"text":"据报道"}]}}]}""",
                """
                {"candidates":[{"content":{"role":"model","parts":[{"text":"，BTC 上涨"}]},"finishReason":"STOP",
                  "groundingMetadata":{"webSearchQueries":["BTC news","BTC price"],
                    "groundingChunks":[{"web":{"uri":"https://a.com/1","title":"A1"}},{"web":{"uri":"https://b.com/2","title":"B2"}}]}}],
                 "usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":9,"totalTokenCount":14}}"""
        };
        GeminiChatModel m = model(null, true, mock(ToolCallingManager.class));
        List<ChatResponse> frames = m.stream(new Prompt("BTC 新闻", allowWebSearch(m))).collectList().block();

        List<SearchEvent> search = frames.stream()
                .filter(f -> f.getMetadata().<String>get(SearchEvent.KEY) != null)
                .map(f -> SearchEvent.parse(f.getMetadata().get(SearchEvent.KEY))).toList();
        assertThat(search).hasSize(1);
        assertThat(search.getFirst().phase()).isEqualTo(SearchEvent.SEARCHED);
        assertThat(search.getFirst().query()).isEqualTo("BTC news · BTC price");
        assertThat(search.getFirst().sources()).extracting(SearchEvent.Source::url)
                .containsExactly("https://a.com/1", "https://b.com/2");
        assertThat(frames.stream().map(f -> f.getResult().getOutput().getText()).reduce("", String::concat))
                .isEqualTo("据报道，BTC 上涨");
        ChatResponse last = frames.getLast();
        assertThat(last.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
        // 相邻纯文本 part 合并、思考 part 不存
        JSONArray parts = JSON.parseArray((String) last.getResult().getOutput().getMetadata().get(GeminiChatModel.PARTS_KEY));
        assertThat(parts).hasSize(1);
        assertThat(parts.getJSONObject(0).getString("text")).isEqualTo("据报道，BTC 上涨");
    }

    // ========== 错误归类 ==========

    @Test
    void 拒收400判非瞬时_安全终止与截断判非瞬时_断流判瞬时() {
        GeminiChatModel m = model();
        httpStatus = 400;
        httpBody = "{\"error\":{\"code\":400,\"message\":\"Multiple tools are supported only when they are all search tools.\",\"status\":\"INVALID_ARGUMENT\"}}";
        assertThatThrownBy(() -> m.call(new Prompt("q")))
                .isInstanceOf(NonTransientAiException.class)
                .hasMessageContaining("400").hasMessageContaining("search tools");

        httpStatus = 0;
        chunks = new String[]{
                """
                {"candidates":[{"content":{"role":"model","parts":[]},"finishReason":"SAFETY"}]}"""
        };
        assertThatThrownBy(() -> model().call(new Prompt("q")))
                .isInstanceOf(NonTransientAiException.class)
                .hasMessageContaining("SAFETY");

        // MAX_TOKENS 带着半截正文来，按 STOP 收就是半截结论落库
        chunks = new String[]{
                """
                {"candidates":[{"content":{"role":"model","parts":[{"text":"半截"}]},"finishReason":"MAX_TOKENS"}]}"""
        };
        assertThatThrownBy(() -> model().call(new Prompt("q")))
                .isInstanceOf(NonTransientAiException.class)
                .hasMessageContaining("MAX_TOKENS");

        chunks = new String[]{PLAIN[0]};
        assertThatThrownBy(() -> model().call(new Prompt("q")))
                .isInstanceOf(TransientAiException.class)
                .hasMessageContaining("断流");
    }
}
