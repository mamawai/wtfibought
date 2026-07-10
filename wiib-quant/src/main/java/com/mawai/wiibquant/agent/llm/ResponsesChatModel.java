package com.mawai.wiibquant.agent.llm;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI Responses API（/v1/responses）协议的 ChatModel 实现。
 * <p>
 * 为什么自研：Spring AI 1.1.x 的 OpenAiChatModel 只会说 /v1/chat/completions；
 * Grok Build（经 CPA）/OpenAI 官方思考模型的原生协议是 Responses，走原生协议才能带 reasoning.effort 控思考档位。
 * <p>
 * 与框架的契约（读 spring-ai-alibaba 1.1.2.0 源码确认）：
 * <ul>
 *   <li>AgentLlmNode 默认流式（ChatClient.stream()），QuantLlm/Summarization 走阻塞 call()</li>
 *   <li>agent 的工具一律外部执行（internalToolExecutionEnabled=false，图 ToolNode 执行）——
 *       本类只需返回带 toolCalls 的 AssistantMessage、接受 ToolResponseMessage 入参；
 *       内部执行循环仍按 Spring AI 标准语义实现，兜底非 agent 的 ChatClient.tools() 用法</li>
 *   <li>流式聚合（NodeExecutor）按帧拼 text、按 id 合并 toolCalls：每帧只发增量文本，工具调用在 item 完成时整只发一次</li>
 * </ul>
 * 无状态模式（不回传加密思考块）：流式聚合会重建消息丢 metadata，跨轮思考复用在此框架下不可行，
 * 每轮思考开销由 reasoning.effort 封顶。
 */
@Slf4j
public class ResponsesChatModel implements ChatModel {

    private static final Duration CALL_TIMEOUT = Duration.ofMinutes(10);
    /** SSE 相邻事件最大间隔：防半开连接把消费方永久挂死（behavior 的 blockLast 会占死信号量）。
     *  取 5 分钟是给 high 档长思考的静默期留余量——多数服务端思考期间也会发 reasoning 事件/keepalive 注释行，都算心跳 */
    private static final Duration STREAM_IDLE_TIMEOUT = Duration.ofMinutes(5);

    private final WebClient webClient;
    private final String model;
    private final Double temperature;
    /** 思考档位 none/low/medium/high；null=不传走模型默认（来自 DB 配置行，非请求级） */
    private final String reasoningEffort;
    private final ToolCallingManager toolCallingManager;
    private final RetryTemplate retryTemplate;

    public ResponsesChatModel(String apiKey, String baseUrl, String model, Double temperature,
                              String reasoningEffort, ToolCallingManager toolCallingManager,
                              RetryTemplate retryTemplate) {
        this.model = model;
        this.temperature = temperature;
        this.reasoningEffort = reasoningEffort;
        this.toolCallingManager = toolCallingManager;
        this.retryTemplate = retryTemplate;
        // 深研判单次回包可达数百KB，默认256KB codec上限不够
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    @Override
    public ChatOptions getDefaultOptions() {
        ToolCallingChatOptions.Builder builder = ToolCallingChatOptions.builder().model(model);
        if (temperature != null) {
            builder.temperature(temperature);
        }
        return builder.build();
    }

    // ========== 阻塞调用 ==========

    @Override
    public @NonNull ChatResponse call(Prompt prompt) {
        ChatResponse response = retryTemplate.execute(ctx -> doCall(prompt));
        // Spring AI 标准语义：内部工具执行开启且模型要调工具 → 执行后带结果续问，直到模型给出最终回答
        // （isInternalToolExecutionEnabled 对 null options 安全：走默认值 true）
        if (response.hasToolCalls() && ToolCallingChatOptions.isInternalToolExecutionEnabled(prompt.getOptions())) {
            ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
            if (result.returnDirect()) {
                return response;
            }
            return call(new Prompt(result.conversationHistory(), prompt.getOptions()));
        }
        return response;
    }

    private ChatResponse doCall(Prompt prompt) {
        JSONObject body = buildRequestBody(prompt, false);
        String raw = webClient.post()
                .uri("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.toString())
                .retrieve()
                .onStatus(org.springframework.http.HttpStatusCode::isError, resp ->
                        resp.bodyToMono(String.class).defaultIfEmpty("")
                                .flatMap(errBody -> Mono.error(toApiException(resp.statusCode().value(), errBody))))
                .bodyToMono(String.class)
                .block(CALL_TIMEOUT);
        if (raw == null || raw.isBlank()) {
            throw new NonTransientAiException("Responses API 返回空响应");
        }
        return parseResponse(JSON.parseObject(raw));
    }

    // ========== 流式调用 ==========

    @Override
    public @NonNull Flux<ChatResponse> stream(Prompt prompt) {
        // 是否可能内部执行工具在请求前即可判定：agent（internal=false）和无工具调用（QuantLlm）
        // 都走纯透传——帧到即发，工作台 token 实时性不受影响
        if (!mayExecuteToolsInternally(prompt)) {
            return streamOnce(prompt);
        }
        // 内部执行分支（非 agent 的 ChatClient.tools() 用法）：必须攒齐帧才知道要不要调工具
        return streamOnce(prompt).collectList().flatMapMany(frames -> {
            if (!hasAnyToolCall(frames)) {
                return Flux.fromIterable(frames);
            }
            ChatResponse merged = mergeFrames(frames);
            ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, merged);
            if (result.returnDirect()) {
                return Flux.fromIterable(frames);
            }
            return stream(new Prompt(result.conversationHistory(), prompt.getOptions()));
        });
    }

    private boolean mayExecuteToolsInternally(Prompt prompt) {
        if (!(prompt.getOptions() instanceof ToolCallingChatOptions toolOptions)) {
            return false;
        }
        return ToolCallingChatOptions.isInternalToolExecutionEnabled(prompt.getOptions())
                && !toolCallingManager.resolveToolDefinitions(toolOptions).isEmpty();
    }

    /**
     * 单轮 SSE：增量文本逐帧发（供工作台 token 流），工具调用在 output_item.done 整只发一帧，
     * response.completed 发带 usage 的收尾帧。
     */
    private Flux<ChatResponse> streamOnce(Prompt prompt) {
        JSONObject body = buildRequestBody(prompt, true);
        StreamState state = new StreamState();
        return webClient.post()
                .uri("/v1/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body.toString())
                .retrieve()
                .onStatus(org.springframework.http.HttpStatusCode::isError, resp ->
                        resp.bodyToMono(String.class).defaultIfEmpty("")
                                .flatMap(errBody -> Mono.error(toApiException(resp.statusCode().value(), errBody))))
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .timeout(STREAM_IDLE_TIMEOUT)
                .onErrorMap(java.util.concurrent.TimeoutException.class, e ->
                        new TransientAiException("Responses SSE 空闲超过 " + STREAM_IDLE_TIMEOUT.toMinutes() + " 分钟，判定连接挂死"))
                .concatMap(sse -> toFrames(sse, state));
    }

    /** SSE 事件流的累计状态：判定收尾帧的 finishReason、无增量服务端的兜底 */
    private static class StreamState {
        boolean sawText;
        boolean sawToolCall;
    }

    private Flux<ChatResponse> toFrames(ServerSentEvent<String> sse, StreamState state) {
        String data = sse.data();
        if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) {
            return Flux.empty();
        }
        JSONObject event = JSON.parseObject(data);
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
            case "response.output_item.done" -> {
                JSONObject item = event.getJSONObject("item");
                if (item == null || !"function_call".equals(item.getString("type"))) {
                    return Flux.empty();
                }
                state.sawToolCall = true;
                return Flux.just(toolCallFrame(parseToolCall(item)));
            }
            case "response.completed" -> {
                JSONObject response = event.getJSONObject("response");
                List<ChatResponse> frames = new ArrayList<>();
                // 兜底：不发增量事件的服务端，从完整响应里补全文（正常流式两标志必有其一，不会走到）
                if (!state.sawText && !state.sawToolCall && response != null) {
                    String fullText = extractOutputText(response.getJSONArray("output"));
                    if (!fullText.isEmpty()) {
                        frames.add(textFrame(fullText));
                    }
                }
                frames.add(finalFrame(state.sawToolCall, response));
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
            // reasoning 摘要、arguments 增量等事件不进正文流，忽略
            default -> {
                return Flux.empty();
            }
        }
    }

    private ChatResponse textFrame(String delta) {
        AssistantMessage message = AssistantMessage.builder().content(delta).build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private ChatResponse toolCallFrame(AssistantMessage.ToolCall toolCall) {
        AssistantMessage message = AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private ChatResponse finalFrame(boolean sawToolCall, JSONObject response) {
        AssistantMessage message = AssistantMessage.builder().content("").build();
        Generation generation = new Generation(message, ChatGenerationMetadata.builder()
                .finishReason(sawToolCall ? "TOOL_CALLS" : "STOP").build());
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder().model(model);
        if (response != null && response.getJSONObject("usage") != null) {
            metadata.usage(parseUsage(response.getJSONObject("usage")));
        }
        return new ChatResponse(List.of(generation), metadata.build());
    }

    private boolean hasAnyToolCall(List<ChatResponse> frames) {
        return frames.stream().anyMatch(f -> f.getResult().getOutput().hasToolCalls());
    }

    /** 帧合并（仅内部工具执行分支用）：拼文本、收工具调用，成一个完整 ChatResponse 供 executeToolCalls */
    private ChatResponse mergeFrames(List<ChatResponse> frames) {
        StringBuilder text = new StringBuilder();
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        for (ChatResponse frame : frames) {
            AssistantMessage output = frame.getResult().getOutput();
            if (output.getText() != null) {
                text.append(output.getText());
            }
            toolCalls.addAll(output.getToolCalls());
        }
        AssistantMessage merged = AssistantMessage.builder().content(text.toString()).toolCalls(toolCalls).build();
        return new ChatResponse(List.of(new Generation(merged)));
    }

    // ========== 请求构建 ==========

    private JSONObject buildRequestBody(Prompt prompt, boolean stream) {
        JSONObject body = new JSONObject();
        ChatOptions options = prompt.getOptions();

        body.put("model", options != null && options.getModel() != null ? options.getModel() : model);
        Double temp = options != null && options.getTemperature() != null ? options.getTemperature() : temperature;
        if (temp != null) {
            body.put("temperature", temp);
        }
        if (reasoningEffort != null) {
            body.put("reasoning", new JSONObject().fluentPut("effort", reasoningEffort));
        }
        // 无状态：不让服务端存会话（历史我们每轮全量带），OpenAI 官方 store 默认 true 必须显式关
        body.put("store", false);
        body.put("stream", stream);

        // system 消息进 instructions（Responses 惯例），其余按序转 input items
        StringBuilder instructions = new StringBuilder();
        JSONArray input = new JSONArray();
        for (Message message : prompt.getInstructions()) {
            switch (message.getMessageType()) {
                case SYSTEM -> {
                    if (!instructions.isEmpty()) {
                        instructions.append("\n\n");
                    }
                    instructions.append(message.getText());
                }
                case USER -> input.add(messageItem("user", "input_text", message.getText()));
                case ASSISTANT -> {
                    AssistantMessage assistant = (AssistantMessage) message;
                    if (assistant.getText() != null && !assistant.getText().isBlank()) {
                        input.add(messageItem("assistant", "output_text", assistant.getText()));
                    }
                    // 历史工具调用重建为 function_call item——function_call_output 必须有配对的调用项
                    for (AssistantMessage.ToolCall tc : assistant.getToolCalls()) {
                        input.add(new JSONObject()
                                .fluentPut("type", "function_call")
                                .fluentPut("call_id", tc.id())
                                .fluentPut("name", tc.name())
                                .fluentPut("arguments", tc.arguments()));
                    }
                }
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
        if (options instanceof ToolCallingChatOptions toolOptions) {
            List<ToolDefinition> definitions = toolCallingManager.resolveToolDefinitions(toolOptions);
            if (!definitions.isEmpty()) {
                JSONArray tools = new JSONArray();
                for (ToolDefinition def : definitions) {
                    tools.add(new JSONObject()
                            .fluentPut("type", "function")
                            .fluentPut("name", def.name())
                            .fluentPut("description", def.description())
                            .fluentPut("parameters", JSON.parseObject(def.inputSchema())));
                }
                body.put("tools", tools);
                body.put("tool_choice", "auto");
            }
        }
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

    // ========== 响应解析 ==========

    private ChatResponse parseResponse(JSONObject response) {
        if ("failed".equals(response.getString("status"))) {
            throw new NonTransientAiException("Responses API 失败: " + extractErrorMessage(response));
        }
        JSONArray output = response.getJSONArray("output");
        String text = extractOutputText(output);
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        if (output != null) {
            for (int i = 0; i < output.size(); i++) {
                JSONObject item = output.getJSONObject(i);
                if ("function_call".equals(item.getString("type"))) {
                    toolCalls.add(parseToolCall(item));
                }
            }
        }

        AssistantMessage message = AssistantMessage.builder().content(text).toolCalls(toolCalls).build();
        Generation generation = new Generation(message, ChatGenerationMetadata.builder()
                .finishReason(toolCalls.isEmpty() ? "STOP" : "TOOL_CALLS").build());
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder()
                .id(response.getString("id"))
                .model(response.getString("model") != null ? response.getString("model") : model);
        if (response.getJSONObject("usage") != null) {
            metadata.usage(parseUsage(response.getJSONObject("usage")));
        }
        return new ChatResponse(List.of(generation), metadata.build());
    }

    private String extractOutputText(JSONArray output) {
        if (output == null) {
            return "";
        }
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

    /** 429/5xx 归为瞬时（RetryTemplate 会重试），其余 4xx 直接失败——配置错误重试也没用 */
    private RuntimeException toApiException(int status, String body) {
        String message = "Responses API HTTP " + status + ": " + (body.length() > 500 ? body.substring(0, 500) : body);
        if (status == 429 || status >= 500) {
            return new TransientAiException(message);
        }
        return new NonTransientAiException(message);
    }
}
