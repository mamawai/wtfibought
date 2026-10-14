package com.mawai.wiibagent.chat.gate;

import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 工作台运行注册表：sessionId → 在跑的那一轮（SSE 事件出口 + 中断信号）。
 * <ul>
 *   <li>运行状态：切页/刷新回来后前端查 status 得知"AI 还在后台跑"（不随 SSE 断连中止）</li>
 *   <li>事件总线：深研判等长耗时工具同步阻塞跑，经此把阶段进度推给 SSE 通道，
 *       解决"几分钟零输出不知道卡在哪"；trader 动作类工具经此推表单卡，把执行权交回用户点击</li>
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

    /** 统一出口：会话已结束、通道已断连，两种都返回 false。 */
    private boolean publish(String sessionId, String event, ObjectNode data) {
        Run run = runs.get(sessionId);
        return run != null && run.emitter().test(event, data);
    }
}
