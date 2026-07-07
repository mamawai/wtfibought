package com.mawai.wiibquant.agent.toolkit;

import com.mawai.wiibquant.agent.config.AiAgentRuntimeManager;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * quant 域 LLM 调用门面：每次调用现取 RuntimeManager 当前模型——Admin 热更新模型配置即时生效，
 * 图/服务不持有 ChatClient 也不用重建。P4 深浅分层时加 lightCall(quant-light 模型)。
 */
@Component
@RequiredArgsConstructor
public class QuantLlm {

    private final AiAgentRuntimeManager runtimeManager;

    /** 深研判调用（quant 模型），阻塞返回全文；异常上抛由调用方降级。 */
    public String call(String prompt) {
        ChatClient client = ChatClient.builder(runtimeManager.current().quantChatModel()).build();
        return client.prompt().user(prompt).call().content();
    }
}
