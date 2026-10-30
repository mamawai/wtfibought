package com.mawai.wiibquant.market.service;

import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * K 线取数层：工具层共用的那一份行情，带 TTL 缓存；同一份数据并发要时只放一个去拉。
 * <p>
 * 缓存建在取数层而不是各工具结果上，这么写为了挡住网络那一侧：
 * 键是 symbol+interval（历史窗口再带上截止时间），一份数据能同时喂 klines / indicators / kline_structure 的任意参数组合。
 * <p>
 * <b>按 symbol+interval 存一份，根数够就切片</b>：Binance 返回的是"最近 N 根"，
 * 所以 192 根天然含着 120 根。谁先拉了长的，后面要短的直接切，不再打网络。
 * 反过来短的不覆盖长的——否则 indicators 拉完 120 根就把 klines 的 192 根挤没了。
 * <p>
 * <b>缓存活不过 K 线边界</b>：TTL 和下一根边界取早的，末根一收盘就得重拉（见 {@link CacheExpiry}）；
 * 在途合并也只在同一根里——边界后来的调用不等边界前发出的请求。
 * 截止时间那根已经收盘的历史窗口例外：一根都不会变，存一天。
 * <p>
 * <b>同一份数据只放一个线程去拉，其余等它</b>：几十个 trader 在同一个 5m 边界一起醒来、
 * 首轮又都被强制调工具，缓存这时必然已过期（TTL 60s < 唤醒周期 300s），
 * 不拦就是几十个并发全打上游。等待者阻塞在 future 上，不自旋也不轮询。
 */
@Slf4j
@Component
public class KlineFetcher {

    /**
     * 缓存条目数上限。键是 symbol×interval，而工具入口用的是宽松归一（不校验白名单），
     * 模型查什么就存什么——没有上限就是无界增长。128 够装下白名单几个币的全部周期还有富余。
     */
    private static final int MAX_ENTRIES = 128;

    /** 已收盘的历史窗口的有效期 */
    static final long CLOSED_WINDOW_TTL_MS = 86_400_000L;

    /** 周期的毫秒数，按它找 K 线边界 */
    private static final Map<String, Long> INTERVAL_MS = Map.of(
            "1m", 60_000L, "5m", 300_000L, "15m", 900_000L, "1h", 3_600_000L, "4h", 14_400_000L, "1d", 86_400_000L);

    /** expireAt：这份数据用到什么时候 */
    private record Cached(List<KlineBar> bars, long expireAt) {
    }

    private final BinanceRestClient binanceRestClient;
    private final long ttlMillis;

    /** LRU：超了淘汰最久没被访问的。accessOrder=true 让 get 也算一次访问 */
    private final Map<String, Cached> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    /** 在途请求：TTL 过期瞬间多个线程同时 miss，只放一个去真拉，其余等它 */
    private final Map<String, CompletableFuture<List<KlineBar>>> inFlight = new ConcurrentHashMap<>();

    /** 墙钟注入点：TTL 边界要测得了，真等 60 秒不现实 */
    LongSupplier nowMs = System::currentTimeMillis;

    public KlineFetcher(BinanceRestClient binanceRestClient,
                        @Value("${quant.toolkit.kline-ttl-ms:60000}") long ttlMillis) {
        this.binanceRestClient = binanceRestClient;
        this.ttlMillis = ttlMillis;
    }

    /**
     * 取最近 limit 根合约 K 线（时间升序，末根仍在形成）。
     * 取不到时返回空表，调用方按"数据不可用"降级。
     */
    public List<KlineBar> fetch(String symbol, String interval, int limit) {
        return fetch(symbol, interval, limit, null);
    }

    /** @param endTimeMs 窗口以这个时刻结尾（含它所在那根）；null 就是截止现在 */
    public List<KlineBar> fetch(String symbol, String interval, int limit, Long endTimeMs) {
        String key = symbol + ":" + interval + (endTimeMs == null ? "" : ":" + endTimeMs);
        List<KlineBar> hit = fromCache(key, limit);
        if (hit != null) {
            return hit;
        }
        long intervalMs = INTERVAL_MS.get(interval);
        long now = nowMs.getAsLong();
        // 在途键带 limit：要 192 的和要 120 的各等各的，别让要 120 的先拿到结果又发现不够用；
        // 带边界：边界后来的不等边界前发出的请求，那份回包末根已经收盘了
        String flightKey = key + ":" + limit + ":" + CacheExpiry.edge(now, intervalMs);
        CompletableFuture<List<KlineBar>> mine = new CompletableFuture<>();
        CompletableFuture<List<KlineBar>> running = inFlight.putIfAbsent(flightKey, mine);
        if (running != null) {
            return join(running, flightKey, limit);
        }
        try {
            // 抢到之后复查：等待期间别人可能已经拉回了更长的一份
            List<KlineBar> again = fromCache(key, limit);
            if (again != null) {
                mine.complete(again);
                return again;
            }
            List<KlineBar> bars = List.copyOf(KlineHistoryStore.parseRawFuturesKlines(
                    binanceRestClient.getFuturesKlines(symbol, interval, limit, endTimeMs)));
            // 空表不进缓存：熔断/网络失败时存进去，接下来 60s 全都拿不到数据。
            // 有效期按发请求那一刻算：请求跨过边界才回来的，回包可能是边界前的
            if (!bars.isEmpty()) {
                put(key, bars, expireAt(now, intervalMs, endTimeMs));
            }
            mine.complete(bars);
            return bars;
        } catch (Throwable t) {
            // join() 无超时且不可中断，漏一次 complete 就留下杀不掉的等待线程，所以 Error 也要给终局
            mine.completeExceptionally(t);
            throw t;
        } finally {
            inFlight.remove(flightKey, mine);
        }
    }

    /** 截止时间那根已收盘的窗口存一天；没传截止时间、或截止时间落在还在走的这根，按实时规则 */
    private long expireAt(long now, long intervalMs, Long endTimeMs) {
        boolean closed = endTimeMs != null && CacheExpiry.edge(endTimeMs, intervalMs) + intervalMs <= now;
        return closed ? now + CLOSED_WINDOW_TTL_MS : CacheExpiry.at(now, ttlMillis, intervalMs);
    }

    /** 新鲜且根数够才算命中；返回最近 limit 根。 */
    private List<KlineBar> fromCache(String key, int limit) {
        Cached c = cache.get(key);
        if (c == null || nowMs.getAsLong() >= c.expireAt() || c.bars().size() < limit) {
            return null;
        }
        return c.bars().subList(c.bars().size() - limit, c.bars().size());
    }

    /** 旧的那份过期了才随便覆盖；没过期的，短的不许把长的挤掉、早到期的不许把晚到期的挤掉 */
    private void put(String key, List<KlineBar> bars, long expireAt) {
        synchronized (cache) {
            Cached prev = cache.get(key);
            if (prev == null || nowMs.getAsLong() >= prev.expireAt()
                    || (bars.size() >= prev.bars().size() && expireAt >= prev.expireAt())) {
                cache.put(key, new Cached(bars, expireAt));
            }
        }
    }

    /**
     * 等在途请求的结果。失败时等待者拿到空表而不是异常——一个 symbol 拉炸了，
     * 不该把等它的其他决策线程一起打断。
     */
    private List<KlineBar> join(CompletableFuture<List<KlineBar>> running, String flightKey, int limit) {
        try {
            List<KlineBar> bars = running.join();
            return bars.size() > limit ? bars.subList(bars.size() - limit, bars.size()) : bars;
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("[KlineFetcher] 等待在途拉取失败 key={}", flightKey, cause);
            return List.of();
        }
    }
}
