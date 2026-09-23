package com.mawai.wiibagent.prediction;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.dto.PredictionBetResponse;
import com.mawai.wiibcommon.dto.PredictionRoundResponse;
import com.mawai.wiibcommon.entity.JevPredictionDecision;
import com.mawai.wiibagent.llm.jev.JevPlatformConfig;
import com.mawai.wiibagent.mapper.JevPredictionDecisionMapper;
import com.mawai.wiibagent.prediction.PredictionJudge.Judgment;
import com.mawai.wiibagent.prediction.PredictionRules.Book;
import com.mawai.wiibagent.prediction.PredictionRules.Entry;
import com.mawai.wiibagent.prediction.PredictionRules.Review;
import com.mawai.wiibagent.prediction.PredictionStateWriter.Snapshot;
import com.mawai.wiibquant.external.sim.SimPredictionClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import static com.mawai.wiibcommon.entity.JevPredictionDecision.ACTION_BUY_DOWN;
import static com.mawai.wiibcommon.entity.JevPredictionDecision.ACTION_BUY_UP;
import static com.mawai.wiibcommon.entity.JevPredictionDecision.ACTION_ERROR;
import static com.mawai.wiibcommon.entity.JevPredictionDecision.ACTION_HOLD;
import static com.mawai.wiibcommon.entity.JevPredictionDecision.ACTION_SELL;
import static com.mawai.wiibcommon.entity.JevPredictionDecision.ACTION_STAY_OUT;
import static com.mawai.wiibagent.prediction.PredictionRules.NO_BALANCE;
import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 预测员回路：每秒一跳对齐 5 分钟窗口，开盘后到了配置里的每个检查点秒数（起手每 15 秒）就问一次 Jev，每次一行落库；
 * 另一条每分钟的回填把结算结果、盈亏补进去。
 * <p>
 * 一次检查点：用当前局的账户查本回合注单 → 写 state → 盘口太旧就不问不动 → 问 Jev 这一回合 UP 会不会赢 →
 * 重读盘口（问的那一两百毫秒里价可能变了）→ 空仓拿 Jev 的胜率比两边成本定买不买、持仓代码按公平价卖或拿着。
 * 卖掉就是空仓，同回合后面的检查点照常问买不买。钱包付不起一注、也没有等结算的注单就关掉开关，等重新开局。
 * <p>
 * 平台 Jev 没配 key 或 /admin 开关关着不开检查点；回填不看开关，关掉前的行照样补齐。
 * 写 state 失败落 ERROR 行；问 Jev 或下注失败也落 ERROR 行，但数学部分（p_model、盘口）已经填上。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JevPredictionRunner {

    static final int WINDOW_SECONDS = PredictionStateWriter.WINDOW_SECONDS;
    /** 这之后不开检查点：离收盘太近，下单可能撞上锁盘 */
    static final int LAST_CHECKPOINT_SECONDS = 285;
    /** 回填只看这么久以内的行，更早的当死账 */
    static final long SWEEP_LOOKBACK_SECONDS = 24 * 3600;

    private final JevPlatformConfig platform;
    private final JevPredictionConfig cfg;
    private final JevPredictionSwitch sw;
    private final PredictionStateWriter writer;
    private final PredictionJudge judge;
    private final JevPredictionAccount account;
    private final JevPredictionRuns runs;
    private final SimPredictionClient sim;
    private final CacheService cache;
    private final JevPredictionDecisionMapper mapper;

    /** 墙钟注入点 */
    LongSupplier nowMs = System::currentTimeMillis;

    private final AtomicBoolean busy = new AtomicBoolean();
    /** 每个检查点最近跑过的窗口；重启后第一次靠库里查重 */
    private final Map<String, Long> lastRun = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        if (!platform.enabled()) {
            return;
        }
        long now = nowMs.getAsLong();
        long ws = windowStart(now);
        String cp = checkpointFor(now / 1000 - ws, cfg);
        if (cp == null || Long.valueOf(ws).equals(lastRun.get(cp))) {
            return;
        }
        // 开关到了检查点才读：每秒读一次 Redis 没必要。窗口中途打开，还在区间里的检查点照跑
        if (!sw.isOn() || !busy.compareAndSet(false, true)) {
            return;
        }
        lastRun.put(cp, ws);
        Thread.startVirtualThread(() -> {
            try {
                runCheckpoint(ws, cp);
            } finally {
                busy.set(false);
            }
        });
    }

    static long windowStart(long nowMs) {
        long sec = nowMs / 1000;
        return sec - sec % WINDOW_SECONDS;
    }

    /** 开盘后第几秒该跑哪个检查点：落在 [s_i, s_{i+1}) 就是 T{s_i}，最后一个到 LAST 为止；不在任何区间回 null */
    static String checkpointFor(long elapsedSeconds, JevPredictionConfig cfg) {
        if (elapsedSeconds >= LAST_CHECKPOINT_SECONDS) {
            return null;
        }
        String cp = null;
        for (int s : cfg.getCheckpointSeconds()) {
            if (elapsedSeconds < s) {
                break;
            }
            cp = "T" + s;
        }
        return cp;
    }

    /** 一次检查点，包私有供单测直接调 */
    void runCheckpoint(long ws, String checkpoint) {
        if (mapper.countCheckpoint(ws, checkpoint) > 0) {
            return;
        }
        int runNo = runs.current().getRunNo();
        JevPredictionDecision d = new JevPredictionDecision();
        d.setRunNo(runNo);
        d.setWindowStart(ws);
        d.setCheckpoint(checkpoint);
        d.setDecidedAt(nowMs.getAsLong());
        d.setAction(ACTION_ERROR);
        // state_json 不许空，写 state 之前就失败的行放空对象
        d.setStateJson("{}");
        try {
            long userId = account.userId(runNo);
            PredictionBetResponse active = activeBet(sim.recentBets(userId, 10), ws);
            if (active != null) {
                d.setBetId(active.getId());
                d.setStake(active.getCost());
                d.setShares(active.getContracts());
                d.setAvgPrice(active.getAvgPrice());
            }
            Snapshot snap = writer.write(ws);
            fillMath(d, snap);
            fillBook(d, snap.raw().book());
            decide(d, userId, snap, active);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            // error 列 500 字，上游回包塞进异常信息时会超
            d.setError(msg.length() > 500 ? msg.substring(0, 500) : msg);
            log.warn("[JevPred] {} {} 失败: {}", ws, checkpoint, msg);
        }
        mapper.insert(d);
        log.info("[JevPred] R{} {} {} action={} pJev={} pModel={} pMkt={} reason={}", runNo, ws, checkpoint, d.getAction(),
                d.getPJev(), d.getPModel(), d.getPMkt(), d.getReason());
    }

    /** 盘口太旧不问；问完重读盘口，问的时候盘口停了也不动。reason 的代码页面按首个词出提示，见 PredictionRules */
    private void decide(JevPredictionDecision d, long userId, Snapshot snap, PredictionBetResponse active) {
        String idle = active != null ? ACTION_HOLD : ACTION_STAY_OUT;
        if (!fresh(d, snap.raw().bookUpdatedAtMs())) {
            d.setAction(idle);
            d.setReason("STALE_BOOK " + (d.getBookAgeMs() == null ? "none" : d.getBookAgeMs()));
            return;
        }
        Judgment j = judge.judge(snap);
        fillJev(d, j);
        Book book = new Book(cache.getPredictionAsk("UP"), cache.getPredictionBid("UP"),
                cache.getPredictionAsk("DOWN"), cache.getPredictionBid("DOWN"));
        fillBook(d, book);
        if (!fresh(d, cache.getPredictionBookUpdatedAt())) {
            d.setAction(idle);
            d.setReason("STALE_WHILE_ASKING");
            return;
        }
        act(d, userId, j, book, active);
    }

    /** 记下盘口年龄；没记录或超龄回 false */
    private boolean fresh(JevPredictionDecision d, Long updatedAtMs) {
        if (updatedAtMs == null) {
            d.setBookAgeMs(null);
            return false;
        }
        long age = nowMs.getAsLong() - updatedAtMs;
        d.setBookAgeMs((int) age);
        return age <= cfg.getBookMaxAgeMs();
    }

    private static void fillMath(JevPredictionDecision d, Snapshot snap) {
        d.setStateJson(MAPPER.writeValueAsString(snap.state()));
        d.setPModel(dec(snap.raw().pModel()));
        d.setLeadSigma(dec(snap.raw().zModel()));
    }

    /** 实际拿来决策的那份盘口 */
    private static void fillBook(JevPredictionDecision d, Book book) {
        d.setPMkt(PredictionRules.impliedUp(book));
        d.setUpAsk(book.upAsk());
        d.setDownAsk(book.downAsk());
        d.setUpBid(book.upBid());
        d.setDownBid(book.downBid());
    }

    private static void fillJev(JevPredictionDecision d, Judgment j) {
        d.setAnswersJson(MAPPER.writeValueAsString(j.answers()));
        d.setPJev(dec(j.pJev()));
        d.setModel(j.model());
        d.setInputTokens(j.inputTokens());
        d.setLatencyMs(j.latencyMs());
    }

    /** 持仓代码按公平价卖或拿着；空仓按 Jev 的胜率买或不买，钱包付不起一注、也没有等结算的注单就关开关 */
    private void act(JevPredictionDecision d, long userId, Judgment j, Book book, PredictionBetResponse active) {
        if (active != null) {
            Review r = PredictionRules.review(j.pModel(), active.getSide(), book, cfg);
            d.setReason(r.reason());
            if (ACTION_SELL.equals(r.action())) {
                sim.sell(userId, active.getId(), null);
            }
            d.setAction(r.action());
            return;
        }
        Entry e = PredictionRules.entry(j, book, sim.gameBalance(userId), cfg);
        d.setEdge(e.edge() == null ? null : dec(e.edge()));
        d.setReason(e.reason());
        if (ACTION_STAY_OUT.equals(e.action())) {
            d.setAction(ACTION_STAY_OUT);
            // ACTIVE 的注单 = 前面回合还没结算（收盘后一分钟多才结），本金押着，结了可能回钱
            if (NO_BALANCE.equals(e.reason())
                    && sim.recentBets(userId, 10).stream().noneMatch(b -> "ACTIVE".equals(b.getStatus()))) {
                sw.set(false);
                log.warn("[JevPred] 钱包付不起一注，自动关闭预测员");
            }
            return;
        }
        PredictionBetResponse bet = sim.buy(userId, e.side(), e.stake());
        d.setBetId(bet.getId());
        d.setStake(bet.getCost());
        d.setShares(bet.getContracts());
        d.setAvgPrice(bet.getAvgPrice());
        d.setAction(e.action());
    }

    private static PredictionBetResponse activeBet(List<PredictionBetResponse> bets, long ws) {
        for (PredictionBetResponse b : bets) {
            if (b.getWindowStart() == ws && "ACTIVE".equals(b.getStatus())) {
                return b;
            }
        }
        return null;
    }

    /**
     * 回填：过去 24 小时里没结果的行，回合 SETTLED 就填结果（VOID 记分时不算）；
     * BUY 行的注单到终态就填盈亏，注单按行所属那一局的账户查。HOLD/SELL 行只记动作，盈亏归开仓那一行。不看开关，不调 Jev。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void settleSweep() {
        if (!platform.enabled()) {
            return;
        }
        long now = nowMs.getAsLong();
        long currentWs = windowStart(now);
        List<JevPredictionDecision> pending = mapper.selectPendingSettle(currentWs, currentWs - SWEEP_LOOKBACK_SECONDS);
        if (pending.isEmpty()) {
            return;
        }
        // 局号 → 这一局账户最近的注单
        Map<Integer, Map<Long, PredictionBetResponse>> bets = new HashMap<>();
        Map<Long, List<JevPredictionDecision>> byWindow = pending.stream()
                .collect(Collectors.groupingBy(JevPredictionDecision::getWindowStart));
        for (Map.Entry<Long, List<JevPredictionDecision>> e : byWindow.entrySet()) {
            try {
                PredictionRoundResponse round = sim.round(e.getKey());
                if (round == null || !"SETTLED".equals(round.getStatus())) {
                    continue;
                }
                for (JevPredictionDecision d : e.getValue()) {
                    if (d.getOutcome() == null) {
                        d.setOutcome(round.getOutcome());
                    }
                    if (d.getBetId() != null && d.getPnl() == null
                            && (ACTION_BUY_UP.equals(d.getAction()) || ACTION_BUY_DOWN.equals(d.getAction()))) {
                        Map<Long, PredictionBetResponse> runBets = bets.computeIfAbsent(d.getRunNo(),
                                n -> sim.recentBets(account.userId(n), 50).stream()
                                        .collect(Collectors.toMap(PredictionBetResponse::getId, Function.identity())));
                        d.setPnl(PredictionRules.pnl(runBets.get(d.getBetId())));
                    }
                    mapper.updateById(d);
                }
            } catch (Exception ex) {
                log.warn("[JevPred] 回填失败 windowStart={}: {}", e.getKey(), ex.toString());
            }
        }
    }

    private static BigDecimal dec(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }
}
