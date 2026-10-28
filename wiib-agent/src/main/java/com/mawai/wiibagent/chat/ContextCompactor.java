package com.mawai.wiibagent.chat;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.llm.SseChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 长对话整理：新一轮开始前，上下文用量达到窗口的 {@code triggerRatio}（默认 90%）就整理一次，只在两轮之间做。
 * <p>
 * 请求是整段历史原样加一条整理指令，走主 agent 的 client（系统提示词、工具、思考配置都和平时一样），流式发、点停止就掐断。
 * 整段发出去失败，去掉原始内容、截短工具回执再试一次。
 * <pre>
 * 整理前：[ ……很长的历史…… ]
 * 整理后：[ 摘要（用户消息） ] [ 上一问起的对话，只留文本 ]   ← 新问题接在后面
 * </pre>
 */
@Slf4j
@Component
public class ContextCompactor {

    /** 精简重发时每条工具回执留的字数 */
    private static final int SLIM_TOOL_RESULT_CHARS = 2000;

    private final PromptCatalog prompts;
    private final double triggerRatio;

    public ContextCompactor(PromptCatalog prompts,
                            @Value("${agent.workbench.compact-trigger-ratio:0.9}") double triggerRatio) {
        this.prompts = prompts;
        this.triggerRatio = triggerRatio;
    }

    /**
     * 上下文到没到整理线。
     *
     * @param contextTokens 随上下文存的用量读数，没有（老会话、中断过、回退过）就按字数估
     */
    public boolean shouldCompact(ChatAgentFactory.Leaves leaves, Long contextTokens, List<Message> history) {
        long tokens = contextTokens != null ? contextTokens : leaves.mainClient().estimateTokens(history);
        return tokens >= leaves.contextWindow() * triggerRatio;
    }

    /** 整理一次；拿不到摘要（两次都失败、被中断、模型去调了工具）返回 empty，调用方沿用原历史 */
    public Optional<List<Message>> compact(ChatAgentFactory.Leaves leaves, List<Message> history,
                                           CompletableFuture<Void> cancel) {
        List<Message> request = new ArrayList<>(history);
        request.add(new UserMessage(prompts.get(leaves.lang(), "chat.compact.instruction")));
        Optional<String> summary;
        try {
            summary = summarize(leaves, request, cancel);
        } catch (RuntimeException e) {
            log.warn("[Compact] 整理请求失败，去掉原始内容、截短工具回执再试一次 messages={}", history.size(), e);
            try {
                summary = cancelled(cancel) ? Optional.empty() : summarize(leaves, slim(request), cancel);
            } catch (RuntimeException again) {
                log.warn("[Compact] 精简后还是失败，沿用原历史 messages={}", history.size(), again);
                return Optional.empty();
            }
        }
        return summary.map(text -> {
            List<Message> compacted = new ArrayList<>();
            compacted.add(new UserMessage(prompts.get(leaves.lang(), "chat.compact.preamble") + "\n\n" + text));
            compacted.addAll(recent(history));
            log.info("[Compact] 整理完成 {} 条 → {} 条，摘要 {} 字", history.size(), compacted.size(), text.length());
            return compacted;
        });
    }

    /** 发一次整理请求拿摘要正文；被中断、模型没给文本或去调了工具返回 empty，请求本身失败往上抛 */
    private Optional<String> summarize(ChatAgentFactory.Leaves leaves, List<Message> request,
                                       CompletableFuture<Void> cancel) {
        StringBuilder text = new StringBuilder();
        AtomicBoolean toolCalls = new AtomicBoolean();
        leaves.mainClient().streamingExecute(request, cancel)
                .filter(frame -> !frame.getResults().isEmpty())
                .doOnNext(frame -> {
                    AssistantMessage output = frame.getResult().getOutput();
                    if (output.hasToolCalls()) {
                        toolCalls.set(true);
                    }
                    if (output.getText() != null) {
                        text.append(output.getText());
                    }
                })
                .blockLast();
        if (cancelled(cancel)) {
            return Optional.empty();
        }
        if (toolCalls.get() || text.toString().isBlank()) {
            log.warn("[Compact] 模型没交出摘要（没给文本或去调了工具），沿用原历史");
            return Optional.empty();
        }
        return Optional.of(text.toString().strip());
    }

    /**
     * 上一问（带轮起始标记的那条）起到末尾的对话：用户消息原样（之后到的子运行报告、唤醒指令都在内），
     * 不带工具调用的回答只留文本，工具过程丢掉；没有带标记的提问就空
     */
    List<Message> recent(List<Message> history) {
        int start = history.size() - 1;
        while (start >= 0 && ChatTurnStreamer.turnLang(history.get(start), prompts).isEmpty()) {
            start--;
        }
        if (start < 0) {
            return List.of();
        }
        List<Message> recent = new ArrayList<>();
        for (Message message : history.subList(start, history.size())) {
            if (message instanceof UserMessage) {
                recent.add(message);
            } else if (message instanceof AssistantMessage answer && !answer.hasToolCalls()
                    && answer.getText() != null && !answer.getText().isBlank()) {
                recent.add(new AssistantMessage(answer.getText()));
            }
        }
        return recent;
    }

    /** 精简版请求：assistant 去掉原始内容，工具回执截到 {@link #SLIM_TOOL_RESULT_CHARS} 字 */
    private static List<Message> slim(List<Message> request) {
        return request.stream().map(message -> switch (message) {
            case AssistantMessage assistant -> SseChatModel.withoutRaw(assistant);
            case ToolResponseMessage tool -> ToolResponseMessage.builder().responses(tool.getResponses().stream()
                    .map(r -> new ToolResponseMessage.ToolResponse(r.id(), r.name(), clip(r.responseData())))
                    .toList()).build();
            default -> message;
        }).toList();
    }

    private static String clip(String text) {
        return text.length() <= SLIM_TOOL_RESULT_CHARS ? text : text.substring(0, SLIM_TOOL_RESULT_CHARS) + "…";
    }

    private static boolean cancelled(CompletableFuture<Void> cancel) {
        return cancel != null && cancel.isDone();
    }
}
