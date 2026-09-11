package com.mawai.wiibagent.llm;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具挂载与 tool_choice 的协议适配，各协议在这里收口：
 * <ul>
 *   <li>openai 协议（Spring AI {@code OpenAiChatModel}）：tool_choice 是 {@link OpenAiChatOptions#getToolChoice()} 字段</li>
 *   <li>自研 SSE 协议（{@link SseChatModel} 子类）：portable 的 ChatOptions 没有这个字段，
 *       经 toolContext 的 {@link #CONTEXT_KEY} 捎进去，模型建请求体时读回</li>
 * </ul>
 * 调用方只说"挂哪些工具、这次强不强制"，不关心底下是哪条协议。
 * <p>
 * <b>options 一律从 {@code model.getOptions().mutate()} 派生，不许用泛型 builder 另起炉灶</b>：
 * Spring AI 2.0 的 OpenAiChatModel 不再把运行时 options 合并进默认 options，而是把
 * {@code prompt.getOptions()} 直接硬转 {@code OpenAiChatOptions}——塞个 {@code ToolCallingChatOptions.builder()}
 * 造的 DefaultToolCallingChatOptions 进去当场 ClassCastException。真跑实证：路由这一次调用抛了、
 * 被兜成 FINISH，整轮零专家派发，用户看到的就是"没有可用数据"（openai 协议端点全中，responses 协议无感）。
 */
public final class ToolChoice {

    public static final String REQUIRED = "required";
    public static final String AUTO = "auto";

    /** {@link SseChatModel} 子类读的 toolContext 键：值 required/auto/具体工具名；缺省按 auto */
    public static final String CONTEXT_KEY = "wiib_tool_choice";

    /** 各协议共用的参数名。上游拒收强制时报错文案里带它，是 ResilientChatService 判降级的抓手 */
    public static final String PARAM = "tool_choice";

    private ToolChoice() {
    }

    /** 挂工具：从模型自己的 options 派生，具体类型跟着模型走（OpenAiChatOptions / DefaultToolCallingChatOptions） */
    public static ToolCallingChatOptions withTools(ChatModel model, List<ToolCallback> tools) {
        if (!(model.getOptions() instanceof ToolCallingChatOptions base)) {
            throw new IllegalStateException("模型 " + model.getClass().getSimpleName() + " 的 options 不支持挂工具");
        }
        return base.mutate().toolCallbacks(tools).build();
    }

    /** 把强制程度落到对应协议的字段上；返回新 options，入参不动 */
    public static ChatOptions apply(ChatOptions options, String choice) {
        // OpenAiChatOptions 也实现了 ToolCallingChatOptions，必须先判它
        if (options instanceof OpenAiChatOptions openAi) {
            return openAi.mutate().toolChoice(choice).build();
        }
        if (options instanceof ToolCallingChatOptions tool) {
            Map<String, Object> context = tool.getToolContext() == null
                    ? new HashMap<>() : new HashMap<>(tool.getToolContext());
            context.put(CONTEXT_KEY, choice);
            return tool.mutate().toolContext(context).build();
        }
        return options;
    }

    /** 自研协议侧读回本次调用的 tool_choice；没捎就是 auto */
    public static String of(ChatOptions options) {
        if (options instanceof ToolCallingChatOptions tool && tool.getToolContext() != null
                && tool.getToolContext().get(CONTEXT_KEY) != null) {
            return tool.getToolContext().get(CONTEXT_KEY).toString();
        }
        return AUTO;
    }

    /**
     * 首轮判定：最后一条用户消息之后没有工具回执才算首轮。
     * ReactLoop 是循环，"首轮强制"拿到工具结果后必须放开否则收不了尾；判据只看最后一条用户消息之后这一段
     * 而非全历史——summarizer 用过深研判工具后 ToolResponseMessage 会随会话历史落库、下一轮原样喂给专家，
     * 扫全历史会让之后每个专家的首轮强制全部失效。
     */
    public static boolean isFirstTurn(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Message m = history.get(i);
            if (m instanceof ToolResponseMessage) {
                return false;
            }
            if (m instanceof UserMessage) {
                return true;
            }
        }
        return true;
    }
}
