package com.mawai.wiibagent.llm;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 长对话压缩：模型调用前检查历史长度，超阈值就把老消息交给浅模型总结成一段，替换原文。
 * 不压缩上下文会一路涨到撞破模型窗口，届时直接报错。
 * <p>
 * {@link ReactLoop} 每次调模型前调一次，返回非空就整体替换历史，之后本轮都用压缩后的——
 * 一轮 ReAct 可能调用模型五到十次，若只作用于单次调用则每次都要重压，白烧浅模型的钱。
 * <p>
 * 压缩后的结构（借鉴 spring-ai-alibaba SummarizationHook）：
 * <pre>
 * [首条用户消息]  ← 原文保留，摘要再怎么压也不该丢掉"用户到底要什么"
 * [SystemMessage: 摘要（分段追加，老段落原样留着）]
 * [最近 N 条消息] ← 原文保留
 * </pre>
 * 三处针对本项目的改动：token 估算按中英文分别校准（框架一律 charCount/4，中文会低估约 4 倍）；
 * 摘要提示词与角色名跟用户语言走（{@code chat.compress.*}，英文指令压缩中文效果差，反过来也一样）；
 * 摘要按段落追加而不是被反复重压（摘要套摘要几轮下来早期事实就没了，且除首条用户消息外再无原文锚点可归因）。
 * <p>
 * 写按当前语言、认按全部语言：旧摘要是写入时那门语言落库的，所以 {@link #isSummary} 与
 * {@link #countSegments} 逐语言各认一遍。
 */
@Slf4j
public class ConversationSummarizer {

    /** 切点前后各扫这么多条，找是否有跨越切点的工具调用配对 */
    private static final int TOOL_PAIR_SEARCH_RANGE = 5;
    /** 单段工具内容进摘要输入的字数上限：深研判一次回包几百KB，不截断的话摘要输入自己就先爆了 */
    private static final int TOOL_TEXT_LIMIT = 1200;

    private final ChatModel summaryModel;
    private final int thresholdTokens;
    private final int messagesToKeep;
    private final PromptCatalog prompts;
    /** 摘要正文、段头、角色名都按它写；压缩器是建叶子时装上的，语言跟着叶子走 */
    private final AgentLang lang;

    public ConversationSummarizer(ChatModel summaryModel, int thresholdTokens, int messagesToKeep,
                                  PromptCatalog prompts, AgentLang lang) {
        this.summaryModel = summaryModel;
        this.thresholdTokens = thresholdTokens;
        this.messagesToKeep = messagesToKeep;
        this.prompts = prompts;
        this.lang = lang;
    }

    /** 空 = 这次不压（没到阈值 / 没有安全切点 / 没有新原文可压 / 压缩失败），调用方沿用原始历史 */
    public Optional<List<Message>> compress(List<Message> messages) {
        int tokens = estimateTokens(messages);
        if (tokens < thresholdTokens) {
            return Optional.empty();
        }
        int cutoff = findSafeCutoff(messages);
        if (cutoff <= 0) {
            log.warn("[Summarize] 找不到安全切点，跳过压缩 tokens={} messages={}", tokens, messages.size());
            return Optional.empty();
        }
        try {
            List<Message> compressed = rebuild(messages, cutoff);
            if (compressed.isEmpty()) {
                // 切点前只剩首条用户消息与老摘要：没有新原文可压，压了也是白烧浅模型的钱
                log.warn("[Summarize] 无新原文可压，跳过 tokens={} messages={}", tokens, messages.size());
                return Optional.empty();
            }
            log.info("[Summarize] 压缩 {} 条 → {} 条（原 ~{} tokens）", messages.size(), compressed.size(), tokens);
            return Optional.of(compressed);
        } catch (Exception e) {
            // 压缩失败不该打断对话：宁可带着长上下文继续，撞窗口是下一步的事
            log.warn("[Summarize] 压缩失败，沿用原始对话", e);
            return Optional.empty();
        }
    }

    /**
     * 这条是不是压缩产出的摘要。压缩后的形状是「首问 + 摘要 + 保留窗」，
     * 重新生成回退要靠它认出"队首那条 user 是压缩留下的首问、不是本轮提问"。
     */
    public static boolean isSummary(Message message, PromptCatalog prompts) {
        if (!(message instanceof SystemMessage system) || system.getText() == null) {
            return false;
        }
        // 认全部语言的前缀：认不出的旧摘要会被当普通历史再揉一遍，早期事实当场丢失
        for (AgentLang candidate : AgentLang.values()) {
            if (system.getText().startsWith(prompts.get(candidate, "chat.compress.summaryPrefix"))) {
                return true;
            }
        }
        return false;
    }

    /** 返回空列表 = 没有新原文可压 */
    private List<Message> rebuild(List<Message> messages, int cutoff) {
        UserMessage firstUser = messages.stream()
                .filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                .findFirst().orElse(null);

        SystemMessage previousSummary = null;
        List<Message> toSummarize = new ArrayList<>();
        for (int i = 0; i < cutoff; i++) {
            Message message = messages.get(i);
            if (message == firstUser) {
                continue;
            }
            // 上次压缩留下的摘要靠固定前缀认出来，原样留着不再压第二遍——
            // 它在 index 1，不挑出来的话每次压缩都会把它再揉一遍，几轮后早期事实彻底消失且无从归因
            if (previousSummary == null && isSummary(message, prompts)) {
                previousSummary = (SystemMessage) message;
                continue;
            }
            toSummarize.add(message);
        }
        if (toSummarize.isEmpty()) {
            return List.of();
        }

        List<Message> compressed = new ArrayList<>();
        if (firstUser != null) {
            compressed.add(firstUser);
        }
        compressed.add(new SystemMessage(appendSegment(previousSummary, summarize(toSummarize))));
        compressed.addAll(messages.subList(cutoff, messages.size()));
        return compressed;
    }

    /** 老摘要整段原样留下，新的一段接在后面并标号——分段是为了归因（第1段最早），不是把历史越揉越糊 */
    private String appendSegment(SystemMessage previousSummary, String freshSummary) {
        String head = previousSummary == null
                ? prompts.get(lang, "chat.compress.summaryPrefix") : previousSummary.getText();
        return head + "\n" + prompts.get(lang, "chat.compress.segmentMark",
                Map.of("n", countSegments(head) + 1)) + "\n" + freshSummary;
    }

    /**
     * 已有几段。段头可能是另一门语言写的（中途切过语言），逐语言各数一遍再相加，段号才连得上。
     */
    private int countSegments(String summaryText) {
        int count = 0;
        for (AgentLang candidate : AgentLang.values()) {
            String mark = prompts.get(candidate, "chat.compress.segmentPrefix");
            for (int i = summaryText.indexOf(mark); i >= 0; i = summaryText.indexOf(mark, i + mark.length())) {
                count++;
            }
        }
        return count;
    }

    private String summarize(List<Message> messages) {
        StringBuilder text = new StringBuilder();
        for (Message message : messages) {
            text.append(prompts.get(lang, "chat.compress.roleLine",
                    Map.of("role", roleOf(message), "text", textOf(message)))).append("\n");
        }
        return summaryModel.call(new Prompt(prompts.get(lang, "chat.compress.prompt",
                Map.of("history", text)))).getResult().getOutput().getText();
    }

    /**
     * 摘要输入用的文本。ToolResponseMessage.getText() 恒为空串（构造时传的就是 ""），真内容在
     * responseData()；AssistantMessage 的工具参数也不在正文里。阈值估算本就把这两样算进去了——
     * 压缩是被工具结果的体积撑触发的，只读 getText() 等于把行情数字和研判结论正好全扔掉。
     */
    String textOf(Message message) {
        if (message instanceof ToolResponseMessage toolResponse) {
            StringBuilder text = new StringBuilder();
            for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(response.name()).append(" → ").append(clip(response.responseData()));
            }
            return text.toString();
        }
        if (message instanceof AssistantMessage assistant) {
            StringBuilder text = new StringBuilder(assistant.getText() == null ? "" : assistant.getText());
            for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(prompts.get(lang, "chat.compress.toolCall",
                        Map.of("name", toolCall.name(), "args", clip(toolCall.arguments()))));
            }
            return text.toString();
        }
        return message.getText() == null ? "" : message.getText();
    }

    private String clip(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= TOOL_TEXT_LIMIT
                ? text : text.substring(0, TOOL_TEXT_LIMIT) + prompts.get(lang, "chat.compress.clipped");
    }

    private String roleOf(Message message) {
        if (message instanceof UserMessage) return prompts.get(lang, "chat.compress.role.user");
        if (message instanceof AssistantMessage) return prompts.get(lang, "chat.compress.role.assistant");
        if (message instanceof SystemMessage) return prompts.get(lang, "chat.compress.role.system");
        if (message instanceof ToolResponseMessage) return prompts.get(lang, "chat.compress.role.tool");
        return prompts.get(lang, "chat.compress.role.other");
    }

    /**
     * 从"保留最近 N 条"的理想切点往前找，直到不会切断工具调用配对为止。
     * <p>
     * AssistantMessage(toolCalls) 与其对应的 ToolResponseMessage 必须同生共死——
     * 只留一半（有结果没调用记录、或有调用没结果）模型 API 会直接报错。
     */
    private int findSafeCutoff(List<Message> messages) {
        if (messages.size() <= messagesToKeep) {
            return 0;
        }
        for (int cutoff = messages.size() - messagesToKeep; cutoff >= 0; cutoff--) {
            if (isSafeCutoff(messages, cutoff)) {
                return cutoff;
            }
        }
        return 0;
    }

    private boolean isSafeCutoff(List<Message> messages, int cutoff) {
        int from = Math.max(0, cutoff - TOOL_PAIR_SEARCH_RANGE);
        int to = Math.min(messages.size(), cutoff + TOOL_PAIR_SEARCH_RANGE);
        for (int i = from; i < to; i++) {
            if (!(messages.get(i) instanceof AssistantMessage assistant) || assistant.getToolCalls().isEmpty()) {
                continue;
            }
            Set<String> callIds = new HashSet<>();
            assistant.getToolCalls().forEach(tc -> callIds.add(tc.id()));
            if (separatesToolPair(messages, i, cutoff, callIds)) {
                return false;
            }
        }
        return true;
    }

    private boolean separatesToolPair(List<Message> messages, int assistantIndex, int cutoff, Set<String> callIds) {
        for (int i = assistantIndex + 1; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof ToolResponseMessage toolResponse)) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                // 配对的两条一个在切点前、一个在切点后 → 切坏了
                if (callIds.contains(response.id()) && (assistantIndex < cutoff) != (i < cutoff)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * token 估算：CJK 字符按 1 字≈1 token，其余按 4 字符≈1 token。
     * 框架自带的计数器一律 charCount/4，中文会低估约 4 倍——阈值设 6000 实际到 20000+ 才触发。
     * <p>
     * public 是给 {@code ChatTurnRunner} 打轮次指标用的（跨包）。只是估算，不是计费口径。
     */
    public static int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            total += estimateTokens(message.getText());
            if (message instanceof ToolResponseMessage toolResponse) {
                for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                    total += estimateTokens(response.responseData());
                }
            } else if (message instanceof AssistantMessage assistant) {
                for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
                    total += estimateTokens(toolCall.arguments());
                }
            }
        }
        return total;
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x2E80) { // CJK 及其标点起始区
                cjk++;
            }
        }
        return cjk + (text.length() - cjk) / 4;
    }
}
