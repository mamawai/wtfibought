package com.mawai.wiibagent.chat.gate;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * 工作台运行注册表：sessionId → 运行中标记 + SSE 事件出口。
 * <ul>
 *   <li>运行状态：切页/刷新回来后前端查 status 得知"AI 还在后台跑"（图不随 SSE 断连中止）</li>
 *   <li>事件总线：深研判等长耗时工具在图内同步阻塞跑，经此把阶段进度推给 SSE 通道，
 *       解决"几分钟零输出不知道卡在哪"；trader 动作类工具经此推表单卡，把执行权交回用户点击</li>
 * </ul>
 * 进程内存级即可：单实例部署，重启即无运行中会话，无需持久化。
 */
@Component
public class WorkbenchRunRegistry {

    /**
     * 事件出口：返回 true=真推出去了。用 BiPredicate 为了把"推没推出去"答给调用方。
     */
    public interface Emitter extends BiPredicate<String, JSONObject> {
    }

    /** value=SSE 事件出口（key 存在即"运行中"） */
    private final Map<String, Emitter> runs = new ConcurrentHashMap<>();

    /** 一轮对话开跑：登记运行中 + 挂事件出口。 */
    public void start(String sessionId, Emitter emitter) {
        runs.put(sessionId, emitter);
    }

    /** 一轮对话结束（含异常路径）。 */
    public void finish(String sessionId) {
        runs.remove(sessionId);
    }

    public boolean isRunning(String sessionId) {
        return runs.containsKey(sessionId);
    }

    /** 工具侧：推一条阶段进度。data 字段名 {@code text} 是既有前端契约，别改。 */
    public void publishProgress(String sessionId, String text) {
        publish(sessionId, "progress", new JSONObject().fluentPut("text", text));
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
    public boolean publishForm(String sessionId, String formType, JSONObject prefill) {
        JSONObject data = new JSONObject().fluentPut("form", formType);
        if (prefill != null) {
            data.fluentPut("prefill", prefill);
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
    public boolean publishBehaviorReport(String sessionId, JSONObject report) {
        return publish(sessionId, "behavior_report", new JSONObject().fluentPut("report", report));
    }

    /** 统一出口：会话已结束、通道已断连，两种都返回 false。 */
    private boolean publish(String sessionId, String event, JSONObject data) {
        Emitter emitter = runs.get(sessionId);
        return emitter != null && emitter.test(event, data);
    }
}
