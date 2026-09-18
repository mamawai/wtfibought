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
     * {"symbol","type","price"}                       普通 tick：type ∈ spot|markprice|futures
     * {"type":"gap","kind":"spot|futures","from","to"} 价格连接的空窗区间（无 symbol），sim 拉 1m K 线补漏
     * </pre>
     * sim（MatchPriceConsumer）订阅做撮合/强平/结算；agent 两个订阅者——VolatilitySentinel 取 markprice
     * 喂波动哨兵，ExecutionPriceConsumer 取 futures tick 驱动策略触价单。
     */
    public static final String PRICE = "feed:price";

    /**
     * K线收盘 Redis Stream key（feed 写 → agent 侧 KlineStreamConsumer 用 quant 消费组消费）。
     * 收盘驱动策略信号 / 交易员唤醒 / 叙事对账，丢一根少一次触发，必须 at-least-once，
     * 故用 Stream+消费组而非会丢的 Pub/Sub。
     */
    public static final String KLINE_CLOSED_STREAM = "stream:kline:closed";
}
