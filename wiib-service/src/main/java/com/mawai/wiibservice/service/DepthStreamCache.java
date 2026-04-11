package com.mawai.wiibservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 合约 depth20@100ms WS 快照缓存。
 * 比 REST 轮询更新鲜（100ms 级），供 CollectDataNode 优先使用。
 */
@Slf4j
@Component
public class DepthStreamCache {

    private final ConcurrentHashMap<String, Snapshot> snapshots = new ConcurrentHashMap<>();

    /** WS depth 回调，存原始 JSON + 交易所事件时间 */
    public void onDepthUpdate(String symbol, String rawJson, long eventTimeMs) {
        snapshots.put(symbol, new Snapshot(rawJson, eventTimeMs));
    }

    /**
     * 获取缓存的深度快照。
     * @param maxAgeMs 最大允许年龄（毫秒），超龄返回 null
     * @return 原始 JSON 或 null
     */
    public String getFreshDepth(String symbol, long maxAgeMs) {
        Snapshot snap = snapshots.get(symbol);
        if (snap == null) return null;
        if (System.currentTimeMillis() - snap.timestamp > maxAgeMs) return null;
        return snap.json;
    }

    public boolean hasFreshData(String symbol) {
        return getFreshDepth(symbol, 2000) != null;
    }

    private record Snapshot(String json, long timestamp) {}
}
