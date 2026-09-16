package com.mawai.wiibagent.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 工具调用全量轨迹：{@link ReactLoop} 每次模型给出 tool_call 时调 {@link #record}，模型每轮想调的工具
 * （含 klines/indicators 等数据工具——它们是无状态共享 bean，自己没有记录点）都记下 名字+参数。
 * 每次运行 new 一个。
 * 收集器活在调用方手里：超时 cancel 打断循环时，已发生的记录仍保得住。
 */
public class ToolCallTraceHook {

    /** 循环线程写、调用方在 cancel 后读——COW 挡住这对竞争（写入≤模型调用上限次，成本可忽略） */
    private final List<ObjectNode> calls = new CopyOnWriteArrayList<>();

    public List<ObjectNode> calls() {
        return List.copyOf(calls);
    }

    public void record(AssistantMessage reply) {
        for (AssistantMessage.ToolCall tc : reply.getToolCalls()) {
            ObjectNode row = MAPPER.createObjectNode().put("tool", tc.name());
            try {
                row.set("args", MAPPER.readValue(tc.arguments(), ObjectNode.class));
            } catch (Exception ignore) {
                // 参数不是合法 JSON 就只留工具名——轨迹的价值在"调了什么"，参数是锦上添花
            }
            calls.add(row);
        }
    }
}
