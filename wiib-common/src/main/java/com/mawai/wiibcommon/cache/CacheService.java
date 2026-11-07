package com.mawai.wiibcommon.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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

    // ==================== BTC TWAP 历史（feed 写，Redis ZSet：score=ts，member=ts:price 保唯一，按 6 分钟裁） ====================
    // TWAP 就是官网显示的当前价，页面折线图用

    private static final long PRICE_HISTORY_TTL_MS = 360_000;
    private static final String BTC_TWAP_HISTORY_KEY = "prediction:btctwap:history";

    /** Chainlink BTC 60 秒 TWAP 点，顺手裁掉 6 分钟外的旧点 */
    public void addBtcTwapPoint(long timestampMs, BigDecimal price) {
        ZSetOperations<String, String> zset = stringRedisTemplate.opsForZSet();
        zset.add(BTC_TWAP_HISTORY_KEY, timestampMs + ":" + price.toPlainString(), timestampMs);
        zset.removeRangeByScore(BTC_TWAP_HISTORY_KEY, 0, timestampMs - PRICE_HISTORY_TTL_MS);
    }

    /** TWAP 历史按时间升序，接口形状 {time, price 字符串}；价格纯数字不含冒号，member 第一个冒号后就是价 */
    public List<Map<String, Object>> getBtcTwapHistory(long fromMs) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                stringRedisTemplate.opsForZSet().rangeByScoreWithScores(BTC_TWAP_HISTORY_KEY, fromMs, Double.MAX_VALUE);
        if (tuples == null) return List.of();
        List<Map<String, Object>> result = new ArrayList<>(tuples.size());
        for (ZSetOperations.TypedTuple<String> t : tuples) {
            String member = t.getValue();
            result.add(Map.of("time", t.getScore().longValue(), "price", member.substring(member.indexOf(':') + 1)));
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

    /** feed 每帧写：标记价和写入时刻一次 MSET 落 Redis（feed 自己不读合约价，不进 L1）。写入时刻是 sim 市价开仓判行情活没活着的心跳 */
    public void setMarkPrice(String symbol, String price, long nowMs) {
        stringRedisTemplate.opsForValue().multiSet(Map.of("market:markprice:" + symbol, price,
                "market:markprice-at:" + symbol, Long.toString(nowMs)));
    }

    /** feed 每帧写：最新价落 Redis */
    public void setFuturesPrice(String symbol, String price) {
        stringRedisTemplate.opsForValue().set("market:futures-price:" + symbol, price);
    }

    /** 标记价上次写入的时刻（毫秒）；没写过回 null */
    public Long getMarkPriceAt(String symbol) {
        String v = stringRedisTemplate.opsForValue().get("market:markprice-at:" + symbol);
        return v == null ? null : Long.parseLong(v);
    }

    // ==================== 资金费率（feed 从 markPrice 推送里写，sim 结算与前端查询读） ====================

    /** 当期费率、下次结算时刻（毫秒）、feed 写入时刻 */
    public record FundingRate(BigDecimal rate, long nextTime, long updatedAt) {}

    // feed 几秒写一次；feed 停了就过期，sim 不按旧的结算时刻收、前端不显示旧数
    private static final Duration FUNDING_RATE_TTL = Duration.ofMinutes(10);

    public void putFundingRate(String symbol, BigDecimal rate, long nextTimeMs, long nowMs) {
        stringRedisTemplate.opsForValue().set("market:funding:" + symbol,
                MAPPER.writeValueAsString(new FundingRate(rate, nextTimeMs, nowMs)), FUNDING_RATE_TTL);
    }

    public FundingRate getFundingRate(String symbol) {
        String v = stringRedisTemplate.opsForValue().get("market:funding:" + symbol);
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
