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
import com.mawai.wiibagent.prediction.PredictionRules.Holding;
import com.mawai.wiibagent.prediction.PredictionStateWriter.Snapshot;
import com.mawai.wiibquant.external.sim.SimPredictionClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
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
 * 一次检查点：用当前局的账户查本回合在持的注单 → 写 state（空仓持仓同一份）→ 盘口太旧、Chainlink 停了就不问不动 →
 * 问 Jev 买 UP / 买 DOWN / 不买：空仓照买；持仓选手里这边加注、选另一边卖掉全部注单、不买就拿着 →
 * Jev 要成交的，等 fill-delay 再看盘口，价比 Jev 看到的差不超过 fill-tolerance 就按那时的价成交，再差算没抢到。
 * 卖掉就是空仓，这一格不反手，同回合后面的检查点照常问。钱包付不起一注、也没有等结算的注单就关掉开关，等重新开局。
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

    /** 本回合在持的注单，按下单先后；都是同一边（选另一边就全卖了） */
    record Position(List<PredictionBetResponse> bets) {

        String side() {
            return bets.getFirst().getSide();
        }

        /** 在持本金合计，不含手续费 */
        BigDecimal cost() {
            return bets.stream().map(PredictionBetResponse::getCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        BigDecimal shares() {
            return bets.stream().map(PredictionBetResponse::getContracts).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        /** 加权均价 */
        BigDecimal avgPrice() {
            return cost().divide(shares(), 4, RoundingMode.HALF_UP);
        }
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
            Position pos = position(sim.recentBets(userId, 10), ws);
            // 持仓行记合计，bet_id 记第一笔
            if (pos != null) {
                d.setBetId(pos.bets().getFirst().getId());
                d.setStake(pos.cost());
                d.setShares(pos.shares());
                d.setAvgPrice(pos.avgPrice());
            }
            Snapshot snap = writer.write(ws);
            fillMath(d, snap);
            fillBook(d, snap.raw().book());
            decide(d, userId, snap, pos);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            // 下单 / 卖出失败时 action 已经先记成不动了，这里改回失败
            d.setAction(ACTION_ERROR);
            // error 列 500 字，上游回包塞进异常信息时会超
            d.setError(msg.length() > 500 ? msg.substring(0, 500) : msg);
            log.warn("[JevPred] {} {} 失败: {}", ws, checkpoint, msg);
        }
        mapper.insert(d);
        log.info("[JevPred] R{} {} {} action={} choice={} pModel={} pMkt={} reason={}", runNo, ws, checkpoint, d.getAction(),
                d.getJevChoice(), d.getPModel(), d.getPMkt(), d.getReason());
    }

    /** 盘口太旧、Chainlink 停了不问；问完按 Jev 拍板走。reason 的代码页面按首个词出提示，见 PredictionRules */
    private void decide(JevPredictionDecision d, long userId, Snapshot snap, Position pos) {
        String idle = pos != null ? ACTION_HOLD : ACTION_STAY_OUT;
        Integer bookAge = ageMs(snap.raw().bookUpdatedAtMs());
        d.setBookAgeMs(bookAge);
        if (bookAge == null || bookAge > cfg.getBookMaxAgeMs()) {
            d.setAction(idle);
            d.setReason("STALE_BOOK " + (bookAge == null ? "none" : bookAge));
            return;
        }
        // Chainlink 转发断流是 Polymarket 上游的事，照常跳过，不算出错
        if (snap.raw().chainlinkAgeMs() > PredictionStateWriter.TICK_MAX_AGE_MS) {
            d.setAction(idle);
            d.setReason("STALE_CHAINLINK " + snap.raw().chainlinkAgeMs());
            return;
        }
        // 持仓没人接盘就卖不了，不用问
        if (pos != null && snap.raw().book().bid(pos.side()) == null) {
            d.setAction(ACTION_HOLD);
            d.setReason("NO_BID");
            return;
        }
        Judgment j = judge.judge(snap);
        fillJev(d, j);
        if (pos != null) {
            holding(d, userId, j, snap.raw().book(), pos);
        } else {
            entry(d, userId, j, snap.raw().book());
        }
    }

    /** 盘口距上次更新多少毫秒；没记录为 null */
    private Integer ageMs(Long updatedAtMs) {
        return updatedAtMs == null ? null : (int) (nowMs.getAsLong() - updatedAtMs);
    }

    private static void fillMath(JevPredictionDecision d, Snapshot snap) {
        d.setStateJson(MAPPER.writeValueAsString(snap.state()));
        d.setPModel(dec(snap.raw().pModel()));
        d.setLeadSigma(dec(snap.raw().zModel()));
        d.setOddsJumpUp(snap.raw().oddsJumpUp() == null ? null : dec(snap.raw().oddsJumpUp()));
        d.setOddsJumpDown(snap.raw().oddsJumpDown() == null ? null : dec(snap.raw().oddsJumpDown()));
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
        d.setJevChoice(j.decision().choice());
        d.setJevChoiceP(dec(j.choiceP()));
        d.setModel(j.model());
        d.setInputTokens(j.inputTokens());
        d.setLatencyMs(j.latencyMs());
    }

    /** 空仓：Jev 选买就等一会儿再看卖价，比它看到的贵不超过容差才买；钱包付不起一注、也没有等结算的注单就关开关 */
    private void entry(JevPredictionDecision d, long userId, Judgment j, Book seen) {
        BigDecimal balance = sim.gameBalance(userId);
        Entry e = PredictionRules.entry(j, seen, balance, cfg);
        d.setAction(ACTION_STAY_OUT);
        d.setReason(e.reason());
        BigDecimal askSeen = e.side() == null ? null : seen.ask(e.side());
        if (askSeen != null) {
            d.setEdge(dec(PredictionRules.edge(sideP(j.pModel(), e.side()), askSeen)));
        }
        if (NO_BALANCE.equals(e.reason())) {
            // ACTIVE 的注单 = 前面回合还没结算（收盘后一分钟多才结），本金押着，结了可能回钱
            if (sim.recentBets(userId, 10).stream().noneMatch(b -> "ACTIVE".equals(b.getStatus()))) {
                sw.set(false);
                log.warn("[JevPred] 钱包付不起一注，自动关闭预测员");
            }
            return;
        }
        if (ACTION_STAY_OUT.equals(e.action())) {
            return;
        }
        fill(d, userId, e.side(), e.action(), e.stake(), askSeen, balance, e.reason());
    }

    /**
     * 持仓：选另一边就卖掉全部注单，选手里这边就加注（跟空仓买入一样等一会儿再成交），其余拿着。
     * edge 记卖出扣费后比数学估计多拿多少；加注成交了这一行就是买入行，edge 改按买入算
     */
    private void holding(JevPredictionDecision d, long userId, Judgment j, Book seen, Position pos) {
        String side = pos.side();
        BigDecimal balance = sim.gameBalance(userId);
        Holding h = PredictionRules.holding(j, side, pos.cost(), seen, balance, cfg);
        d.setAction(ACTION_HOLD);
        d.setReason(h.reason());
        // 没人接盘的 decide 里先拦了，这里一定有买价
        BigDecimal bidSeen = seen.bid(side);
        d.setEdge(dec(PredictionRules.sellOver(sideP(j.pModel(), side), bidSeen)));
        if (ACTION_SELL.equals(h.action())) {
            sellAll(d, userId, pos, bidSeen, h.reason());
        } else if (h.stake() != null) {
            BigDecimal askSeen = seen.ask(side);
            fill(d, userId, side, h.action(), h.stake(), askSeen, balance, h.reason());
            if (!ACTION_HOLD.equals(d.getAction())) {
                d.setEdge(dec(PredictionRules.edge(sideP(j.pModel(), side), askSeen)));
            }
        }
    }

    /** 等 fill-delay 再看卖价，比 Jev 看到的贵不超过容差才按那时的价买；开仓和加注共用 */
    private void fill(JevPredictionDecision d, long userId, String side, String action, BigDecimal want, BigDecimal askSeen,
                      BigDecimal balance, String reason) {
        Book now = bookAfterDelay(d);
        if (now == null) {
            return;
        }
        BigDecimal askNow = now.ask(side);
        if (askNow == null || askNow.compareTo(askSeen.add(cfg.getFillTolerance())) > 0) {
            d.setReason("MISSED " + side + " ask " + askSeen.toPlainString() + "→" + plain(askNow));
            return;
        }
        // 价低了手续费占本金的比例反而高，按成交价再算一次付不付得起
        BigDecimal stake = PredictionRules.stake(want, balance, askNow);
        if (stake == null) {
            d.setReason(NO_BALANCE);
            return;
        }
        PredictionBetResponse bet = sim.buy(userId, side, stake);
        d.setBetId(bet.getId());
        d.setStake(bet.getCost());
        d.setShares(bet.getContracts());
        d.setAvgPrice(bet.getAvgPrice());
        d.setAction(action);
        // 在容差里按别的价成交了，reason 记成 "ask 看到的→实际的"
        if (bet.getAvgPrice().compareTo(askSeen) != 0) {
            d.setReason(reason + "→" + bet.getAvgPrice().stripTrailingZeros().toPlainString());
        }
    }

    /** 等 fill-delay 再看买价，比 Jev 看到的低不超过容差才逐笔卖掉本回合全部注单；中途卖失败落 ERROR 行，没卖掉的下一格照常问 */
    private void sellAll(JevPredictionDecision d, long userId, Position pos, BigDecimal bidSeen, String reason) {
        Book now = bookAfterDelay(d);
        if (now == null) {
            return;
        }
        BigDecimal bidNow = now.bid(pos.side());
        if (bidNow == null || bidNow.compareTo(bidSeen.subtract(cfg.getFillTolerance())) < 0) {
            d.setReason("MISSED SELL bid " + bidSeen.toPlainString() + "→" + plain(bidNow));
            return;
        }
        for (PredictionBetResponse b : pos.bets()) {
            sim.sell(userId, b.getId(), null);
        }
        d.setAction(ACTION_SELL);
        // sim 按同一份盘口的买价卖，价变了就记成 "bid 看到的→实际的"
        if (bidNow.compareTo(bidSeen) != 0) {
            d.setReason(reason + "→" + bidNow.toPlainString());
        }
    }

    /** 等 fill-delay 再读盘口；这时盘口旧了回 null，reason 记 STALE_WHILE_ASKING */
    private Book bookAfterDelay(JevPredictionDecision d) {
        try {
            Thread.sleep(cfg.getFillDelayMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等成交时被打断");
        }
        Integer age = ageMs(cache.getPredictionBookUpdatedAt());
        if (age == null || age > cfg.getBookMaxAgeMs()) {
            d.setReason("STALE_WHILE_ASKING");
            return null;
        }
        return new Book(cache.getPredictionAsk("UP"), cache.getPredictionBid("UP"),
                cache.getPredictionAsk("DOWN"), cache.getPredictionBid("DOWN"));
    }

    /** 这一边的数学胜率 */
    private static double sideP(double pModel, String side) {
        return "UP".equals(side) ? pModel : 1 - pModel;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "none" : v.toPlainString();
    }

    /** 本回合 ACTIVE 的注单按下单先后排；一笔都没有回 null */
    static Position position(List<PredictionBetResponse> bets, long ws) {
        List<PredictionBetResponse> active = bets.stream()
                .filter(b -> b.getWindowStart() == ws && "ACTIVE".equals(b.getStatus()))
                .sorted(Comparator.comparingLong(PredictionBetResponse::getId))
                .toList();
        return active.isEmpty() ? null : new Position(active);
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
