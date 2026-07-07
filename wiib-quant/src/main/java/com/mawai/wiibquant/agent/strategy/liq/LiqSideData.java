package com.mawai.wiibquant.agent.strategy.liq;

/**
 * LiqFade 的 K 线外签名数据门面（premium / taker 买量）。
 *
 * <p>契约：返回值必须是"查询时点已闭合可见"的数据（决策无未来函数）；
 * 缺数据/超约定时效一律返回 NaN，策略把 NaN 当"签名不触发"处理——只会少做，不会做错方向。
 * 回测由 runner 从 DB 历史装载；实盘由 feed 补 1m premium/taker 采样落 Redis 后适配（待接线）。</p>
 */
public interface LiqSideData {

    /** openMs 开盘的那根 5m 桶的 taker 买量（base 量纲，与 KlineBar.volume 同源可比）；缺 → NaN。 */
    double takerBuy(String symbol, long bucketOpenMs);

    /** ≤ atMs 的最新 premium 采样（perp 相对指数价的比例，如 -0.0015 = -15bp）；无/超时效 → NaN。 */
    double premiumAt(String symbol, long atMs);
}
