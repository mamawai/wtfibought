package com.mawai.wiibquant.agent.chat;

import com.alibaba.cloud.ai.graph.store.StoreItem;
import com.alibaba.cloud.ai.graph.store.stores.DatabaseStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;

/**
 * PG 方言的 DatabaseStore：只覆写 putItem。
 * <p>
 * 框架（graph-core 1.1.2.0）的 putItem 用 H2 专有语法 {@code MERGE INTO ... KEY(id)}，
 * PostgreSQL 不认——跨会话记忆写入从上线起一直静默失败。父类其余方法（建表/查询/删除）
 * 全是标准 SQL，PG 原生兼容，无需重写。
 * <p>
 * id 生成沿用父类 protected createStoreKey，保证与 getItem/deleteItem 的键一致；
 * 不持父类的 JVM 读写锁（private 拿不到）——PG 的 upsert 自身原子，本地锁在这里本就无意义。
 */
public class PostgresDatabaseStore extends DatabaseStore {

    /** 与父类无参构造的默认表名一致（父类字段 private，只能复述） */
    private static final String TABLE = "spring_ai_store";

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public PostgresDatabaseStore(DataSource dataSource) {
        super(dataSource); // 父类构造完成建表（CREATE TABLE IF NOT EXISTS，PG 兼容）
        this.dataSource = dataSource;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.findAndRegisterModules();
    }

    @Override
    public void putItem(StoreItem item) {
        validatePutItem(item);
        // 冲突时 created_at 保留首次写入值，仅更新内容与 updated_at
        String sql = "INSERT INTO " + TABLE
                + " (id, namespace, key_name, value_json, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (id) DO UPDATE SET value_json = EXCLUDED.value_json, updated_at = EXCLUDED.updated_at";
        try (Connection conn = dataSource.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, createStoreKey(item.getNamespace(), item.getKey()));
            stmt.setString(2, objectMapper.writeValueAsString(item.getNamespace()));
            stmt.setString(3, item.getKey());
            stmt.setString(4, objectMapper.writeValueAsString(item.getValue()));
            stmt.setTimestamp(5, new Timestamp(item.getCreatedAt()));
            stmt.setTimestamp(6, new Timestamp(item.getUpdatedAt()));
            stmt.executeUpdate();
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to store item in database", e);
        }
    }
}
