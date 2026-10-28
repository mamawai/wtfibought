package com.mawai.wiibagent.chat.store;

import com.mawai.wiibagent.mapper.WorkbenchChatContextMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * 会话模型侧上下文的存取口：{@code workbench_chat_context} 表一会话一行，
 * 整体替换，没有增量语义。对话链路只经这里读写模型上下文。
 * <p>
 * 存的是每轮结束时主 agent 的最终 messages——tool_call/tool_response 配对、子运行报告、
 * 整理上下文留下的摘要都在里面。下一轮 {@link #load} 出来直接当起跑历史。
 * <p>
 * 序列化经 {@link ChatContextCodec}：读兼容老的对象流格式，写裸 JSON。
 * <p>
 * 读失败往上抛：当成空历史跑完一轮，会拿这一轮整体覆盖掉整段上下文。写、删失败只记日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatContextStore {

    private final WorkbenchChatContextMapper contextMapper;

    /**
     * 一个会话的模型上下文。
     *
     * @param contextTokens 这份上下文多大（token）：正常作答结束时记下的读数；中断、出错、回退过的为 null，用时按字数估
     * @param prefix        这份历史是在哪套系统提示词与工具下跑出来的（{@link com.mawai.wiibagent.llm.LlmClient#fingerprint}），老数据为 null
     */
    public record State(List<Message> messages, Long contextTokens, String prefix) {
        public static final State EMPTY = new State(List.of(), null, null);
    }

    /** 取会话上下文；没有行（新会话）返回 {@link State#EMPTY} */
    public State load(String sessionId) {
        List<byte[]> rows = contextMapper.selectState(sessionId);
        if (rows.isEmpty()) {
            return State.EMPTY;
        }
        try {
            return ChatContextCodec.readState(rows.getFirst());
        } catch (IOException e) {
            throw new UncheckedIOException("会话上下文解不开 sessionId=" + sessionId, e);
        }
    }

    /**
     * 整体覆盖会话上下文。失败只记日志不抛：调用点在答案已经流给用户之后，
     * 抛上去救不回本轮，只会把成功的回答标成失败；代价是下一轮丢这轮的上下文。
     */
    public void save(String sessionId, long userId, State state) {
        try {
            byte[] bytes = ChatContextCodec.write(state);
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
