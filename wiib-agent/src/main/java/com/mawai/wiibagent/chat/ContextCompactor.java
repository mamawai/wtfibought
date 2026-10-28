package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.store.ChatHistoryService;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.llm.ContextUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 长对话整理：新一轮开始前，上一轮结束时的上下文用量达到窗口的 {@code triggerRatio}（默认 90%）就整理一次。
 * 只在两轮之间做，不在工具循环中间做。
 * <p>
 * 做法照 Claude Code 与 Anthropic 推荐的客户端压缩：整段历史原样发给主模型、末尾追加一条整理指令，
 * 系统提示词、工具、思考配置都和平时一样——请求前缀与上一轮相同，整段历史命中提示缓存。
 * <pre>
 * 整理前：[ ……很长的历史…… ]
 * 整理后：[ 摘要（用户消息） ] [ 上一轮的提问原文 ] [ 上一轮最终回答的纯文本 ]   ← 新问题接在后面
 * </pre>
 * 以前的思考块、搜索密文都不再回放，保留的那一轮也只有文本，与 preserved thinking 兼容。
 * 多留上一轮原文：聊天里的追问几乎都指着上一个回答，里面的点位数字照抄才不走样。
 */
@Slf4j
@Component
public class ContextCompactor {

    private final ChatHistoryService chatHistoryService;
    private final PromptCatalog prompts;
    private final double triggerRatio;

    public ContextCompactor(ChatHistoryService chatHistoryService, PromptCatalog prompts,
                            @Value("${agent.workbench.compact-trigger-ratio:0.9}") double triggerRatio) {
        this.chatHistoryService = chatHistoryService;
        this.prompts = prompts;
        this.triggerRatio = triggerRatio;
    }

    /** 上一轮结束时的用量到没到整理线；没有实测值（老会话、上游不报用量）按字数估 */
    public boolean shouldCompact(ChatAgentFactory.Leaves leaves, String sessionId, List<Message> history) {
        if (history.isEmpty()) {
            return false;
        }
        Long measured = chatHistoryService.lastContextTokens(sessionId);
        long tokens = measured != null ? measured : ContextUsage.estimate(history);
        return tokens >= leaves.contextWindow() * triggerRatio;
    }

    /** 整理一次；失败（报错、模型没给出摘要文本）返回 empty，调用方沿用原历史，下一轮再试 */
    public Optional<List<Message>> compact(ChatAgentFactory.Leaves leaves, List<Message> history) {
        AgentLang lang = leaves.lang();
        List<Message> request = new ArrayList<>(history);
        request.add(new UserMessage(prompts.get(lang, "chat.compact.instruction")));
        String summary;
        try {
            summary = leaves.mainClient().execute(request).getResult().getOutput().getText();
        } catch (RuntimeException e) {
            log.warn("[Compact] 整理失败，沿用原历史 messages={}", history.size(), e);
            return Optional.empty();
        }
        if (summary == null || summary.isBlank()) {
            log.warn("[Compact] 模型没给出摘要文本（多半去调了工具），沿用原历史 messages={}", history.size());
            return Optional.empty();
        }
        List<Message> compacted = new ArrayList<>();
        compacted.add(new UserMessage(prompts.get(lang, "chat.compact.preamble") + "\n\n" + summary.strip()));
        compacted.addAll(lastExchange(history));
        log.info("[Compact] 整理完成 {} 条 → {} 条，摘要 {} 字", history.size(), compacted.size(), summary.length());
        return Optional.of(compacted);
    }

    /**
     * 上一轮的提问原文（带轮起始标记的那条）+ 它之后最后一条最终回答的纯文本；找不到就空。
     * 回答重新造一条不带 metadata 的：原始内容块（思考块、搜索密文）不跟过来
     */
    List<Message> lastExchange(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (!isTurnQuestion(history.get(i))) {
                continue;
            }
            for (int j = history.size() - 1; j > i; j--) {
                if (history.get(j) instanceof AssistantMessage answer && !answer.hasToolCalls()
                        && answer.getText() != null && !answer.getText().isBlank()) {
                    return List.of(new UserMessage(history.get(i).getText()), new AssistantMessage(answer.getText()));
                }
            }
            return List.of();
        }
        return List.of();
    }

    /** 带轮起始标记的用户消息（拼法见 ChatTurnStreamer）；认全部语言的标记，切语言后旧轮还是旧语言 */
    private boolean isTurnQuestion(Message message) {
        if (!(message instanceof UserMessage) || message.getText() == null) {
            return false;
        }
        for (AgentLang lang : AgentLang.values()) {
            if (message.getText().startsWith(prompts.get(lang, "chat.turn.timePrefix"))) {
                return true;
            }
        }
        return false;
    }
}
