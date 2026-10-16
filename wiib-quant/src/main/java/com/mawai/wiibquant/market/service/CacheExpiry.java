package com.mawai.wiibquant.market.service;

/**
 * 行情缓存的到期时刻：TTL 和下一根 K 线边界取早的，末根一收盘就得重拉。
 * 刚过边界 {@link #EDGE_MS} 内拉的只存 {@link #EDGE_MS}：我们时钟比币安快一点时，回包里上一根可能还是半成品。
 */
final class CacheExpiry {

    static final long EDGE_MS = 2_000L;

    private CacheExpiry() {
    }

    /** intervalMs ≤ 0 = 不看边界，只按 TTL */
    static long at(long fetchedAt, long ttlMs, long intervalMs) {
        if (intervalMs <= 0) {
            return fetchedAt + ttlMs;
        }
        long edge = edge(fetchedAt, intervalMs);
        return fetchedAt - edge < EDGE_MS ? fetchedAt + EDGE_MS : Math.min(fetchedAt + ttlMs, edge + intervalMs);
    }

    /** 刚过去的那个边界 */
    static long edge(long at, long intervalMs) {
        return at - Math.floorMod(at, intervalMs);
    }
}
