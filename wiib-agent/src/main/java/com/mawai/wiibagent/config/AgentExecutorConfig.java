package com.mawai.wiibagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 工作台对话一轮跑在哪个执行器上：虚拟线程，一轮一个，全程阻塞在上游 HTTP 上，池大小不该成为约束。
 * 做成 bean 是给 {@code ChatWorkbenchController} 构造注入的，测试传一个已关的进来就能走到"提交失败还名额"那条路。
 */
@Configuration
public class AgentExecutorConfig {

    @Bean
    public ExecutorService workbenchStreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
