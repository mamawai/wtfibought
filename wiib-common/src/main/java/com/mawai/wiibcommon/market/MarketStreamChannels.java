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
     * {"symbol","type","price"}                            普通 tick：type ∈ markprice|futures
     * {"type":"gap","kind":"mark|futures|spot","from","to"} 行情连接的空窗区间（无 symbol），sim 拉 1m K 线补漏
     * </pre>
     * sim（MatchPriceConsumer）取 markprice 做强平、取 gap 补漏；agent 的 VolatilitySentinel、WatchSentinel 取 markprice
     * 喂波动哨兵和盯盘哨兵；quant 的 ExecutionPriceConsumer 取 futures tick 驱动策略触价单。
     * gap 的 kind：mark 标记价连接断过，futures/spot 是合约/现货成交连接断过（这段成交没进 Stream）。
     */
    public static final String PRICE = "feed:price";

    /**
     * 逐笔成交 Redis Stream key 前缀，每币一条，feed 按到达顺序 XADD，字段见下面 TRADE_*。
     * sim 按顺序读来判限价单、止损、止盈（消费组 sim）；合约这条 agent 还拿来算订单流指标。
     * 现货单独一条，币名跟合约撞（都叫 BTCUSDT）。
     */
    public static final String FUTURES_TRADES_PREFIX = "market:orderflow:";
    public static final String SPOT_TRADES_PREFIX = "market:spot-trade:";

    /** 成交时间（交易所毫秒） */
    public static final String TRADE_TS = "ts";
    /** 成交价，币安原样字符串 */
    public static final String TRADE_PRICE = "p";
    /** 成交量，币安原样字符串 */
    public static final String TRADE_QTY = "q";
    /** "1" 买方是挂单方（这笔是主动卖），"0" 主动买 */
    public static final String TRADE_BUYER_MAKER = "bm";
    /** feed 收到这笔的时刻（本机毫秒）；现货盘口快照没有交易所时间，跟它比先后 */
    public static final String TRADE_RECEIVED_AT = "rt";

    /**
     * K线收盘 Redis Stream key（feed 写 → agent 侧 KlineStreamConsumer 用 quant 消费组消费）。
     * 收盘驱动策略信号 / 交易员唤醒 / 叙事对账，丢一根少一次触发，必须 at-least-once，
     * 故用 Stream+消费组而非会丢的 Pub/Sub。
     */
    public static final String KLINE_CLOSED_STREAM = "stream:kline:closed";
}
