package com.mawai.wiibcommon.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mawai.wiibcommon.market.TimeWeightedAverage.Point;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 缓存服务 - 封装Redis操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    // L1: 加密货币价格（spot/mark/futures 用 key 前缀区分）。put 刷新只在 feed 进程（WS tick），
    // sim/quant 读进程无人回填，必须短过期回源 Redis——否则拆进程后首读值永久冻结（曾致标记价/成交价死价）
    private final Cache<String, BigDecimal> cryptoPriceCache = Caffeine.newBuilder()
            .maximumSize(50).expireAfterWrite(Duration.ofSeconds(1)).build();

    // prediction 缓存改 Redis 后端（feed 写 sim 读跨进程），窗口/盘口数据靠 TTL 自动清理
    private static final Duration PREDICTION_TTL = Duration.ofMinutes(20);


    // ==================== Polymarket openPrice ====================

    public void putPolymarketOpenPrice(long windowStart, BigDecimal price) {
        stringRedisTemplate.opsForValue().set("prediction:openprice:" + windowStart, price.toPlainString(), PREDICTION_TTL);
    }

    public BigDecimal getPolymarketOpenPrice(long windowStart) {
        String v = stringRedisTemplate.opsForValue().get("prediction:openprice:" + windowStart);
        return v != null ? new BigDecimal(v) : null;
    }

    public void putPolymarketClosePrice(long windowStart, BigDecimal price) {
        stringRedisTemplate.opsForValue().set("prediction:closeprice:" + windowStart, price.toPlainString(), PREDICTION_TTL);
    }

    public BigDecimal getPolymarketClosePrice(long windowStart) {
        String v = stringRedisTemplate.opsForValue().get("prediction:closeprice:" + windowStart);
        return v != null ? new BigDecimal(v) : null;
    }

    // ==================== Polymarket官方回合时间 ====================

    public record PredictionOfficialWindow(
            long windowStart,
            long startTimeMs,
            long endTimeMs,
            long referenceNowMs,
            long referenceLocalTimeMs
    ) {}

    // feed 写 sim 读：官方回合时间存 Redis（JSON），TTL 自动清理旧窗口
    public void putPredictionOfficialWindow(long windowStart, long startTimeMs, long endTimeMs,
                                            long referenceNowMs, long referenceLocalTimeMs) {
        PredictionOfficialWindow w = new PredictionOfficialWindow(
                windowStart, startTimeMs, endTimeMs, referenceNowMs, referenceLocalTimeMs);
        stringRedisTemplate.opsForValue().set("prediction:window:" + windowStart, MAPPER.writeValueAsString(w), PREDICTION_TTL);
    }

    public PredictionOfficialWindow getPredictionOfficialWindow(long windowStart) {
        String v = stringRedisTemplate.opsForValue().get("prediction:window:" + windowStart);
        return v != null ? MAPPER.readValue(v, PredictionOfficialWindow.class) : null;
    }

    // ==================== BTC价格历史（feed 写，Redis ZSet：score=ts，member=ts:price 保唯一，按 6 分钟裁） ====================
    // 现货给预测员算公平价；TWAP 就是官网显示的当前价，页面折线图用

    private static final long PRICE_HISTORY_TTL_MS = 360_000;
    private static final String BTC_PRICE_HISTORY_KEY = "prediction:btcprice:history";
    private static final String BTC_TWAP_HISTORY_KEY = "prediction:btctwap:history";

    /** Chainlink BTC 现货价点 */
    public void addBtcPricePoint(long timestampMs, BigDecimal price) {
        addPoint(BTC_PRICE_HISTORY_KEY, timestampMs, price);
    }

    /** 现货价点按时间升序（时刻 + 价） */
    public List<Point> getBtcPricePoints(long fromMs) {
        return pricePoints(BTC_PRICE_HISTORY_KEY, fromMs);
    }

    /** Chainlink BTC 60 秒 TWAP 点 */
    public void addBtcTwapPoint(long timestampMs, BigDecimal price) {
        addPoint(BTC_TWAP_HISTORY_KEY, timestampMs, price);
    }

    /** TWAP 历史的接口形状（页面折线图用）：{time, price 字符串} */
    public List<Map<String, Object>> getBtcTwapHistory(long fromMs) {
        List<Point> points = pricePoints(BTC_TWAP_HISTORY_KEY, fromMs);
        List<Map<String, Object>> result = new ArrayList<>(points.size());
        for (Point p : points) {
            result.add(Map.of("time", p.timeMs(), "price", p.price().toPlainString()));
        }
        return result;
    }

    /** 写一个点，顺手裁掉 6 分钟外的旧点 */
    private void addPoint(String key, long timestampMs, BigDecimal price) {
        ZSetOperations<String, String> zset = stringRedisTemplate.opsForZSet();
        zset.add(key, timestampMs + ":" + price.toPlainString(), timestampMs);
        zset.removeRangeByScore(key, 0, timestampMs - PRICE_HISTORY_TTL_MS);
    }

    /** 读 member=ts:price 的 ZSet，按时间升序；价格纯数字不含冒号，第一个冒号后就是价 */
    private List<Point> pricePoints(String key, long fromMs) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                stringRedisTemplate.opsForZSet().rangeByScoreWithScores(key, fromMs, Double.MAX_VALUE);
        if (tuples == null) return List.of();
        List<Point> result = new ArrayList<>(tuples.size());
        for (ZSetOperations.TypedTuple<String> t : tuples) {
            String member = t.getValue();
            result.add(new Point(t.getScore().longValue(), new BigDecimal(member.substring(member.indexOf(':') + 1))));
        }
        return result;
    }

    // ==================== Polymarket UP/DOWN 价格 ====================

    /** null = 这一边没有买一，删掉 */
    public void putPredictionBid(String side, BigDecimal bid) {
        putOrDelete("prediction:" + side + ":bid", bid);
    }

    public BigDecimal getPredictionBid(String side) {
        String v = stringRedisTemplate.opsForValue().get("prediction:" + side + ":bid");
        return v != null ? new BigDecimal(v) : null;
    }

    /** null = 这一边没有卖一，删掉 */
    public void putPredictionAsk(String side, BigDecimal ask) {
        putOrDelete("prediction:" + side + ":ask", ask);
    }

    private void putOrDelete(String key, BigDecimal price) {
        if (price == null) {
            stringRedisTemplate.delete(key);
        } else {
            stringRedisTemplate.opsForValue().set(key, price.toPlainString(), PREDICTION_TTL);
        }
    }

    public BigDecimal getPredictionAsk(String side) {
        String v = stringRedisTemplate.opsForValue().get("prediction:" + side + ":ask");
        return v != null ? new BigDecimal(v) : null;
    }

    public void clearPredictionPrices() {
        stringRedisTemplate.delete(java.util.List.of(
                "prediction:UP:bid", "prediction:UP:ask", "prediction:DOWN:bid", "prediction:DOWN:ask",
                PREDICTION_BOOK_UPDATED_AT_KEY));
    }

    // ==================== Polymarket 盘口新鲜度与 UP 价走势（feed 每秒采样写，预测员读） ====================

    private static final String PREDICTION_BOOK_UPDATED_AT_KEY = "prediction:book:updatedAt";
    private static final String PREDICTION_UP_MID_HISTORY_KEY = "prediction:upmid:history";

    /** 盘口最近一次变化的时刻(ms)，按 Polymarket 消息自带的时间：盘口价本身不带时间，推送落后或断线时价是旧的，靠它判旧 */
    public void putPredictionBookUpdatedAt(long ms) {
        stringRedisTemplate.opsForValue().set(PREDICTION_BOOK_UPDATED_AT_KEY, Long.toString(ms), PREDICTION_TTL);
    }

    /** 没有记录回 null */
    public Long getPredictionBookUpdatedAt() {
        String v = stringRedisTemplate.opsForValue().get(PREDICTION_BOOK_UPDATED_AT_KEY);
        return v == null ? null : Long.parseLong(v);
    }

    /** UP 的买卖中间价采样点，和 BTC 价格历史同样按 6 分钟裁 */
    public void addPredictionUpMidPoint(long timestampMs, BigDecimal mid) {
        addPoint(PREDICTION_UP_MID_HISTORY_KEY, timestampMs, mid);
    }

    /** UP 中间价采样点按时间升序 */
    public List<Point> getPredictionUpMidPoints(long fromMs) {
        return pricePoints(PREDICTION_UP_MID_HISTORY_KEY, fromMs);
    }

    // ==================== 加密货币价格 ====================

    public BigDecimal getCryptoPrice(String symbol) {
        BigDecimal cached = cryptoPriceCache.getIfPresent("spot:" + symbol);
        if (cached != null) return cached;
        String val = stringRedisTemplate.opsForValue().get("market:price:" + symbol);
        if (val == null) return null;
        BigDecimal price = new BigDecimal(val);
        cryptoPriceCache.put("spot:" + symbol, price);
        return price;
    }

    public Map<String, BigDecimal> getCryptoPrices(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) return Map.of();
        Map<String, BigDecimal> result = new HashMap<>();
        for (String s : symbols) {
            BigDecimal price = getCryptoPrice(s);
            if (price != null) result.put(s, price);
        }
        return result;
    }

    public BigDecimal getMarkPrice(String symbol) {
        BigDecimal cached = cryptoPriceCache.getIfPresent("mark:" + symbol);
        if (cached != null) return cached;
        String val = stringRedisTemplate.opsForValue().get("market:markprice:" + symbol);
        if (val == null) return null;
        BigDecimal price = new BigDecimal(val);
        cryptoPriceCache.put("mark:" + symbol, price);
        return price;
    }

    public BigDecimal getFuturesPrice(String symbol) {
        BigDecimal cached = cryptoPriceCache.getIfPresent("futures:" + symbol);
        if (cached != null) return cached;
        String val = stringRedisTemplate.opsForValue().get("market:futures-price:" + symbol);
        if (val == null) return null;
        BigDecimal price = new BigDecimal(val);
        cryptoPriceCache.put("futures:" + symbol, price);
        return price;
    }

    public void putCryptoPrice(String symbol, BigDecimal price) {
        cryptoPriceCache.put("spot:" + symbol, price);
    }

    public void putFuturesPrice(String symbol, BigDecimal price) {
        cryptoPriceCache.put("futures:" + symbol, price);
    }

    public void putMarkPrice(String symbol, BigDecimal price) {
        cryptoPriceCache.put("mark:" + symbol, price);
    }

    // ==================== 资金费率（结算点写，扣费与前端查询读） ====================

    public record FundingRate(BigDecimal rate, long fetchedAt) {}

    // 9 小时盖到下一个结算点，多出 1 小时留给结算延迟；连着两个结算点都没拉到就自然过期，前端不显示旧数
    private static final Duration FUNDING_RATE_TTL = Duration.ofHours(9);

    public void putFundingRate(String symbol, BigDecimal rate, long fetchedAtMs) {
        stringRedisTemplate.opsForValue().set("market:funding-rate:" + symbol,
                MAPPER.writeValueAsString(new FundingRate(rate, fetchedAtMs)), FUNDING_RATE_TTL);
    }

    public FundingRate getFundingRate(String symbol) {
        String v = stringRedisTemplate.opsForValue().get("market:funding-rate:" + symbol);
        return v != null ? MAPPER.readValue(v, FundingRate.class) : null;
    }

    // ==================== 通用缓存 ====================

    public void set(String key, String value, Duration duration) {
        stringRedisTemplate.opsForValue().set(key, value, duration);
    }

    public void set(String key, String value) {
        stringRedisTemplate.opsForValue().set(key, value);
    }

    public String get(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    public void delete(String key) {
        stringRedisTemplate.unlink(key);
    }

    public boolean setIfAbsent(String key, String value, Duration ttl) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, value, ttl));
    }

    public Long increment(String key, long delta) {
        return stringRedisTemplate.opsForValue().increment(key, delta);
    }

    /**
     * 原子扣减，不允许低于 floor。
     * @return 实际扣减量（0 ~ amount）
     */
    public long decrementWithFloor(String key, long amount, long floor) {
        String lua = """
                local cur = tonumber(redis.call('GET', KEYS[1]) or '0')
                local amt = tonumber(ARGV[1])
                local flr = tonumber(ARGV[2])
                local avail = cur - flr
                if avail <= 0 then return 0 end
                if amt > avail then amt = avail end
                redis.call('DECRBY', KEYS[1], amt)
                return amt
                """;
        return stringRedisTemplate.execute(
                RedisScript.of(lua, Long.class),
                List.of(key),
                String.valueOf(amount), String.valueOf(floor)
        );
    }

    // ==================== 对象序列化（使用RedisTemplate） ====================

    public void setObject(String key, Object obj, Duration ttl) {
        redisTemplate.opsForValue().set(key, obj, ttl);
    }

    @SuppressWarnings("unchecked")
    public <T> T getObject(String key) {
        return (T) redisTemplate.opsForValue().get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> getList(String key) {
        return (List<T>) redisTemplate.opsForValue().get(key);
    }

    // ==================== ZSet操作 ====================

    public Boolean zAdd(String key, String value, double score) {
        return stringRedisTemplate.opsForZSet().add(key, value, score);
    }

    public Long zRemove(String key, Object... values) {
        return stringRedisTemplate.opsForZSet().remove(key, values);
    }

    public Double zScore(String key, String value) {
        return stringRedisTemplate.opsForZSet().score(key, value);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Double> zRangeByScoreAndRemove(String key, double min, double max) {
        String lua = """
                local hits = redis.call('ZRANGEBYSCORE', KEYS[1], ARGV[1], ARGV[2])
                if #hits == 0 then return {} end
                local result = {}
                for i, m in ipairs(hits) do
                    result[#result + 1] = m
                    result[#result + 1] = redis.call('ZSCORE', KEYS[1], m)
                end
                redis.call('ZREM', KEYS[1], unpack(hits))
                return result
                """;
        List<String> raw = stringRedisTemplate.execute(
                RedisScript.of(lua, List.class),
                List.of(key),
                String.valueOf(min), String.valueOf(max));
        if (raw.isEmpty()) return Map.of();
        Map<String, Double> result = new LinkedHashMap<>();
        for (int i = 0; i < raw.size(); i += 2) {
            result.put(raw.get(i), Double.parseDouble(raw.get(i + 1)));
        }
        return result;
    }

    public Set<ZSetOperations.TypedTuple<String>> zRangeByScoreWithScores(String key, double min, double max) {
        return stringRedisTemplate.opsForZSet().rangeByScoreWithScores(key, min, max);
    }
}
