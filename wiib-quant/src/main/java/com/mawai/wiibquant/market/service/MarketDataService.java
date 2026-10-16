package com.mawai.wiibquant.market.service;

import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.enums.KlineInterval;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.market.DepthStreamCache;
import com.mawai.wiibcommon.market.ForceOrderService;
import com.mawai.wiibcommon.market.OrderFlowAggregator;
import com.mawai.wiibquant.market.domain.FeatureSnapshot;
import com.mawai.wiibquant.market.collect.BuildFeaturesBuilder;
import com.mawai.wiibquant.market.collect.CollectDataNode;
import com.mawai.wiibquant.external.deribit.DeribitClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 市场数据组装服务：采集 → 特征快照 一条链，带 TTL 缓存。
 * 工具层（对话 agent / AI Trader）共用这条链，避免各处重复采集。
 * 缓存活不过数据自己的边界（见 {@link CacheExpiry}）：整装快照到下一根 5m 收盘，资金费到下次 8h 结算。
 * 规则信号面板与脆弱度合成层已随预测管线下线（2026-08：生产验证无前瞻信息，原料字段仍在快照里原样提供）。
 */
@Slf4j
@Service
public class MarketDataService {

    /** 整装快照按 5m 翻篇：里面的指标、regime 都是按 5m/15m 收盘算的，唤醒也都踩在 5m 边界上 */
    private static final long BAR_MS = Duration.ofMinutes(5).toMillis();
    /** 资金费 8 小时结算一次：费率、下次结算时间都在这个边界上换 */
    private static final long FUNDING_MS = Duration.ofHours(8).toMillis();
    /** 盘口只认这么新的：WS 快照超龄就回 REST，REST 那份也只存这么久 */
    private static final long DEPTH_FRESH_MS = 2_000L;

    /** 资金费历史的兜底上限：8 小时结算一次，同一周期内这个值本就没变过；超了就是跨周期的错数 */
    private static final long FUNDING_MAX_STALE_MS = Duration.ofHours(8).toMillis();

    /** 资金费上下文的兜底上限：里面装着标记价，是实时价；一刻钟前的还能当"大致现价"，再久就是误导 */
    private static final long PREMIUM_MAX_STALE_MS = Duration.ofMinutes(15).toMillis();

    /**
     * 两个缓存都得有上限：入口是宽松归一（{@link QuantConstants#normalizeSymbolLenient}），
     * <b>不校验白名单</b>——模型问哪个币就存哪个，采集失败的 unavailable 也照样进缓存。
     * 裸 Map 只增不减，等于把"存什么"的决定权交给了模型。
     * <p>
     * 整装快照给 32：一条里装着各周期 K 线原文加全套特征，是这里最占地方的东西，
     * 而白名单统共几个币，32 够覆盖它们再加上临时问到的。
     * rawCache 给 128：每个 symbol 最多三条（funding / premium / depth），值是几 KB 的 JSON，可以宽松些。
     */
    private static final int MAX_ASSEMBLY_ENTRIES = 32;
    private static final int MAX_RAW_ENTRIES = 128;

    private final BinanceRestClient binanceRestClient;
    private final DepthStreamCache depthStreamCache;
    private final CollectDataNode collectNode;
    private final BuildFeaturesBuilder featuresBuilder;
    private final long ttlMillis;

    /** 整装快照 + 用到什么时候 */
    private record CachedAssembly(MarketAssembly assembly, long expireAt) {}

    /** LRU：满了淘汰最久没被碰过的那条。accessOrder=true 让 get 也算一次访问 */
    private final Map<String, CachedAssembly> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedAssembly> eldest) {
                    return size() > MAX_ASSEMBLY_ENTRIES;
                }
            });

    /** 在途采集：TTL 过期瞬间多个线程同时 miss，只放一个去真采集，其余等它的结果 */
    private final Map<String, CompletableFuture<MarketAssembly>> inFlight = new ConcurrentHashMap<>();

    /** 裸 REST 结果的缓存：资金费历史/盘口这类"工具直取"的数据，与整装快照分开老化。at 是写入时刻，兜底年龄按它算 */
    private record Cached(String value, long at, long expireAt) {}

    private final Map<String, Cached> rawCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                    return size() > MAX_RAW_ENTRIES;
                }
            });

    /** 两个缓存共用的墙钟，可注入只为让到期和"超龄不再兜"测得了——真等 8 小时不现实 */
    LongSupplier nowMs = System::currentTimeMillis;

    public MarketDataService(BinanceRestClient binanceRestClient,
                             ForceOrderService forceOrderService,
                             DepthStreamCache depthStreamCache,
                             DeribitClient deribitClient,
                             OrderFlowAggregator orderFlowAggregator,
                             @Value("${trading.decision-interval:M5}") KlineInterval decisionInterval,
                             @Value("${quant.toolkit.assembly-ttl-ms:60000}") long ttlMillis) {
        this.binanceRestClient = binanceRestClient;
        this.depthStreamCache = depthStreamCache;
        this.collectNode = new CollectDataNode(binanceRestClient, forceOrderService, depthStreamCache, deribitClient);
        this.featuresBuilder = new BuildFeaturesBuilder(orderFlowAggregator, decisionInterval);
        this.ttlMillis = ttlMillis;
    }

    /** 工具层统一入口：没到期直接复用；到期时同 symbol 只允许一个线程真采集（防击穿）。 */
    public MarketAssembly assemble(String symbol) {
        String normalized = QuantConstants.normalizeSymbolLenient(symbol);
        CachedAssembly cached = cache.get(normalized);
        if (fresh(cached)) {
            return cached.assembly();
        }
        long now = nowMs.getAsLong();
        // putIfAbsent 原子选出唯一"采集者"：抢到的去真采集，没抢到的等它的 future。
        // 不用 computeIfAbsent——mapping 函数期间会锁住整个 bin，同 bin 的别的 symbol 也跟着卡。
        // 键带 5m 边界：边界后来的不等边界前开始的采集
        String flightKey = normalized + ":" + CacheExpiry.edge(now, BAR_MS);
        CompletableFuture<MarketAssembly> mine = new CompletableFuture<>();
        CompletableFuture<MarketAssembly> running = inFlight.putIfAbsent(flightKey, mine);
        if (running != null) {
            return join(running, normalized);
        }
        try {
            // 抢到采集权后复查：cache.get 到 putIfAbsent 之间可能已经有人放了新快照进来，别白打一轮
            CachedAssembly again = cache.get(normalized);
            if (fresh(again)) {
                mine.complete(again.assembly());
                return again.assembly();
            }
            MarketAssembly assembled = assembleFresh(normalized);
            // 不可用的不进缓存：一次抖动不该让接下来 60s 都报没数据。到期按开始采集那一刻算，采集本身要跑好几秒
            if (assembled.available()) {
                cache.put(normalized, new CachedAssembly(assembled, CacheExpiry.at(now, ttlMillis, BAR_MS)));
            }
            mine.complete(assembled);
            return assembled;
        } catch (Throwable t) {
            // join() 无超时且不可中断，漏一次 complete 就留下一批杀不掉的僵尸线程，所以 Error 也要给终局。
            // 一处 catch 覆盖全部逃逸路径，等待者日志里拿到的就是原始根因（爆栈/OOM 本尊）；
            // 换成 finally 里补一个壳异常，真凶会被盖住，而且每次采集都白建一个带栈对象——OOM 时它自己就分配不出来
            mine.completeExceptionally(t);
            throw t;
        } finally {
            inFlight.remove(flightKey, mine);
        }
    }

    /**
     * 资金费历史（近 30 条）。失败返回 null，调用方按"数据不可用"处理。
     * 取不到新数据时兜过期那份，但只兜 8 小时以内的：资金费 8 小时才结算一次，同一个周期内这个值
     * 本就没变过，兜它无害；而熔断冷却 120s 比 TTL 60s 长，不兜就会白白黑掉中间那 60 秒。
     * 超过 8 小时就跨周期了，那个数是实质错误的，当没有数据处理（走调用方既有的 available:false）。
     * <p>
     * 这 8 小时在 funding_history 工具那条路上大半够不着：工具是"历史 + 资金费上下文"一起出的，
     * 而 {@link #premiumIndex} 只兜 15 分钟，两者共用同一个熔断器，premium 先一步空掉整条工具
     * 就报不可用了。也就是说停摆超过一刻钟，历史这边兜再久也露不出来。
     */
    public String fundingHistory(String symbol) {
        String normalized = QuantConstants.normalizeSymbolLenient(symbol);
        return rawCached("funding:" + normalized, ttlMillis, FUNDING_MS, FUNDING_MAX_STALE_MS,
                () -> binanceRestClient.getFundingRateHistory(normalized, 30));
    }

    /**
     * 资金费上下文（下次结算时间 / 上次费率 / 标记价）。仅供 @Tool 层给 LLM 读，失败返回 null。
     * <p>
     * 允许兜过期那份，上限 15 分钟——比资金费历史短一个量级，因为这里装着标记价，是实时价格。
     * 一刻钟内的价当"大致现价"给模型读还行，总比告诉它"没数据"强（不兜的话熔断期这里一 null
     * 就把整条 funding_history 拖垮，历史那边的兜底也就白做了）；再久就是拿旧价冒充现价，宁可报不可用。
     * <p>
     * <b>算钱的路径不许走这里</b>：trader 下单量（TraderWakeupRunner）直调 BinanceRestClient 取实时
     * markPrice；sim 那边限价撮合（FuturesSettlementServiceImpl.onPriceUpdate）与强平
     * （FuturesLiquidationService）吃 feed 经 Redis 推来的 WS 实时价，资金费结算读 feed 每 tick
     * 刷的 markprice 缓存。BTC 一分钟就能走 0.1~0.3%，拿缓存价格算钱是真会出偏差的。
     */
    public String premiumIndex(String symbol) {
        String normalized = QuantConstants.normalizeSymbolLenient(symbol);
        return rawCached("premium:" + normalized, ttlMillis, FUNDING_MS, PREMIUM_MAX_STALE_MS,
                () -> binanceRestClient.getPremiumIndex(normalized));
    }

    /**
     * 合约盘口深度。优先 WS 快照，断流才回退 REST；失败返回 null。
     * 返回的是原始档位（WS 为 top20），截到工具描述承诺的档数由工具层做。
     */
    public String orderbook(String symbol) {
        String normalized = QuantConstants.normalizeSymbolLenient(symbol);
        // WS depth20@100ms 比 REST 新鲜三个数量级且不吃 Binance 配额，优先用它；
        // 字段名 feed 侧已统一成 bids/asks，与 REST 同构（见 DepthStreamHandler）
        String ws = depthStreamCache.getFreshDepth(normalized, DEPTH_FRESH_MS);
        if (ws != null) {
            return ws;
        }
        // 盘口只存 2s、一点过期数据都不兜（maxStale=0）：agent 拿它挂限价单，BTC 一分钟就能走 0.1~0.3%，宁可报不可用
        return rawCached("depth:" + normalized, DEPTH_FRESH_MS, 0, 0,
                () -> binanceRestClient.getFuturesOrderbook(normalized, 10));
    }

    /**
     * 裸 REST 取数 + 缓存。这里有两个时间概念，别混：
     * {@code ttlMs} 是新鲜期（还会被 {@code intervalMs} 的下一个边界截短），没过就直接返回、压根不发请求；
     * {@code maxStale} 只在"过了新鲜期且取新数据失败"时才登场，管这条旧数据还顶不顶得住。
     *
     * @param intervalMs 数据按它翻篇（资金费 8h），缓存活不过下一个边界；0 = 只看 TTL
     * @param maxStale 兜底的年龄上限，从写进缓存那一刻起算的<b>总年龄</b>（毫秒），0 = 不兜。
     *                 超龄按没有数据返回 null，走调用方既有的 available:false 降级链。
     *                 超龄条目仍留在 map 里（本层不淘汰），只是不会再被返回
     */
    private String rawCached(String key, long ttlMs, long intervalMs, long maxStale, Supplier<String> loader) {
        Cached hit = rawCache.get(key);
        long now = nowMs.getAsLong();
        if (hit != null && now < hit.expireAt()) {
            return hit.value();
        }
        try {
            String value = loader.get();
            if (value != null) {
                rawCache.put(key, new Cached(value, now, CacheExpiry.at(now, ttlMs, intervalMs)));
                return value;
            }
        } catch (Exception e) {
            log.warn("[Toolkit] 取数失败 key={}", key, e);
        }
        return hit != null && nowMs.getAsLong() - hit.at() < maxStale ? hit.value() : null;
    }

    private boolean fresh(CachedAssembly cached) {
        return cached != null && nowMs.getAsLong() < cached.expireAt();
    }

    /**
     * 等在途采集的结果。采集失败时等待者拿到的是降级的 unavailable——异常不跨线程传播，
     * 只在采集线程自己那里往上抛；一个 symbol 采集炸了不该把等它的对话/交易线程一起打断。
     */
    private MarketAssembly join(CompletableFuture<MarketAssembly> running, String symbol) {
        try {
            return running.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("[Toolkit] 等待在途采集失败 symbol={}", symbol, cause);
            return MarketAssembly.unavailable(symbol, Map.of());
        }
    }

    MarketAssembly assembleFresh(String symbol) {
        long startMs = System.currentTimeMillis();
        Map<String, Object> raw = collectNode.collect(symbol, null);
        if (!Boolean.TRUE.equals(raw.get("data_available"))) {
            log.warn("[Toolkit] 采集不可用 symbol={}", symbol);
            return MarketAssembly.unavailable(symbol, raw);
        }
        Map<String, Object> featureOut = featuresBuilder.buildFeatures(symbol, raw);
        FeatureSnapshot snapshot = (FeatureSnapshot) featureOut.get("feature_snapshot");
        if (snapshot == null) {
            return MarketAssembly.unavailable(symbol, raw);
        }
        log.info("[Toolkit] 组装完成 symbol={} 耗时{}ms", symbol, System.currentTimeMillis() - startMs);
        return new MarketAssembly(symbol, true, raw, featureOut, snapshot, Instant.now());
    }

}
