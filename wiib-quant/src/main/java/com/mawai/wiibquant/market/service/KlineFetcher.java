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
 * 键只有 symbol+interval，一份数据能同时喂 klines / indicators / kline_structure 的任意参数组合。
 * <p>
 * <b>按 symbol+interval 存一份，根数够就切片</b>：Binance 返回的是"最近 N 根"，
 * 所以 192 根天然含着 120 根。谁先拉了长的，后面要短的直接切，不再打网络。
 * 反过来短的不覆盖长的——否则 indicators 拉完 120 根就把 klines 的 192 根挤没了。
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

    private record Cached(List<KlineBar> bars, long at) {
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
        String key = symbol + ":" + interval;
        List<KlineBar> hit = fromCache(key, limit);
        if (hit != null) {
            return hit;
        }
        // 在途键带 limit：要 192 的和要 120 的各等各的，别让要 120 的先拿到结果又发现不够用
        String flightKey = key + ":" + limit;
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
                    binanceRestClient.getFuturesKlines(symbol, interval, limit, null)));
            // 空表不进缓存：熔断/网络失败时存进去，接下来 60s 全都拿不到数据
            if (!bars.isEmpty()) {
                put(key, bars);
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

    /** 新鲜且根数够才算命中；返回最近 limit 根。 */
    private List<KlineBar> fromCache(String key, int limit) {
        Cached c = cache.get(key);
        if (c == null || nowMs.getAsLong() - c.at() >= ttlMillis || c.bars().size() < limit) {
            return null;
        }
        return c.bars().subList(c.bars().size() - limit, c.bars().size());
    }

    /** 只有更长的、或旧的那份已经过期时才覆盖——短的不许把长的挤掉。 */
    private void put(String key, List<KlineBar> bars) {
        synchronized (cache) {
            Cached prev = cache.get(key);
            if (prev == null || bars.size() >= prev.bars().size()
                    || nowMs.getAsLong() - prev.at() >= ttlMillis) {
                cache.put(key, new Cached(bars, nowMs.getAsLong()));
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
