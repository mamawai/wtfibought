package com.mawai.wiibquant.agent.chat;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 工作台运行注册表：sessionId → 运行中标记 + 进度监听。
 * <ul>
 *   <li>运行状态：切页/刷新回来后前端查 status 得知"AI 还在后台跑"（图不随 SSE 断连中止）</li>
 *   <li>进度总线：深研判等长耗时工具在图内同步阻塞跑，经此把阶段进度推给 SSE 通道，
 *       解决"几分钟零输出不知道卡在哪"</li>
 * </ul>
 * 进程内存级即可：单实例部署，重启即无运行中会话，无需持久化。
 */
@Component
public class WorkbenchRunRegistry {

    /** value=进度监听（没有进度可推时也占位，key 存在即"运行中"） */
    private final Map<String, Consumer<String>> runs = new ConcurrentHashMap<>();

    /** 一轮对话开跑：登记运行中 + 挂进度监听。 */
    public void start(String sessionId, Consumer<String> progressListener) {
        runs.put(sessionId, progressListener);
    }

    /** 一轮对话结束（含异常路径）。 */
    public void finish(String sessionId) {
        runs.remove(sessionId);
    }

    public boolean isRunning(String sessionId) {
        return runs.containsKey(sessionId);
    }

    /** 工具侧：推一条阶段进度（无监听=会话已结束或断连，静默丢弃）。 */
    public void publishProgress(String sessionId, String text) {
        Consumer<String> listener = runs.get(sessionId);
        if (listener != null) {
            listener.accept(text);
        }
    }
}
