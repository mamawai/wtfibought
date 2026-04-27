package com.mawai.wiibservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * aggTrade 流式聚合：维护 5 分钟滑动窗口，按需输出 order flow 指标。
 * BinanceWsClient 写入，BuildFeaturesNode 读取。
 */
@Slf4j
@Component
public class OrderFlowAggregator {

    private static final int MAX_AGE_MS = 300_000; // 5min
    private static final double LARGE_TRADE_USDT = 50_000; // BTC 大单阈值

    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<long[]>> buffers = new ConcurrentHashMap<>();
    // long[]: [0]=timestamp, [1]=priceE2(price*100整数), [2]=qtyE8(qty*1e8整数), [3]=isBuyerMaker(0/1)

    // 每个 symbol 最近一次 onAggTrade 时间戳。WS 卡顿>30s 时 BuildFeaturesNode 注入 STALE_AGG_TRADE
    private final ConcurrentHashMap<String, Long> lastUpdateMs = new ConcurrentHashMap<>();

    /**
     * WS aggTrade 回调，高频调用，零分配热路径。
     * isBuyerMaker=true → taker 是卖方（主动卖）
     */
    public void onAggTrade(String symbol, double price, double qty, boolean isBuyerMaker, long timestamp) {
        buffers.computeIfAbsent(symbol, k -> new ConcurrentLinkedDeque<>())
                .addLast(new long[]{timestamp, (long) (price * 100), (long) (qty * 1e8), isBuyerMaker ? 1 : 0});
        lastUpdateMs.put(symbol, timestamp);
    }

    /** 最近一次 onAggTrade 时间戳；从未收到返回 0 */
    public long getLastUpdateMs(String symbol) {
        return lastUpdateMs.getOrDefault(symbol, 0L);
    }

    /**
     * 获取指定窗口内的 order flow 指标。窗口单位秒。
     * 返回 null 表示该 symbol 无数据（WS 未连接或刚启动）。
     */
    public Metrics getMetrics(String symbol, int windowSeconds) {
        var deque = buffers.get(symbol);
        if (deque == null || deque.isEmpty()) return null;

        long cutoff = System.currentTimeMillis() - windowSeconds * 1000L;
        double buyVol = 0, sellVol = 0, largeBuyVol = 0, largeSellVol = 0;
        int count = 0;

        for (long[] t : deque) {
            if (t[0] < cutoff) continue;
            double p = t[1] / 100.0;
            double q = t[2] / 1e8;
            double usdt = p * q;
            boolean isSell = t[3] == 1;
            if (isSell) {
                sellVol += usdt;
                if (usdt > LARGE_TRADE_USDT) largeSellVol += usdt;
            } else {
                buyVol += usdt;
                if (usdt > LARGE_TRADE_USDT) largeBuyVol += usdt;
            }
            count++;
        }

        double total = buyVol + sellVol;
        if (total < 1) return new Metrics(0, 0, 0, 0, 0);

        double tradeDelta = (buyVol - sellVol) / total;
        double intensity = (double) count / Math.max(1, windowSeconds);
        double largeTotal = largeBuyVol + largeSellVol;
        double largeBias = largeTotal > 0 ? (largeBuyVol - largeSellVol) / largeTotal : 0;
        return new Metrics(tradeDelta, intensity, largeBias, total, count);
    }

    /** 是否有足够数据（至少 10 笔成交） */
    public boolean hasData(String symbol) {
        var deque = buffers.get(symbol);
        return deque != null && deque.size() >= 10;
    }

    /** 定期清理过期数据，由 WS 线程调用 */
    public void trim(String symbol) {
        var deque = buffers.get(symbol);
        if (deque == null) return;
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        long[] head;
        while ((head = deque.peekFirst()) != null && head[0] < cutoff) {
            deque.pollFirst();
        }
    }

    public record Metrics(
            double tradeDelta,      // [-1,1] 主动买卖差
            double tradeIntensity,  // 笔/秒
            double largeTradeBias,  // [-1,1] 大单方向偏差
            double totalVolumeUsdt, // 窗口总成交额
            int tradeCount          // 窗口成交笔数
    ) {}
}
