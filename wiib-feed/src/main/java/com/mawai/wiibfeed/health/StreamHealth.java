package com.mawai.wiibfeed.health;

import com.mawai.wiibfeed.WsConnection;

/**
 * 一条 WS 流的健康快照（前端展示 + 手动重试用）。
 * status 序列化为枚举名（CONNECTED/CONNECTING/RECONNECTING/DISCONNECTED）；lastMessageAt 为 epoch millis，
 * 前端可本地算"距上次数据 Xs"而无需服务端轮询。
 */
public record StreamHealth(String name, WsConnection.Status status, long lastMessageAt, int reconnectAttempt) {
    public static StreamHealth of(WsConnection c) {
        return new StreamHealth(c.name(), c.status(), c.lastMessageAt(), c.reconnectAttempt());
    }
}
