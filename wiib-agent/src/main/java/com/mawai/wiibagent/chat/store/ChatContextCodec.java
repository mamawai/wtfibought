package com.mawai.wiibagent.chat.store;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

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
 * 值为 null 的键不写（text 为 null 就没有 text 键，老行里写的是 {@code "text":null}，读起来一样）。
 * 读回时 metadata 整个交给 builder，构造器自己会再 put 一次 messageType 枚举。
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
        ArrayNode list = MAPPER.createArrayNode();
        for (Message message : messages) {
            list.add(toJson(message));
        }
        return MAPPER.writeValueAsBytes(MAPPER.createObjectNode().set("messages", list));
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
        JsonNode list = MAPPER.readTree(json).get("messages");
        if (list == null || list.isNull()) {
            return List.of();
        }
        List<Message> messages = new ArrayList<>(list.size());
        for (JsonNode item : list) {
            messages.add(fromJson(item));
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

    private static ObjectNode toJson(Message message) {
        ObjectNode o = MAPPER.createObjectNode();
        switch (message) {
            case UserMessage user -> {
                o.put(TYPE_KEY, USER).put("text", user.getText());
                List<Media> media = user.getMedia();
                if (!media.isEmpty()) {
                    ArrayNode list = MAPPER.createArrayNode();
                    for (Media m : media) {
                        MimeType mime = m.getMimeType();
                        list.add(MAPPER.createObjectNode()
                                .put(TYPE_KEY, MEDIA_TYPE)
                                .put("id", m.getId())
                                .put("name", m.getName())
                                .put("mimetype", mime.getType() + "/" + mime.getSubtype())
                                .put("data", Base64.getEncoder().encodeToString(m.getDataAsByteArray())));
                    }
                    o.set("media", list);
                }
            }
            case AssistantMessage assistant -> {
                o.put(TYPE_KEY, ASSISTANT).put("text", assistant.getText());
                ArrayNode list = MAPPER.createArrayNode();
                for (AssistantMessage.ToolCall c : assistant.getToolCalls()) {
                    list.add(MAPPER.createObjectNode()
                            .put(TYPE_KEY, TOOL_CALL_TYPE)
                            .put("id", c.id())
                            .put("type", c.type())
                            .put("name", c.name())
                            .put("arguments", c.arguments()));
                }
                o.set("toolCalls", list);
            }
            case ToolResponseMessage tool -> {
                o.put(TYPE_KEY, TOOL);
                ArrayNode list = MAPPER.createArrayNode();
                for (ToolResponseMessage.ToolResponse r : tool.getResponses()) {
                    list.add(MAPPER.createObjectNode()
                            .put(TYPE_KEY, TOOL_RESPONSE_TYPE)
                            .put("id", r.id())
                            .put("name", r.name())
                            .put("responseData", r.responseData()));
                }
                o.set("responses", list);
            }
            case SystemMessage system -> o.put(TYPE_KEY, SYSTEM).put("text", system.getText());
            default -> throw new IllegalStateException(
                    "会话上下文写不了这种消息 class=" + message.getClass().getName());
        }
        o.set("metadata", MAPPER.valueToTree(message.getMetadata()));
        return o;
    }

    private static Message fromJson(JsonNode o) {
        String type = o.path(TYPE_KEY).asString(null);
        Map<String, Object> metadata = metadata(o);
        return switch (type) {
            case USER -> UserMessage.builder()
                    .text(o.path("text").asString(null)).metadata(metadata).media(media(o)).build();
            case ASSISTANT -> AssistantMessage.builder()
                    .content(o.path("text").asString(null)).properties(metadata).toolCalls(toolCalls(o)).build();
            case TOOL -> ToolResponseMessage.builder()
                    .responses(toolResponses(o)).metadata(metadata).build();
            case SYSTEM -> SystemMessage.builder()
                    .text(o.path("text").asString(null)).metadata(metadata).build();
            case null, default -> throw new IllegalStateException("会话上下文有不认识的消息类型 @type=" + type);
        };
    }

    /** 读回成可改的 Map：小数是 BigDecimal、整数是 Integer/Long */
    private static Map<String, Object> metadata(JsonNode o) {
        JsonNode metadata = o.get("metadata");
        return metadata == null || metadata.isNull()
                ? Map.of() : MAPPER.convertValue(metadata, new TypeReference<Map<String, Object>>() {
        });
    }

    private static List<Media> media(JsonNode o) {
        JsonNode list = o.path("media");
        if (list.isEmpty()) {
            return List.of();
        }
        List<Media> media = new ArrayList<>(list.size());
        for (JsonNode m : list) {
            media.add(Media.builder()
                    .id(m.path("id").asString(null))
                    .name(m.path("name").asString(null))
                    .mimeType(MimeType.valueOf(m.path("mimetype").asString(null)))
                    .data(Base64.getDecoder().decode(m.path("data").asString(null)))
                    .build());
        }
        return media;
    }

    private static List<AssistantMessage.ToolCall> toolCalls(JsonNode o) {
        JsonNode list = o.path("toolCalls");
        if (list.isEmpty()) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> calls = new ArrayList<>(list.size());
        for (JsonNode c : list) {
            calls.add(new AssistantMessage.ToolCall(c.path("id").asString(null), c.path("type").asString(null),
                    c.path("name").asString(null), c.path("arguments").asString(null)));
        }
        return calls;
    }

    private static List<ToolResponseMessage.ToolResponse> toolResponses(JsonNode o) {
        JsonNode list = o.path("responses");
        if (list.isEmpty()) {
            return List.of();
        }
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(list.size());
        for (JsonNode r : list) {
            responses.add(new ToolResponseMessage.ToolResponse(r.path("id").asString(null),
                    r.path("name").asString(null), r.path("responseData").asString(null)));
        }
        return responses;
    }

}
