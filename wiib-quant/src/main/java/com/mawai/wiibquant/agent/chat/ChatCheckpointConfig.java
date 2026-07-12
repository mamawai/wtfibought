package com.mawai.wiibquant.agent.chat;

import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import com.alibaba.cloud.ai.graph.store.Store;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 工作台持久化配置：PostgresSaver（会话 checkpoint，断连续聊地基）+ DatabaseStore（跨会话长期记忆），
 * 都落主库 PG，框架自动建表。
 */
@Configuration
public class ChatCheckpointConfig {

    /** 跨会话长期记忆存储（P5）：复用 Spring 主 DataSource，自动建 store 表（PG 方言，见 PostgresDatabaseStore）。 */
    @Bean
    public Store workbenchStore(DataSource dataSource) {
        return new PostgresDatabaseStore(dataSource);
    }

    @Bean
    public BaseCheckpointSaver workbenchCheckpointSaver(
            DataSource dataSource,
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String user,
            @Value("${spring.datasource.password}") String password) throws SQLException {
        // 库的 initTable DDL 非幂等（索引没加 IF NOT EXISTS），表已存在时重跑必炸：先查一把，建过就跳过建表
        boolean tablesExist;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT to_regclass('graphcheckpoint')")) {
            tablesExist = rs.next() && rs.getString(1) != null;
        }
        URI uri = URI.create(url.replaceFirst("^jdbc:", ""));
        String database = uri.getPath().replaceFirst("^/", "").replaceAll("\\?.*$", "");
        return PostgresSaver.builder()
                .host(uri.getHost())
                .port(uri.getPort() > 0 ? uri.getPort() : 5432)
                .database(database)
                .user(user)
                .password(password)
                .createTables(!tablesExist)
                .build();
    }
}
