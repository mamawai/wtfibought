package com.mawai.wiibagent.chat.store;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.WorkbenchChatMessage;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.llm.SearchEvent;
import com.mawai.wiibagent.llm.UsageTrackingChatModel;
import com.mawai.wiibagent.mapper.WorkbenchChatMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 工作台对话历史（展示用）：user/assistant 消息按会话落库，支撑历史会话列表与回看。
 * 续聊上下文不靠它——那是 {@link ChatContextStore} 的事（存的是模型侧完整 messages）；
 * 本表只为前端展示，所以 agent 调度/HITL 过程事件不存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatHistoryService {

    private static final int TITLE_MAX = 40;

    private final WorkbenchChatMessageMapper messageMapper;
    /** 只为认出补答行与续跑指令：它们的正文是词表文案，判定见 {@link ChatRowKind} */
    private final PromptCatalog prompts;
    /** 会话列表的标题兜底文案（跟当次请求的界面语言） */
    private final MessageCatalog messages;

    /** 会话摘要：标题=首条用户消息截断。 */
    public record SessionSummary(String sessionId, String title, int messageCount, long lastAt) {}

    /**
     * 一轮的读数，只挂在 assistant 行上（user 行与历史老数据为 null）。
     * token 三项 null = 上游端点没报 usage 或本轮账不可信，<b>不是 0</b>——展示层必须区分这两者。
     */
    public record TurnMeta(String modelLabel, Integer modelCalls, Long promptTokens,
                           Long completionTokens, Long totalTokens, Integer latencyMs) {

        /** 正常轮：账本快照直接入账 */
        public static TurnMeta of(String modelLabel, UsageTrackingChatModel.UsageSnapshot usage, int latencyMs) {
            return new TurnMeta(modelLabel, usage.modelCalls(), usage.promptTokens(),
                    usage.completionTokens(), usage.totalTokens(), latencyMs);
        }

        /** 只报耗时：账本里混着别轮的账时用它，判据见 {@code ChatYieldCoordinator.hasInFlightExperts} */
        public static TurnMeta latencyOnly(String modelLabel, int latencyMs) {
            return new TurnMeta(modelLabel, null, null, null, null, latencyMs);
        }
    }

    /**
     * kind=特殊行的码（见 {@link ChatRowKind}），普通行为 null，前端认码不认文案。
     * sources=这一轮联网搜索的来源（答案底部展示），没搜过或老数据为 null；不属于读数，单独一列
     */
    public record ChatMessage(long id, String role, String content, long createdAt, TurnMeta meta, String kind,
                              List<SearchEvent.Source> sources) {}

    /** 追加一条消息。历史是增益不是主链，失败只记日志不打断对话。 */
    public boolean append(String sessionId, long userId, String role, String content) {
        return append(sessionId, userId, role, content, null, null);
    }

    /**
     * 带本轮读数与来源的追加（assistant 行专用；meta/sources 为空即退化成普通追加）。
     * 返回是否真落了库：重新生成靠它决定敢不敢删旧答案——没落上还删，这个提问就一条答案都不剩了。
     */
    public boolean append(String sessionId, long userId, String role, String content, TurnMeta meta,
                          List<SearchEvent.Source> sources) {
        if (content == null || content.isBlank()) return false;
        try {
            WorkbenchChatMessage row = new WorkbenchChatMessage();
            row.setSessionId(sessionId);
            row.setUserId(userId);
            row.setRole(role);
            row.setContent(content);
            if (meta != null) {
                row.setModelLabel(meta.modelLabel());
                row.setModelCalls(meta.modelCalls());
                row.setPromptTokens(meta.promptTokens());
                row.setCompletionTokens(meta.completionTokens());
                row.setTotalTokens(meta.totalTokens());
                row.setLatencyMs(meta.latencyMs());
            }
            if (sources != null && !sources.isEmpty()) {
                row.setSources(SearchEvent.Source.toJson(sources).toJSONString());
            }
            // createdAt 由全局 MetaObjectHandler 填，不手塞
            messageMapper.insert(row);
            return true;
        } catch (Exception e) {
            log.warn("[ChatHistory] 写入失败 sessionId={}", sessionId, e);
            return false;
        }
    }

    /** 我的会话列表，按最后活跃倒序。标题在这截——省略号规则属展示逻辑，不进 SQL。 */
    public List<SessionSummary> sessions(long userId, int limit) {
        return messageMapper.selectSessions(userId, limit).stream()
                .map(row -> new SessionSummary(
                        row.getSessionId(),
                        truncate(row.getTitle()),
                        row.getMessageCount(),
                        toEpochMillis(row.getLastAt())))
                .toList();
    }

    /** 我的全部会话号（清空全部会话时逐个删要用）。 */
    public List<String> sessionIds(long userId) {
        return messageMapper.selectSessionIds(userId);
    }

    /** 删除整个会话的展示记录，调用方已做归属校验。 */
    public void deleteSession(String sessionId) {
        messageMapper.delete(new LambdaQueryWrapper<WorkbenchChatMessage>()
                .eq(WorkbenchChatMessage::getSessionId, sessionId));
    }

    /** 删一条消息（重新生成时抹掉旧答案那行）。调用方已做归属校验。 */
    public void deleteMessage(long id) {
        messageMapper.deleteById(id);
    }

    /** 单会话全部消息（按 id 升序＝发生顺序），调用方已做归属校验。 */
    public List<ChatMessage> messages(String sessionId) {
        return messageMapper.selectList(new LambdaQueryWrapper<WorkbenchChatMessage>()
                        .eq(WorkbenchChatMessage::getSessionId, sessionId)
                        .orderByAsc(WorkbenchChatMessage::getId))
                .stream()
                .map(row -> new ChatMessage(row.getId(), row.getRole(), row.getContent(),
                        toEpochMillis(row.getCreatedAt()), metaOf(row),
                        ChatRowKind.of(row.getRole(), row.getContent(), prompts), sourcesOf(row)))
                .toList();
    }

    /** 没搜过的行（user 行、老数据）给 null，不给空列表 */
    private static List<SearchEvent.Source> sourcesOf(WorkbenchChatMessage row) {
        return row.getSources() == null ? null : SearchEvent.Source.fromJson(JSON.parseArray(row.getSources()));
    }

    /** 没落过读数的行（user 行、老数据）给 null 而不是空壳，省得前端再判一层"有对象但全空" */
    private static TurnMeta metaOf(WorkbenchChatMessage row) {
        if (row.getModelLabel() == null && row.getLatencyMs() == null) {
            return null;
        }
        return new TurnMeta(row.getModelLabel(), row.getModelCalls(), row.getPromptTokens(),
                row.getCompletionTokens(), row.getTotalTokens(), row.getLatencyMs());
    }

    private String truncate(String s) {
        if (s == null || s.isBlank()) return messages.get("agent.chat.untitled");
        return s.length() > TITLE_MAX ? s.substring(0, TITLE_MAX) + "…" : s;
    }

    /**
     * 前端契约是毫秒时间戳。列是不带时区的 timestamp，按本机时区还原成 epoch——
     * 这跟原先 {@code ResultSet.getTimestamp().getTime()} 的口径完全一致（JDBC 也是按 JVM 默认时区解释钟面时间）。
     */
    private static long toEpochMillis(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
