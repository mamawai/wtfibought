package com.mawai.wiibagent.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 单次运行的模型调用保险丝：ReAct 是循环，模型可能陷进"查完行情又想查持仓、查完持仓又想查行情"
 * 的死转里一路烧 token。超过上限就直接结束本轮，把已有结果交出去。
 * 倒数第二次（最后一次能执行工具）的回执末尾贴一句预算已尽，让模型下一次调用直接收尾，
 * 而不是撞上限硬切、这轮连结论块都没有。
 * <p>
 * 三个纯函数，不持有任何状态：计数在 {@link ReactLoop} 手里，"这次是第几次调用"由它判、由它决定调哪个。
 */
@Slf4j
public class ModelCallLimiter {

    private final int runLimit;
    /** 占位回执正文（llm.callLimit.notExecuted）：说清"没执行"，不是伪造的成功结果——
     * 模型和复盘都要看得懂。按语言在建 agent 时由调用方取词表传入 */
    private final String notExecuted;
    /** 预算收尾提示（llm.callLimit.lastCall）：最后一次能执行工具时贴在回执末尾，下一次模型调用直接给最终答复 */
    private final String lastCall;

    public ModelCallLimiter(int runLimit, String notExecuted, String lastCall) {
        this.runLimit = runLimit;
        this.notExecuted = notExecuted;
        this.lastCall = lastCall;
    }

    /** 本轮模型调用上限 */
    public int limit() {
        return runLimit;
    }

    /**
     * 这批 tool_call 一个都不执行，逐个补标记未执行的配对回执。调用方保证 reply 带 tool_call。
     * <p>
     * 只跳不补 = 留下永远等不到 tool_result 的孤儿——工作台会把这段残缺历史持久化，
     * 之后每次续聊重建成 function_call 却找不到 function_call_output，上游直接 400，会话只能删掉重开。
     */
    public ToolResponseMessage placeholders(AssistantMessage reply) {
        log.warn("[CallLimit] 模型调用达上限 {}，本轮提前结束", runLimit);
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall call : reply.getToolCalls()) {
            responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), notExecuted));
        }
        return ToolResponseMessage.builder().responses(responses).build();
    }

    /**
     * 提示贴在最后一条工具回执的末尾，不另起一条 user 消息：tool_result 后面紧跟 user 文本，
     * Anthropic/Gemini 协议不一定收
     */
    public ToolResponseMessage withLastCallNotice(ToolResponseMessage responses) {
        List<ToolResponseMessage.ToolResponse> list = new ArrayList<>(responses.getResponses());
        ToolResponseMessage.ToolResponse tail = list.getLast();
        list.set(list.size() - 1, new ToolResponseMessage.ToolResponse(
                tail.id(), tail.name(), tail.responseData() + "\n\n" + lastCall));
        return ToolResponseMessage.builder().responses(list).build();
    }
}
