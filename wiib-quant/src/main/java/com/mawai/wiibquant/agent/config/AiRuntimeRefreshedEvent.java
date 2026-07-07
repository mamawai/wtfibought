package com.mawai.wiibquant.agent.config;

import org.springframework.context.ApplicationEvent;

/** AI 运行时模型配置刷新事件：构建期绑定 ChatModel 的组件（对话图等）监听此事件重建。 */
public class AiRuntimeRefreshedEvent extends ApplicationEvent {

    public AiRuntimeRefreshedEvent(Object source) {
        super(source);
    }
}
