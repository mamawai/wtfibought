package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import com.mawai.wiibagent.chat.store.ChatHistoryService;
import com.mawai.wiibagent.chat.store.ChatRowKind;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.llm.ConversationSummarizer;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 重新生成前的上下文回退：把模型侧会话上下文退回"最后一问已在、回答未出"的状态。
 * <p>
 * 与 HTTP 无关——"回不去"只交回 empty，翻成 2206 是 {@link ChatWorkbenchController} 的事。
 * 独立成类是为了能单独立起来测：它要钉的三条（标记只是候选 / 压缩首问那个假位置 / 跨语言认标记）
 * 一条都用不上模型、名额闸与 SSE。
 */
@Component
@RequiredArgsConstructor
public class ChatTurnRewinder {

    private final ChatHistoryService chatHistoryService;
    private final ChatContextStore contextStore;
    private final PromptCatalog prompts;

    /** 回退的产物：要重问的原文，以及那条等着被顶替的旧答案行 */
    public record Rollback(String question, long answerId) {
    }

    /**
     * 回退并交出要重问的原文和那条待顶替的旧答案行。
     * <p>
     * 上下文从尾部回删到本轮提问为止（含它）——一轮的尾巴不止"一问一答"，中间还夹着专家结论、
     * 交接指令与 tool_call 配对。展示表这里一行不动：旧答案要留到新答案确实落库之后才删，见 {@link ChatTurnStreamer}。
     * <p>
     * <b>光靠轮起始标记定位不住</b>：历史压缩会把首条用户消息<b>原样</b>放回压缩结果队首
     *（见 {@code ConversationSummarizer}），那条正是会话第一轮的提问、同样带着标记。
     * 所以标记只用来找候选，还要拿它与展示表里那条提问核对——对不上就说明本轮提问已被压进摘要，回不去了。
     * <p>
     * 回不去的一律交回 empty，不做半吊子的补偿。几种回不去的原因在 HTTP 上是同一个码，不分型。
     * <p>
     * <b>调用方必须先拿到名额再调</b>：此刻没有别的轮在跑（补答也占同一个名额），
     * 读到的历史与上下文不会被人从背后改掉，回退也不会被别人的落库覆盖。
     */
    public Optional<Rollback> rewind(String sessionId, long userId) {
        List<ChatHistoryService.ChatMessage> history = chatHistoryService.messages(sessionId);
        if (history.isEmpty() || !"assistant".equals(history.getLast().role())
                || ChatRowKind.DEFERRED.equals(history.getLast().kind())) {
            return Optional.empty();
        }
        ChatHistoryService.ChatMessage answer = history.getLast();
        String question = null;
        for (int i = history.size() - 2; i >= 0; i--) {
            if ("user".equals(history.get(i).role())) {
                question = history.get(i).content();
                break;
            }
        }
        if (question == null) {
            return Optional.empty();
        }
        List<Message> context = contextStore.load(sessionId);
        int cut = cutAt(context, question);
        if (cut < 0) {
            return Optional.empty();
        }
        // 切在队首且紧跟着摘要，说明命中的是压缩原样放回的首问，不是本轮提问——
        // 同一句常用问法在一个会话里问两遍就会这样，文本对得上但位置是假的，照切会把整段上下文连摘要清空
        if (cut == 0 && context.size() > 1 && ConversationSummarizer.isSummary(context.get(1), prompts)) {
            return Optional.empty();
        }
        contextStore.save(sessionId, userId, List.copyOf(context.subList(0, cut)));
        return Optional.of(new Rollback(question, answer.id()));
    }

    /**
     * 从尾部找本轮提问那条（带轮起始标记的 user 消息）。标记按语言取自词表（chat.turn.*），
     * 认的时候遍历全部语言：切语言后旧轮的标记仍是旧语言。核对用命中那门语言的问题前缀——
     * enriched 是一门语言一次拼成的，不存在跨语言混拼。
     *
     * @return 切点下标；-1=一条带标记的都没有，或命中那条与提问对不上，两种都是回不去
     */
    private int cutAt(List<Message> context, String question) {
        for (int i = context.size() - 1; i >= 0; i--) {
            Message message = context.get(i);
            if (!(message instanceof UserMessage) || message.getText() == null) {
                continue;
            }
            String text = message.getText();
            for (AgentLang lang : AgentLang.values()) {
                if (!text.startsWith(prompts.get(lang, "chat.turn.timePrefix"))) {
                    continue;
                }
                // 核对的是 enriched 的尾巴（拼法见 ChatTurnStreamer.Turn.run），对不上就是压缩把本轮提问
                // 吃掉了，此时命中的那条是压缩留下的首问——照它切会把中间好几轮连同摘要一起抹掉
                return text.endsWith(prompts.get(lang, "chat.turn.questionPrefix") + question) ? i : -1;
            }
        }
        return -1;
    }
}
