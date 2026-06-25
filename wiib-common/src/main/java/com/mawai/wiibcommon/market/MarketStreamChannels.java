package com.mawai.wiibcommon.market;

/**
 * 行情流 Redis 通道契约（feed 发布 → sim/quant 订阅）。
 * 拆服务后这是跨进程的唯一行情入口，集中定义避免散落硬编码。
 */
public final class MarketStreamChannels {

    private MarketStreamChannels() {}

    /**
     * 行情价格事件 Pub/Sub 通道。feed 发布，payload 为 JSON：
     * <pre>
     * {"symbol","type","price"}              普通 tick：type ∈ spot|markprice|futures
     * {"symbol","type":"spot-recover","low","high"}       现货限价单区间补漏
     * {"symbol","type":"futures-recover","low","high"}    合约限价单区间补漏
     * {"symbol","type":"liq-recover","markLow","markHigh","futLow","futHigh"}  强平区间补漏
     * </pre>
     * sim（MatchPriceConsumer）订阅做撮合/强平/结算；quant（SentinelPriceConsumer）只取 markprice 喂哨兵。
     */
    public static final String PRICE = "feed:price";

    /**
     * K线收盘 Redis Stream key（feed 写 → quant 消费组消费）。
     * 收盘驱动预测/策略，丢一根少一次触发，必须 at-least-once，故用 Stream+消费组而非会丢的 Pub/Sub。
     */
    public static final String KLINE_CLOSED_STREAM = "stream:kline:closed";
}
