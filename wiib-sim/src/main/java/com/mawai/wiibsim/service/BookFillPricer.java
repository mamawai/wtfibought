package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.market.DepthStreamCache;
import com.mawai.wiibcommon.market.DepthStreamCache.Snapshot;
import com.mawai.wiibcommon.market.TradeRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static com.mawai.wiibcommon.market.MarketStreamChannels.*;
import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 市价成交价：拿这一刻的 depth20 快照，买单从卖一往上、卖单从买一往下一档一档吃，取加权均价。
 * 每笔单子独立扫真实盘口，不扣别的 sim 单吃过的量；超过 20 档的部分按最后一档的价。
 * 快照比 Stream 里最新一笔成交旧时，扫出来的价不能好于那笔成交（买不低于、卖不高于）；快照不旧就照盘口。
 * feed 1 秒内没收到过快照、或者扫的过程出错，就用调用方给的兜底价。
 * 限价单另有 {@link #futuresWithinLimit}：只吃挂单价以内的档，看能不能整笔成交。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookFillPricer {

    /** 快照、触发价超过这么久就算旧的 */
    static final long MAX_AGE_MS = 1_000L;
    private static final int PRICE_SCALE = 8;

    private final DepthStreamCache depthCache;
    private final StringRedisTemplate redisTemplate;

    /** 现在下的单：按这一刻的盘口扫 */
    public BigDecimal futures(String symbol, boolean buy, BigDecimal qty, BigDecimal fallback) {
        return fill(depthCache.snapshot(symbol), FUTURES_TRADES_PREFIX + symbol, buy, qty, fallback);
    }

    /**
     * 触发出来的单：price 是触发它的价，priceAt 是这个价出现的时刻。
     * 超过 1 秒的旧价（补漏、积压的成交）跟现在的盘口对不上，直接按 price 成交
     */
    public BigDecimal futures(String symbol, boolean buy, BigDecimal qty, BigDecimal price, long priceAt) {
        if (System.currentTimeMillis() - priceAt > MAX_AGE_MS) return price;
        return futures(symbol, buy, qty, price);
    }

    public BigDecimal spot(String symbol, boolean buy, BigDecimal qty, BigDecimal fallback) {
        return fill(depthCache.spotSnapshot(symbol), SPOT_TRADES_PREFIX + symbol, buy, qty, fallback);
    }

    /**
     * 限价单这一刻能不能整笔成交：只吃挂单价以内的档（买不高于、卖不低于），够整笔返回成交价，不够返回 null。
     * 没盘口就看 fallback（标记价/最新价）够不够得着挂单价，够得着按 fallback 成交
     */
    public BigDecimal futuresWithinLimit(String symbol, boolean buy, BigDecimal qty, BigDecimal limit, BigDecimal fallback) {
        return withinLimit(depthCache.snapshot(symbol), FUTURES_TRADES_PREFIX + symbol, buy, qty, limit, fallback);
    }

    public BigDecimal spotWithinLimit(String symbol, boolean buy, BigDecimal qty, BigDecimal limit, BigDecimal fallback) {
        return withinLimit(depthCache.spotSnapshot(symbol), SPOT_TRADES_PREFIX + symbol, buy, qty, limit, fallback);
    }

    private BigDecimal fill(Snapshot snap, String tradesKey, boolean buy, BigDecimal qty, BigDecimal fallback) {
        try {
            JsonNode book = freshBook(snap);
            BigDecimal vwap = book == null ? null : walk(book, buy, qty, null);
            return vwap == null ? fallback : capByLatest(book, snap.atMs(), tradesKey, buy, vwap);
        } catch (Exception e) {
            log.warn("[BookFill] 扫盘口失败，按兜底价成交 trades={} msg={}", tradesKey, e.toString());
            return fallback;
        }
    }

    private BigDecimal withinLimit(Snapshot snap, String tradesKey, boolean buy, BigDecimal qty,
                                   BigDecimal limit, BigDecimal fallback) {
        try {
            JsonNode book = freshBook(snap);
            if (book != null) {
                BigDecimal vwap = walk(book, buy, qty, limit);
                return vwap == null ? null : reachable(capByLatest(book, snap.atMs(), tradesKey, buy, vwap), buy, limit);
            }
        } catch (Exception e) {
            log.warn("[BookFill] 扫盘口失败，按兜底价判 trades={} msg={}", tradesKey, e.toString());
        }
        return reachable(fallback, buy, limit);
    }

    /** price 没越过挂单价（买不高于、卖不低于）就是它，越过了返回 null */
    private static BigDecimal reachable(BigDecimal price, boolean buy, BigDecimal limit) {
        return (buy ? price.compareTo(limit) <= 0 : price.compareTo(limit) >= 0) ? price : null;
    }

    /** feed 1 秒内收到的快照；没有返回 null */
    private static JsonNode freshBook(Snapshot snap) {
        if (snap == null || System.currentTimeMillis() - snap.atMs() > MAX_AGE_MS) return null;
        return MAPPER.readTree(snap.json());
    }

    /**
     * 从一档往后吃 qty，返回加权均价；超过 20 档的部分按最后一档的价。
     * limit 不为空只吃挂单价以内的档，碰到挂单价外面那档还没吃够就返回 null；这一边是空的也返回 null
     */
    private static BigDecimal walk(JsonNode book, boolean buy, BigDecimal qty, BigDecimal limit) {
        BigDecimal left = qty;
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal price = null;
        for (JsonNode level : book.path(buy ? "asks" : "bids")) {
            BigDecimal levelPrice = new BigDecimal(level.get(0).asString());
            if (limit != null && reachable(levelPrice, buy, limit) == null) return null;
            price = levelPrice;
            BigDecimal take = left.min(new BigDecimal(level.get(1).asString()));
            amount = amount.add(take.multiply(price));
            left = left.subtract(take);
            if (left.signum() == 0) break;
        }
        if (price == null) return null;
        if (left.signum() > 0) amount = amount.add(left.multiply(price));
        return amount.divide(qty, PRICE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 快照比成交 Stream 里最新一笔旧：扫出来的价不能好于那笔（买不低于、卖不高于）。
     * 合约快照带交易所事件时间 E，跟成交时间 T 比；现货快照没有 E，两边都用 feed 收到的时刻比
     */
    private BigDecimal capByLatest(JsonNode book, long snapAt, String tradesKey, boolean buy, BigDecimal vwap) {
        TradeRecord latest = latestTrade(tradesKey);
        if (latest == null) return vwap;
        boolean bookOlder = book.has("E") ? book.get("E").asLong() < latest.ts() : snapAt < latest.receivedAt();
        if (!bookOlder) return vwap;
        return buy ? vwap.max(latest.price()) : vwap.min(latest.price());
    }

    /** 成交 Stream 里最后一笔；没有返回 null */
    private TradeRecord latestTrade(String key) {
        List<MapRecord<String, Object, Object>> last = redisTemplate.opsForStream()
                .reverseRange(key, Range.unbounded(), Limit.limit().count(1));
        if (last == null || last.isEmpty()) return null;
        return TradeRecord.of(last.getFirst().getValue());
    }
}
