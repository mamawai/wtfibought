package com.mawai.wiibagent.chat.gate;

import com.mawai.wiibagent.chat.run.ChildRun;
import com.mawai.wiibagent.llm.ContextUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 工作台运行注册表：sessionId → 在跑的那一轮（SSE 事件出口 + 中断信号）。
 * <ul>
 *   <li>运行状态：切页/刷新回来后前端查 status 得知"AI 还在后台跑"（不随 SSE 断连中止）</li>
 *   <li>事件总线：主 agent 与子 agent 每批工具调用、主 agent 每次调完模型的上下文用量、子运行的开始/进度/结束、
 *       行为分析的阶段进度经此推给 SSE 通道；
 *       trader 动作类工具经此推表单卡，把执行权交回用户点击。没轮在跑时推不出去（返回 false），
 *       子运行的事件丢了也不要紧，前端本地空闲时轮询 status 的 children/pending 补</li>
 *   <li>中断：/cancel 完成那轮的信号，轮在下一个检查点收尾</li>
 * </ul>
 * 进程内存级即可：单实例部署，重启即无运行中会话，无需持久化。
 */
@Component
public class WorkbenchRunRegistry {

    /**
     * 事件出口：返回 true=真推出去了。用 BiPredicate 为了把"推没推出去"答给调用方。
     */
    public interface Emitter extends BiPredicate<String, ObjectNode> {
    }

    /** 一轮在跑的现场 */
    private record Run(Emitter emitter, CompletableFuture<Void> cancel) {
    }

    /** key 存在即"运行中" */
    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    /** 一轮对话开跑：登记运行中 + 挂事件出口 + 挂中断信号 */
    public void start(String sessionId, Emitter emitter, CompletableFuture<Void> cancel) {
        runs.put(sessionId, new Run(emitter, cancel));
    }

    /** 一轮对话结束（含异常路径）。 */
    public void finish(String sessionId) {
        runs.remove(sessionId);
    }

    public boolean isRunning(String sessionId) {
        return runs.containsKey(sessionId);
    }

    /** 用户点停止：完成那轮的中断信号。没轮在跑返回 false，调用方如实告诉用户按钮点晚了 */
    public boolean requestCancel(String sessionId) {
        Run run = runs.get(sessionId);
        if (run == null) {
            return false;
        }
        run.cancel().complete(null);
        return true;
    }

    /** 工具侧：推一条阶段进度。data 字段名 {@code text} 是既有前端契约，别改。 */
    public void publishProgress(String sessionId, String text) {
        publish(sessionId, "progress", MAPPER.createObjectNode().put("text", text));
    }

    /** 主 agent 每次调完模型的上下文用量，对话框底栏的计量条跟着动 */
    public void publishContext(String sessionId, ContextUsage context) {
        publish(sessionId, "context", context.toJson());
    }

    /**
     * 工具侧：请前端弹一张表单卡（模型只出预填，执行权归用户点击）。
     * <p>
     * 返回值不能省：断连、会话已结束这几种情况下卡根本推不出去，
     * 调用方必须据此如实告诉模型"没弹出来"，否则模型会宣称已弹卡，用户却永远等不到。
     *
     * @param formType note / wake / review
     * @param prefill  预填字段，可为 null（不放这个字段，前端按空表单渲染）
     */
    public boolean publishForm(String sessionId, String formType, ObjectNode prefill) {
        ObjectNode data = MAPPER.createObjectNode().put("form", formType);
        if (prefill != null) {
            data.set("prefill", prefill);
        }
        return publish(sessionId, "form_request", data);
    }

    /**
     * 工具侧：把一份结构化行为分析报告推给前端渲染成卡片。
     * <p>
     * 返回值同 {@link #publishForm} 不能省：推不出去时调用方必须如实告诉模型"卡没上屏"，
     * 好让它把结论用文字讲一遍——否则模型说"报告已展示"，用户屏幕上一片空白。
     * <p>
     * 整份报告只经这条通道给前端，回模型的是裁剪版（见 {@code BehaviorToolkit}）：
     * 30 天逐日快照对模型是纯噪音，对卡片却是那条资产曲线。
     */
    public boolean publishBehaviorReport(String sessionId, ObjectNode report) {
        return publish(sessionId, "behavior_report", MAPPER.createObjectNode().set("report", report));
    }

    /** 子运行开跑：过程轨立一条。brief 是任务书（delegate 才有），前端点开那条能看到 */
    public void publishChildStart(String sessionId, ChildRun run) {
        ObjectNode data = MAPPER.createObjectNode()
                .put("runId", run.id()).put("kind", run.kind().name()).put("label", run.label());
        if (run.brief() != null) {
            data.put("brief", run.brief());
        }
        publish(sessionId, "child_start", data);
    }

    /** 子运行的阶段进度（深研判的 Bull/Bear/Judge 等），挂在那条过程轨条目上 */
    public void publishChildProgress(String sessionId, String runId, String text) {
        publish(sessionId, "child_progress", MAPPER.createObjectNode().put("runId", runId).put("text", text));
    }

    /** 子运行结束：status 是 DONE / FAILED / CANCELLED，失败带原因 */
    public void publishChildDone(ChildRun run) {
        ObjectNode data = MAPPER.createObjectNode().put("runId", run.id()).put("status", run.status().name());
        String error = run.error();
        if (error != null) {
            data.put("error", error);
        }
        publish(run.sessionId(), "child_done", data);
    }

    /**
     * 模型要调工具了：一条回复里的那批连名带参一起推，前端画成过程条目，下一批到了就把上一批标成做完。
     *
     * @param runId 非空=某个子运行里的调用，前端挂进它那条的内部；空=主 agent 自己的
     */
    public void publishToolCalls(String sessionId, String runId, List<AssistantMessage.ToolCall> calls) {
        if (!runs.containsKey(sessionId)) {
            return;     // 没轮在跑推不出去，参数树也别白建：worker 常常比主轮活得久
        }
        ArrayNode list = MAPPER.createArrayNode();
        for (AssistantMessage.ToolCall call : calls) {
            ObjectNode row = list.addObject().put("id", call.id()).put("name", call.name());
            ObjectNode args = argsOf(call);
            if (args != null) {
                row.set("args", args);
            }
        }
        ObjectNode data = MAPPER.createObjectNode();
        if (runId != null) {
            data.put("runId", runId);
        }
        data.set("calls", list);
        publish(sessionId, "tool_calls", data);
    }

    /** 工具参数；不是合法 JSON 对象返回 null，只给工具名 */
    public static ObjectNode argsOf(AssistantMessage.ToolCall call) {
        try {
            return MAPPER.readValue(call.arguments(), ObjectNode.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 统一出口：会话已结束、通道已断连，两种都返回 false。 */
    private boolean publish(String sessionId, String event, ObjectNode data) {
        Run run = runs.get(sessionId);
        return run != null && run.emitter().test(event, data);
    }
}
