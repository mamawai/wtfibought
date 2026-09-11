package com.mawai.wiibagent.chat.store;

import com.mawai.wiibagent.mapper.WorkbenchChatContextMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 会话模型侧上下文的存取口：{@code workbench_chat_context} 表一会话一行，
 * 整体替换，没有增量语义。对话链路只经这里读写模型上下文。
 * <p>
 * 存的是每轮结束时 summarizer 叶子的最终 messages——专家结论、tool_call/tool_response 配对、
 * 压缩后的摘要状态都在里面。下一轮 {@link #load} 出来直接当起跑历史。
 * <p>
 * 序列化经 {@link ChatContextCodec}：读兼容老的对象流格式，写裸 JSON。
 * <p>
 * 三个方法一律不向上抛：读失败当这轮没有历史，写、删失败只记日志。上下文存取不中断对话。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatContextStore {

    private final WorkbenchChatContextMapper contextMapper;

    /**
     * 取会话历史。无行（新会话）返回空；读失败也返回空——降级成"这轮没有上下文"，
     */
    public List<Message> load(String sessionId) {
        List<byte[]> rows;
        try {
            rows = contextMapper.selectState(sessionId);
        } catch (Exception e) {
            log.error("[ChatContext] 上下文读取失败，本轮无历史续跑 sessionId={}", sessionId, e);
            return List.of();
        }
        if (rows.isEmpty()) {
            return List.of();
        }
        byte[] bytes = rows.getFirst();
        try {
            return ChatContextCodec.read(bytes);
        } catch (Exception e) {
            log.error("[ChatContext] 上下文反序列化失败，本轮无历史续跑 sessionId={}", sessionId, e);
            return List.of();
        }
    }

    /**
     * 整体覆盖会话历史。失败只记日志不抛：调用点在答案已经流给用户之后，
     * 抛上去救不回本轮，只会把成功的回答标成失败；代价是下一轮丢这轮的上下文。
     */
    public void save(String sessionId, long userId, List<Message> messages) {
        try {
            byte[] bytes = ChatContextCodec.write(messages);
            contextMapper.upsert(sessionId, userId, bytes);
        } catch (Exception e) {
            log.error("[ChatContext] 上下文落库失败，下一轮将丢失本轮历史 sessionId={}", sessionId, e);
        }
    }

    /** 删会话时清上下文。尽力清：失败只影响存储占用，不该让"列表里已删"的观感落空。 */
    public void purge(String sessionId) {
        try {
            contextMapper.deleteBySessionId(sessionId);
        } catch (Exception e) {
            log.warn("[ChatContext] 上下文删除失败 sessionId={} msg={}", sessionId, e.toString());
        }
    }
}
