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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * K线代理的 Redis 缓存层：多人同刷/切周期不再放大到 Binance（权重限频、418 封 IP 是全站行情单点风险）。
 * <p>一致性依据：已闭合 bar 不可变，缓存只活到它最后一根收完，不让半成品活过 K 线边界；
 * 还在长的那根由 WS 流实时驱动、REST 仅作进页快照，边界之内命中多旧都无所谓。
 * <p>回源只拉 100 / 500 两档根数，按档缓存，返回前截最后 limit 根；合约要 ≤100 根的，100 档没有就用 500 档现成的。
 * <p>同一个 key 同时 miss 只放一个去回源，其余等它；币安熔断中直接回 null，不耗限流令牌。
 * <p>Redis 故障直接穿透打 Binance，不影响可用性；回源失败（null）不进缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KlineCacheService {

    /** 最后一根已收完的：闭合 bar 不可变，1h 纯为控内存 */
    private static final Duration HISTORY_TTL = Duration.ofHours(1);
    /** 刚过边界这么久以内只存这么久：我们时钟比币安快一点时，回包里上一根可能还是半成品 */
    private static final long EDGE_MS = 2_000L;
    /** 回源两档根数：币安合约 limit ≤100 权重 1、≤500 权重 2；现货不管多少根权重都是 2，只用 500 档 */
    private static final int SMALL = 100;
    private static final int FULL = 500;
    /** 前端用到的周期和它的毫秒数，别的一律拒 */
    private static final Map<String, Long> INTERVAL_MS = Map.of(
            "1m", 60_000L, "5m", 300_000L, "15m", 900_000L, "1h", 3_600_000L, "4h", 14_400_000L, "1d", 86_400_000L);

    private final KlineOrigin klineOrigin;
    private final StringRedisTemplate redisTemplate;
    private final TradeFilterRegistry tradeFilterRegistry;
    private final BStockService bStockService;
    /** 在途回源：同一个 key 同时 miss 只放一个去拉，其余等它 */
    private final Map<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();
    /** 墙钟注入点：TTL 按 K 线边界算，要可测 */
    LongSupplier nowMs = System::currentTimeMillis;

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
        if (!symbolKnown || !INTERVAL_MS.containsKey(interval)) {
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
                redisTemplate.opsForValue().set(key, fresh, ttl(interval, endTime));
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

    /**
     * 缓存活到回包最后一根收完为止：那根还在长就到它收完（最新页即下一根边界），收完了存 1h。
     * 刚过边界 2 秒内只存 2 秒，见 EDGE_MS
     */
    private Duration ttl(String interval, Long endTime) {
        long ms = INTERVAL_MS.get(interval), now = nowMs.getAsLong();
        // 回包最后一根落在的时刻：最新页和 endTime 在未来的（VP 在长的块）都是现在
        long at = endTime == null ? now : Math.min(endTime, now);
        long closeAt = (at / ms + 1) * ms;
        // 刚过去的那个边界：还在长的是它的开盘，收完的是它的收盘
        long edge = closeAt > now ? closeAt - ms : closeAt;
        if (now - edge < EDGE_MS) return Duration.ofMillis(EDGE_MS);
        return closeAt > now ? Duration.ofMillis(closeAt - now) : HISTORY_TTL;
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
