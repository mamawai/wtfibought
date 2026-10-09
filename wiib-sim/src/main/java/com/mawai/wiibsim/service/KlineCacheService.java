package com.mawai.wiibsim.service;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * K线代理的 Redis 缓存层：多人同刷/切周期不再放大到 Binance（权重限频、418 封 IP 是全站行情单点风险）。
 * <p>一致性依据：已闭合 bar 不可变，历史翻页（带 endTime）可长缓存；最新页只有最后一根会变，
 * 而图表最后一根由 WS 流实时驱动、REST 仅作进页快照，短 TTL 的滞后会被 WS 首帧立即覆盖。
 * <p>回源只拉 100 / 500 两档根数，按档缓存，返回前截最后 limit 根；合约要 ≤100 根的，100 档没有就用 500 档现成的。
 * <p>同一个 key 同时 miss 只放一个去回源，其余等它；币安熔断中直接回 null，不耗限流令牌。
 * <p>Redis 故障直接穿透打 Binance，不影响可用性；回源失败（null）不进缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KlineCacheService {

    /** 最新页（不带 endTime）：只保"同一时刻大家看同一份"，滞后由 WS 兜底 */
    private static final Duration LATEST_TTL = Duration.ofSeconds(10);
    /** 历史翻页（带 endTime）：闭合 bar 不可变，1h 纯为控内存 */
    private static final Duration HISTORY_TTL = Duration.ofHours(1);
    /** endTime 离现在不到这么久的，最后几根可能还没收完，按最新页的短 TTL 存 */
    private static final long FRESH_WINDOW_MS = 60_000L;
    /** 回源两档根数：币安合约 limit ≤100 权重 1、≤500 权重 2；现货不管多少根权重都是 2，只用 500 档 */
    private static final int SMALL = 100;
    private static final int FULL = 500;
    /** 前端用到的周期，别的一律拒 */
    private static final Set<String> INTERVALS = Set.of("1m", "5m", "15m", "1h", "4h", "1d");

    private final KlineOrigin klineOrigin;
    private final StringRedisTemplate redisTemplate;
    private final TradeFilterRegistry tradeFilterRegistry;
    private final BStockService bStockService;
    /** 在途回源：同一个 key 同时 miss 只放一个去拉，其余等它 */
    private final Map<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

    /** 现货K线（crypto 现货 / bStock 共用）：只认上架的现货币种和 bStock */
    public String spotKlines(String symbol, String interval, int limit, Long endTime) {
        String s = symbol.toUpperCase();
        check(interval, tradeFilterRegistry.allSpot().containsKey(s) || bStockService.isBStockSymbol(s));
        return klines(false, s, interval, limit, endTime);
    }

    /** 合约K线：只认上架的合约币种 */
    public String futuresKlines(String symbol, String interval, int limit, Long endTime) {
        String s = symbol.toUpperCase();
        check(interval, tradeFilterRegistry.allFutures().containsKey(s));
        return klines(true, s, interval, limit, endTime);
    }

    private static void check(String interval, boolean symbolKnown) {
        if (!symbolKnown || !INTERVALS.contains(interval)) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
    }

    private String klines(boolean futures, String symbol, String interval, int limit, Long endTime) {
        int n = Math.min(Math.max(limit, 1), FULL);
        // 回源拉哪档；拉 100 档的先一次 MGET 查 100 档和 500 档，先用小的
        int size = futures && n <= SMALL ? SMALL : FULL;
        List<Integer> sizes = size == SMALL ? List.of(SMALL, FULL) : List.of(FULL);
        List<String> keys = sizes.stream().map(s -> key(futures, symbol, interval, s, endTime)).toList();
        List<String> hits = List.of();
        try {
            hits = redisTemplate.opsForValue().multiGet(keys);
        } catch (Exception e) {
            log.warn("[KlineCache] Redis 读失败，穿透直连 keys={}: {}", keys, e.getMessage());
        }
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i) != null) return tail(hits.get(i), n, sizes.get(i));
        }

        // 币安熔断中直接空手回：不耗限流令牌，也不进缓存
        if (klineOrigin.blocked()) return null;

        // 同一个 key 只放一个去回源，其余等它；领头的失败了等的人各自再拉
        String fetchKey = keys.getFirst();
        CompletableFuture<String> mine = new CompletableFuture<>();
        CompletableFuture<String> running = inFlight.putIfAbsent(fetchKey, mine);
        if (running != null) {
            try {
                return tail(running.join(), n, size);
            } catch (CompletionException e) {
                return tail(fetch(futures, symbol, interval, size, endTime, fetchKey), n, size);
            }
        }
        try {
            String fresh = fetch(futures, symbol, interval, size, endTime, fetchKey);
            mine.complete(fresh);
            return tail(fresh, n, size);
        } catch (Throwable t) {
            mine.completeExceptionally(t);
            throw t;
        } finally {
            inFlight.remove(fetchKey, mine);
        }
    }

    /** 走代理 bean 过限流切面回源，被拒抛 BizException 直接出去；拉到了写缓存 */
    private String fetch(boolean futures, String symbol, String interval, int size, Long endTime, String key) {
        String fresh = endTime == null && !StpUtil.isLogin()
                ? klineOrigin.guest(futures, symbol, interval, size)
                : klineOrigin.member(futures, symbol, interval, size, endTime);
        if (fresh != null) {
            try {
                redisTemplate.opsForValue().set(key, fresh, ttl(endTime));
            } catch (Exception e) {
                log.warn("[KlineCache] Redis 写失败 key={}: {}", key, e.getMessage());
            }
        }
        return fresh;
    }

    /** key 带 v2：存的是 10 列的精简 K 线 */
    private static String key(boolean futures, String symbol, String interval, int size, Long endTime) {
        return "kline:v2:" + (futures ? "fut" : "spot") + ":" + symbol + ":" + interval + ":" + size
                + ":" + (endTime == null ? "latest" : endTime);
    }

    /** 最新页、离现在不到 1 分钟的 endTime 短存，其余长存 */
    private static Duration ttl(Long endTime) {
        return endTime == null || endTime > System.currentTimeMillis() - FRESH_WINDOW_MS ? LATEST_TTL : HISTORY_TTL;
    }

    /**
     * 从 size 根里截最后 n 根；要满这一档、回源失败（null）、本来就不够长，都原样返回。
     * 缓存里的值都是 getSlimKlines 用 Jackson 紧凑写出来的，一行一个扁平数组，行与行之间只有 "],["，从尾往前数它
     */
    private static String tail(String json, int n, int size) {
        if (json == null || n >= size) return json;
        int at = json.length();
        for (int i = 0; i < n; i++) {
            at = json.lastIndexOf("],[", at - 1);
            if (at < 0) return json;
        }
        return "[" + json.substring(at + 2);
    }
}
