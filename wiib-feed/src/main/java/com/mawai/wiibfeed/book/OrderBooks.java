package com.mawai.wiibfeed.book;

import com.mawai.wiibcommon.broadcast.MarketBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全部合约币的本地盘口：depth20 和增量深度两条流往里喂，每半秒把每个币归并好的盘口广播一次，
 * sim 中继到 /topic/depth/{symbol}，交易页的盘口订它。depth20 断着或卡着不推就不发，前端收不到就知道断了。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderBooks {

    /** depth20 那条连接这么久一帧都没来（哪个币的都算）就当它卡住了，一个币都不发 */
    static final long STALE_MS = 2_000;

    private final MarketBroadcaster broadcaster;
    private final Map<String, OrderBook> books = new ConcurrentHashMap<>();
    /** depth20 最近一帧（不分币）收到的时刻 */
    private volatile long topAt;

    /** depth20 的一帧（原始报文，b / a 两个数组各 20 档），atMs 是收到它的时刻 */
    public void onTop(String symbol, String raw, long atMs) {
        topAt = atMs;
        book(symbol).onTop(DepthParse.levels(raw, "\"b\":["), DepthParse.levels(raw, "\"a\":["));
    }

    /** 增量深度的一帧：pu 是上一条的最后更新号，u 是这一条的 */
    public void onDiff(String symbol, long pu, long u, String raw) {
        boolean chained = book(symbol).onDiff(pu, u, DepthParse.levels(raw, "\"b\":["), DepthParse.levels(raw, "\"a\":["));
        if (!chained) log.warn("[盘口] {} 增量接不上（pu={}），20 档以外清掉重攒", symbol, pu);
    }

    /** depth20 那条连接断了 */
    public void resetTop() {
        books.values().forEach(OrderBook::resetTop);
    }

    /** 增量那条连接断了或重连了 */
    public void resetDiff() {
        books.values().forEach(OrderBook::resetDiff);
    }

    @Scheduled(fixedRate = 500)
    public void publish() {
        if (System.currentTimeMillis() - topAt > STALE_MS) return;
        books.forEach((symbol, book) -> {
            String json = book.snapshot();
            if (json != null) broadcaster.broadcastDepth(symbol, json);
        });
    }

    private OrderBook book(String symbol) {
        return books.computeIfAbsent(symbol, _ -> new OrderBook());
    }
}
