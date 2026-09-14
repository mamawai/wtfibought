package com.mawai.wiibquant.whale;

import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient;
import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient.AccountState;
import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient.Bucket;
import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient.SubAccount;
import com.mawai.wiibquant.mapper.WhaleAddressMapper;
import com.mawai.wiibquant.mapper.WhaleAddressMapper.Known;
import com.mawai.wiibquant.mapper.WhaleAddressMapper.Row;
import com.mawai.wiibquant.whale.WhaleQualifier.Candidate;
import com.mawai.wiibquant.whale.WhaleQualifier.Rules;
import com.mawai.wiibquant.whale.WhaleQualifier.Verdict;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * 每日地址池认证（docs/hyperliquid-whale.md §4.1）：
 * 排行榜出新候选实体 ∪ 库里已知的主地址 → subAccounts 展开成交易账户（内联状态）→ 主地址与没被展开覆盖的已知子账户补查
 * clearinghouseState → 门 1 → 只对过门 1 且 role 未知的主地址查 userRole → 排序取前 cap 进池 → upsert whale_address。
 * <p>
 * 失败语义：排行榜下载失败只是本轮没有新候选，已知账户照常认证；单个地址任一请求失败就跳过它，行不动。
 * 新账户过了门 1 才建行，门 1 拦下的不存。
 * 池子不绑死在排行榜上：它下线了已知账户照常跑，只是不再有新人。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WhalePoolTask {

    private final HyperliquidClient client;
    private final WhaleAddressMapper mapper;
    private final WhaleProperties props;
    /** 启动补跑与 cron 撞上时后者跳过 */
    private final AtomicBoolean running = new AtomicBoolean();

    LongSupplier nowMs = System::currentTimeMillis;

    /** 每日 04:30 北京时间 */
    @Scheduled(cron = "${whale.pool.cron:0 30 4 * * *}", zone = "Asia/Shanghai")
    public void daily() {
        if (props.isEnabled()) {
            refresh();
        }
    }

    /** 启动时表为空就在虚拟线程上补跑一次；查表失败只记 warn */
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        if (!props.isEnabled()) {
            return;
        }
        Thread.ofVirtual().name("whale-pool-bootstrap").start(() -> {
            try {
                if (mapper.count() > 0) {
                    return;
                }
            } catch (Exception e) {
                log.warn("[WhalePool] 启动查表失败，跳过补跑: {}", e.toString());
                return;
            }
            refresh();
        });
    }

    /** 一轮完整认证，同步跑完 */
    public void refresh() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[WhalePool] 上一轮还没跑完，跳过");
            return;
        }
        try {
            run();
        } catch (Exception e) {
            log.error("[WhalePool] 本轮异常中止", e);
        } finally {
            running.set(false);
        }
    }

    private void run() {
        long start = nowMs.getAsLong();
        Rules rules = new Rules(props.getPool().getMinAccountValue(), props.getPool().getMaxPositions(),
                props.getPoll().getMinPositionValue(), new HashSet<>(props.getCoins()), props.getPool().getCap());

        // 已知账户：主地址（非 vault）要重新展开，子账户的主地址也要展开，vault 行不再碰
        Map<String, Known> known = new HashMap<>();
        Set<String> masters = new LinkedHashSet<>();
        Set<String> knownSubs = new LinkedHashSet<>();
        for (Known k : mapper.selectKnown()) {
            known.put(k.getAddress(), k);
            if (k.getParentAddress() == null) {
                if (!WhaleQualifier.isVault(k.getRole())) {
                    masters.add(k.getAddress());
                }
            } else {
                knownSubs.add(k.getAddress());
                masters.add(k.getParentAddress());
            }
        }
        int knownMasters = masters.size();

        // 排行榜出新候选：榜上净值 ≥ min-leaderboard-value 的实体，这个门槛定的是认证时长
        int fromLeaderboard = 0;
        try {
            for (String address : client.leaderboard(props.getPool().getMinLeaderboardValue())) {
                Known k = known.get(address);
                if (k == null || !WhaleQualifier.isVault(k.getRole())) {
                    if (masters.add(address)) {
                        fromLeaderboard++;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[WhalePool] 排行榜下载失败，本轮不加新候选: {}", e.toString());
        }
        log.info("[WhalePool] 候选主地址 {} 个（已知 {} + 榜上新 {}），已知子账户 {} 个",
                masters.size(), knownMasters, fromLeaderboard, knownSubs.size());

        // 展开 + 主地址自身状态；已知子账户没被展开覆盖的（主地址展开失败）补查一次
        Map<String, Candidate> accounts = new ConcurrentHashMap<>();
        try (ExecutorService pool = executor()) {
            for (String m : masters) {
                pool.submit(() -> expand(m, accounts));
                pool.submit(() -> fetchState(m, null, known, accounts));
            }
        }
        try (ExecutorService pool = executor()) {
            for (String s : knownSubs) {
                if (!accounts.containsKey(s)) {
                    pool.submit(() -> fetchState(s, known.get(s).getParentAddress(), known, accounts));
                }
            }
        }

        // 门 1：用已到手的状态，不再花权重
        List<Verdict> passed = new ArrayList<>();
        List<Verdict> rejected = new ArrayList<>();
        for (Candidate c : accounts.values()) {
            Verdict v = WhaleQualifier.gate1(c, rules);
            (v.passed() ? passed : rejected).add(v);
        }

        // 门 2：只对过门 1 且 role 未知的主地址查，结果存行以后不再查；查失败的本轮不下结论、行不动
        Map<String, String> roles = new ConcurrentHashMap<>();
        List<Verdict> needRole = passed.stream()
                .filter(v -> v.candidate().isMaster() && v.candidate().role() == null).toList();
        try (ExecutorService pool = executor()) {
            for (Verdict v : needRole) {
                pool.submit(() -> {
                    String addr = v.candidate().address();
                    try {
                        roles.put(addr, client.userRole(addr, Bucket.POOL));
                    } catch (Exception e) {
                        log.warn("[WhalePool] userRole 失败 {}: {}", addr, e.toString());
                    }
                });
            }
        }
        List<Verdict> withRole = new ArrayList<>(passed.size());
        for (Verdict v : passed) {
            Candidate c = v.candidate();
            if (c.role() != null) {
                withRole.add(v);
            } else if (roles.containsKey(c.address())) {
                withRole.add(new Verdict(c.withRole(roles.get(c.address())), v.trackedMaxPosition(), null, false));
            }
        }

        // 入池排序 + cap，然后落库：过了门 1 的全写，门 1 拦下的只更新已知行
        List<Verdict> ranked = WhaleQualifier.rank(withRole, rules);
        long now = nowMs.getAsLong();
        int inPool = 0;
        int vault = 0;
        int overCap = 0;
        int updatedRejected = 0;
        for (Verdict v : ranked) {
            mapper.upsert(row(v, now));
            if (v.inPool()) {
                inPool++;
            } else if (WhaleQualifier.VAULT.equals(v.rejectReason())) {
                vault++;
            } else {
                overCap++;
            }
        }
        for (Verdict v : rejected) {
            if (known.containsKey(v.candidate().address())) {
                mapper.upsert(row(v, now));
                updatedRejected++;
            }
        }
        log.info("[WhalePool] 本轮 {} 个账户过门 1（其中 {} 个查了角色，{} 个没查到）；进池 {}，vault {}，超额 {}；已知账户被拦 {}，耗时 {} 分钟",
                passed.size(), needRole.size(), needRole.size() - roles.size(), inPool, vault, overCap, updatedRejected,
                (nowMs.getAsLong() - start) / 60_000);
    }

    /** 并发度 = 在途上限：多了也只是排队等桶 */
    private ExecutorService executor() {
        return Executors.newFixedThreadPool(props.getMaxInFlight(), Thread.ofVirtual().factory());
    }

    /** subAccounts 展开：子账户带内联状态，角色天然 subAccount */
    private void expand(String master, Map<String, Candidate> out) {
        try {
            for (SubAccount s : client.subAccounts(master, Bucket.POOL)) {
                out.put(s.address(), new Candidate(s.address(), master, "subAccount", s.state()));
            }
        } catch (Exception e) {
            log.warn("[WhalePool] 展开失败 {}: {}", master, e.toString());
        }
    }

    private void fetchState(String address, String parent, Map<String, Known> known, Map<String, Candidate> out) {
        try {
            AccountState state = client.clearinghouseState(address, Bucket.POOL);
            Known k = known.get(address);
            String role = parent != null ? "subAccount" : (k == null ? null : k.getRole());
            out.put(address, new Candidate(address, parent, role, state));
        } catch (Exception e) {
            log.warn("[WhalePool] clearinghouseState 失败 {}: {}", address, e.toString());
        }
    }

    private static Row row(Verdict v, long now) {
        Candidate c = v.candidate();
        Row r = new Row();
        r.setAddress(c.address());
        r.setParentAddress(c.parentAddress());
        r.setRole(c.role());
        r.setInPool(v.inPool());
        r.setAccountValue(c.state().accountValue());
        r.setPositionCount(c.state().positions().size());
        r.setTrackedMaxPosition(v.trackedMaxPosition());
        r.setRejectReason(v.rejectReason());
        r.setFirstSeenAt(now);
        r.setQualifiedAt(v.passed() ? now : null);
        return r;
    }
}
