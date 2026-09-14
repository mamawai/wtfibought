package com.mawai.wiibquant.whale;

import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient;
import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient.AccountState;
import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient.AssetCtx;
import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient.Bucket;
import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient.Position;
import com.mawai.wiibquant.mapper.WhaleAddressMapper;
import com.mawai.wiibquant.mapper.WhalePositionMapper;
import com.mawai.wiibquant.mapper.WhalePositionMapper.Latest;
import com.mawai.wiibquant.mapper.WhaleSnapshotMapper;
import com.mawai.wiibquant.whale.WhaleAggregator.CoinSnapshot;
import com.mawai.wiibquant.whale.WhaleAggregator.Side;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * 每 10 分钟持仓轮询（docs/hyperliquid-whale.md §5.1）：
 * metaAndAssetCtxs 拿标记价与全市场持仓量 → 池内地址逐个 clearinghouseState → 每币聚合写 whale_snapshot →
 * 与上一轮的 (address, coin) → (szi, entryPx) 对比，变了才写 whale_position，上轮有本轮没有写 szi=0。
 * <p>
 * 失败语义：metaAndAssetCtxs 失败本轮什么都不写（没有快照价与分母）；单地址失败跳过，它的基线不动、不算已平；
 * 成功率不足 50% 本轮什么都不写只记 warn。observed_at 取本轮所在的整 10 分钟槽，与 5m K 线边界重合。
 * 基线放进程内存，首轮从表里加载每个 (address, coin) 的最近一行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhalePositionTask {

    /** 一轮一个槽；改 whale.poll.cron 周期要同步改它 */
    static final long SLOT_MS = 600_000;

    /** 上一轮的 szi 与 entryPx，只放开着的仓位 */
    record Baseline(BigDecimal szi, BigDecimal entryPx) {
    }

    private final HyperliquidClient client;
    private final WhaleAddressMapper addressMapper;
    private final WhaleSnapshotMapper snapshotMapper;
    private final WhalePositionMapper positionMapper;
    private final WhaleProperties props;
    private final AtomicBoolean running = new AtomicBoolean();
    /** (address|coin) → 基线；null=还没从表加载 */
    private Map<String, Baseline> baseline;

    LongSupplier nowMs = System::currentTimeMillis;

    @Scheduled(cron = "${whale.poll.cron:0 */10 * * * *}", zone = "Asia/Shanghai")
    public void poll() {
        if (props.isEnabled()) {
            run();
        }
    }

    /** 一轮，同步跑完 */
    public void run() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[WhalePosition] 上一轮还没跑完，跳过");
            return;
        }
        try {
            doRun();
        } catch (Exception e) {
            log.error("[WhalePosition] 本轮异常中止", e);
        } finally {
            running.set(false);
        }
    }

    private void doRun() {
        // 就近取整到槽，cron 早触发几毫秒也落本槽
        long observedAt = (nowMs.getAsLong() + SLOT_MS / 2) / SLOT_MS * SLOT_MS;
        Set<String> coins = new HashSet<>(props.getCoins());

        Map<String, AssetCtx> ctx = new HashMap<>();
        try {
            for (AssetCtx c : client.metaAndAssetCtxs(Bucket.POLL)) {
                if (coins.contains(c.coin())) {
                    ctx.put(c.coin(), c);
                }
            }
        } catch (Exception e) {
            log.warn("[WhalePosition] metaAndAssetCtxs 失败，本轮不写快照: {}", e.toString());
            return;
        }

        List<String> addresses = addressMapper.selectPoolAddresses();
        if (addresses.isEmpty()) {
            log.info("[WhalePosition] 池子为空，本轮跳过");
            return;
        }
        if (baseline == null) {
            baseline = loadBaseline();
        }

        Map<String, AccountState> states = new ConcurrentHashMap<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(props.getMaxInFlight(), Thread.ofVirtual().factory())) {
            for (String a : addresses) {
                pool.submit(() -> {
                    try {
                        states.put(a, client.clearinghouseState(a, Bucket.POLL));
                    } catch (Exception e) {
                        log.warn("[WhalePosition] clearinghouseState 失败 {}: {}", a, e.toString());
                    }
                });
            }
        }
        if (states.size() * 2 < addresses.size()) {
            log.warn("[WhalePosition] 成功 {}/{} 不足一半，本轮不写快照", states.size(), addresses.size());
            return;
        }

        // 每币聚合：一个地址一个币只有一条仓位，仓位数就是地址数
        Map<String, List<Position>> byCoin = new HashMap<>();
        for (AccountState s : states.values()) {
            for (Position p : s.positions()) {
                if (coins.contains(p.coin())) {
                    byCoin.computeIfAbsent(p.coin(), _ -> new ArrayList<>()).add(p);
                }
            }
        }
        int snapshots = 0;
        int expected = 0;
        for (String coin : props.getCoins()) {
            AssetCtx c = ctx.get(coin);
            if (c == null) {
                log.warn("[WhalePosition] metaAndAssetCtxs 里没有 {}，跳过", coin);
                continue;
            }
            expected++;
            CoinSnapshot s = WhaleAggregator.aggregate(coin, c.markPx(), c.openInterest(),
                    byCoin.getOrDefault(coin, List.of()), props.getPoll().getMinPositionValue());
            snapshots += snapshotMapper.insert(snapshotRow(observedAt, states.size(), s));
        }
        if (snapshots < expected) {
            log.warn("[WhalePosition] 槽 {} 已有快照，本轮 {} 个币没写", observedAt, expected - snapshots);
        }

        // 变化流：只对本轮查到的地址比
        int changes = 0;
        for (var e : states.entrySet()) {
            changes += diff(observedAt, e.getKey(), e.getValue(), coins);
        }
        log.info("[WhalePosition] 槽 {} 地址 {}/{} 快照 {} 币，仓位变化 {} 行", observedAt, states.size(), addresses.size(), snapshots, changes);
    }

    private Map<String, Baseline> loadBaseline() {
        Map<String, Baseline> out = new HashMap<>();
        for (Latest l : positionMapper.selectLatest()) {
            if (l.getSzi().signum() != 0) {
                out.put(l.getAddress() + "|" + l.getCoin(), new Baseline(l.getSzi(), l.getEntryPx()));
            }
        }
        log.info("[WhalePosition] 基线 {} 个开着的仓位", out.size());
        return out;
    }

    /** 一个地址：盯盘币仓位不论大小，szi/entryPx 变了写一行；上轮有本轮没有写 szi=0 */
    private int diff(long observedAt, String address, AccountState state, Set<String> coins) {
        int n = 0;
        Set<String> open = new HashSet<>();
        for (Position p : state.positions()) {
            if (!coins.contains(p.coin()) || p.szi().signum() == 0) {
                continue;
            }
            open.add(p.coin());
            String key = address + "|" + p.coin();
            Baseline b = baseline.get(key);
            if (b != null && b.szi().compareTo(p.szi()) == 0 && b.entryPx().compareTo(p.entryPx()) == 0) {
                continue;
            }
            // 先写库再动基线：写失败下轮还会重试
            positionMapper.insert(positionRow(observedAt, address, p));
            baseline.put(key, new Baseline(p.szi(), p.entryPx()));
            n++;
        }
        for (String coin : coins) {
            String key = address + "|" + coin;
            if (!open.contains(coin) && baseline.containsKey(key)) {
                positionMapper.insert(closedRow(observedAt, address, coin));
                baseline.remove(key);
                n++;
            }
        }
        return n;
    }

    private static WhaleSnapshotMapper.Row snapshotRow(long observedAt, int poolSize, CoinSnapshot s) {
        WhaleSnapshotMapper.Row r = new WhaleSnapshotMapper.Row();
        r.setObservedAt(observedAt);
        r.setCoin(s.coin());
        r.setPrice(s.price());
        r.setHlOpenInterest(s.hlOpenInterest());
        r.setPoolSize(poolSize);
        Side l = s.longSide();
        r.setLongCount(l.count());
        r.setLongNotional(l.notional());
        r.setLongWavgEntry(l.wavgEntry());
        r.setLongMedianEntry(l.medianEntry());
        r.setLongTop1Share(l.top1Share());
        r.setLongUpnl(l.upnl());
        Side sh = s.shortSide();
        r.setShortCount(sh.count());
        r.setShortNotional(sh.notional());
        r.setShortWavgEntry(sh.wavgEntry());
        r.setShortMedianEntry(sh.medianEntry());
        r.setShortTop1Share(sh.top1Share());
        r.setShortUpnl(sh.upnl());
        r.setEntryBucketsJson(s.entryBucketsJson());
        r.setLiqBucketsJson(s.liqBucketsJson());
        return r;
    }

    private static WhalePositionMapper.Row positionRow(long observedAt, String address, Position p) {
        WhalePositionMapper.Row r = new WhalePositionMapper.Row();
        r.setObservedAt(observedAt);
        r.setAddress(address);
        r.setCoin(p.coin());
        r.setSzi(p.szi());
        r.setEntryPx(p.entryPx());
        r.setPositionValue(p.positionValue());
        r.setLeverage(p.leverage());
        r.setLiquidationPx(p.liquidationPx());
        r.setUnrealizedPnl(p.unrealizedPnl());
        return r;
    }

    private static WhalePositionMapper.Row closedRow(long observedAt, String address, String coin) {
        WhalePositionMapper.Row r = new WhalePositionMapper.Row();
        r.setObservedAt(observedAt);
        r.setAddress(address);
        r.setCoin(coin);
        r.setSzi(BigDecimal.ZERO);
        return r;
    }
}
