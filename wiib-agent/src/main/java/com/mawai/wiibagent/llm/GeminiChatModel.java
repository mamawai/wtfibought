package com.mawai.wiibagent.llm;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Gemini generateContent 协议（/v1beta/models/{model}:streamGenerateContent?alt=sse）。
 * <p>
 * 思考档位：留空不传；none → {@code thinkingConfig.thinkingBudget=0}；其余 → {@code thinkingConfig.thinkingLevel}（大写枚举）。
 * 服务端搜索声明 {@code {google_search:{}}}；搜索只有完成态（groundingMetadata），没有"开始"信号。
 * <p>
 * parts 原样回传：本轮 model 的原始 parts 数组挂在收尾帧消息的 {@link #PARTS_KEY} 上，只有还在工具循环里的那条
 * （后面紧跟工具回执）原样回放（thoughtSignature、functionCall 自带的 id 都在里面），其余按文本 + functionCall 拼
 * （见 {@link #inToolLoop}）。
 * functionCall 不带 id 时自己合成一个（{@link #SYNTHETIC_ID_PREFIX} + 本次订阅的随机段 + 序号 + 名字，跨轮不重），
 * 回传 functionResponse 认出合成 id 就不带 id，按名字配。
 * 同角色连续消息合成一条多 part 消息（多轮要求 user/model 交替）。
 */
public class GeminiChatModel extends SseChatModel<GeminiChatModel.State> {

    /** 消息 metadata 键：本轮 model 原始 parts 数组的 JSON 串 */
    public static final String PARTS_KEY = "wiib_gemini_parts";
    static final String SYNTHETIC_ID_PREFIX = "wiib_fc_";

    private static final Duration LIST_TIMEOUT = Duration.ofSeconds(30);

    public GeminiChatModel(String apiKey, String baseUrl, String model, Double temperature,
                           String reasoningEffort, ToolCallingManager toolCallingManager, boolean webSearch) {
        super("Gemini", OpenAiBaseUrl.strip(baseUrl, "/v1beta"), Map.of("x-goog-api-key", apiKey),
                model, temperature, reasoningEffort, toolCallingManager, webSearch);
    }

    /** 模型清单：GET /v1beta/models → models[].name（形如 models/gemini-xxx，去掉前缀）；pageSize 给上限一页拿完 */
    public static List<String> listModels(String baseUrl, String apiKey) {
        String body = WebClient.create(OpenAiBaseUrl.strip(baseUrl, "/v1beta")).get()
                .uri("/v1beta/models?pageSize=1000")
                .header("x-goog-api-key", apiKey)
                .retrieve().bodyToMono(String.class).block(LIST_TIMEOUT);
        JSONArray models = JSON.parseObject(body).getJSONArray("models");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < models.size(); i++) {
            String name = models.getJSONObject(i).getString("name");
            names.add(name.startsWith("models/") ? name.substring("models/".length()) : name);
        }
        return names.stream().sorted().toList();
    }

    @Override
    protected State newState() {
        return new State();
    }

    @Override
    protected String requestUri(Prompt prompt) {
        return "/v1beta/models/" + effectiveModel(prompt) + ":streamGenerateContent?alt=sse";
    }

    // ========== 请求构建 ==========

    @Override
    protected JSONObject requestBody(Prompt prompt) {
        JSONObject body = new JSONObject();
        ChatOptions options = prompt.getOptions();

        JSONObject generation = new JSONObject();
        Double temp = effectiveTemperature(prompt);
        if (temp != null) {
            generation.put("temperature", temp);
        }
        if ("none".equals(reasoningEffort)) {
            generation.put("thinkingConfig", new JSONObject().fluentPut("thinkingBudget", 0));
        } else if (reasoningEffort != null) {
            generation.put("thinkingConfig", new JSONObject().fluentPut("thinkingLevel", reasoningEffort.toUpperCase()));
        }
        if (!generation.isEmpty()) {
            body.put("generationConfig", generation);
        }

        StringBuilder system = new StringBuilder();
        JSONArray contents = new JSONArray();
        List<Message> history = prompt.getInstructions();
        for (int i = 0; i < history.size(); i++) {
            Message message = history.get(i);
            switch (message.getMessageType()) {
                case SYSTEM -> {
                    if (!system.isEmpty()) {
                        system.append("\n\n");
                    }
                    system.append(message.getText());
                }
                case USER -> append(contents, "user", List.of(textPart(message.getText())));
                case ASSISTANT -> append(contents, "model",
                        assistantParts((AssistantMessage) message, inToolLoop(history, i)));
                case TOOL -> {
                    List<JSONObject> parts = new ArrayList<>();
                    for (ToolResponseMessage.ToolResponse tr : ((ToolResponseMessage) message).getResponses()) {
                        JSONObject response = new JSONObject().fluentPut("name", tr.name())
                                .fluentPut("response", responseObject(tr.responseData()));
                        if (tr.id() != null && !tr.id().startsWith(SYNTHETIC_ID_PREFIX)) {
                            response.put("id", tr.id());
                        }
                        parts.add(new JSONObject().fluentPut("functionResponse", response));
                    }
                    append(contents, "user", parts);
                }
            }
        }
        if (!system.isEmpty()) {
            body.put("systemInstruction", new JSONObject().fluentPut("parts",
                    new JSONArray().fluentAdd(textPart(system.toString()))));
        }
        body.put("contents", contents);

        List<String> toolNames = new ArrayList<>();
        if (options instanceof ToolCallingChatOptions toolOptions) {
            JSONArray tools = new JSONArray();
            JSONArray declarations = new JSONArray();
            for (ToolDefinition def : toolCallingManager.resolveToolDefinitions(toolOptions)) {
                // parametersJsonSchema 收 JSON Schema 原文；parameters 是 OpenAPI 子集，additionalProperties 这类字段会被拒
                declarations.add(new JSONObject()
                        .fluentPut("name", def.name())
                        .fluentPut("description", def.description())
                        .fluentPut("parametersJsonSchema", JSON.parseObject(def.inputSchema())));
                toolNames.add(def.name());
            }
            if (!declarations.isEmpty()) {
                tools.add(new JSONObject().fluentPut("functionDeclarations", declarations));
            }
            if (searchAllowed(toolOptions)) {
                tools.add(new JSONObject().fluentPut("google_search", new JSONObject()));
                toolNames.add("google_search");
            }
            if (!tools.isEmpty()) {
                body.put("tools", tools);
                JSONObject config = functionCallingConfig(ToolChoice.of(toolOptions));
                if (config != null) {
                    body.put("toolConfig", new JSONObject().fluentPut("functionCallingConfig", config));
                }
            }
        }
        logRequest(effectiveModel(prompt), body.getJSONObject("toolConfig"), toolNames);
        return body;
    }

    /** required → ANY；具体工具名 → ANY + allowedFunctionNames；auto 不传 */
    private static JSONObject functionCallingConfig(String choice) {
        if (ToolChoice.AUTO.equals(choice)) {
            return null;
        }
        JSONObject config = new JSONObject().fluentPut("mode", "ANY");
        if (!ToolChoice.REQUIRED.equals(choice)) {
            config.put("allowedFunctionNames", new JSONArray().fluentAdd(choice));
        }
        return config;
    }

    private static void append(JSONArray contents, String role, List<JSONObject> parts) {
        if (parts.isEmpty()) {
            return;
        }
        JSONObject last = contents.isEmpty() ? null : contents.getJSONObject(contents.size() - 1);
        if (last != null && role.equals(last.getString("role"))) {
            last.getJSONArray("parts").addAll(parts);
            return;
        }
        contents.add(new JSONObject().fluentPut("role", role).fluentPut("parts", new JSONArray(parts)));
    }

    private static JSONObject textPart(String text) {
        return new JSONObject().fluentPut("text", text == null ? "" : text);
    }

    /** 工具循环里的那条带 {@link #PARTS_KEY} 就原样回放；其余按文本 + functionCall 拼（合成 id 不回传） */
    private static List<JSONObject> assistantParts(AssistantMessage assistant, boolean replayRaw) {
        List<JSONObject> parts = new ArrayList<>();
        if (replayRaw && assistant.getMetadata().get(PARTS_KEY) instanceof String raw) {
            JSONArray stored = JSON.parseArray(raw);
            for (int i = 0; i < stored.size(); i++) {
                parts.add(stored.getJSONObject(i));
            }
            return parts;
        }
        if (assistant.getText() != null && !assistant.getText().isBlank()) {
            parts.add(textPart(assistant.getText()));
        }
        for (AssistantMessage.ToolCall tc : assistant.getToolCalls()) {
            JSONObject call = new JSONObject().fluentPut("name", tc.name())
                    .fluentPut("args", tc.arguments() == null || tc.arguments().isBlank()
                            ? new JSONObject() : JSON.parseObject(tc.arguments()));
            if (tc.id() != null && !tc.id().startsWith(SYNTHETIC_ID_PREFIX)) {
                call.put("id", tc.id());
            }
            parts.add(new JSONObject().fluentPut("functionCall", call));
        }
        return parts;
    }

    /** functionResponse.response 必须是 JSON 对象：工具回执本身是对象就直接用，否则包一层 */
    private static JSONObject responseObject(String responseData) {
        String data = responseData == null ? "" : responseData.trim();
        if (data.startsWith("{")) {
            try {
                return JSON.parseObject(data);
            } catch (RuntimeException ignored) {
                // 不是合法 JSON 对象，按文本包
            }
        }
        return new JSONObject().fluentPut("output", data);
    }

    // ========== 响应解析 ==========

    /** 一次订阅里累计的原始 parts（相邻纯文本 part 合并），收尾时挂到消息 metadata 原样回传 */
    protected static class State extends StreamState {
        final JSONArray parts = new JSONArray();
        /** 合成 id 的随机段，一次订阅一个：同名函数跨轮调用的 id 不能撞 */
        final String idNonce = Long.toHexString(ThreadLocalRandom.current().nextLong());
        int callSeq;
        JSONObject usage;
    }

    @Override
    protected Flux<ChatResponse> toFrames(JSONObject chunk, State state) {
        JSONObject error = chunk.getJSONObject("error");
        if (error != null) {
            String message = "Gemini 流式错误: " + error.getInteger("code") + " " + error.getString("message");
            Integer code = error.getInteger("code");
            return Flux.error(code != null && (code == 429 || code >= 500)
                    ? new TransientAiException(message) : new NonTransientAiException(message));
        }
        if (chunk.getJSONObject("usageMetadata") != null) {
            state.usage = chunk.getJSONObject("usageMetadata");
        }
        JSONArray candidates = chunk.getJSONArray("candidates");
        if (candidates == null || candidates.isEmpty()) {
            JSONObject feedback = chunk.getJSONObject("promptFeedback");
            if (feedback != null && feedback.getString("blockReason") != null) {
                return Flux.error(new NonTransientAiException("Gemini 拒绝生成: " + feedback.getString("blockReason")));
            }
            return Flux.empty();
        }
        JSONObject candidate = candidates.getJSONObject(0);
        List<ChatResponse> frames = new ArrayList<>();
        JSONObject content = candidate.getJSONObject("content");
        JSONArray parts = content == null ? null : content.getJSONArray("parts");
        if (parts != null) {
            for (int i = 0; i < parts.size(); i++) {
                JSONObject part = parts.getJSONObject(i);
                // 思考摘要 part 不是正文，也不必回传
                if (Boolean.TRUE.equals(part.getBoolean("thought"))) {
                    continue;
                }
                if (part.getJSONObject("functionCall") != null) {
                    JSONObject call = part.getJSONObject("functionCall");
                    String id = call.getString("id") != null ? call.getString("id")
                            : SYNTHETIC_ID_PREFIX + state.idNonce + "_" + (++state.callSeq) + "_" + call.getString("name");
                    JSONObject args = call.getJSONObject("args");
                    state.sawToolCall = true;
                    frames.add(toolCallFrame(new AssistantMessage.ToolCall(id, "function", call.getString("name"),
                            (args == null ? new JSONObject() : args).toJSONString())));
                } else if (part.getString("text") != null && !part.getString("text").isEmpty()) {
                    state.sawText = true;
                    frames.add(textFrame(part.getString("text")));
                }
                storePart(state, part);
            }
        }
        JSONObject grounding = candidate.getJSONObject("groundingMetadata");
        if (grounding != null) {
            SearchEvent event = groundingEvent(grounding);
            if (event != null) {
                frames.add(searchFrame(event));
            }
        }
        String finishReason = candidate.getString("finishReason");
        if (finishReason != null) {
            // 只有 STOP 是完整回答：MAX_TOKENS 是截断、SAFETY 等是拦下，半截不当结论
            if (!"STOP".equals(finishReason)) {
                return Flux.error(new NonTransientAiException("Gemini 生成终止: " + finishReason));
            }
            frames.add(finalFrame(state.sawToolCall, usageMetadata(state, chunk.getString("responseId")),
                    Map.of(PARTS_KEY, state.parts.toJSONString())));
        }
        return Flux.fromIterable(frames);
    }

    /** 相邻的纯文本 part 并成一个，回放时不至于几十个碎片；带签名/functionCall 的原样单存 */
    private static void storePart(State state, JSONObject part) {
        boolean plainText = part.size() == 1 && part.getString("text") != null;
        JSONObject last = state.parts.isEmpty() ? null : state.parts.getJSONObject(state.parts.size() - 1);
        if (plainText && last != null && last.size() == 1 && last.getString("text") != null) {
            last.put("text", last.getString("text") + part.getString("text"));
            return;
        }
        state.parts.add(part);
    }

    /** groundingMetadata → 搜完事件：搜索词与命中站点一起给（协议不区分每个词各命中了谁） */
    private static SearchEvent groundingEvent(JSONObject grounding) {
        JSONArray queries = grounding.getJSONArray("webSearchQueries");
        JSONArray chunks = grounding.getJSONArray("groundingChunks");
        if ((queries == null || queries.isEmpty()) && (chunks == null || chunks.isEmpty())) {
            return null;
        }
        List<SearchEvent.Source> sources = new ArrayList<>();
        if (chunks != null) {
            for (int i = 0; i < chunks.size(); i++) {
                JSONObject web = chunks.getJSONObject(i).getJSONObject("web");
                if (web != null) {
                    sources.add(new SearchEvent.Source(web.getString("uri"), web.getString("title")));
                }
            }
        }
        String query = queries == null || queries.isEmpty() ? null
                : String.join(" · ", queries.toJavaList(String.class));
        return SearchEvent.searched(query, sources);
    }

    private ChatResponseMetadata usageMetadata(State state, String responseId) {
        ChatResponseMetadata.Builder metadata = metadata().id(responseId);
        if (state.usage != null) {
            metadata.usage(new DefaultUsage(
                    state.usage.getInteger("promptTokenCount"),
                    state.usage.getInteger("candidatesTokenCount"),
                    state.usage.getInteger("totalTokenCount")));
        }
        return metadata.build();
    }
}
