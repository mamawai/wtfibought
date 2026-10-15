package com.mawai.wiibagent.chat.tools;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.ChatAgentFactory;
import com.mawai.wiibagent.chat.run.ChildRun;
import com.mawai.wiibagent.chat.run.ChildRunRegistry;
import com.mawai.wiibagent.chat.run.WorkerKind;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.llm.ReactLoop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.function.Function;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 派后台子 agent 的工具：主 agent 现写任务书、选一个 worker 预设，子 agent 在自己的上下文里跑，
 * 报告经 {@link ChildRunRegistry} 回灌会话。tool-as-agent 与 orchestrator-workers 共用一套子运行时，
 * 区别只在任务书是代码写死（深研判）还是模型现写（这里）。
 * <p>
 * 开关与上限归 {@link ChildRunRegistry#spawn}；这里只管把 worker 建出来、跑完取报告。
 * worker 每批工具调用带着 runId 推给前端（tool_calls），用户点开那条子运行能看到它在做什么。
 */
@Slf4j
public class DelegateToolkit {

    /** 过程轨上的名字别太长 */
    private static final int MAX_LABEL_CHARS = 40;

    private final ChildRunRegistry childRuns;
    /** 按预设现建一个 worker（独享账本），建法在 {@link ChatAgentFactory} */
    private final Function<WorkerKind, ChatAgentFactory.Worker> workers;
    private final PromptCatalog prompts;
    private final long userId;
    private final AgentLang lang;

    public DelegateToolkit(ChildRunRegistry childRuns, Function<WorkerKind, ChatAgentFactory.Worker> workers,
                           PromptCatalog prompts, long userId, AgentLang lang) {
        this.childRuns = childRuns;
        this.workers = workers;
        this.prompts = prompts;
        this.userId = userId;
        this.lang = lang;
    }

    // description 只是编译期兜底：真正发给模型的那份按语言取自词表 tool.delegate（见 LocalizedToolCallbacks）
    @Tool(name = "delegate", description = """
            Hand a self-contained sub-task to a background worker agent and return at once.
            worker: "researcher" (reads the news flashes and, when the endpoint allows, the web; returns a sourced
            digest) or "analyst" (pulls live market data and indicators for symbols; returns a structured brief).
            brief: the whole task in one message - objective, scope (symbols / time range / sources), what the
            report must contain and roughly how long it may be. The worker sees nothing of this conversation.
            Returns {runId, status: "running"} immediately: keep answering what you can, do not wait or poll;
            you will be woken with the worker's report. Several may run at once up to the user's limit;
            a refusal comes back as {error} - then do that part yourself with your own tools.""")
    public String delegate(@ToolParam(description = "researcher | analyst") String worker,
                           @ToolParam(description = "The complete, self-contained task brief for the worker") String brief,
                           @ToolParam(required = false, description = "Short label for the process rail, e.g. 'ETF flows this week'") String label,
                           ToolContext context) {
        WorkerKind kind = WorkerKind.parse(worker);
        if (kind == null) {
            return error(prompts.get(lang, "chat.delegate.unknownWorker"));
        }
        if (brief == null || brief.isBlank()) {
            return error(prompts.get(lang, "chat.delegate.emptyBrief"));
        }
        String shown = label == null || label.isBlank() ? prompts.get(lang, kind.labelKey()) : label.strip();
        if (shown.length() > MAX_LABEL_CHARS) {
            shown = shown.substring(0, MAX_LABEL_CHARS) + "…";
        }
        ChildRunRegistry.Spawn spawn = childRuns.spawn(
                new ChildRunRegistry.Spec(ToolRunContext.sessionId(context), userId, ChildRun.Kind.DELEGATE,
                        shown, brief.strip(), lang),
                run -> runWorker(kind, run));
        if (!spawn.started()) {
            return error(spawn.refusal());
        }
        log.info("[Delegate] 派 {} session={} run=#{} label={}", kind, spawn.run().sessionId(), spawn.run().id(), shown);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("runId", spawn.run().id());
        out.put("status", "running");
        out.put("message", prompts.get(lang, "chat.childRun.started"));
        return MAPPER.writeValueAsString(out);
    }

    /** 子运行主体：任务书当唯一的用户消息喂给 worker，最后一条有正文的助手消息就是报告 */
    private String runWorker(WorkerKind kind, ChildRun run) {
        ChatAgentFactory.Worker worker = workers.apply(kind);
        ReactLoop.Listener listener = new ReactLoop.Listener() {
            @Override
            public void message(Message message) {
                // worker 要调工具了：带着 runId 推出去，前端挂进这条子运行的内部。
                // 取消/超时之后模型还可能回一帧带 tool_call 的回复，屏上那条已经结束了，不推
                if (!run.cancelled() && message instanceof AssistantMessage reply && reply.hasToolCalls()) {
                    childRuns.toolCalls(run, reply.getToolCalls());
                }
            }
        };
        ReactLoop.Result result = worker.loop().run(List.of(new UserMessage(run.brief())), run.sessionId(),
                run.cancel(), listener);
        log.info("[Delegate] #{} {} 跑完 modelCalls={} usage={}", run.id(), kind, result.modelCalls(),
                worker.usage().snapshot());
        List<Message> messages = result.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage assistant
                    && assistant.getText() != null && !assistant.getText().isBlank()) {
                return assistant.getText();
            }
        }
        return prompts.get(lang, "chat.childRun.empty");
    }

    private static String error(String message) {
        return MAPPER.writeValueAsString(MAPPER.createObjectNode().put("error", message));
    }
}
