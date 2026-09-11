package com.mawai.wiibagent.chat.tools;

import com.mawai.wiibagent.llm.ReactLoop;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 工具执行期的会话号传递：{@link ReactLoop} 执行工具时把会话号放进 Spring AI 的 {@link ToolContext}，
 * 工具方法声明一个 ToolContext 参数就读得到（不进 schema，模型看不见也填不了）。
 * <p>
 * 调用方把会话号传给 {@code ReactLoop.run}（见 ChatTurnRunner.streamSummarizer），工具方法体用 {@link #sessionId} 读。
 * 不在循环里跑（单测直接调方法）时没带这个键，读到 null。
 */
public final class ToolRunContext {

    /** ToolContext 里的会话号键 */
    public static final String SESSION_KEY = ReactLoop.SESSION_KEY;

    private ToolRunContext() {
    }

    /** 工具方法体内读当前会话号；没带就是 null */
    public static String sessionId(ToolContext context) {
        return (String) context.getContext().get(SESSION_KEY);
    }
}
