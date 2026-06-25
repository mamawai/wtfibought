package com.mawai.wiibservice.service;

import com.alibaba.fastjson2.JSON;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 缓存服务 - 封装Redis操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    // L1: 股票日内行情（getCurrentPrice + getDailyQuote 共用）
    private final Cache<Long, Map<String, String>> stockDailyCache = Caffeine.newBuilder().maximumSize(500).build();
    // L1: 加密货币价格（spot/mark 用 key 前缀区分）
    private final Cache<String, BigDecimal> cryptoPriceCache = Caffeine.newBuilder().maximumSize(50).build();

    // prediction 缓存改 Redis 后端（feed 写 sim 读跨进程），窗口/盘口数据靠 TTL 自动清理
    private static final Duration PREDICTION_TTL = Duration.ofMinutes(20);

    // ==================== 实时行情 ====================

    /**
     * 获取股票实时价格
     * @return 当前价格，无数据返回null
     */
    public BigDecimal getCurrentPrice(Long stockId) {
        Map<String, String> daily = getStockDaily(stockId);
        if (daily == null) return null;
        String last = daily.get("last");
        return last != null ? new BigDecimal(last) : null;
    }

    /**
     * 批量获取股票实时价格
     * @return stockId -> price，无数据的stockId不在map中
     */
    public Map<Long, BigDecimal> getCurrentPrices(List<Long> stockIds) {
        if (stockIds == null || stockIds.isEmpty()) return Map.of();
        Map<Long, BigDecimal> priceMap = new HashMap<>();
        for (Long id : stockIds) {
            BigDecimal price = getCurrentPrice(id);
            if (price != null) priceMap.put(id, price);
        }
        return priceMap;
    }

    /**
     * 获取股票当日行情汇总
     * @return {open, high, low, last, prevClose}
     */
    public Map<String, BigDecimal> getDailyQuote(Long stockId) {
        Map<String, String> daily = getStockDaily(stockId);
        if (daily == null) return null;

        Map<String, BigDecimal> result = new HashMap<>();
        daily.forEach((k, v) -> result.put(k, new BigDecimal(v)));
        return result;
    }

    public void putStockDaily(Long stockId, Map<String, String> daily) {
        stockDailyCache.put(stockId, daily);
    }

    private Map<String, String> getStockDaily(Long stockId) {
        Map<String, String> daily = stockDailyCache.getIfPresent(stockId);
        if (daily != null) return daily;
        // fallback: 重启后caffeine为空，从redis加载
        String dailyKey = String.format("stock:daily:%s:%d", LocalDate.now(), stockId);
        Map<Object, Object> raw = stringRedisTemplate.opsForHash().entries(dailyKey);
        if (raw.isEmpty()) return null;
        Map<String, String> m = new HashMap<>(raw.size());
        raw.forEach((k, v) -> m.put(k.toString(), v.toString()));
        stockDailyCache.put(stockId, m);
        return m;
    }

    public BigDecimal getPrevTradingDayLast(Long stockId) {
        LocalDate today = LocalDate.now();
        LocalDate prevTradeDay = switch (today.getDayOfWeek()) {
            case SUNDAY -> today.minusDays(2);
            case MONDAY -> today.minusDays(3);
            default -> today.minusDays(1);
        };
        String key = String.format("stock:daily:%s:%d", prevTradeDay, stockId);
        String last = (String) stringRedisTemplate.opsForHash().get(key, "last");
        return last != null ? new BigDecimal(last) : null;
    }

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
        stringRedisTemplate.opsForValue().set("prediction:window:" + windowStart, JSON.toJSONString(w), PREDICTION_TTL);
    }

    public PredictionOfficialWindow getPredictionOfficialWindow(long windowStart) {
        String v = stringRedisTemplate.opsForValue().get("prediction:window:" + windowStart);
        return v != null ? JSON.parseObject(v, PredictionOfficialWindow.class) : null;
    }

    // ==================== BTC价格历史（feed 写 sim 读，Redis ZSet：score=ts，member=ts:price 保唯一） ====================

    private static final long PRICE_HISTORY_TTL_MS = 360_000;
    private static final String BTC_PRICE_HISTORY_KEY = "prediction:btcprice:history";

    /** chainlink BTC 价格点入 ZSet，按 score 裁掉 6 分钟外的旧点 */
    public void addBtcPricePoint(long timestampMs, BigDecimal price) {
        ZSetOperations<String, String> zset = stringRedisTemplate.opsForZSet();
        zset.add(BTC_PRICE_HISTORY_KEY, timestampMs + ":" + price.toPlainString(), timestampMs);
        zset.removeRangeByScore(BTC_PRICE_HISTORY_KEY, 0, timestampMs - PRICE_HISTORY_TTL_MS);
    }

    public List<Map<String, Object>> getBtcPriceHistory(long fromMs) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                stringRedisTemplate.opsForZSet().rangeByScoreWithScores(BTC_PRICE_HISTORY_KEY, fromMs, Double.MAX_VALUE);
        if (tuples == null || tuples.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>(tuples.size());
        for (ZSetOperations.TypedTuple<String> t : tuples) {
            String member = t.getValue();
            if (member == null || t.getScore() == null) continue;
            // member=ts:price，价格纯数字不含冒号，取第一个冒号后即价格
            result.add(Map.of("time", t.getScore().longValue(),
                    "price", member.substring(member.indexOf(':') + 1)));
        }
        return result;
    }

    // ==================== Polymarket UP/DOWN 价格 ====================

    public void putPredictionBid(String side, BigDecimal bid) {
        stringRedisTemplate.opsForValue().set("prediction:" + side + ":bid", bid.toPlainString(), PREDICTION_TTL);
    }

    public BigDecimal getPredictionBid(String side) {
        String v = stringRedisTemplate.opsForValue().get("prediction:" + side + ":bid");
        return v != null ? new BigDecimal(v) : null;
    }

    public void putPredictionAsk(String side, BigDecimal ask) {
        stringRedisTemplate.opsForValue().set("prediction:" + side + ":ask", ask.toPlainString(), PREDICTION_TTL);
    }

    public BigDecimal getPredictionAsk(String side) {
        String v = stringRedisTemplate.opsForValue().get("prediction:" + side + ":ask");
        return v != null ? new BigDecimal(v) : null;
    }

    public void clearPredictionPrices() {
        stringRedisTemplate.delete(java.util.List.of(
                "prediction:UP:bid", "prediction:UP:ask", "prediction:DOWN:bid", "prediction:DOWN:ask"));
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
        if (val != null) {
            BigDecimal price = new BigDecimal(val);
            cryptoPriceCache.put("mark:" + symbol, price);
            return price;
        }
        return getCryptoPrice(symbol);
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

    // ==================== 通用缓存 ====================

    public void set(String key, String value, long timeout, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    public void set(String key, String value, Duration duration) {
        stringRedisTemplate.opsForValue().set(key, value, duration);
    }

    public void set(String key, String value) {
        stringRedisTemplate.opsForValue().set(key, value);
    }

    public String get(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    public List<String> multiGet(Collection<String> keys) {
        return stringRedisTemplate.opsForValue().multiGet(keys);
    }

    public void delete(String key) {
        stringRedisTemplate.unlink(key);
    }

    public boolean hasKey(String key) {
        return stringRedisTemplate.hasKey(key);
    }

    public boolean setIfAbsent(String key, String value, long timeout, TimeUnit unit) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, value, timeout, unit));
    }

    public void expire(String key, long timeout, TimeUnit unit) {
        stringRedisTemplate.expire(key, timeout, unit);
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

    // ==================== Hash操作 ====================

    public void hSet(String key, String field, String value) {
        stringRedisTemplate.opsForHash().put(key, field, value);
    }

    public String hGet(String key, String field) {
        Object value = stringRedisTemplate.opsForHash().get(key, field);
        return value != null ? value.toString() : null;
    }

    public Map<String, String> hGetAll(String key) {
        Map<Object, Object> raw = stringRedisTemplate.opsForHash().entries(key);
        Map<String, String> result = new HashMap<>();
        raw.forEach((k, v) -> result.put(k.toString(), v.toString()));
        return result;
    }

    public void hSetAll(String key, Map<String, String> hash) {
        stringRedisTemplate.opsForHash().putAll(key, hash);
    }

    // ==================== List操作 ====================

    public void lRightPushAll(String key, List<String> values) {
        stringRedisTemplate.opsForList().rightPushAll(key, values);
    }

    public List<String> lRange(String key, long start, long end) {
        return stringRedisTemplate.opsForList().range(key, start, end);
    }

    public String lIndex(String key, long index) {
        return stringRedisTemplate.opsForList().index(key, index);
    }

    public Long lLen(String key) {
        return stringRedisTemplate.opsForList().size(key);
    }

    // ==================== 对象序列化（使用RedisTemplate） ====================

    public void setObject(String key, Object obj, long timeout, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, obj, timeout, unit);
    }

    @SuppressWarnings("unchecked")
    public <T> T getObject(String key) {
        return (T) redisTemplate.opsForValue().get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> getList(String key) {
        return (List<T>) redisTemplate.opsForValue().get(key);
    }

    // ==================== Set操作 ====================

    /**
     * 添加元素到Set
     */
    public void sAdd(String key, String... values) {
        stringRedisTemplate.opsForSet().add(key, values);
    }

    /**
     * 从Set中移除元素
     */
    public Long sRemove(String key, Object... values) {
        return stringRedisTemplate.opsForSet().remove(key, values);
    }

    /**
     * 获取Set的所有成员
     */
    public Set<String> sMembers(String key) {
        return stringRedisTemplate.opsForSet().members(key);
    }

    /**
     * 判断元素是否在Set中
     */
    public Boolean sIsMember(String key, Object value) {
        return stringRedisTemplate.opsForSet().isMember(key, value);
    }

    /**
     * 获取Set的大小
     */
    public Long sSize(String key) {
        return stringRedisTemplate.opsForSet().size(key);
    }

    /**
     * 多个Set的交集
     */
    public Set<String> sIntersect(String key, String otherKey) {
        return stringRedisTemplate.opsForSet().intersect(key, otherKey);
    }

    /**
     * 多个Set的并集
     */
    public Set<String> sUnion(String key, String otherKey) {
        return stringRedisTemplate.opsForSet().union(key, otherKey);
    }

    /**
     * 多个Set的差集
     */
    public Set<String> sDifference(String key, String otherKey) {
        return stringRedisTemplate.opsForSet().difference(key, otherKey);
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

    public Set<String> zRangeByScore(String key, double min, double max) {
        return stringRedisTemplate.opsForZSet().rangeByScore(key, min, max);
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

    // ==================== 通用Key操作 ====================

    /**
     * 删除多个key
     */
    public Long deleteKeys(Collection<String> keys) {
        return stringRedisTemplate.delete(keys);
    }

    /**
     * 批量删除key（通配符）
     * 使用SCAN迭代+UNLINK异步删除，避免阻塞Redis
     */
    public void deletePattern(String pattern) {
        Set<String> keys = scanKeys(pattern, 200);
        if (!keys.isEmpty()) {
            stringRedisTemplate.unlink(keys);
        }
    }

    /**
     * 查找匹配的key（使用SCAN迭代，避免KEYS阻塞）
     */
    public Set<String> scanKeys(String pattern, int count) {
        Set<String> keys = new HashSet<>();
        try (Cursor<String> cursor = stringRedisTemplate.scan(
                ScanOptions.scanOptions()
                        .match(pattern)
                        .count(count)
                        .build())) {
            cursor.forEachRemaining(keys::add);
        } catch (Exception e) {
            log.error("SCAN keys failed: pattern={}, count={}, error={}",
                    pattern, count, e.getMessage(), e);
            throw new RuntimeException("SCAN keys failed: " + e.getMessage(), e);
        }
        return keys;
    }

    /**
     * 检查多个key是否存在
     */
    public Long countExistingKeys(Collection<String> keys) {
        return stringRedisTemplate.countExistingKeys(keys);
    }
}
