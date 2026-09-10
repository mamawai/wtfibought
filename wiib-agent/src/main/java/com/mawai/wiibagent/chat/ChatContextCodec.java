package com.mawai.wiibagent.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * {@code workbench_chat_context.state} 那列字节的编解码：Spring AI 的 Message 列表 ⇄ 字节。
 * <p>
 * 读认两种：老行是 Java 对象流的壳（{@code AC ED} 开头，块数据里一个 int 长度 + JSON 的 UTF-8 字节）包着 JSON，
 * 新行整段就是裸 JSON。写只出裸 JSON。一个会话一行、每轮结束整体覆盖，所以老行下一轮写回去自然就成新格式了。
 * <p>
 * JSON 形状（{@code @type} 认消息类型）：
 * <pre>
 * {"messages":[
 *   {"@type":"USER","text":"…","media":[{…,"mimetype":"image/png","data":"&lt;base64&gt;"}],"metadata":{…}},
 *   {"@type":"ASSISTANT","text":"…","toolCalls":[{"id":"…","type":"function","name":"…","arguments":"{…}"}],"metadata":{…}},
 *   {"@type":"TOOL","responses":[{"id":"…","name":"…","responseData":"…"}],"metadata":{…}},
 *   {"@type":"SYSTEM","text":"…","metadata":{…}}]}
 * </pre>
 * metadata 恒写整个 map（里头的 messageType 枚举写成名字串）；media 只在非空时写，toolCalls 恒写；
 * text 为 null 照写 null。读回时 metadata 整个交给 builder，构造器自己会再 put 一次 messageType 枚举。
 */
public final class ChatContextCodec {

    private static final String TYPE_KEY = "@type";
    private static final String USER = "USER";
    private static final String ASSISTANT = "ASSISTANT";
    private static final String TOOL = "TOOL";
    private static final String SYSTEM = "SYSTEM";
    /** 老行里 media/toolCall/toolResponse 带的是全限定类名，写的时候照抄，读的时候按字段取、不认这个名 */
    private static final String MEDIA_TYPE = "org.springframework.ai.content.Media";
    private static final String TOOL_CALL_TYPE = "org.springframework.ai.chat.messages.AssistantMessage$ToolCall";
    private static final String TOOL_RESPONSE_TYPE = "org.springframework.ai.chat.messages.ToolResponseMessage$ToolResponse";

    private ChatContextCodec() {
    }

    /** 写成裸 JSON 的 UTF-8 字节 */
    public static byte[] write(List<Message> messages) {
        JSONArray list = new JSONArray(messages.size());
        for (Message message : messages) {
            list.add(toJson(message));
        }
        JSONObject root = new JSONObject().fluentPut("messages", list);
        return JSON.toJSONString(root, JSONWriter.Feature.WriteMapNullValue).getBytes(StandardCharsets.UTF_8);
    }

    /** 读：AC ED 开头的先从对象流壳里剥出 JSON，否则整段就是 JSON */
    public static List<Message> read(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return List.of();
        }
        String json = bytes.length >= 2 && bytes[0] == (byte) 0xAC && bytes[1] == (byte) 0xED
                ? unwrap(bytes)
                : new String(bytes, StandardCharsets.UTF_8);
        if (json.isBlank()) {
            return List.of();
        }
        JSONObject root = JSON.parseObject(json);
        JSONArray list = root == null ? null : root.getJSONArray("messages");
        if (list == null) {
            return List.of();
        }
        List<Message> messages = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            messages.add(fromJson(list.getJSONObject(i)));
        }
        return messages;
    }

    /** 对象流壳里只有一段块数据：int 长度 + UTF-8 字节，没有任何对象，不会触发类加载 */
    private static String unwrap(byte[] bytes) throws IOException {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            int len = in.readInt();
            if (len == 0) {
                return "";
            }
            byte[] data = new byte[len];
            in.readFully(data);
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private static JSONObject toJson(Message message) {
        JSONObject o = new JSONObject();
        switch (message) {
            case UserMessage user -> {
                o.fluentPut(TYPE_KEY, USER).fluentPut("text", user.getText());
                List<Media> media = user.getMedia();
                if (!media.isEmpty()) {
                    JSONArray list = new JSONArray(media.size());
                    for (Media m : media) {
                        MimeType mime = m.getMimeType();
                        list.add(new JSONObject()
                                .fluentPut(TYPE_KEY, MEDIA_TYPE)
                                .fluentPut("id", m.getId())
                                .fluentPut("name", m.getName())
                                .fluentPut("mimetype", mime.getType() + "/" + mime.getSubtype())
                                .fluentPut("data", Base64.getEncoder().encodeToString(m.getDataAsByteArray())));
                    }
                    o.put("media", list);
                }
            }
            case AssistantMessage assistant -> {
                o.fluentPut(TYPE_KEY, ASSISTANT).fluentPut("text", assistant.getText());
                JSONArray list = new JSONArray();
                for (AssistantMessage.ToolCall c : assistant.getToolCalls()) {
                    list.add(new JSONObject()
                            .fluentPut(TYPE_KEY, TOOL_CALL_TYPE)
                            .fluentPut("id", c.id())
                            .fluentPut("type", c.type())
                            .fluentPut("name", c.name())
                            .fluentPut("arguments", c.arguments()));
                }
                o.put("toolCalls", list);
            }
            case ToolResponseMessage tool -> {
                o.put(TYPE_KEY, TOOL);
                JSONArray list = new JSONArray();
                for (ToolResponseMessage.ToolResponse r : tool.getResponses()) {
                    list.add(new JSONObject()
                            .fluentPut(TYPE_KEY, TOOL_RESPONSE_TYPE)
                            .fluentPut("id", r.id())
                            .fluentPut("name", r.name())
                            .fluentPut("responseData", r.responseData()));
                }
                o.put("responses", list);
            }
            case SystemMessage system -> o.fluentPut(TYPE_KEY, SYSTEM).fluentPut("text", system.getText());
            default -> throw new IllegalStateException(
                    "会话上下文写不了这种消息 class=" + message.getClass().getName());
        }
        o.put("metadata", new JSONObject(message.getMetadata()));
        return o;
    }

    private static Message fromJson(JSONObject o) {
        String type = o.getString(TYPE_KEY);
        Map<String, Object> metadata = metadata(o);
        return switch (type) {
            case USER -> UserMessage.builder()
                    .text(o.getString("text")).metadata(metadata).media(media(o)).build();
            case ASSISTANT -> AssistantMessage.builder()
                    .content(o.getString("text")).properties(metadata).toolCalls(toolCalls(o)).build();
            case TOOL -> ToolResponseMessage.builder()
                    .responses(toolResponses(o)).metadata(metadata).build();
            case SYSTEM -> SystemMessage.builder()
                    .text(o.getString("text")).metadata(metadata).build();
            case null, default -> throw new IllegalStateException("会话上下文有不认识的消息类型 @type=" + type);
        };
    }

    private static Map<String, Object> metadata(JSONObject o) {
        JSONObject metadata = o.getJSONObject("metadata");
        return metadata == null ? Map.of() : metadata;
    }

    private static List<Media> media(JSONObject o) {
        JSONArray list = o.getJSONArray("media");
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<Media> media = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            JSONObject m = list.getJSONObject(i);
            media.add(Media.builder()
                    .id(m.getString("id"))
                    .name(m.getString("name"))
                    .mimeType(MimeType.valueOf(m.getString("mimetype")))
                    .data(Base64.getDecoder().decode(m.getString("data")))
                    .build());
        }
        return media;
    }

    private static List<AssistantMessage.ToolCall> toolCalls(JSONObject o) {
        JSONArray list = o.getJSONArray("toolCalls");
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> calls = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            JSONObject c = list.getJSONObject(i);
            calls.add(new AssistantMessage.ToolCall(c.getString("id"), c.getString("type"),
                    c.getString("name"), c.getString("arguments")));
        }
        return calls;
    }

    private static List<ToolResponseMessage.ToolResponse> toolResponses(JSONObject o) {
        JSONArray list = o.getJSONArray("responses");
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            JSONObject r = list.getJSONObject(i);
            responses.add(new ToolResponseMessage.ToolResponse(r.getString("id"),
                    r.getString("name"), r.getString("responseData")));
        }
        return responses;
    }
}
