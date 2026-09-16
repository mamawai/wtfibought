package com.mawai.wiibagent.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * OpenAI Responses API（/v1/responses）协议。
 * <p>
 * 思考档位走 reasoning.effort；无状态模式（store=false，历史每轮全量带），
 * 服务端搜索声明 {@code {"type":"web_search"}}。
 * 提示缓存不用声明，各家都是自动的；只捎一个分组键让同前缀的请求落到同一台机器（见 {@link #cacheKey}）。
 * 流式：正文只认 output_text.delta，工具调用整只收在 output_item.done，response.completed 发收尾帧；
 * 不发增量事件的网关，正文、工具与原始 item 都从 completed 的 output 兜底。
 * <p>
 * 原始 item 回放：本轮 output 的 item 数组（reasoning / message / function_call…）挂在收尾帧消息的 {@link #ITEMS_KEY} 上，
 * 只有还在工具循环里的那条（后面紧跟工具回执）原样回放——store=false 下 reasoning 的 encrypted_content 得原样回去，
 * 模型才接得上上一步的推理；其余按文本 + function_call 拼（见 {@link #inToolLoop}）。
 */
public class ResponsesChatModel extends SseChatModel<ResponsesChatModel.State> {

    /** 消息 metadata 键：本轮 output 原始 item 数组的 JSON 串 */
    public static final String ITEMS_KEY = "wiib_responses_items";

    public ResponsesChatModel(String apiKey, String baseUrl, String model, Double temperature,
                              String reasoningEffort, ToolCallingManager toolCallingManager,
                              boolean webSearch) {
        super("Responses", OpenAiBaseUrl.strip(baseUrl, "/v1"),
                Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey),
                model, temperature, reasoningEffort, toolCallingManager, webSearch);
    }

    @Override
    protected State newState() {
        return new State();
    }

    @Override
    protected String requestUri(Prompt prompt) {
        return "/v1/responses";
    }

    // ========== 请求构建 ==========

    @Override
    protected ObjectNode requestBody(Prompt prompt) {
        ObjectNode body = MAPPER.createObjectNode();
        ChatOptions options = prompt.getOptions();

        body.put("model", effectiveModel(prompt));
        Double temp = effectiveTemperature(prompt);
        if (temp != null) {
            body.put("temperature", temp);
        }
        if (reasoningEffort != null) {
            body.set("reasoning", MAPPER.createObjectNode().put("effort", reasoningEffort));
        }
        // 无状态：不让服务端存会话，OpenAI 官方 store 默认 true 必须显式关
        body.put("store", false);
        body.put("stream", true);

        // system 消息进 instructions（Responses 惯例），其余按序转 input items
        StringBuilder instructions = new StringBuilder();
        ArrayNode input = MAPPER.createArrayNode();
        List<Message> history = prompt.getInstructions();
        for (int i = 0; i < history.size(); i++) {
            Message message = history.get(i);
            switch (message.getMessageType()) {
                case SYSTEM -> {
                    if (!instructions.isEmpty()) {
                        instructions.append("\n\n");
                    }
                    instructions.append(message.getText());
                }
                case USER -> input.add(messageItem("user", "input_text", message.getText()));
                case ASSISTANT -> input.addAll(assistantItems((AssistantMessage) message, inToolLoop(history, i)));
                case TOOL -> {
                    for (ToolResponseMessage.ToolResponse tr : ((ToolResponseMessage) message).getResponses()) {
                        input.add(MAPPER.createObjectNode()
                                .put("type", "function_call_output")
                                .put("call_id", tr.id())
                                .put("output", tr.responseData()));
                    }
                }
            }
        }
        if (!instructions.isEmpty()) {
            body.put("instructions", instructions.toString());
        }
        body.set("input", input);

        // 工具定义：Responses 是扁平结构（name 在顶层，不像 completions 嵌在 function 下）
        List<String> toolNames = new ArrayList<>();
        if (options instanceof ToolCallingChatOptions toolOptions) {
            ArrayNode tools = MAPPER.createArrayNode();
            for (ToolDefinition def : toolCallingManager.resolveToolDefinitions(toolOptions)) {
                tools.add(MAPPER.createObjectNode()
                        .put("type", "function")
                        .put("name", def.name())
                        .put("description", def.description())
                        .set("parameters", MAPPER.readTree(def.inputSchema())));
                toolNames.add(def.name());
            }
            if (searchAllowed(toolOptions)) {
                tools.add(MAPPER.createObjectNode().put("type", "web_search"));
                toolNames.add("web_search");
            }
            if (!tools.isEmpty()) {
                body.set("tools", tools);
                // 强不强制用工具由调用方按次决定（首轮强制/单次结构化调用），经 toolContext 捎进来（ToolChoice）
                body.put("tool_choice", ToolChoice.of(toolOptions));
            }
        }
        body.put("prompt_cache_key", cacheKey(instructions.toString(), toolNames));
        logRequest(body.path("model").asString(null), body.path("tool_choice").asString(null), toolNames);
        return body;
    }

    /**
     * 提示缓存的分组键：它不是缓存句柄，是个标签，告诉上游"带同一个标签的请求共用同一段前缀"。
     * xAI 按它路由到同一台机器（缓存按机器存，不给标签就可能每次换机器，前面存的白存），
     * OpenAI 拿它分账与隔离。
     * <p>
     * 取 instructions + 工具名的哈希：这两样正好决定了可复用的那段前缀，同一个 agent 跨轮跨唤醒键不变，
     * 它们变了键跟着变（那时旧前缀本就失效）。送哈希不送原文——system 里有用户自己写的字。
     */
    private static String cacheKey(String instructions, List<String> toolNames) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(instructions.getBytes(StandardCharsets.UTF_8));
            for (String name : toolNames) {
                sha.update(name.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(sha.digest(), 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private ObjectNode messageItem(String role, String contentType, String text) {
        return MAPPER.createObjectNode()
                .put("type", "message")
                .put("role", role)
                .set("content", MAPPER.createArrayNode().add(MAPPER.createObjectNode()
                        .put("type", contentType)
                        .put("text", text == null ? "" : text)));
    }

    /**
     * assistant 消息 → input item。工具循环里的那条带 {@link #ITEMS_KEY} 就原样回放；
     * 其余按文本 + function_call 拼（已结束的轮次、别的协议留下的历史、压缩产物）。
     */
    private List<ObjectNode> assistantItems(AssistantMessage assistant, boolean replayRaw) {
        List<ObjectNode> items = new ArrayList<>();
        if (replayRaw && assistant.getMetadata().get(ITEMS_KEY) instanceof String raw) {
            for (JsonNode item : MAPPER.readValue(raw, ArrayNode.class)) {
                items.add((ObjectNode) item);
            }
            return items;
        }
        if (assistant.getText() != null && !assistant.getText().isBlank()) {
            items.add(messageItem("assistant", "output_text", assistant.getText()));
        }
        // 历史工具调用重建为 function_call item——function_call_output 必须有配对的调用项
        for (AssistantMessage.ToolCall tc : assistant.getToolCalls()) {
            items.add(MAPPER.createObjectNode()
                    .put("type", "function_call")
                    .put("call_id", tc.id())
                    .put("name", tc.name())
                    .put("arguments", tc.arguments()));
        }
        return items;
    }

    // ========== 响应解析 ==========

    /** 一次订阅里按顺序攒的原始 output item：收尾时整体挂到消息 metadata 原样回传 */
    protected static class State extends StreamState {
        final ArrayNode items = MAPPER.createArrayNode();
    }

    @Override
    protected Flux<ChatResponse> toFrames(JsonNode event, State state) {
        // 事件类型以 data.type 为准（比 event: 行更普适，CPA/OpenAI 都带）
        String type = event.path("type").asString(null);
        if (type == null) {
            return Flux.empty();
        }
        switch (type) {
            case "response.output_text.delta" -> {
                String delta = event.path("delta").asString(null);
                if (delta == null || delta.isEmpty()) {
                    return Flux.empty();
                }
                state.sawText = true;
                return Flux.just(textFrame(delta));
            }
            // 服务端搜索项（web_search_call 等，各家名字不同，认 *_search_call 后缀）：added=开搜，done=搜完
            case "response.output_item.added" -> {
                JsonNode item = event.get("item");
                if (item == null || item.isNull() || !isSearchCall(item)) {
                    return Flux.empty();
                }
                return Flux.just(searchFrame(SearchEvent.searching(item.path("action").path("query").asString(null))));
            }
            case "response.output_item.done" -> {
                JsonNode item = event.get("item");
                if (item == null || item.isNull()) {
                    return Flux.empty();
                }
                state.items.add(item);
                if (isSearchCall(item)) {
                    return Flux.just(searchFrame(searchedEvent(item.path("action"))));
                }
                if (!"function_call".equals(item.path("type").asString(null))) {
                    return Flux.empty();
                }
                state.sawToolCall = true;
                return Flux.just(toolCallFrame(parseToolCall(item)));
            }
            case "response.output_text.annotation.added" -> {
                JsonNode annotation = event.path("annotation");
                if (!"url_citation".equals(annotation.path("type").asString(null))) {
                    return Flux.empty();
                }
                return Flux.just(searchFrame(SearchEvent.cited(List.of(
                        new SearchEvent.Source(annotation.path("url").asString(null),
                                annotation.path("title").asString(null))))));
            }
            case "response.completed" -> {
                JsonNode response = event.hasNonNull("response") ? event.get("response") : null;
                // 简易网关会把非流式响应原样包成 completed 事件发出，payload 里的 status 可能
                // 仍是 failed/incomplete——事件类型说完成、payload 说截断时信 payload
                String status = response == null ? null : response.path("status").asString(null);
                if ("failed".equals(status) || "incomplete".equals(status)) {
                    return Flux.error(new NonTransientAiException(
                            "Responses 流式失败: " + extractErrorMessage(response)));
                }
                List<ChatResponse> frames = new ArrayList<>();
                JsonNode output = response == null || !response.hasNonNull("output") ? null : response.get("output");
                // 兜底：不发增量事件的服务端，正文和工具调用都只在完整响应的 output 里
                if (!state.sawText && !state.sawToolCall && output != null) {
                    String fullText = extractOutputText(output);
                    if (!fullText.isEmpty()) {
                        frames.add(textFrame(fullText));
                    }
                    for (JsonNode item : output) {
                        if ("function_call".equals(item.path("type").asString(null))) {
                            state.sawToolCall = true;
                            frames.add(toolCallFrame(parseToolCall(item)));
                        }
                    }
                }
                // 原始 item 同理：一条 output_item.done 都没来的，整份 output 就是它
                if (state.items.isEmpty() && output != null) {
                    output.forEach(state.items::add);
                }
                frames.add(finalFrame(state.sawToolCall, completedMetadata(response),
                        Map.of(ITEMS_KEY, MAPPER.writeValueAsString(state.items))));
                return Flux.fromIterable(frames);
            }
            case "response.failed", "response.incomplete" -> {
                String message = event.hasNonNull("response")
                        ? extractErrorMessage(event.get("response"))
                        : type;
                return Flux.error(new NonTransientAiException("Responses 流式失败: " + message));
            }
            case "error" -> {
                return Flux.error(new NonTransientAiException(
                        "Responses 流式错误: " + event.path("message").asString(null)));
            }
            // 已知且无需处理的事件：进度心跳、reasoning 摘要、arguments 增量（工具整只收在 output_item.done）、
            // 文本/分段的 added/done（正文只认 delta）
            case "response.created", "response.in_progress",
                 "response.content_part.added", "response.content_part.done",
                 "response.output_text.done",
                 "response.reasoning_summary_text.delta", "response.reasoning_summary_text.done",
                 "response.reasoning_summary_part.added", "response.reasoning_summary_part.done",
                 "response.function_call_arguments.delta", "response.function_call_arguments.done" -> {
                return Flux.empty();
            }
            default -> {
                if (state.firstUnknown(type)) {
                    log.info("[Responses] 跳过陌生事件类型 {}", type);
                }
                return Flux.empty();
            }
        }
    }

    /** 收尾帧的 metadata：id、usage，以及服务端搜索观测（xAI usage 形态）——搜没搜必须有据可查 */
    private ChatResponseMetadata completedMetadata(JsonNode response) {
        ChatResponseMetadata.Builder metadata = metadata();
        if (response != null) {
            metadata.id(response.path("id").asString(null));
            if (response.hasNonNull("usage")) {
                JsonNode usage = response.get("usage");
                metadata.usage(parseUsage(usage));
                logCacheHit(usage);
                int serverTools = usage.path("num_server_side_tools_used").asInt(0);
                if (serverTools > 0) {
                    metadata.keyValue("num_server_side_tools_used", serverTools);
                    JsonNode details = usage.get("server_side_tool_usage_details");
                    if (details != null && !details.isNull()) {
                        metadata.keyValue("server_side_tool_usage_details", MAPPER.writeValueAsString(details));
                    }
                    log.info("[Responses] {} 服务端工具调用{}次 明细={}", model, serverTools, details);
                }
            }
        }
        return metadata.build();
    }

    /** 缓存命中观测：cached_tokens 是 input_tokens 内部的明细、不另加，命中好不好只能靠它看 */
    private void logCacheHit(JsonNode usage) {
        int cached = usage.path("input_tokens_details").path("cached_tokens").asInt(0);
        if (cached > 0) {
            log.info("[Responses] {} 缓存命中{}/{}", model, cached, usage.path("input_tokens").asInt(0));
        }
    }

    private String extractOutputText(JsonNode output) {
        StringBuilder text = new StringBuilder();
        for (JsonNode item : output) {
            if (!"message".equals(item.path("type").asString(null))) {
                continue;
            }
            for (JsonNode part : item.path("content")) {
                String partText = part.path("text").asString(null);
                if ("output_text".equals(part.path("type").asString(null)) && partText != null) {
                    text.append(partText);
                }
            }
        }
        return text.toString();
    }

    private static boolean isSearchCall(JsonNode item) {
        String type = item.path("type").asString(null);
        return type != null && type.endsWith("_search_call");
    }

    /** 搜完：query 与 sources 都在 action 里（有就取，没有就是空） */
    private static SearchEvent searchedEvent(JsonNode action) {
        List<SearchEvent.Source> sources = new ArrayList<>();
        for (JsonNode s : action.path("sources")) {
            sources.add(new SearchEvent.Source(s.path("url").asString(null), s.path("title").asString(null)));
        }
        return SearchEvent.searched(action.path("query").asString(null), sources);
    }

    private AssistantMessage.ToolCall parseToolCall(JsonNode item) {
        // call_id 是配对 function_call_output 的键；个别实现只给 id，兜底用它
        String callId = item.path("call_id").asString(null) != null
                ? item.path("call_id").asString(null) : item.path("id").asString(null);
        // arguments 按协议是 JSON 串；个别网关直接给对象，转回串
        JsonNode arguments = item.get("arguments");
        return new AssistantMessage.ToolCall(callId, "function", item.path("name").asString(null),
                arguments == null || arguments.isNull() ? null
                        : arguments.isString() ? arguments.asString() : MAPPER.writeValueAsString(arguments));
    }

    private Usage parseUsage(JsonNode usage) {
        return new DefaultUsage(
                usage.hasNonNull("input_tokens") ? usage.get("input_tokens").asInt() : null,
                usage.hasNonNull("output_tokens") ? usage.get("output_tokens").asInt() : null,
                usage.hasNonNull("total_tokens") ? usage.get("total_tokens").asInt() : null);
    }

    private String extractErrorMessage(JsonNode response) {
        String message = response.path("error").path("message").asString(null);
        if (message != null) {
            return message;
        }
        JsonNode incomplete = response.get("incomplete_details");
        if (incomplete != null && !incomplete.isNull()) {
            return "incomplete: " + incomplete.path("reason").asString(null);
        }
        return "未知错误";
    }
}
