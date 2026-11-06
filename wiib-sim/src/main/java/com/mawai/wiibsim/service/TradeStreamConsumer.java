package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.TradeRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.Record;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.mawai.wiibcommon.market.MarketStreamChannels.FUTURES_TRADES_PREFIX;
import static com.mawai.wiibcommon.market.MarketStreamChannels.SPOT_TRADES_PREFIX;

/**
 * 成交触发（sim 侧）：按顺序读 feed 写的逐笔成交 Stream（合约、现货每个币一条），判限价单、止损、止盈。
 *
 * <p>消费组 sim 记着每条读到哪，重启接着读；读了没 ACK 的那批（处理到一半挂了）启动时先重放。
 * 一条 key 同时只处理一批，ACK 了才读它的下一批，所以同一个币按成交先后来，不同币互不等。
 * 一批里每一项找最先碰到它的那笔，同一个用户按碰到的先后执行，不同用户并发。</p>
 *
 * <p>Stream 里缺的那段拉 1m K 线补：feed 成交连接断过（gap 事件，见 {@link #recoverGap}），
 * 或 sim 停太久、没读的被裁掉了（启动时比消费组读到的位置和 Stream 最早一条）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeStreamConsumer {

    private static final String GROUP = "sim";
    /** 单实例部署，消费者名固定：重启后读得回上次没 ACK 的那批 */
    private static final String CONSUMER = "sim";
    private static final int BATCH = 200;
    /** 没新成交就歇一下再读：不带 BLOCK 的读走共享连接，带了每次都要单开一条连接 */
    private static final long IDLE_MS = 50L;
    private static final long RETRY_MS = 1_000L;
    /** 补漏空窗上限：超过就不补 */
    static final long MAX_GAP_MS = 60 * 60_000L;

    private final StringRedisTemplate redisTemplate;
    private final BinanceProperties props;
    private final BinanceRestClient restClient;
    private final CacheService cacheService;
    private final FuturesSettlementService settlementService;
    private final FuturesLiquidationService liquidationService;
    private final CryptoOrderService cryptoOrderService;
    private final TraderSymbolService traderSymbolService;

    /** 正在处理一批的 key */
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private volatile boolean running = true;
    private List<String> keys = List.of();
    /** 新建消费组从哪读起 */
    private ReadOffset groupFrom = ReadOffset.latest();

    /**
     * 建好消费组、补上被裁掉的那段、重放没 ACK 的，然后一直读。
     * lastAliveMs 是 sim 上次活着的时刻：1 小时内就接着读（新建的组从它读起）；
     * 没有或超过 1 小时当第一次启动，从现在读起、停机那段不补，跟标记价补漏一个口径
     */
    public void start(long lastAliveMs) {
        List<String> all = new ArrayList<>();
        props.getAllFuturesSymbols().forEach(s -> all.add(FUTURES_TRADES_PREFIX + s));
        props.getAllSpotSymbols().forEach(s -> all.add(SPOT_TRADES_PREFIX + s));
        keys = all;
        boolean resume = lastAliveMs > 0 && System.currentTimeMillis() - lastAliveMs <= MAX_GAP_MS;
        groupFrom = resume ? ReadOffset.from(lastAliveMs + "-0") : ReadOffset.latest();
        Thread.ofVirtual().name("trade-stream").start(() -> {
            for (String key : keys) {
                boolean existed = ensureGroup(key);
                if (resume) recoverTrimmed(key);
                else if (existed) restartGroup(key);
            }
            replayPending();
            readLoop();
        });
        log.info("[TradeStream] 读 {} 条成交 Stream，{}", keys.size(),
                resume ? "接着上次读，新建的组从 " + groupFrom.getOffset() + " 读起" : "停机超过 1 小时或不知道停了多久，从现在读起");
    }

    /** 停机先停读：连接工厂关掉之后再读只会刷报错 */
    @EventListener(ContextClosedEvent.class)
    public void stop() {
        running = false;
    }

    /** 建消费组；返回组是不是早就有了 */
    private boolean ensureGroup(String key) {
        try {
            redisTemplate.opsForStream().createGroup(key, groupFrom, GROUP);
            log.info("[TradeStream] 建消费组 key={} 从 {} 读起", key, groupFrom.getOffset());
            return false;
        } catch (Exception e) {
            if (redisError(e, "BUSYGROUP")) return true;
            log.warn("[TradeStream] 建消费组失败 key={}: {}", key, e.toString());
            return false;
        }
    }

    /** 停太久了：老的组连同没 ACK 的一起扔掉，从现在读起 */
    private void restartGroup(String key) {
        try {
            redisTemplate.opsForStream().destroyGroup(key, GROUP);
            redisTemplate.opsForStream().createGroup(key, ReadOffset.latest(), GROUP);
        } catch (Exception e) {
            log.warn("[TradeStream] 消费组跳到现在失败 key={}: {}", key, e.toString());
        }
    }

    /** Redis 报的错是不是这个错误码（BUSYGROUP / NOGROUP），码在最里层那个异常的消息开头 */
    private static boolean redisError(Exception e, String code) {
        String msg = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
        return msg != null && msg.contains(code);
    }

    /** 消费组读到的位置比 Stream 现存最早一条还早：中间没读的被裁掉了（sim 停太久），拉 K 线补 */
    private void recoverTrimmed(String key) {
        try {
            String lastDelivered = redisTemplate.opsForStream().groups(key).stream()
                    .filter(g -> GROUP.equals(g.groupName()))
                    .map(StreamInfo.XInfoGroup::lastDeliveredId)
                    .findFirst().orElse(null);
            List<MapRecord<String, Object, Object>> first = redisTemplate.opsForStream()
                    .range(key, Range.unbounded(), Limit.limit().count(1));
            if (lastDelivered == null || first == null || first.isEmpty()) return;
            long from = RecordId.of(lastDelivered).getTimestamp();
            long to = first.getFirst().getId().getTimestamp();
            // from 为 0：组是刚在空 Stream 上建的，没有要接的
            if (from == 0L || to <= from) return;
            if (to - from > MAX_GAP_MS) {
                log.info("[TradeStream] {} 被裁掉的那段 {}ms 超 1h，不补", key, to - from);
                return;
            }
            log.info("[TradeStream] {} 没读的被裁掉了 {}ms，拉 K 线补", key, to - from);
            recoverSymbol(key.startsWith(FUTURES_TRADES_PREFIX), symbolOf(key), from, to);
        } catch (Exception e) {
            log.warn("[TradeStream] 查被裁掉的那段失败 key={}: {}", key, e.toString());
        }
    }

    /**
     * 上次读了没 ACK 的先重放：每条 key 从头往后把待确认的过一遍（ACK 不上也只过一遍），
     * 触发靠 CAS / 摘索引，重放不会重复成交
     */
    private void replayPending() {
        for (String key : keys) {
            String after = "0";
            while (running) {
                List<MapRecord<String, Object, Object>> pending;
                try {
                    pending = read(List.of(key), ReadOffset.from(after));
                } catch (Exception e) {
                    log.warn("[TradeStream] 读没 ACK 的成交失败 key={}: {}", key, e.toString());
                    break;
                }
                if (pending.isEmpty()) break;
                processAndAck(key, pending);
                after = pending.getLast().getId().getValue();
            }
        }
    }

    private void readLoop() {
        while (running) {
            List<String> idle = keys.stream().filter(k -> !busy.contains(k)).toList();
            List<MapRecord<String, Object, Object>> records;
            try {
                records = idle.isEmpty() ? List.of() : read(idle, ReadOffset.lastConsumed());
            } catch (Exception e) {
                if (!running) return;
                log.warn("[TradeStream] 读成交失败，{}ms 后重试: {}", RETRY_MS, e.toString());
                // 组没了（启动时没建成、Redis 重启丢了）就按启动定的起点重建
                if (redisError(e, "NOGROUP")) keys.forEach(this::ensureGroup);
                pause(RETRY_MS);
                continue;
            }
            if (records.isEmpty()) {
                pause(IDLE_MS);
                continue;
            }
            byKey(records).forEach((key, batch) -> {
                busy.add(key);
                Thread.startVirtualThread(() -> {
                    try {
                        processAndAck(key, batch);
                    } finally {
                        busy.remove(key);
                    }
                });
            });
        }
    }

    /** 一批成交：找命中、执行完再 ACK。执行失败的项自己放回索引，下一笔穿过再来；整批出错也 ACK，不卡住后面的成交 */
    private void processAndAck(String key, List<MapRecord<String, Object, Object>> batch) {
        try {
            // 没 ACK 的条目已经被裁掉的，重放读回来是空的，跳过
            List<PriceTick> ticks = batch.stream()
                    .filter(r -> !r.getValue().isEmpty())
                    .map(r -> PriceTick.of(TradeRecord.of(r.getValue()))).toList();
            if (!ticks.isEmpty()) TradeHit.runByUser(hits(key.startsWith(FUTURES_TRADES_PREFIX), symbolOf(key), ticks));
        } catch (Exception e) {
            log.error("[TradeStream] 处理成交失败 key={} 条数={}", key, batch.size(), e);
        }
        try {
            redisTemplate.opsForStream().acknowledge(key, GROUP, batch.stream().map(Record::getId).toArray(RecordId[]::new));
        } catch (Exception e) {
            // 没 ACK 上的留在待确认列表里，下次启动重放
            log.warn("[TradeStream] ACK 失败 key={}: {}", key, e.toString());
        }
    }

    /** feed 成交连接断过的 [from, to]：每个币拉 1m K 线当行情补判一遍 */
    public void recoverGap(boolean futures, long from, long to) {
        for (String symbol : futures ? props.getAllFuturesSymbols() : props.getAllSpotSymbols()) {
            recoverSymbol(futures, symbol, from, to);
        }
    }

    private void recoverSymbol(boolean futures, String symbol, long from, long to) {
        try {
            List<KlineBar> bars = futures ? restClient.futuresBars1m(symbol, from, to)
                    : restClient.spotBars1m(symbol, from, to);
            if (bars.isEmpty()) return;
            TradeHit.runByUser(hits(futures, symbol, bars.stream().map(PriceTick::of).toList()));
        } catch (Exception e) {
            log.warn("[TradeStream] {}补漏失败 symbol={}: {}", futures ? "合约" : "现货", symbol, e.toString());
        }
    }

    /**
     * 兜底巡检：每个币拿缓存里的最新成交价当作这一刻的一笔，再判一遍限价单、止损、止盈。
     * 补的是读 Stream 这条路自己出岔子（读线程卡住、挂掉）的时候；交易员的币没有成交 Stream，全靠这里
     */
    public void sweep() {
        long now = System.currentTimeMillis();
        for (String symbol : traderSymbolService.allFuturesSymbols()) {
            sweepSymbol(true, symbol, cacheService.getFuturesPrice(symbol), now);
        }
        for (String symbol : props.getAllSpotSymbols()) {
            sweepSymbol(false, symbol, cacheService.getCryptoPrice(symbol), now);
        }
    }

    private void sweepSymbol(boolean futures, String symbol, BigDecimal price, long now) {
        // 缓存还没热
        if (price == null) return;
        try {
            TradeHit.runByUser(hits(futures, symbol, List.of(PriceTick.of(price, now))));
        } catch (Exception e) {
            log.error("[TradeStream] 兜底巡检失败 symbol={}", symbol, e);
        }
    }

    private List<TradeHit> hits(boolean futures, String symbol, List<PriceTick> ticks) {
        if (!futures) return cryptoOrderService.limitHits(symbol, ticks);
        // 止损止盈先判：限价单那边出错不能连累这批的止损止盈（限价单只查不摘，下一笔穿过还能捞到）
        List<TradeHit> hits = new ArrayList<>(liquidationService.stopHits(symbol, ticks));
        try {
            hits.addAll(settlementService.limitHits(symbol, ticks));
        } catch (Exception e) {
            log.error("[TradeStream] 判限价单失败 symbol={}", symbol, e);
        }
        return hits;
    }

    @SuppressWarnings("unchecked")
    private List<MapRecord<String, Object, Object>> read(List<String> streamKeys, ReadOffset offset) {
        StreamOffset<String>[] offsets = streamKeys.stream()
                .map(k -> StreamOffset.create(k, offset)).toArray(StreamOffset[]::new);
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .read(Consumer.from(GROUP, CONSUMER), StreamReadOptions.empty().count(BATCH), offsets);
        return records == null ? List.of() : records;
    }

    /** 按 key 分开，key 里保持读出来的先后 */
    private static Map<String, List<MapRecord<String, Object, Object>>> byKey(
            List<MapRecord<String, Object, Object>> records) {
        Map<String, List<MapRecord<String, Object, Object>>> byKey = new LinkedHashMap<>();
        for (var r : records) byKey.computeIfAbsent(r.getStream(), k -> new ArrayList<>()).add(r);
        return byKey;
    }

    private static String symbolOf(String key) {
        return key.substring(key.startsWith(FUTURES_TRADES_PREFIX)
                ? FUTURES_TRADES_PREFIX.length() : SPOT_TRADES_PREFIX.length());
    }

    private void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            running = false;
        }
    }
}
