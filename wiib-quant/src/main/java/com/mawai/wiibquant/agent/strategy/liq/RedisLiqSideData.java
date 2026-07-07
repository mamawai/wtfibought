package com.mawai.wiibquant.agent.strategy.liq;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link LiqSideData} 实盘实现：读 feed 落的 Redis 采样。
 * 写方与键格式（跨模块各自持常量的既有惯例，改动须两侧同步）：
 * <ul>
 *   <li>taker 买量：KlineStreamHandler 收盘写 {@code market:takerbuy5m:<SYM>:<openMs>}=V原串（TTL 2h）</li>
 *   <li>指数价：FuturesStreamHandler markPrice 流写 {@code market:indexprice:<SYM>}=价格|写入ms</li>
 *   <li>最新价：FuturesStreamHandler miniTicker 流写 {@code market:futures-price:<SYM>}</li>
 * </ul>
 * 契约：缺数据/超时效一律 NaN——签名不触发只少做不做错；值格式异常直接抛（feed 侧 bug 要暴露，
 * 由 StrategyRuntime 的按根兜底捕获）。
 */
public final class RedisLiqSideData implements LiqSideData {

    private static final String TAKER_BUY_PREFIX = "market:takerbuy5m:";
    private static final String INDEX_PRICE_PREFIX = "market:indexprice:";
    private static final String FUTURES_PRICE_PREFIX = "market:futures-price:";

    private final StringRedisTemplate redis;
    private final long premStaleMaxMs;

    public RedisLiqSideData(StringRedisTemplate redis, long premStaleMaxMs) {
        this.redis = redis;
        this.premStaleMaxMs = premStaleMaxMs;
    }

    @Override
    public double takerBuy(String symbol, long bucketOpenMs) {
        String v = redis.opsForValue().get(TAKER_BUY_PREFIX + symbol + ":" + bucketOpenMs);
        return v == null ? Double.NaN : Double.parseDouble(v);
    }

    /**
     * premium=(最新价-指数价)/指数价（上线前口径校验：与研究用 vision premiumIndex 重叠窗口对比）。
     * 指数价采样比 atMs 旧超过 premStaleMaxMs 按缺数据 NaN；采样比 atMs 新属实盘正常
     * （收盘后 1-2s 决策，feed 每秒在写），决策时点已可见，放行不算未来函数。
     */
    @Override
    public double premiumAt(String symbol, long atMs) {
        String idxRaw = redis.opsForValue().get(INDEX_PRICE_PREFIX + symbol);
        String last = redis.opsForValue().get(FUTURES_PRICE_PREFIX + symbol);
        if (idxRaw == null || last == null) return Double.NaN;
        int sep = idxRaw.indexOf('|');
        if (atMs - Long.parseLong(idxRaw.substring(sep + 1)) > premStaleMaxMs) return Double.NaN;
        double idx = Double.parseDouble(idxRaw.substring(0, sep));
        return (Double.parseDouble(last) - idx) / idx;
    }
}
