package com.mawai.wiibagent.promptgen;

import com.mawai.wiibagent.llm.ReactLoop;
import com.mawai.wiibagent.llm.SseChatModel;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.enums.AgentLang;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import static com.mawai.wiibagent.promptgen.PromptGenToolkit.ASK_USER;
import static com.mawai.wiibagent.promptgen.PromptGenToolkit.WRITE_DRAFT;
import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 一轮的现场，工具和 ReactLoop 监听器共用。
 * <p>
 * messages = 起跑历史 + 循环里每条新消息：工具执行时看到的正好是"到这批工具调用为止"的历史。
 * 写好的一版先挂在这儿，等这批回执进了历史再落库，快照里才有"已交第 N 版"这条。
 */
final class PromptGenRun implements ReactLoop.Listener {

    private final long userId;
    private final AgentLang lang;
    private final AgentLang outputLang;
    private final AiTrader trader;
    private final List<Message> messages;
    /** 往页面推一帧；通道断了 SseChannel 自己吞掉 */
    private final BiConsumer<String, ObjectNode> emitter;
    /** 用户点了停止就完成 */
    private final CompletableFuture<Void> cancel;
    private final PromptGenStore store;

    private PromptGenHistory.Drafted staged;
    private boolean asked;

    /** @param trader 可空：还没建 trader */
    PromptGenRun(long userId, AgentLang lang, AgentLang outputLang, AiTrader trader, List<Message> history,
                 BiConsumer<String, ObjectNode> emitter, CompletableFuture<Void> cancel, PromptGenStore store) {
        this.userId = userId;
        this.lang = lang;
        this.outputLang = outputLang;
        this.trader = trader;
        this.messages = new ArrayList<>(history);
        this.emitter = emitter;
        this.cancel = cancel;
        this.store = store;
    }

    long userId() {
        return userId;
    }

    AgentLang lang() {
        return lang;
    }

    AiTrader trader() {
        return trader;
    }

    /** 必问项随对话走：写出过版本就不再问 mode */
    Set<String> required(List<PromptGenHistory.Step> steps) {
        return PromptGenFacts.required(trader, PromptGenHistory.lastDraft(steps) != null);
    }

    List<Message> messages() {
        return messages;
    }

    /** 起跑历史里的原始内容（思考块等）去掉，按文本重建；循环开跑前调 */
    void plainHistory() {
        messages.replaceAll(m -> m instanceof AssistantMessage a ? SseChatModel.withoutRaw(a) : m);
    }

    CompletableFuture<Void> cancel() {
        return cancel;
    }

    boolean cancelled() {
        return cancel.isDone();
    }

    PromptGenStore store() {
        return store;
    }

    void emit(String event, ObjectNode data) {
        emitter.accept(event, data);
    }

    void status(String stage) {
        emit("status", MAPPER.createObjectNode().put("stage", stage));
    }

    /** 这一轮已经推过题卡了 */
    boolean asked() {
        return asked;
    }

    void markAsked() {
        asked = true;
    }

    PromptGenHistory.Drafted staged() {
        return staged;
    }

    void stage(PromptGenHistory.Drafted drafted) {
        staged = drafted;
    }

    @Override
    public void chunk(ChatResponse frame) {
        String text = frame.getResults().getFirst().getOutput().getText();
        if (text != null && !text.isEmpty()) {
            emit("text", MAPPER.createObjectNode().put("text", text));
        }
    }

    @Override
    public void message(Message message) {
        messages.add(message);
        if (message instanceof AssistantMessage reply) {
            List<AssistantMessage.ToolCall> reads = reply.getToolCalls().stream()
                    .filter(call -> !ASK_USER.equals(call.name()) && !WRITE_DRAFT.equals(call.name()))
                    .toList();
            if (!reads.isEmpty()) {
                status("reading");
                // 读数据的这一批连名带参推给页面画过程轨，回执回来那帧 thinking 就是这批读完了
                ArrayNode calls = MAPPER.createArrayNode();
                reads.forEach(call -> calls.addObject().put("name", call.name()).set("args", PromptGenHistory.args(call)));
                ObjectNode data = MAPPER.createObjectNode();
                data.set("calls", calls);
                emit("tool_calls", data);
            }
        }
        if (message instanceof ToolResponseMessage responses) {
            if (staged != null) {
                store.saveVersion(userId, staged, outputLang, messages);
                emit("version", MAPPER.valueToTree(PromptGenView.Version.of(staged)));
                staged = null;
            }
            // 真推出了题卡这一轮就收工了；没推（被拒、或这批没出题）agent 接着想
            if (!asked) {
                status("thinking");
            }
        }
    }
}
