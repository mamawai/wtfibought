package com.mawai.wiibquant.agent.chat;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * 工作台会话 checkpoint 配置：PostgresSaver 复用主库连接（解析 jdbc url），框架自动建 checkpoint 表。
 * 会话按 threadId=sessionId 持久化——断连续聊 + P5 HITL interrupt/resume 的共同地基。
 */
@Configuration
public class ChatCheckpointConfig {

    @Bean
    public BaseCheckpointSaver workbenchCheckpointSaver(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String user,
            @Value("${spring.datasource.password}") String password) {
        URI uri = URI.create(url.replaceFirst("^jdbc:", ""));
        String database = uri.getPath().replaceFirst("^/", "").replaceAll("\\?.*$", "");
        return PostgresSaver.builder()
                .host(uri.getHost())
                .port(uri.getPort() > 0 ? uri.getPort() : 5432)
                .database(database)
                .user(user)
                .password(password)
                .createTables(true)
                .build();
    }
}
