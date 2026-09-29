package com.mawai.wiibagent.jev.predictor;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.dto.PredictionBetResponse;
import com.mawai.wiibcommon.dto.PredictionRoundResponse;
import com.mawai.wiibcommon.entity.JevPredictionDecision;
import com.mawai.wiibcommon.entity.JevPredictionRun;
import com.mawai.wiibcommon.market.TimeWeightedAverage.Point;
import com.mawai.wiibagent.jev.JevPlatformConfig;
import com.mawai.wiibagent.mapper.JevPredictionDecisionMapper;
import com.mawai.wiibagent.jev.predictor.PredictionJudge.Judgment;
import com.mawai.wiibagent.jev.predictor.PredictionRules.Book;
import com.mawai.wiibagent.jev.predictor.PredictionRules.Decision;
import com.mawai.wiibagent.jev.predictor.PredictionStateWriter.OddsJump;
import com.mawai.wiibagent.jev.predictor.PredictionStateWriter.Snapshot;
import com.mawai.wiibquant.external.sim.SimPredictionClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import static com.mawai.wiibcommon.entity.JevPredictionDecision.ACTION_BUY_DOWN;
import static com.mawai.wiibcommon.entity.JevPredictionDecision.ACTION_BUY_UP;
import static com.mawai.wiibcommon.entity.JevPredictionDecision.ACTION_ERROR;
import static com.mawai.wiibcommon.entity.JevPredictionDecision.ACTION_HOLD;
import static com.mawai.wiibcommon.entity.JevPredictionDecision.ACTION_SELL;
import static com.mawai.wiibcommon.entity.JevPredictionDecision.ACTION_STAY_OUT;
import static com.mawai.wiibcommon.entity.JevPredictionRun.ARM_JUMP_CODE;
import static com.mawai.wiibcommon.entity.JevPredictionRun.ARM_JUMP_JEV;
import static com.mawai.wiibcommon.entity.JevPredictionRun.ARM_TIMER_JEV;
import static com.mawai.wiibagent.jev.predictor.PredictionRules.NO_BALANCE;
import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 预测员回路：每秒一跳对齐 5 分钟窗口，三组对照各占一局一个账户，一次唤醒按涉及的组各写一行；
 * 另一条每分钟的回填把结算结果、盈亏和唤醒后的价补进去。
 * <ul>
 *   <li>突变唤醒（v5-1、v5-2）：开盘后 30~270 秒，每秒看最近几秒的 UP 中间价，任一边 3 秒内涨到 jump-threshold、
 *       起跳价在区间里就唤醒，看突变那一边，checkpoint 记 J + 开盘后第几秒；两次突变唤醒至少隔 wake-cooldown-ms</li>
 *   <li>整点唤醒（v5-3）：到了 timer-seconds 的每一格就唤醒，空仓看数学上领先的那边、持仓看手里那一边，
 *       checkpoint 记 T + 那一格；突变唤醒后 wake-cooldown-ms 内到点的整点跳过不补；整点不挡突变</li>
 * </ul>
 * 一次唤醒：写一次 state → 盘口太旧、Chainlink 停了就不问不动 → 问一次 Jev 六道盘面题，回答写进每一行 → 各组按自己的规则定买卖 →
 * 要成交的等一次 fill-delay、读一次盘口，都按这份盘口成交：买入比看到的贵、卖出比看到的低都不超过 fill-tolerance 才成交，否则算没抢到。
 * Jev 失败时 v5-1 照常走，要看 Jev 的组落 ERROR 行。某一组付不起一注、也没有等结算的注单就停掉这一组，在跑的组都停了才关开关。
 * <p>
 * 平台 Jev 没配 key、/admin 开关关着、还没开过带组的局都不唤醒；回填不看开关，关掉前的行照样补齐。
 * 写 state 或查账户失败，各组都落 ERROR 行；某一组下单、卖出失败只落它自己的 ERROR 行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JevPredictionRunner {

    static final int WINDOW_SECONDS = PredictionStateWriter.WINDOW_SECONDS;
    /** 这之后不开整点：离收盘太近，下单可能撞上锁盘 */
    static final int LAST_CHECKPOINT_SECONDS = 285;
    /** 开盘后这段里才找突变（含两端） */
    static final int JUMP_FIRST_SECONDS = 30;
    static final int JUMP_LAST_SECONDS = 270;
    /** 找突变读最近这么久的 UP 中间价 */
    static final long JUMP_LOOKBACK_MS = 5_000L;
    /** 两个采样点相隔不超过这么久算"3 秒内"，每秒采一次，留半秒给采样时刻的抖动；右端点也要在最近这么久里 */
    static final long JUMP_SPAN_MS = 3_500L;
    /** 突变唤醒、整点唤醒各自涉及的组 */
    static final List<String> JUMP_ARMS = List.of(ARM_JUMP_CODE, ARM_JUMP_JEV);
    static final List<String> TIMER_ARMS = List.of(ARM_TIMER_JEV);
    /** 回填只看这么久以内的行，更早的当死账 */
    static final long SWEEP_LOOKBACK_SECONDS = 24 * 3600;
    /** 记唤醒后多久的 UP 中间价 */
    static final long AFTER_SHORT_MS = 15_000L;
    static final long AFTER_LONG_MS = 45_000L;
    /** 补唤醒后的价：决策在 5 分钟到 46 秒前的行（45 秒那一刻已经过了还多 1 秒），UP 中间价读最近 6 分钟（Redis 只存这么久） */
    static final long AFTER_FROM_MS = 300_000L;
    static final long AFTER_TO_MS = 46_000L;
    static final long AFTER_SAMPLES_MS = 360_000L;

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
    /** 起线程的注入点，单测换成当场跑 */
    Consumer<Runnable> launch = Thread::startVirtualThread;

    private final AtomicBoolean busy = new AtomicBoolean();
    /** 每一格整点最近跑过（或跳过）的窗口；重启后第一次靠库里查重 */
    private final Map<String, Long> timerRun = new ConcurrentHashMap<>();
    /** 上一次突变唤醒的时刻 */
    private volatile long lastJumpWakeMs;
    /** 钱不够停掉的局号；重启后清空，停掉的组下一次唤醒会再记一行 NO_BALANCE 再停 */
    private final Set<Integer> stopped = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        if (!platform.enabled()) {
            return;
        }
        long now = nowMs.getAsLong();
        long ws = windowStart(now);
        long elapsed = now / 1000 - ws;
        // 先找突变：在找的时段里、离上次突变唤醒够久才找
        boolean jumpOpen = elapsed >= JUMP_FIRST_SECONDS && elapsed <= JUMP_LAST_SECONDS
                && now - lastJumpWakeMs >= cfg.getWakeCooldownMs();
        OddsJump jump = jumpOpen ? latestJump(cache.getPredictionUpMidPoints(now - JUMP_LOOKBACK_MS), now, cfg) : null;
        // 开关和在跑的局到了要跑才读；突变的两组都停了就当没有突变
        List<JevPredictionRun> jumpRuns = jump != null && sw.isOn() ? liveRuns(JUMP_ARMS) : List.of();
        if (!jumpRuns.isEmpty()) {
            start(ws, "J" + elapsed, jump, jumpRuns, () -> lastJumpWakeMs = now);
            return;
        }
        String slot = timerCheckpoint(elapsed, cfg);
        if (slot == null || Long.valueOf(ws).equals(timerRun.get(slot))) {
            return;
        }
        // 突变刚唤醒过：这一格跳过，不再补
        if (now - lastJumpWakeMs < cfg.getWakeCooldownMs()) {
            timerRun.put(slot, ws);
            return;
        }
        // 窗口中途打开开关，还在区间里的整点照跑
        if (!sw.isOn()) {
            return;
        }
        List<JevPredictionRun> timerRuns = liveRuns(TIMER_ARMS);
        if (timerRuns.isEmpty()) {
            // v5-3 停了：整点不再问 Jev
            timerRun.put(slot, ws);
            return;
        }
        start(ws, slot, null, timerRuns, () -> timerRun.put(slot, ws));
    }

    /** 单飞：上一次唤醒还没跑完就不开新的；开起来了才记下这次唤醒 */
    private void start(long ws, String checkpoint, OddsJump jump, List<JevPredictionRun> wakeRuns, Runnable markRun) {
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        markRun.run();
        launch.accept(() -> {
            try {
                runWake(ws, checkpoint, jump, wakeRuns);
            } finally {
                busy.set(false);
            }
        });
    }

    /** 这些组里在跑、没因为钱不够停掉的局 */
    private List<JevPredictionRun> liveRuns(List<String> arms) {
        return runs.active().stream().filter(r -> arms.contains(r.getArm()) && !stopped.contains(r.getRunNo())).toList();
    }

    static long windowStart(long nowMs) {
        long sec = nowMs / 1000;
        return sec - sec % WINDOW_SECONDS;
    }

    /** 开盘后第几秒该跑哪一格整点：落在 [s_i, s_{i+1}) 就是 T{s_i}，最后一格到 LAST 为止；不在任何区间回 null */
    static String timerCheckpoint(long elapsedSeconds, JevPredictionConfig cfg) {
        if (elapsedSeconds >= LAST_CHECKPOINT_SECONDS) {
            return null;
        }
        String cp = null;
        for (int s : cfg.getTimerSeconds()) {
            if (elapsedSeconds < s) {
                break;
            }
            cp = "T" + s;
        }
        return cp;
    }

    /**
     * 找突变：右端点取最近 3.5 秒内的采样点，左端点取离右端点不超过 3.5 秒的更早采样点。
     * UP 这边算突变 = 右 − 左 ≥ 阈值且左在起跳区间里；DOWN 这边 = 左 − 右 ≥ 阈值且 1 − 左在起跳区间里。
     * 多对都满足时按 {@link OddsJump#beats} 取；没有回 null
     */
    static OddsJump latestJump(List<Point> upMids, long now, JevPredictionConfig cfg) {
        OddsJump best = null;
        for (int j = 0; j < upMids.size(); j++) {
            Point r = upMids.get(j);
            if (r.timeMs() > now || now - r.timeMs() > JUMP_SPAN_MS) {
                continue;
            }
            for (int i = 0; i < j; i++) {
                Point l = upMids.get(i);
                if (r.timeMs() - l.timeMs() > JUMP_SPAN_MS) {
                    continue;
                }
                BigDecimal rise = r.price().subtract(l.price());
                boolean upJump = rise.compareTo(cfg.getJumpThreshold()) >= 0 && inFromBand(l.price(), cfg);
                boolean downJump = rise.negate().compareTo(cfg.getJumpThreshold()) >= 0
                        && inFromBand(BigDecimal.ONE.subtract(l.price()), cfg);
                OddsJump x = new OddsJump(l.timeMs(), r.timeMs(), l.price(), r.price());
                if ((upJump || downJump) && x.beats(best)) {
                    best = x;
                }
            }
        }
        return best;
    }

    /** 起跳价在 [jump-from-min, jump-from-max] 里 */
    private static boolean inFromBand(BigDecimal from, JevPredictionConfig cfg) {
        return from.compareTo(cfg.getJumpFromMin()) >= 0 && from.compareTo(cfg.getJumpFromMax()) <= 0;
    }

    /** 一次唤醒里一组的那一行，和这一组这一回合的情况 */
    private static final class Leg {
        final JevPredictionRun run;
        final JevPredictionDecision row;
        long userId;
        /** v5-1、v5-2：这一局这一回合买过的那一行 */
        JevPredictionDecision bought;
        /** v5-3：这一局这一回合在持的那一注 */
        PredictionBetResponse held;
        /** 买入前读到的余额，按成交价再算一次注额用 */
        BigDecimal balance;
        /** 定了要成交的买或卖，等一次 fill-delay 各组一起成交 */
        Decision todo;
        boolean failed;

        Leg(JevPredictionRun run, JevPredictionDecision row) {
            this.run = run;
            this.row = row;
        }

        String arm() {
            return run.getArm();
        }

        boolean holding() {
            return bought != null || held != null;
        }
    }

    /** 一次唤醒，包私有供单测直接调；wakeRuns 是这次涉及、还在跑的局，jump 整点传 null */
    void runWake(long ws, String checkpoint, OddsJump jump, List<JevPredictionRun> wakeRuns) {
        long decidedAt = nowMs.getAsLong();
        List<Leg> legs = new ArrayList<>();
        for (JevPredictionRun run : wakeRuns) {
            // 查重带局号：重启后同一格不重跑
            if (mapper.countCheckpoint(run.getRunNo(), ws, checkpoint) == 0) {
                legs.add(new Leg(run, newRow(run.getRunNo(), ws, checkpoint, decidedAt)));
            }
        }
        if (legs.isEmpty()) {
            return;
        }
        try {
            wake(legs, ws, jump);
        } catch (Exception e) {
            // 写 state、查账户、等成交这些各组共用的步骤失败，各组都落 ERROR
            legs.forEach(l -> fail(l, e));
        }
        for (Leg l : legs) {
            mapper.insert(l.row);
            log.info("[JevPred] R{} {} {} {} side={} action={} pModel={} pJev={} pMkt={} reason={}", l.run.getRunNo(), l.arm(), ws,
                    checkpoint, l.row.getSide(), l.row.getAction(), l.row.getPModel(), l.row.getPJev(), l.row.getPMkt(), l.row.getReason());
        }
        stopBroke(legs);
    }

    private static JevPredictionDecision newRow(int runNo, long ws, String checkpoint, long decidedAt) {
        JevPredictionDecision d = new JevPredictionDecision();
        d.setRunNo(runNo);
        d.setWindowStart(ws);
        d.setCheckpoint(checkpoint);
        d.setDecidedAt(decidedAt);
        d.setAction(ACTION_ERROR);
        // state_json 不许空，写 state 之前就失败的行放空对象
        d.setStateJson("{}");
        return d;
    }

    /**
     * 各组查这一回合的情况 → 写一次 state → 盘口太旧、Chainlink 停了就不问不动 → 定看哪一边 → 问一次 Jev →
     * 各组按规则定买卖 → 一起成交。reason 的代码见 PredictionRules
     */
    private void wake(List<Leg> legs, long ws, OddsJump jump) {
        for (Leg l : legs) {
            l.userId = account.userId(l.run.getRunNo());
            if (ARM_TIMER_JEV.equals(l.arm())) {
                l.held = heldBet(sim.recentBets(l.userId, 10), ws);
                if (l.held != null) {
                    holdFields(l.row, l.held.getId(), l.held.getCost(), l.held.getContracts(), l.held.getAvgPrice());
                }
            } else {
                l.bought = mapper.selectRoundBuy(l.run.getRunNo(), ws);
                if (l.bought != null) {
                    holdFields(l.row, l.bought.getBetId(), l.bought.getStake(), l.bought.getShares(), l.bought.getAvgPrice());
                }
            }
        }
        Snapshot snap = writer.write(ws, jump);
        Integer bookAge = ageMs(snap.raw().bookUpdatedAtMs());
        String stale = bookAge == null || bookAge > cfg.getBookMaxAgeMs() ? "STALE_BOOK " + (bookAge == null ? "none" : bookAge)
                // Chainlink 转发断流是 Polymarket 上游的事，照常跳过，不算出错
                : snap.raw().chainlinkAgeMs() > PredictionStateWriter.TICK_MAX_AGE_MS ? "STALE_CHAINLINK " + snap.raw().chainlinkAgeMs()
                : null;
        for (Leg l : legs) {
            fillMath(l.row, snap);
            fillBook(l.row, snap.raw().book());
            l.row.setBookAgeMs(bookAge);
            // 没成交的：持仓记 HOLD，空仓记 STAY_OUT
            l.row.setAction(l.holding() ? ACTION_HOLD : ACTION_STAY_OUT);
            l.row.setReason(stale);
        }
        if (stale != null) {
            return;
        }
        // 突变看突变那一边；整点只有 v5-3 一组，持仓看手里那一边，空仓看数学上领先的那边
        Leg first = legs.getFirst();
        String side = jump != null ? jump.side()
                : first.held != null ? first.held.getSide() : snap.raw().zModel() >= 0 ? "UP" : "DOWN";
        legs.forEach(l -> l.row.setSide(side));
        Judgment j = askJev(legs, snap, side);
        for (Leg l : legs) {
            if (!l.failed) {
                decide(l, j, snap);
            }
        }
        execute(legs, snap.raw().book());
    }

    /** 问一次 Jev，回答写进每一行；失败了要看 Jev 的组落 ERROR，v5-1 不看 Jev 照常走 */
    private Judgment askJev(List<Leg> legs, Snapshot snap, String side) {
        try {
            Judgment j = judge.judge(snap, side);
            legs.forEach(l -> fillJev(l.row, j));
            return j;
        } catch (Exception e) {
            legs.stream().filter(l -> !ARM_JUMP_CODE.equals(l.arm())).forEach(l -> fail(l, e));
            return null;
        }
    }

    /** 按这一组的规则定：v5-1、v5-2 这一回合买过的只记录；v5-3 持仓看卖不卖、空仓看买不买。要成交的记下来一起成交 */
    private void decide(Leg l, Judgment j, Snapshot snap) {
        Book book = snap.raw().book();
        try {
            Decision dec;
            if (ARM_TIMER_JEV.equals(l.arm())) {
                if (l.held != null) {
                    dec = PredictionRules.timerSell(j, l.held.getSide(), book, cfg);
                } else {
                    l.balance = sim.gameBalance(l.userId);
                    dec = PredictionRules.timerBuy(j, book, l.balance, cfg);
                }
            } else if (l.bought != null) {
                // 这一局这一回合买过了：照问 Jev 只记录，第二个词是买的那一边
                dec = new Decision(ACTION_HOLD, l.row.getSide(), null,
                        "HOLD " + (ACTION_BUY_UP.equals(l.bought.getAction()) ? "UP" : "DOWN"));
            } else {
                l.balance = sim.gameBalance(l.userId);
                dec = ARM_JUMP_CODE.equals(l.arm())
                        ? PredictionRules.jumpCodeEntry(l.row.getSide(), book, l.balance, cfg)
                        : PredictionRules.jumpJevEntry(j, book, l.balance, cfg);
            }
            l.row.setReason(dec.reason());
            if (dec.trades()) {
                l.todo = dec;
            }
        } catch (Exception e) {
            fail(l, e);
        }
    }

    /** 要成交的等一次 fill-delay、读一次盘口，各组都按这份盘口成交；这时盘口旧了都记 STALE_WHILE_ASKING */
    private void execute(List<Leg> legs, Book seen) {
        List<Leg> todo = legs.stream().filter(l -> l.todo != null).toList();
        if (todo.isEmpty()) {
            return;
        }
        Book now = bookAfterDelay();
        for (Leg l : todo) {
            if (now == null) {
                l.row.setReason("STALE_WHILE_ASKING");
                continue;
            }
            try {
                if (ACTION_SELL.equals(l.todo.action())) {
                    sell(l, seen, now);
                } else {
                    buy(l, seen, now);
                }
            } catch (Exception e) {
                fail(l, e);
            }
        }
    }

    /** 买入：卖价比看到的贵不超过容差才按那时的价买 */
    private void buy(Leg l, Book seen, Book now) {
        JevPredictionDecision d = l.row;
        String side = l.todo.side();
        BigDecimal askSeen = seen.ask(side);
        BigDecimal askNow = now.ask(side);
        if (askNow == null || askNow.compareTo(askSeen.add(cfg.getFillTolerance())) > 0) {
            d.setReason("MISSED " + side + " ask " + askSeen.toPlainString() + "→" + plain(askNow));
            return;
        }
        // 价低了手续费占本金的比例反而高，按成交价再算一次付不付得起
        BigDecimal stake = PredictionRules.stake(l.todo.stake(), l.balance, askNow);
        if (stake == null) {
            d.setReason(NO_BALANCE);
            return;
        }
        PredictionBetResponse bet = sim.buy(l.userId, side, stake);
        holdFields(d, bet.getId(), bet.getCost(), bet.getContracts(), bet.getAvgPrice());
        d.setAction(l.todo.action());
        // 在容差里按别的价成交了，reason 记成 "ask 看到的→实际的"
        if (bet.getAvgPrice().compareTo(askSeen) != 0) {
            d.setReason(l.todo.reason() + "→" + bet.getAvgPrice().stripTrailingZeros().toPlainString());
        }
    }

    /** 卖出：买价比看到的低不超过容差才把手里这一注全卖掉，sim 按那时的买价成交 */
    private void sell(Leg l, Book seen, Book now) {
        JevPredictionDecision d = l.row;
        String side = l.todo.side();
        BigDecimal bidSeen = seen.bid(side);
        BigDecimal bidNow = now.bid(side);
        if (bidNow == null || bidNow.compareTo(bidSeen.subtract(cfg.getFillTolerance())) < 0) {
            d.setReason("MISSED SELL bid " + bidSeen.toPlainString() + "→" + plain(bidNow));
            return;
        }
        sim.sell(l.userId, l.held.getId(), null);
        d.setAction(ACTION_SELL);
        // 价变了记成 "bid 看到的→实际的"
        if (bidNow.compareTo(bidSeen) != 0) {
            d.setReason(l.todo.reason() + "→" + bidNow.toPlainString());
        }
    }

    /** 等 fill-delay 再读盘口；这时盘口旧了回 null */
    private Book bookAfterDelay() {
        try {
            Thread.sleep(cfg.getFillDelayMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等成交时被打断");
        }
        Integer age = ageMs(cache.getPredictionBookUpdatedAt());
        if (age == null || age > cfg.getBookMaxAgeMs()) {
            return null;
        }
        return new Book(cache.getPredictionAsk("UP"), cache.getPredictionBid("UP"),
                cache.getPredictionAsk("DOWN"), cache.getPredictionBid("DOWN"));
    }

    /** 这一组落 ERROR 行，不再成交 */
    private void fail(Leg l, Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        l.failed = true;
        l.todo = null;
        l.row.setAction(ACTION_ERROR);
        // error 列 500 字，上游回包塞进异常信息时会超
        l.row.setError(msg.length() > 500 ? msg.substring(0, 500) : msg);
        log.warn("[JevPred] R{} {} {} 失败: {}", l.run.getRunNo(), l.row.getWindowStart(), l.row.getCheckpoint(), msg);
    }

    /** 付不起一注、也没有等结算的注单，这一组就停掉，不再唤醒不再写行；在跑的组都停了才关总开关 */
    private void stopBroke(List<Leg> legs) {
        boolean newlyStopped = false;
        for (Leg l : legs) {
            if (!NO_BALANCE.equals(l.row.getReason())) {
                continue;
            }
            try {
                // ACTIVE 的注单 = 还没结算的（收盘后一分钟多才结），本金押着，结了可能回钱
                if (sim.recentBets(l.userId, 10).stream().noneMatch(b -> "ACTIVE".equals(b.getStatus()))) {
                    stopped.add(l.run.getRunNo());
                    newlyStopped = true;
                    log.warn("[JevPred] R{} {} 钱包付不起一注，停掉这一组", l.run.getRunNo(), l.arm());
                }
            } catch (Exception e) {
                log.warn("[JevPred] R{} 查注单失败: {}", l.run.getRunNo(), e.toString());
            }
        }
        if (newlyStopped && runs.active().stream().allMatch(r -> stopped.contains(r.getRunNo()))) {
            sw.set(false);
            log.warn("[JevPred] 在跑的组都停了，自动关闭预测员");
        }
    }

    /** 这一回合 ACTIVE 的那一注；v5-3 同一时刻最多持有一笔，没有回 null */
    static PredictionBetResponse heldBet(List<PredictionBetResponse> bets, long ws) {
        return bets.stream().filter(b -> b.getWindowStart() == ws && "ACTIVE".equals(b.getStatus())).findFirst().orElse(null);
    }

    /** 注单字段：买入行是这一注，持仓行是在持的那一注 */
    private static void holdFields(JevPredictionDecision d, Long betId, BigDecimal stake, BigDecimal shares, BigDecimal avgPrice) {
        d.setBetId(betId);
        d.setStake(stake);
        d.setShares(shares);
        d.setAvgPrice(avgPrice);
    }

    /** 盘口距上次更新多少毫秒；没记录为 null */
    private Integer ageMs(Long updatedAtMs) {
        return updatedAtMs == null ? null : (int) (nowMs.getAsLong() - updatedAtMs);
    }

    private static void fillMath(JevPredictionDecision d, Snapshot snap) {
        d.setStateJson(MAPPER.writeValueAsString(snap.state()));
        d.setPModel(dec(snap.raw().pModel()));
        d.setLeadSigma(dec(snap.raw().zModel()));
        OddsJump jump = snap.raw().jump();
        if (jump != null) {
            // UP 涨记正数进 up，UP 跌记负数进 down，另一个记 0
            BigDecimal size = jump.to().subtract(jump.from());
            boolean rose = size.signum() > 0;
            d.setOddsJumpUp(rose ? size : BigDecimal.ZERO);
            d.setOddsJumpDown(rose ? BigDecimal.ZERO : size);
        }
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

    private static String plain(BigDecimal v) {
        return v == null ? "none" : v.toPlainString();
    }

    /**
     * 回填：先补唤醒后 15 秒、45 秒的 UP 中间价；再看过去 24 小时里没结果的行，回合 SETTLED 就填结果（VOID 记分时不算），
     * BUY 行的注单到终态（含卖掉）就填盈亏，注单按行所属那一局的账户查。HOLD/SELL 行只记动作，盈亏归开仓那一行。不看开关，不调 Jev。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void settleSweep() {
        if (!platform.enabled()) {
            return;
        }
        long now = nowMs.getAsLong();
        fillAfterPrices(now);
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

    /**
     * 补唤醒后 15 秒、45 秒的 UP 中间价：决策在 5 分钟到 46 秒前、两个都空着的行，采样一次读出，逐行算那两刻的价。
     * 两个都算不出的行不写，过了 5 分钟自然不再查
     */
    private void fillAfterPrices(long now) {
        try {
            List<JevPredictionDecision> rows = mapper.selectPendingAfterPrice(now - AFTER_FROM_MS, now - AFTER_TO_MS);
            if (rows.isEmpty()) {
                return;
            }
            List<Point> upMids = cache.getPredictionUpMidPoints(now - AFTER_SAMPLES_MS);
            for (JevPredictionDecision d : rows) {
                BigDecimal p15 = afterPrice(upMids, d, AFTER_SHORT_MS);
                BigDecimal p45 = afterPrice(upMids, d, AFTER_LONG_MS);
                if (p15 == null && p45 == null) {
                    continue;
                }
                // 只写这两列，别的列留给结算回填
                JevPredictionDecision u = new JevPredictionDecision();
                u.setId(d.getId());
                u.setUpMid15s(p15);
                u.setUpMid45s(p45);
                mapper.updateById(u);
            }
        } catch (Exception e) {
            log.warn("[JevPred] 回填唤醒后的价失败: {}", e.toString());
        }
    }

    /**
     * 唤醒后 afterMs 那一刻的 UP 中间价：不晚于那一刻、不早于本回合开盘的最后一个采样点；
     * 那一刻晚于本回合收盘前 1 秒、或那段没有采样回 null
     */
    static BigDecimal afterPrice(List<Point> upMids, JevPredictionDecision d, long afterMs) {
        long wsMs = d.getWindowStart() * 1000L;
        long t = d.getDecidedAt() + afterMs;
        if (t > wsMs + (WINDOW_SECONDS - 1) * 1000L) {
            return null;
        }
        return PredictionStateWriter.sampleAt(upMids, wsMs, t);
    }

    private static BigDecimal dec(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }
}
