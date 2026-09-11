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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Anthropic Messages API（/v1/messages）协议。
 * <p>
 * 思考档位：留空不传；none → {@code thinking:{type:disabled}}；其余 → {@code thinking:{type:adaptive}} +
 * {@code output_config.effort}。服务端搜索声明 {@code {type:web_search_20250305,name:web_search}}。
 * <p>
 * 内容块原样回传：本轮 assistant 的原始 content 块数组挂在收尾帧消息的 {@link #BLOCKS_KEY} 上，
 * 只有还在工具循环里的那条（后面紧跟工具回执）原样回放这些块（思考块 signature、搜索结果 encrypted_content
 * 都要原样回去），其余按文本 + tool_use 拼（见 {@link #inToolLoop}）。
 * 同角色连续消息合成一条多块消息（Messages 要求 user/assistant 交替）。
 * <p>
 * 提示缓存两个断点：system 首块挂 1h（tools 排在 system 前面，一个断点把两者一起盖住；
 * 每条 SystemMessage 各成一块，首块是 agent 基础提示词，后面的是压缩摘要、会变，不能跟首块粘一起），
 * 末条消息末块挂默认 5 分钟、随对话前移（见 {@link #markCacheTail}）。
 * 收尾只认 end_turn / stop_sequence / tool_use 是完整回答，refusal / max_tokens 等显式失败（见 {@link #abnormalStop}）。
 */
public class AnthropicChatModel extends SseChatModel<AnthropicChatModel.State> {

    /** 消息 metadata 键：本轮 assistant 原始 content 块数组的 JSON 串 */
    public static final String BLOCKS_KEY = "wiib_anthropic_blocks";
    static final String SEARCH_TOOL_TYPE = "web_search_20250305";
    static final String SEARCH_TOOL_NAME = "web_search";
    /** max_tokens 必填，思考 token 也算在内；超过模型上限上游直接 400，64000 是现役模型都收的值 */
    static final int MAX_TOKENS = 64000;
    static final String CACHE_CONTROL = "cache_control";
    private static final Duration LIST_TIMEOUT = Duration.ofSeconds(30);

    public AnthropicChatModel(String apiKey, String baseUrl, String model, Double temperature,
                              String reasoningEffort, ToolCallingManager toolCallingManager, boolean webSearch) {
        super("Anthropic", OpenAiBaseUrl.strip(baseUrl, "/v1"), headers(apiKey),
                model, temperature, reasoningEffort, toolCallingManager, webSearch);
    }

    private static Map<String, String> headers(String apiKey) {
        return Map.of("x-api-key", apiKey, "anthropic-version", "2023-06-01");
    }

    /** 模型清单：GET /v1/models → data[].id；limit 给上限，一页拿完。非 2xx 原样抛给调用方 */
    public static List<String> listModels(String baseUrl, String apiKey) {
        WebClient.RequestHeadersSpec<?> request = WebClient.create(OpenAiBaseUrl.strip(baseUrl, "/v1")).get()
                .uri("/v1/models?limit=1000");
        headers(apiKey).forEach(request::header);
        String body = request.retrieve().bodyToMono(String.class).block(LIST_TIMEOUT);
        JSONArray data = JSON.parseObject(body).getJSONArray("data");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            ids.add(data.getJSONObject(i).getString("id"));
        }
        return ids.stream().sorted().toList();
    }

    @Override
    protected State newState() {
        return new State();
    }

    @Override
    protected String requestUri(Prompt prompt) {
        return "/v1/messages";
    }

    // ========== 请求构建 ==========

    @Override
    protected JSONObject requestBody(Prompt prompt) {
        JSONObject body = new JSONObject();
        ChatOptions options = prompt.getOptions();
        body.put("model", effectiveModel(prompt));
        body.put("max_tokens", MAX_TOKENS);
        body.put("stream", true);
        Double temp = effectiveTemperature(prompt);
        if (temp != null) {
            body.put("temperature", temp);
        }
        if ("none".equals(reasoningEffort)) {
            body.put("thinking", new JSONObject().fluentPut("type", "disabled"));
        } else if (reasoningEffort != null) {
            body.put("thinking", new JSONObject().fluentPut("type", "adaptive"));
            body.put("output_config", new JSONObject().fluentPut("effort", reasoningEffort));
        }

        List<String> toolNames = new ArrayList<>();
        JSONArray tools = new JSONArray();
        if (options instanceof ToolCallingChatOptions toolOptions) {
            for (ToolDefinition def : toolCallingManager.resolveToolDefinitions(toolOptions)) {
                tools.add(new JSONObject()
                        .fluentPut("name", def.name())
                        .fluentPut("description", def.description())
                        .fluentPut("input_schema", JSON.parseObject(def.inputSchema())));
                toolNames.add(def.name());
            }
            if (searchAllowed(toolOptions)) {
                tools.add(new JSONObject().fluentPut("type", SEARCH_TOOL_TYPE).fluentPut("name", SEARCH_TOOL_NAME));
                toolNames.add(SEARCH_TOOL_NAME);
            }
            if (!tools.isEmpty()) {
                body.put("tools", tools);
                JSONObject choice = toolChoice(ToolChoice.of(toolOptions));
                if (choice != null) {
                    body.put("tool_choice", choice);
                }
            }
        }

        JSONArray system = new JSONArray();
        JSONArray messages = new JSONArray();
        List<Message> history = prompt.getInstructions();
        for (int i = 0; i < history.size(); i++) {
            Message message = history.get(i);
            switch (message.getMessageType()) {
                case SYSTEM -> system.add(textBlock(message.getText()));
                case USER -> append(messages, "user", List.of(textBlock(message.getText())));
                case ASSISTANT -> append(messages, "assistant",
                        assistantBlocks((AssistantMessage) message, inToolLoop(history, i)));
                case TOOL -> {
                    List<JSONObject> blocks = new ArrayList<>();
                    for (ToolResponseMessage.ToolResponse tr : ((ToolResponseMessage) message).getResponses()) {
                        blocks.add(new JSONObject()
                                .fluentPut("type", "tool_result")
                                .fluentPut("tool_use_id", tr.id())
                                .fluentPut("content", tr.responseData()));
                    }
                    append(messages, "user", blocks);
                }
            }
        }
        if (!system.isEmpty()) {
            // 1h 只挂首块：隔 15 分钟 / 1 小时再醒的 trader 也能命中同一份 system + tools；
            // 后面的块是压缩摘要，压一次变一次，挂在首块上会把基础提示词的缓存一起冲掉
            system.getJSONObject(0).put(CACHE_CONTROL, cacheControl("1h"));
            body.put("system", system);
        }
        markCacheTail(messages);
        body.put("messages", messages);
        logRequest(body.getString("model"), body.get("tool_choice"), toolNames);
        return body;
    }

    /** 缓存断点；ttl 空=默认 5 分钟 */
    private static JSONObject cacheControl(String ttl) {
        JSONObject control = new JSONObject().fluentPut("type", "ephemeral");
        if (ttl != null) {
            control.put("ttl", ttl);
        }
        return control;
    }

    /** 末条消息的末块挂 5 分钟断点，随对话前移：一次唤醒里的十几次调用靠它复用前面的整段历史 */
    private static void markCacheTail(JSONArray messages) {
        JSONArray content = messages.getJSONObject(messages.size() - 1).getJSONArray("content");
        content.getJSONObject(content.size() - 1).put(CACHE_CONTROL, cacheControl(null));
    }

    /** required → any；具体工具名 → tool；auto 不传（默认就是 auto） */
    private static JSONObject toolChoice(String choice) {
        if (ToolChoice.REQUIRED.equals(choice)) {
            return new JSONObject().fluentPut("type", "any");
        }
        if (ToolChoice.AUTO.equals(choice)) {
            return null;
        }
        return new JSONObject().fluentPut("type", "tool").fluentPut("name", choice);
    }

    /** 同角色连续消息并成一条：块接到上一条尾巴上。空块列表整条不发（Messages 不收空消息） */
    private static void append(JSONArray messages, String role, List<JSONObject> blocks) {
        if (blocks.isEmpty()) {
            return;
        }
        JSONObject last = messages.isEmpty() ? null : messages.getJSONObject(messages.size() - 1);
        if (last != null && role.equals(last.getString("role"))) {
            last.getJSONArray("content").addAll(blocks);
            return;
        }
        messages.add(new JSONObject().fluentPut("role", role).fluentPut("content", new JSONArray(blocks)));
    }

    private static JSONObject textBlock(String text) {
        return new JSONObject().fluentPut("type", "text").fluentPut("text", text == null ? "" : text);
    }

    /**
     * assistant 消息 → 内容块。工具循环里的那条带 {@link #BLOCKS_KEY} 就原样回放；
     * 其余按文本 + tool_use 拼（已结束的轮次、别的协议留下的历史、压缩产物）。
     */
    private static List<JSONObject> assistantBlocks(AssistantMessage assistant, boolean replayRaw) {
        List<JSONObject> blocks = new ArrayList<>();
        if (replayRaw && assistant.getMetadata().get(BLOCKS_KEY) instanceof String raw) {
            JSONArray stored = JSON.parseArray(raw);
            for (int i = 0; i < stored.size(); i++) {
                blocks.add(stored.getJSONObject(i));
            }
            return blocks;
        }
        if (assistant.getText() != null && !assistant.getText().isBlank()) {
            blocks.add(textBlock(assistant.getText()));
        }
        for (AssistantMessage.ToolCall tc : assistant.getToolCalls()) {
            blocks.add(new JSONObject()
                    .fluentPut("type", "tool_use")
                    .fluentPut("id", tc.id())
                    .fluentPut("name", tc.name())
                    .fluentPut("input", tc.arguments().isBlank()
                            ? new JSONObject() : JSON.parseObject(tc.arguments())));
        }
        return blocks;
    }

    // ========== 响应解析 ==========

    /** 一次订阅里按 index 攒的内容块：收尾时整体挂到消息 metadata 原样回传 */
    protected static class State extends StreamState {
        final TreeMap<Integer, JSONObject> blocks = new TreeMap<>();
        /** tool_use / server_tool_use 的 input 是 partial_json 增量拼出来的 */
        final Map<Integer, StringBuilder> partialJson = new HashMap<>();
        /** server_tool_use id → 搜索词，搜索结果块按 tool_use_id 配回 */
        final Map<String, String> queries = new HashMap<>();
        String messageId;
        /** message_start 的 usage：input 与缓存三项在这，message_delta 没带时用它 */
        JSONObject startUsage;
    }

    @Override
    protected Flux<ChatResponse> toFrames(JSONObject event, State state) {
        String type = event.getString("type");
        if (type == null) {
            return Flux.empty();
        }
        switch (type) {
            case "message_start" -> {
                JSONObject message = event.getJSONObject("message");
                state.messageId = message.getString("id");
                state.startUsage = message.getJSONObject("usage");
                return Flux.empty();
            }
            case "content_block_start" -> {
                int index = event.getIntValue("index");
                JSONObject block = event.getJSONObject("content_block");
                state.blocks.put(index, block);
                switch (block.getString("type")) {
                    case "text" -> {
                        // 正常流式起始 text 为空；简易网关可能把整段正文放在起始块里
                        String text = block.getString("text");
                        if (text != null && !text.isEmpty()) {
                            state.sawText = true;
                            return Flux.just(textFrame(text));
                        }
                        return Flux.empty();
                    }
                    case "tool_use", "server_tool_use" -> {
                        state.partialJson.put(index, new StringBuilder());
                        return Flux.empty();
                    }
                    case "web_search_tool_result" -> {
                        return Flux.just(searchFrame(SearchEvent.searched(
                                state.queries.get(block.getString("tool_use_id")), searchResults(block))));
                    }
                    default -> {
                        return Flux.empty();
                    }
                }
            }
            case "content_block_delta" -> {
                int index = event.getIntValue("index");
                JSONObject block = state.blocks.get(index);
                JSONObject delta = event.getJSONObject("delta");
                switch (delta.getString("type")) {
                    case "text_delta" -> {
                        String text = delta.getString("text");
                        if (text == null || text.isEmpty()) {
                            return Flux.empty();
                        }
                        block.put("text", block.getString("text") == null ? text : block.getString("text") + text);
                        state.sawText = true;
                        return Flux.just(textFrame(text));
                    }
                    case "input_json_delta" -> state.partialJson.get(index).append(delta.getString("partial_json"));
                    case "thinking_delta" -> block.put("thinking",
                            (block.getString("thinking") == null ? "" : block.getString("thinking")) + delta.getString("thinking"));
                    case "signature_delta" -> block.put("signature", delta.getString("signature"));
                    case "citations_delta" -> {
                        JSONObject citation = delta.getJSONObject("citation");
                        block.computeIfAbsent("citations", _ -> new JSONArray());
                        block.getJSONArray("citations").add(citation);
                        if ("web_search_result_location".equals(citation.getString("type"))) {
                            return Flux.just(searchFrame(SearchEvent.cited(List.of(
                                    new SearchEvent.Source(citation.getString("url"), citation.getString("title"))))));
                        }
                    }
                    default -> {
                        if (state.firstUnknown("delta:" + delta.getString("type"))) {
                            log.info("[Anthropic] 跳过陌生增量类型 {}", delta.getString("type"));
                        }
                    }
                }
                return Flux.empty();
            }
            case "content_block_stop" -> {
                int index = event.getIntValue("index");
                JSONObject block = state.blocks.get(index);
                switch (block.getString("type")) {
                    case "tool_use" -> {
                        JSONObject input = finishInput(block, state.partialJson.remove(index));
                        state.sawToolCall = true;
                        return Flux.just(toolCallFrame(new AssistantMessage.ToolCall(
                                block.getString("id"), "function", block.getString("name"), input.toJSONString())));
                    }
                    case "server_tool_use" -> {
                        JSONObject input = finishInput(block, state.partialJson.remove(index));
                        String query = input.getString("query");
                        state.queries.put(block.getString("id"), query);
                        return Flux.just(searchFrame(SearchEvent.searching(query)));
                    }
                    case "text" -> {
                        // 空 text 块回放会被拒（text 块必须非空），收尾就不留它
                        if (block.getString("text") == null || block.getString("text").isEmpty()) {
                            state.blocks.remove(index);
                        }
                        return Flux.empty();
                    }
                    default -> {
                        return Flux.empty();
                    }
                }
            }
            case "message_delta" -> {
                JSONObject delta = event.getJSONObject("delta");
                String stopReason = delta == null ? null : delta.getString("stop_reason");
                String abnormal = abnormalStop(stopReason, delta);
                if (abnormal != null) {
                    return Flux.error(new NonTransientAiException("Anthropic " + abnormal));
                }
                boolean toolCalls = state.sawToolCall || "tool_use".equals(stopReason);
                return Flux.just(finalFrame(toolCalls, deltaMetadata(event.getJSONObject("usage"), state),
                        Map.of(BLOCKS_KEY, new JSONArray(state.blocks.values()).toJSONString())));
            }
            case "error" -> {
                JSONObject error = event.getJSONObject("error");
                String message = "Anthropic 流式错误: " + error.getString("type") + " " + error.getString("message");
                // overloaded 是上游过载，换个时机再来大概率就好
                return Flux.error("overloaded_error".equals(error.getString("type"))
                        ? new TransientAiException(message) : new NonTransientAiException(message));
            }
            case "message_stop", "ping" -> {
                return Flux.empty();
            }
            default -> {
                if (state.firstUnknown(type)) {
                    log.info("[Anthropic] 跳过陌生事件类型 {}", type);
                }
                return Flux.empty();
            }
        }
    }

    /**
     * 非正常收尾的说明；正常收尾（end_turn / stop_sequence / tool_use）和中转站自定义值返回 null。
     * 这几种拿到的都不是完整回答，按 STOP 收会把半截或空回答当结论落库：
     * refusal 是安全分类器拦下（可能零输出，也可能流到一半）；max_tokens 撞顶截断；
     * model_context_window_exceeded 上下文写满；pause_turn 服务端搜索回合被暂停，得原样回传才能续，本层不续
     */
    private static String abnormalStop(String stopReason, JSONObject delta) {
        if (stopReason == null) {
            return null;
        }
        return switch (stopReason) {
            case "refusal" -> "拒绝生成" + (delta.getJSONObject("stop_details") == null
                    ? "" : ": " + delta.getJSONObject("stop_details").toJSONString());
            case "max_tokens" -> "输出撞到 max_tokens 上限被截断";
            case "model_context_window_exceeded" -> "上下文窗口写满，输出被截断";
            case "pause_turn" -> "服务端搜索回合被暂停，没拿到完整回答";
            default -> null;
        };
    }

    /** partial_json 拼完就是 input；一个增量都没来的保留起始块自带的 input（无参工具是 {}） */
    private static JSONObject finishInput(JSONObject block, StringBuilder partial) {
        if (partial != null && !partial.isEmpty()) {
            block.put("input", JSON.parseObject(partial.toString()));
        } else if (block.getJSONObject("input") == null) {
            block.put("input", new JSONObject());
        }
        return block.getJSONObject("input");
    }

    /** 搜索结果块的站点；出错时 content 是单个 error 对象，当没搜到 */
    private static List<SearchEvent.Source> searchResults(JSONObject block) {
        List<SearchEvent.Source> sources = new ArrayList<>();
        if (block.get("content") instanceof JSONArray results) {
            for (int i = 0; i < results.size(); i++) {
                JSONObject r = results.getJSONObject(i);
                sources.add(new SearchEvent.Source(r.getString("url"), r.getString("title")));
            }
        }
        return sources;
    }

    /** usage：input 三项先取 message_delta 的、没带再取 message_start 的，output 取 message_delta 的；服务端搜索次数记进观测 */
    private ChatResponseMetadata deltaMetadata(JSONObject usage, State state) {
        ChatResponseMetadata.Builder metadata = metadata().id(state.messageId);
        Integer input = usageField(usage, state.startUsage, "input_tokens");
        Integer output = usage == null ? null : usage.getInteger("output_tokens");
        if (input != null || output != null) {
            // input_tokens 只算没命中缓存的那部分，命中的和写入的各自单列，三项加起来才是整段 prompt
            int cacheRead = orZero(usageField(usage, state.startUsage, "cache_read_input_tokens"));
            int cacheWrite = orZero(usageField(usage, state.startUsage, "cache_creation_input_tokens"));
            int in = orZero(input) + cacheRead + cacheWrite;
            int out = orZero(output);
            metadata.usage(new DefaultUsage(in, out, in + out));
            if (cacheRead > 0 || cacheWrite > 0) {
                log.info("[Anthropic] {} 缓存命中{} 写入{} 未命中{}", model, cacheRead, cacheWrite, orZero(input));
            }
        }
        JSONObject serverTools = usage == null ? null : usage.getJSONObject("server_tool_use");
        Integer searches = serverTools == null ? null : serverTools.getInteger("web_search_requests");
        if (searches != null && searches > 0) {
            metadata.keyValue("web_search_requests", searches);
            log.info("[Anthropic] {} 服务端搜索{}次", model, searches);
        }
        return metadata.build();
    }

    private static Integer usageField(JSONObject delta, JSONObject start, String field) {
        Integer value = delta == null ? null : delta.getInteger(field);
        return value != null || start == null ? value : start.getInteger(field);
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
