package com.mawai.wiibagent.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 给一次模型调用挂工具的 options。
 * <p>
 * <b>options 一律从 {@code model.getOptions().mutate()} 派生，不许用泛型 builder 另起炉灶</b>：
 * Spring AI 2.0 的 OpenAiChatModel 不再把运行时 options 合并进默认 options，而是把
 * {@code prompt.getOptions()} 直接硬转 {@code OpenAiChatOptions}——塞个 {@code ToolCallingChatOptions.builder()}
 * 造的 DefaultToolCallingChatOptions 进去当场 ClassCastException。真跑实证：路由这一次调用抛了、
 * 被兜成 FINISH，整轮零专家派发，用户看到的就是"没有可用数据"（openai 协议端点全中，responses 协议无感）。
 * <p>
 * 不带 tool_choice，一律 auto：Opus 5.5 / Sonnet 5.5 / Fable 5.1 这类模型不收 any / required。
 */
public final class ToolOptions {

    private ToolOptions() {
    }

    /** 挂工具：从模型自己的 options 派生，具体类型跟着模型走（OpenAiChatOptions / DefaultToolCallingChatOptions） */
    public static ToolCallingChatOptions withTools(ChatModel model, List<ToolCallback> tools) {
        if (!(model.getOptions() instanceof ToolCallingChatOptions base)) {
            throw new IllegalStateException("模型 " + model.getClass().getSimpleName() + " 的 options 不支持挂工具");
        }
        return base.mutate().toolCallbacks(tools).build();
    }
}
