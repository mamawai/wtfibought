package com.mawai.wiibagent.llm;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

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
        JsonNode models = MAPPER.readTree(body).get("models");
        List<String> names = new ArrayList<>();
        for (JsonNode item : models) {
            String name = item.path("name").asString(null);
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
    protected ObjectNode requestBody(Prompt prompt) {
        ObjectNode body = MAPPER.createObjectNode();
        ChatOptions options = prompt.getOptions();

        ObjectNode generation = MAPPER.createObjectNode();
        Double temp = effectiveTemperature(prompt);
        if (temp != null) {
            generation.put("temperature", temp);
        }
        if ("none".equals(reasoningEffort)) {
            generation.set("thinkingConfig", MAPPER.createObjectNode().put("thinkingBudget", 0));
        } else if (reasoningEffort != null) {
            generation.set("thinkingConfig", MAPPER.createObjectNode().put("thinkingLevel", reasoningEffort.toUpperCase()));
        }
        if (!generation.isEmpty()) {
            body.set("generationConfig", generation);
        }

        StringBuilder system = new StringBuilder();
        ArrayNode contents = MAPPER.createArrayNode();
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
                    List<ObjectNode> parts = new ArrayList<>();
                    for (ToolResponseMessage.ToolResponse tr : ((ToolResponseMessage) message).getResponses()) {
                        ObjectNode response = MAPPER.createObjectNode().put("name", tr.name())
                                .set("response", responseObject(tr.responseData()));
                        if (tr.id() != null && !tr.id().startsWith(SYNTHETIC_ID_PREFIX)) {
                            response.put("id", tr.id());
                        }
                        parts.add(MAPPER.createObjectNode().set("functionResponse", response));
                    }
                    append(contents, "user", parts);
                }
            }
        }
        if (!system.isEmpty()) {
            body.set("systemInstruction", MAPPER.createObjectNode().set("parts",
                    MAPPER.createArrayNode().add(textPart(system.toString()))));
        }
        body.set("contents", contents);

        List<String> toolNames = new ArrayList<>();
        if (options instanceof ToolCallingChatOptions toolOptions) {
            ArrayNode tools = MAPPER.createArrayNode();
            ArrayNode declarations = MAPPER.createArrayNode();
            for (ToolDefinition def : toolCallingManager.resolveToolDefinitions(toolOptions)) {
                // parametersJsonSchema 收 JSON Schema 原文；parameters 是 OpenAPI 子集，additionalProperties 这类字段会被拒
                declarations.add(MAPPER.createObjectNode()
                        .put("name", def.name())
                        .put("description", def.description())
                        .set("parametersJsonSchema", MAPPER.readTree(def.inputSchema())));
                toolNames.add(def.name());
            }
            if (!declarations.isEmpty()) {
                tools.add(MAPPER.createObjectNode().set("functionDeclarations", declarations));
            }
            if (searchAllowed(toolOptions)) {
                tools.add(MAPPER.createObjectNode().set("google_search", MAPPER.createObjectNode()));
                toolNames.add("google_search");
            }
            if (!tools.isEmpty()) {
                body.set("tools", tools);
                ObjectNode config = functionCallingConfig(ToolChoice.of(toolOptions));
                if (config != null) {
                    body.set("toolConfig", MAPPER.createObjectNode().set("functionCallingConfig", config));
                }
            }
        }
        logRequest(effectiveModel(prompt), body.get("toolConfig"), toolNames);
        return body;
    }

    /** required → ANY；具体工具名 → ANY + allowedFunctionNames；auto 不传 */
    private static ObjectNode functionCallingConfig(String choice) {
        if (ToolChoice.AUTO.equals(choice)) {
            return null;
        }
        ObjectNode config = MAPPER.createObjectNode().put("mode", "ANY");
        if (!ToolChoice.REQUIRED.equals(choice)) {
            config.set("allowedFunctionNames", MAPPER.createArrayNode().add(choice));
        }
        return config;
    }

    private static void append(ArrayNode contents, String role, List<ObjectNode> parts) {
        if (parts.isEmpty()) {
            return;
        }
        JsonNode last = contents.isEmpty() ? null : contents.get(contents.size() - 1);
        if (last != null && role.equals(last.path("role").asString(null))) {
            ((ArrayNode) last.get("parts")).addAll(parts);
            return;
        }
        contents.add(MAPPER.createObjectNode().put("role", role).set("parts", MAPPER.createArrayNode().addAll(parts)));
    }

    private static ObjectNode textPart(String text) {
        return MAPPER.createObjectNode().put("text", text == null ? "" : text);
    }

    /** 工具循环里的那条带 {@link #PARTS_KEY} 就原样回放；其余按文本 + functionCall 拼（合成 id 不回传） */
    private static List<ObjectNode> assistantParts(AssistantMessage assistant, boolean replayRaw) {
        List<ObjectNode> parts = new ArrayList<>();
        if (replayRaw && assistant.getMetadata().get(PARTS_KEY) instanceof String raw) {
            for (JsonNode part : MAPPER.readValue(raw, ArrayNode.class)) {
                parts.add((ObjectNode) part);
            }
            return parts;
        }
        if (assistant.getText() != null && !assistant.getText().isBlank()) {
            parts.add(textPart(assistant.getText()));
        }
        for (AssistantMessage.ToolCall tc : assistant.getToolCalls()) {
            ObjectNode call = MAPPER.createObjectNode().put("name", tc.name())
                    .set("args", tc.arguments() == null || tc.arguments().isBlank()
                            ? MAPPER.createObjectNode() : MAPPER.readTree(tc.arguments()));
            if (tc.id() != null && !tc.id().startsWith(SYNTHETIC_ID_PREFIX)) {
                call.put("id", tc.id());
            }
            parts.add(MAPPER.createObjectNode().set("functionCall", call));
        }
        return parts;
    }

    /** functionResponse.response 必须是 JSON 对象：工具回执本身是对象就直接用，否则包一层 */
    private static ObjectNode responseObject(String responseData) {
        String data = responseData == null ? "" : responseData.trim();
        if (data.startsWith("{")) {
            try {
                return MAPPER.readValue(data, ObjectNode.class);
            } catch (RuntimeException ignored) {
                // 不是合法 JSON 对象，按文本包
            }
        }
        return MAPPER.createObjectNode().put("output", data);
    }

    // ========== 响应解析 ==========

    /** 一次订阅里累计的原始 parts（相邻纯文本 part 合并），收尾时挂到消息 metadata 原样回传 */
    protected static class State extends StreamState {
        final ArrayNode parts = MAPPER.createArrayNode();
        /** 合成 id 的随机段，一次订阅一个：同名函数跨轮调用的 id 不能撞 */
        final String idNonce = Long.toHexString(ThreadLocalRandom.current().nextLong());
        int callSeq;
        JsonNode usage;
    }

    @Override
    protected Flux<ChatResponse> toFrames(JsonNode chunk, State state) {
        if (chunk.hasNonNull("error")) {
            JsonNode error = chunk.get("error");
            Integer code = error.hasNonNull("code") ? error.get("code").asInt() : null;
            String message = "Gemini 流式错误: " + code + " " + error.path("message").asString(null);
            return Flux.error(code != null && (code == 429 || code >= 500)
                    ? new TransientAiException(message) : new NonTransientAiException(message));
        }
        if (chunk.hasNonNull("usageMetadata")) {
            state.usage = chunk.get("usageMetadata");
        }
        JsonNode candidates = chunk.path("candidates");
        if (candidates.isEmpty()) {
            String blockReason = chunk.path("promptFeedback").path("blockReason").asString(null);
            if (blockReason != null) {
                return Flux.error(new NonTransientAiException("Gemini 拒绝生成: " + blockReason));
            }
            return Flux.empty();
        }
        JsonNode candidate = candidates.get(0);
        List<ChatResponse> frames = new ArrayList<>();
        for (JsonNode part : candidate.path("content").path("parts")) {
            // 思考摘要 part 不是正文，也不必回传
            if (part.path("thought").asBoolean(false)) {
                continue;
            }
            if (part.hasNonNull("functionCall")) {
                JsonNode call = part.get("functionCall");
                String name = call.path("name").asString(null);
                String id = call.path("id").asString(null) != null ? call.path("id").asString(null)
                        : SYNTHETIC_ID_PREFIX + state.idNonce + "_" + (++state.callSeq) + "_" + name;
                JsonNode args = call.hasNonNull("args") ? call.get("args") : MAPPER.createObjectNode();
                state.sawToolCall = true;
                frames.add(toolCallFrame(new AssistantMessage.ToolCall(id, "function", name,
                        MAPPER.writeValueAsString(args))));
            } else {
                String text = part.path("text").asString(null);
                if (text != null && !text.isEmpty()) {
                    state.sawText = true;
                    frames.add(textFrame(text));
                }
            }
            storePart(state, part);
        }
        if (candidate.hasNonNull("groundingMetadata")) {
            SearchEvent event = groundingEvent(candidate.get("groundingMetadata"));
            if (event != null) {
                frames.add(searchFrame(event));
            }
        }
        String finishReason = candidate.path("finishReason").asString(null);
        if (finishReason != null) {
            // 只有 STOP 是完整回答：MAX_TOKENS 是截断、SAFETY 等是拦下，半截不当结论
            if (!"STOP".equals(finishReason)) {
                return Flux.error(new NonTransientAiException("Gemini 生成终止: " + finishReason));
            }
            frames.add(finalFrame(state.sawToolCall, usageMetadata(state, chunk.path("responseId").asString(null)),
                    Map.of(PARTS_KEY, MAPPER.writeValueAsString(state.parts))));
        }
        return Flux.fromIterable(frames);
    }

    /** 相邻的纯文本 part 并成一个，回放时不至于几十个碎片；带签名/functionCall 的原样单存 */
    private static void storePart(State state, JsonNode part) {
        boolean plainText = part.size() == 1 && part.path("text").asString(null) != null;
        JsonNode last = state.parts.isEmpty() ? null : state.parts.get(state.parts.size() - 1);
        if (plainText && last != null && last.size() == 1 && last.path("text").asString(null) != null) {
            ((ObjectNode) last).put("text", last.path("text").asString(null) + part.path("text").asString(null));
            return;
        }
        state.parts.add(part);
    }

    /** groundingMetadata → 搜完事件：搜索词与命中站点一起给（协议不区分每个词各命中了谁） */
    private static SearchEvent groundingEvent(JsonNode grounding) {
        JsonNode queries = grounding.path("webSearchQueries");
        JsonNode chunks = grounding.path("groundingChunks");
        if (queries.isEmpty() && chunks.isEmpty()) {
            return null;
        }
        List<SearchEvent.Source> sources = new ArrayList<>();
        for (JsonNode chunk : chunks) {
            JsonNode web = chunk.path("web");
            if (web.isObject()) {
                sources.add(new SearchEvent.Source(web.path("uri").asString(null), web.path("title").asString(null)));
            }
        }
        String query = queries.isEmpty() ? null
                : String.join(" · ", queries.valueStream().map(q -> q.asString(null)).toList());
        return SearchEvent.searched(query, sources);
    }

    private ChatResponseMetadata usageMetadata(State state, String responseId) {
        ChatResponseMetadata.Builder metadata = metadata().id(responseId);
        JsonNode usage = state.usage;
        if (usage != null) {
            Integer prompt = usage.hasNonNull("promptTokenCount") ? usage.get("promptTokenCount").asInt() : null;
            metadata.usage(new DefaultUsage(prompt,
                    usage.hasNonNull("candidatesTokenCount") ? usage.get("candidatesTokenCount").asInt() : null,
                    usage.hasNonNull("totalTokenCount") ? usage.get("totalTokenCount").asInt() : null));
            // 隐式缓存 2.5 起默认开，不用声明；命中数是 promptTokenCount 内部的明细，不另加
            int cached = usage.path("cachedContentTokenCount").asInt(0);
            if (cached > 0) {
                log.info("[Gemini] {} 缓存命中{}/{}", model, cached, prompt);
            }
        }
        return metadata.build();
    }
}
