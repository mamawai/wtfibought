package com.mawai.wiibagent.llm;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Responses API（/v1/responses）协议。
 * <p>
 * 思考档位走 reasoning.effort；无状态模式（store=false，历史每轮全量带），
 * 服务端搜索声明 {@code {"type":"web_search"}}。
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
    protected JSONObject requestBody(Prompt prompt) {
        JSONObject body = new JSONObject();
        ChatOptions options = prompt.getOptions();

        body.put("model", effectiveModel(prompt));
        Double temp = effectiveTemperature(prompt);
        if (temp != null) {
            body.put("temperature", temp);
        }
        if (reasoningEffort != null) {
            body.put("reasoning", new JSONObject().fluentPut("effort", reasoningEffort));
        }
        // 无状态：不让服务端存会话，OpenAI 官方 store 默认 true 必须显式关
        body.put("store", false);
        body.put("stream", true);

        // system 消息进 instructions（Responses 惯例），其余按序转 input items
        StringBuilder instructions = new StringBuilder();
        JSONArray input = new JSONArray();
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
                        input.add(new JSONObject()
                                .fluentPut("type", "function_call_output")
                                .fluentPut("call_id", tr.id())
                                .fluentPut("output", tr.responseData()));
                    }
                }
            }
        }
        if (!instructions.isEmpty()) {
            body.put("instructions", instructions.toString());
        }
        body.put("input", input);

        // 工具定义：Responses 是扁平结构（name 在顶层，不像 completions 嵌在 function 下）
        List<String> toolNames = new ArrayList<>();
        if (options instanceof ToolCallingChatOptions toolOptions) {
            JSONArray tools = new JSONArray();
            for (ToolDefinition def : toolCallingManager.resolveToolDefinitions(toolOptions)) {
                tools.add(new JSONObject()
                        .fluentPut("type", "function")
                        .fluentPut("name", def.name())
                        .fluentPut("description", def.description())
                        .fluentPut("parameters", JSON.parseObject(def.inputSchema())));
                toolNames.add(def.name());
            }
            if (searchAllowed(toolOptions)) {
                tools.add(new JSONObject().fluentPut("type", "web_search"));
                toolNames.add("web_search");
            }
            if (!tools.isEmpty()) {
                body.put("tools", tools);
                // 强不强制用工具由调用方按次决定（首轮强制/单次结构化调用），经 toolContext 捎进来（ToolChoice）
                body.put("tool_choice", ToolChoice.of(toolOptions));
            }
        }
        logRequest(body.getString("model"), body.getString("tool_choice"), toolNames);
        return body;
    }

    private JSONObject messageItem(String role, String contentType, String text) {
        return new JSONObject()
                .fluentPut("type", "message")
                .fluentPut("role", role)
                .fluentPut("content", new JSONArray().fluentAdd(new JSONObject()
                        .fluentPut("type", contentType)
                        .fluentPut("text", text == null ? "" : text)));
    }

    /**
     * assistant 消息 → input item。工具循环里的那条带 {@link #ITEMS_KEY} 就原样回放；
     * 其余按文本 + function_call 拼（已结束的轮次、别的协议留下的历史、压缩产物）。
     */
    private List<JSONObject> assistantItems(AssistantMessage assistant, boolean replayRaw) {
        List<JSONObject> items = new ArrayList<>();
        if (replayRaw && assistant.getMetadata().get(ITEMS_KEY) instanceof String raw) {
            JSONArray stored = JSON.parseArray(raw);
            for (int i = 0; i < stored.size(); i++) {
                items.add(stored.getJSONObject(i));
            }
            return items;
        }
        if (assistant.getText() != null && !assistant.getText().isBlank()) {
            items.add(messageItem("assistant", "output_text", assistant.getText()));
        }
        // 历史工具调用重建为 function_call item——function_call_output 必须有配对的调用项
        for (AssistantMessage.ToolCall tc : assistant.getToolCalls()) {
            items.add(new JSONObject()
                    .fluentPut("type", "function_call")
                    .fluentPut("call_id", tc.id())
                    .fluentPut("name", tc.name())
                    .fluentPut("arguments", tc.arguments()));
        }
        return items;
    }

    // ========== 响应解析 ==========

    /** 一次订阅里按顺序攒的原始 output item：收尾时整体挂到消息 metadata 原样回传 */
    protected static class State extends StreamState {
        final JSONArray items = new JSONArray();
    }

    @Override
    protected Flux<ChatResponse> toFrames(JSONObject event, State state) {
        // 事件类型以 data.type 为准（比 event: 行更普适，CPA/OpenAI 都带）
        String type = event.getString("type");
        if (type == null) {
            return Flux.empty();
        }
        switch (type) {
            case "response.output_text.delta" -> {
                String delta = event.getString("delta");
                if (delta == null || delta.isEmpty()) {
                    return Flux.empty();
                }
                state.sawText = true;
                return Flux.just(textFrame(delta));
            }
            // 服务端搜索项（web_search_call 等，各家名字不同，认 *_search_call 后缀）：added=开搜，done=搜完
            case "response.output_item.added" -> {
                JSONObject item = event.getJSONObject("item");
                if (item == null || !isSearchCall(item)) {
                    return Flux.empty();
                }
                JSONObject action = item.getJSONObject("action");
                return Flux.just(searchFrame(SearchEvent.searching(action == null ? null : action.getString("query"))));
            }
            case "response.output_item.done" -> {
                JSONObject item = event.getJSONObject("item");
                if (item == null) {
                    return Flux.empty();
                }
                state.items.add(item);
                if (isSearchCall(item)) {
                    return Flux.just(searchFrame(searchedEvent(item.getJSONObject("action"))));
                }
                if (!"function_call".equals(item.getString("type"))) {
                    return Flux.empty();
                }
                state.sawToolCall = true;
                return Flux.just(toolCallFrame(parseToolCall(item)));
            }
            case "response.output_text.annotation.added" -> {
                JSONObject annotation = event.getJSONObject("annotation");
                if (annotation == null || !"url_citation".equals(annotation.getString("type"))) {
                    return Flux.empty();
                }
                return Flux.just(searchFrame(SearchEvent.cited(List.of(
                        new SearchEvent.Source(annotation.getString("url"), annotation.getString("title"))))));
            }
            case "response.completed" -> {
                JSONObject response = event.getJSONObject("response");
                // 简易网关会把非流式响应原样包成 completed 事件发出，payload 里的 status 可能
                // 仍是 failed/incomplete——事件类型说完成、payload 说截断时信 payload
                String status = response == null ? null : response.getString("status");
                if ("failed".equals(status) || "incomplete".equals(status)) {
                    return Flux.error(new NonTransientAiException(
                            "Responses 流式失败: " + extractErrorMessage(response)));
                }
                List<ChatResponse> frames = new ArrayList<>();
                JSONArray output = response == null ? null : response.getJSONArray("output");
                // 兜底：不发增量事件的服务端，正文和工具调用都只在完整响应的 output 里
                if (!state.sawText && !state.sawToolCall && output != null) {
                    String fullText = extractOutputText(output);
                    if (!fullText.isEmpty()) {
                        frames.add(textFrame(fullText));
                    }
                    for (int i = 0; i < output.size(); i++) {
                        JSONObject item = output.getJSONObject(i);
                        if ("function_call".equals(item.getString("type"))) {
                            state.sawToolCall = true;
                            frames.add(toolCallFrame(parseToolCall(item)));
                        }
                    }
                }
                // 原始 item 同理：一条 output_item.done 都没来的，整份 output 就是它
                if (state.items.isEmpty() && output != null) {
                    state.items.addAll(output);
                }
                frames.add(finalFrame(state.sawToolCall, completedMetadata(response),
                        Map.of(ITEMS_KEY, state.items.toJSONString())));
                return Flux.fromIterable(frames);
            }
            case "response.failed", "response.incomplete" -> {
                String message = event.getJSONObject("response") != null
                        ? extractErrorMessage(event.getJSONObject("response"))
                        : type;
                return Flux.error(new NonTransientAiException("Responses 流式失败: " + message));
            }
            case "error" -> {
                return Flux.error(new NonTransientAiException(
                        "Responses 流式错误: " + event.getString("message")));
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
    private ChatResponseMetadata completedMetadata(JSONObject response) {
        ChatResponseMetadata.Builder metadata = metadata();
        if (response != null) {
            metadata.id(response.getString("id"));
            JSONObject usage = response.getJSONObject("usage");
            if (usage != null) {
                metadata.usage(parseUsage(usage));
                Integer serverTools = usage.getInteger("num_server_side_tools_used");
                if (serverTools != null && serverTools > 0) {
                    metadata.keyValue("num_server_side_tools_used", serverTools);
                    JSONObject details = usage.getJSONObject("server_side_tool_usage_details");
                    if (details != null) {
                        metadata.keyValue("server_side_tool_usage_details", details.toJSONString());
                    }
                    log.info("[Responses] {} 服务端工具调用{}次 明细={}", model, serverTools, details);
                }
            }
        }
        return metadata.build();
    }

    private String extractOutputText(JSONArray output) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < output.size(); i++) {
            JSONObject item = output.getJSONObject(i);
            if (!"message".equals(item.getString("type"))) {
                continue;
            }
            JSONArray content = item.getJSONArray("content");
            if (content == null) {
                continue;
            }
            for (int j = 0; j < content.size(); j++) {
                JSONObject part = content.getJSONObject(j);
                if ("output_text".equals(part.getString("type")) && part.getString("text") != null) {
                    text.append(part.getString("text"));
                }
            }
        }
        return text.toString();
    }

    private static boolean isSearchCall(JSONObject item) {
        String type = item.getString("type");
        return type != null && type.endsWith("_search_call");
    }

    /** 搜完：query 与 sources 都在 action 里（有就取，没有就是空） */
    private static SearchEvent searchedEvent(JSONObject action) {
        List<SearchEvent.Source> sources = new ArrayList<>();
        JSONArray list = action == null ? null : action.getJSONArray("sources");
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                JSONObject s = list.getJSONObject(i);
                sources.add(new SearchEvent.Source(s.getString("url"), s.getString("title")));
            }
        }
        return SearchEvent.searched(action == null ? null : action.getString("query"), sources);
    }

    private AssistantMessage.ToolCall parseToolCall(JSONObject item) {
        // call_id 是配对 function_call_output 的键；个别实现只给 id，兜底用它
        String callId = item.getString("call_id") != null ? item.getString("call_id") : item.getString("id");
        return new AssistantMessage.ToolCall(callId, "function",
                item.getString("name"), item.getString("arguments"));
    }

    private Usage parseUsage(JSONObject usage) {
        return new DefaultUsage(
                usage.getInteger("input_tokens"),
                usage.getInteger("output_tokens"),
                usage.getInteger("total_tokens"));
    }

    private String extractErrorMessage(JSONObject response) {
        JSONObject error = response.getJSONObject("error");
        if (error != null && error.getString("message") != null) {
            return error.getString("message");
        }
        JSONObject incomplete = response.getJSONObject("incomplete_details");
        if (incomplete != null) {
            return "incomplete: " + incomplete.getString("reason");
        }
        return "未知错误";
    }
}
