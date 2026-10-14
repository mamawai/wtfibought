package com.mawai.wiibagent.chat.run;

import java.util.Locale;

/**
 * delegate 能派的 worker 预设：预设定工具集、模型与护栏，任务书由主 agent 现写。
 * 都是只读的、没有 delegate（深度只有一层）、没有动作表单与 HITL 工具。
 */
public enum WorkerKind {

    /** 浅模型 + 新闻快讯 + 服务端搜索（端点支持时）：读得多、要来源的调研，回摘要 */
    RESEARCHER("chat.worker.researcher", "chat.worker.label.researcher"),
    /** 浅模型 + 行情与指标工具：单币多轮取数分析，回结构化简报 */
    ANALYST("chat.worker.analyst", "chat.worker.label.analyst");

    private final String promptKey;
    private final String labelKey;

    WorkerKind(String promptKey, String labelKey) {
        this.promptKey = promptKey;
        this.labelKey = labelKey;
    }

    /** 系统提示词在词表里的 key */
    public String promptKey() {
        return promptKey;
    }

    /** 模型没给 label 时过程轨上的默认名字 */
    public String labelKey() {
        return labelKey;
    }

    /** 工具参数里的写法，不区分大小写；认不出返回 null */
    public static WorkerKind parse(String name) {
        if (name == null) {
            return null;
        }
        try {
            return valueOf(name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
