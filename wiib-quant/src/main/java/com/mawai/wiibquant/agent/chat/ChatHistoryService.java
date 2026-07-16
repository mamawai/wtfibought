package com.mawai.wiibquant.agent.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;

/**
 * 工作台对话历史（展示用）：user/assistant 消息按会话落库，支撑历史会话列表与回看。
 * 续聊上下文不靠它——那是 PostgresSaver checkpoint 的事（threadId=sessionId 原样复用）；
 * 本表只为前端展示，所以 agent 调度/HITL 过程事件不存。
 */
@Slf4j
@Service
public class ChatHistoryService {

    private static final int TITLE_MAX = 40;

    private final JdbcTemplate jdbc;

    public ChatHistoryService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        // 启动幂等自建表（与 workbenchStore/PostgresSaver 同哲学，免手工跑 DDL；init.sql 同步留档）
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS workbench_chat_message (
                    id          BIGSERIAL PRIMARY KEY,
                    session_id  VARCHAR(80) NOT NULL,
                    user_id     BIGINT NOT NULL,
                    role        VARCHAR(10) NOT NULL,
                    content     TEXT NOT NULL,
                    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_wb_chat_session ON workbench_chat_message (session_id, id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_wb_chat_user ON workbench_chat_message (user_id, id DESC)");
    }

    /** 会话摘要：标题=首条用户消息截断。 */
    public record SessionSummary(String sessionId, String title, int messageCount, long lastAt) {}

    public record ChatMessage(String role, String content, long createdAt) {}

    /** 追加一条消息。历史是增益不是主链，失败只记日志不打断对话。 */
    public void append(String sessionId, long userId, String role, String content) {
        if (content == null || content.isBlank()) return;
        try {
            jdbc.update("INSERT INTO workbench_chat_message (session_id, user_id, role, content) VALUES (?, ?, ?, ?)",
                    sessionId, userId, role, content);
        } catch (Exception e) {
            log.warn("[ChatHistory] 写入失败 sessionId={}", sessionId, e);
        }
    }

    /** 我的会话列表，按最后活跃倒序。 */
    public List<SessionSummary> sessions(long userId, int limit) {
        return jdbc.query("""
                        SELECT m.session_id,
                               (SELECT f.content FROM workbench_chat_message f
                                 WHERE f.session_id = m.session_id AND f.role = 'user'
                                 ORDER BY f.id LIMIT 1) AS title,
                               COUNT(*) AS cnt,
                               MAX(m.created_at) AS last_at
                          FROM workbench_chat_message m
                         WHERE m.user_id = ?
                         GROUP BY m.session_id
                         ORDER BY last_at DESC
                         LIMIT ?""",
                (rs, i) -> new SessionSummary(
                        rs.getString("session_id"),
                        truncate(rs.getString("title")),
                        rs.getInt("cnt"),
                        rs.getTimestamp("last_at").getTime()),
                userId, limit);
    }

    /** 删除整个会话的展示记录，调用方已做归属校验。 */
    public void deleteSession(String sessionId) {
        jdbc.update("DELETE FROM workbench_chat_message WHERE session_id = ?", sessionId);
    }

    /** 单会话全部消息（升序），调用方已做归属校验。 */
    public List<ChatMessage> messages(String sessionId) {
        return jdbc.query("""
                        SELECT role, content, created_at FROM workbench_chat_message
                         WHERE session_id = ? ORDER BY id""",
                (rs, i) -> new ChatMessage(rs.getString(1), rs.getString(2), rs.getTimestamp(3).getTime()),
                sessionId);
    }

    private static String truncate(String s) {
        if (s == null || s.isBlank()) return "（无标题）";
        return s.length() > TITLE_MAX ? s.substring(0, TITLE_MAX) + "…" : s;
    }
}
