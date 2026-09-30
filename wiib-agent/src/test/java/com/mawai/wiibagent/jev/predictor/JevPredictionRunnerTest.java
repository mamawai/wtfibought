package com.mawai.wiibagent.jev.predictor;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.dto.PredictionBetResponse;
import com.mawai.wiibcommon.dto.PredictionRoundResponse;
import com.mawai.wiibcommon.entity.JevPredictionDecision;
import com.mawai.wiibcommon.entity.JevPredictionRun;
import com.mawai.wiibcommon.market.TimeWeightedAverage.Point;
import com.mawai.wiibagent.jev.JevClient.Answer;
import com.mawai.wiibagent.jev.JevPlatformConfig;
import com.mawai.wiibagent.jev.predictor.JevPredictionRunner.Watch;
import com.mawai.wiibagent.jev.predictor.PredictionJudge.Judgment;
import com.mawai.wiibagent.jev.predictor.PredictionRules.Book;
import com.mawai.wiibagent.jev.predictor.PredictionStateWriter.OddsJump;
import com.mawai.wiibagent.jev.predictor.PredictionStateWriter.Raw;
import com.mawai.wiibagent.jev.predictor.PredictionStateWriter.Snapshot;
import com.mawai.wiibagent.mapper.JevPredictionDecisionMapper;
import com.mawai.wiibquant.external.sim.SimPredictionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static com.mawai.wiibcommon.entity.JevPredictionRun.ARM_JUMP_CODE;
import static com.mawai.wiibcommon.entity.JevPredictionRun.ARM_JUMP_JEV;
import static com.mawai.wiibcommon.entity.JevPredictionRun.ARM_TIMER_JEV;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 唤醒时机（整点、突变、冷却、整点不挡突变）、找突变的边界；一次突变唤醒 v5-1、v5-2 各写一行、共用一次 state / Jev，
 * v5-1 记 WATCH 不买、之后每秒盯：又走够了买它、吐回整个跳幅买另一边、盯满没触发记一行，新突变换掉旧的，买过的不盯，Jev 失败照盯；
 * v5-2 按 Jev 判延续、打回买或不买；这一局这一回合买过的只记录；v5-3 买、拿着、卖、卖后再买；贵过或低过容差没抢到；
 * 钱不够只停那一组、三组都停才关开关；盘口太旧 / Chainlink 停了不问；失败落 ERROR 行；回填结果和唤醒后的价
 */
class JevPredictionRunnerTest {

    /** 对齐 5 分钟边界的窗口起点 */
    private static final long WS = 1_790_016_000L;
    /** 和 application.yml 一致，只是成交不等，几个测试共用 */
    static final JevPredictionConfig CFG = new JevPredictionConfig(
            List.of(60, 90, 120, 150, 180, 210, 240, 270),
            new BigDecimal("0.15"), new BigDecimal("0.30"), new BigDecimal("0.50"), 5000,
            new BigDecimal("5"), new BigDecimal("500"),
            15_000, new BigDecimal("0.10"), new BigDecimal("1.0"), 0.70, 0.70,
            new BigDecimal("0.85"), new BigDecimal("0.96"), 0.70, 0.10,
            0, new BigDecimal("0.03"), 5000);
    private static final Book BOOK = PredictionRulesTest.BOOK;
    private static final Book STRONG_UP = PredictionRulesTest.STRONG_UP;
    /** 在跑的三局：v5-1 R11、v5-2 R12、v5-3 R13，账户是 111、112、113 */
    private static final JevPredictionRun CODE = new JevPredictionRun(11, "r5", 0L, ARM_JUMP_CODE, new BigDecimal("500"));
    private static final JevPredictionRun JEV = new JevPredictionRun(12, "r5", 0L, ARM_JUMP_JEV, new BigDecimal("500"));
    private static final JevPredictionRun TIMER = new JevPredictionRun(13, "r5", 0L, ARM_TIMER_JEV, new BigDecimal("500"));
    private static final List<JevPredictionRun> JUMP_RUNS = List.of(CODE, JEV);
    /** 开盘后 55 秒到 57 秒 UP 中间价 40¢ → 58¢ */
    private static final OddsJump JUMP_UP = new OddsJump((WS + 55) * 1000, (WS + 57) * 1000,
            new BigDecimal("0.40"), new BigDecimal("0.58"));
    private static final BigDecimal BROKE = new BigDecimal("0.5");

    private PredictionStateWriter writer;
    private PredictionJudge judge;
    private JevPredictionAccount account;
    private JevPredictionRuns runs;
    private SimPredictionClient sim;
    private CacheService cache;
    private JevPredictionDecisionMapper mapper;
    private JevPredictionRunner runner;
    private final long now = (WS + 150) * 1000;
    /** 落库的行，按落库先后 */
    private final List<JevPredictionDecision> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        writer = mock(PredictionStateWriter.class);
        judge = mock(PredictionJudge.class);
        account = mock(JevPredictionAccount.class);
        runs = mock(JevPredictionRuns.class);
        sim = mock(SimPredictionClient.class);
        cache = mock(CacheService.class);
        mapper = mock(JevPredictionDecisionMapper.class);
        // 开关用真的，底下的 cache 是 mock：不 stub KEY 就是 Redis 里没这个 key
        runner = new JevPredictionRunner(new JevPlatformConfig("sk", "https://api.typesafe.ai", "jev-latest"),
                CFG, new JevPredictionSwitch(cache), writer, judge, account, runs, sim, cache, mapper);
        runner.nowMs = () -> now;
        when(mapper.countCheckpoint(anyInt(), anyLong(), any())).thenReturn(0);
        when(mapper.insert(any(JevPredictionDecision.class))).thenAnswer(inv -> {
            rows.add(inv.getArgument(0));
            return 1;
        });
        when(runs.active()).thenReturn(List.of(CODE, JEV, TIMER));
        when(account.userId(11)).thenReturn(111L);
        when(account.userId(12)).thenReturn(112L);
        when(account.userId(13)).thenReturn(113L);
        when(sim.gameBalance(anyLong())).thenReturn(new BigDecimal("100"));
        // 注单号 = 账户号 + 1000，按那一边的卖价成交
        when(sim.buy(anyLong(), any(), any())).thenAnswer(inv -> bet((Long) inv.getArgument(0) + 1000, "ACTIVE",
                inv.getArgument(1), BOOK.ask(inv.getArgument(1)).toPlainString()));
        when(cache.getPredictionAsk("UP")).thenReturn(BOOK.upAsk());
        when(cache.getPredictionBid("UP")).thenReturn(BOOK.upBid());
        when(cache.getPredictionAsk("DOWN")).thenReturn(BOOK.downAsk());
        when(cache.getPredictionBid("DOWN")).thenReturn(BOOK.downBid());
        when(cache.getPredictionBookUpdatedAt()).thenReturn(now - 700);
        // 唤醒那一刻 UP 中间价 58¢
        when(cache.getPredictionUpMidPoints(anyLong())).thenReturn(List.of(new Point(now - 1000, new BigDecimal("0.58"))));
    }

    /**
     * 数学 z、上涨概率 pModel，Chainlink 0.8 秒前刚更新；jump 为 null 是整点，
     * 突变时 Binance 最近 10 秒涨 $25、30 秒涨 $80，Chainlink 比它低 $18
     */
    private Snapshot snapshot(double z, double pModel, Long bookUpdatedAt, OddsJump jump, Book book) {
        Raw raw = jump == null
                ? new Raw(z, pModel, book, bookUpdatedAt, 800, null, null, null, null)
                : new Raw(z, pModel, book, bookUpdatedAt, 800, jump, 25.0, 80.0, -18.0);
        return new Snapshot(Map.of("market", "..."), raw);
    }

    /** 突变唤醒：数学上没谁领先 */
    private Snapshot jumpSnap() {
        return snapshot(0.1, 0.52, now - 700, JUMP_UP, BOOK);
    }

    /** 整点唤醒：UP 领先一个多正常波动，盘口是 UP 大幅领先那份，等成交时读到的也是它，买入按 0.90 成交 */
    private void timerWake() {
        when(writer.write(WS, null)).thenReturn(snapshot(1.2, 0.74, now - 700, null, STRONG_UP));
        when(cache.getPredictionAsk("UP")).thenReturn(STRONG_UP.upAsk());
        when(cache.getPredictionBid("UP")).thenReturn(STRONG_UP.upBid());
        when(cache.getPredictionAsk("DOWN")).thenReturn(STRONG_UP.downAsk());
        when(cache.getPredictionBid("DOWN")).thenReturn(STRONG_UP.downBid());
        when(sim.buy(anyLong(), any(), any())).thenAnswer(inv ->
                bet((Long) inv.getArgument(0) + 1000, "ACTIVE", inv.getArgument(1), "0.90"));
    }

    private static Answer noul(double p) {
        return new Answer("noul", p, null, null, null, null);
    }

    /** 整点：Jev 看 side 这一边的回答，会赢、最新一步逆着，answers 按回包的样子放 */
    private static Judgment timerJev(String side, double win, double against) {
        Map<String, Answer> answers = new LinkedHashMap<>();
        answers.put("win", noul(win));
        answers.put("pattern", new Answer("choice", null, "chop", null, 0.8, Map.of("chop", 0.6)));
        answers.put("push_fading", noul(0.2));
        answers.put("flow_confirms", noul(0.8));
        answers.put("dip_recovered", noul(0.4));
        answers.put("latest_against", noul(against));
        return new Judgment(side, win, against, null, null, answers, "jev-1.14.0", 820, 140);
    }

    /** 会赢 0.7、最新一步逆着 0.8：v5-3 空仓会买 */
    private static Judgment good(String side) {
        return timerJev(side, 0.7, 0.8);
    }

    /** 突变：Jev 看突变那一边的回答，会赢 0.7、会延续、会被打回 */
    private static Judgment jumpJev(String side, double extend, double reject) {
        Map<String, Answer> answers = new LinkedHashMap<>();
        answers.put("win", noul(0.7));
        answers.put("extend", noul(extend));
        answers.put("reject", noul(reject));
        return new Judgment(side, 0.7, null, extend, reject, answers, "jev-1.14.0", 820, 140);
    }

    private static PredictionBetResponse bet(long id, String status, String side, String avgPrice) {
        PredictionBetResponse b = new PredictionBetResponse();
        b.setId(id);
        b.setWindowStart(WS);
        b.setStatus(status);
        b.setSide(side);
        b.setCost(new BigDecimal("5"));
        b.setContracts(new BigDecimal("12.5"));
        b.setAvgPrice(new BigDecimal(avgPrice));
        return b;
    }

    /** 这一局这一回合已经买过的那一行 */
    private static JevPredictionDecision buyRow(String action, long betId) {
        JevPredictionDecision d = new JevPredictionDecision();
        d.setAction(action);
        d.setBetId(betId);
        d.setStake(new BigDecimal("5"));
        d.setShares(new BigDecimal("12.5"));
        d.setAvgPrice(new BigDecimal("0.40"));
        return d;
    }

    /** 这一局最后落库的那一行 */
    private JevPredictionDecision row(int runNo) {
        return rows.stream().filter(d -> d.getRunNo() == runNo).reduce((a, b) -> b).orElseThrow();
    }

    /** 这一局这个检查点的那一行 */
    private JevPredictionDecision row(int runNo, String checkpoint) {
        return rows.stream().filter(d -> d.getRunNo() == runNo && checkpoint.equals(d.getCheckpoint())).findFirst().orElseThrow();
    }

    // ==================== 唤醒时机 ====================

    @Test
    void 整点从60秒起() {
        assertThat(JevPredictionRunner.timerCheckpoint(59, CFG)).isNull();
        assertThat(JevPredictionRunner.timerCheckpoint(60, CFG)).isEqualTo("T60");
        assertThat(JevPredictionRunner.timerCheckpoint(89, CFG)).isEqualTo("T60");
        assertThat(JevPredictionRunner.timerCheckpoint(90, CFG)).isEqualTo("T90");
        assertThat(JevPredictionRunner.timerCheckpoint(284, CFG)).isEqualTo("T270");
        assertThat(JevPredictionRunner.timerCheckpoint(285, CFG)).isNull();
        assertThat(JevPredictionRunner.windowStart((WS + 299) * 1000)).isEqualTo(WS);
    }

    /** 每秒一个 UP 中间价（美分），从开盘后 fromSec 秒起 */
    private static List<Point> mids(long fromSec, int... cents) {
        List<Point> pts = new ArrayList<>();
        for (int i = 0; i < cents.length; i++) {
            pts.add(new Point((WS + fromSec + i) * 1000, BigDecimal.valueOf(cents[i], 2)));
        }
        return pts;
    }

    /** 开盘后 sec 秒、再过 200 毫秒 */
    private static long atSec(long sec) {
        return (WS + sec) * 1000 + 200;
    }

    @Test
    void 找突变_起跳29不算_30和50算_DOWN那边对称() {
        assertThat(JevPredictionRunner.latestJump(mids(55, 29, 37, 44), atSec(57), CFG)).isNull();
        OddsJump from30 = JevPredictionRunner.latestJump(mids(55, 30, 38, 45), atSec(57), CFG);
        assertThat(from30).isNotNull();
        assertThat(from30.side()).isEqualTo("UP");
        assertThat(from30.from()).isEqualByComparingTo("0.30");
        assertThat(from30.to()).isEqualByComparingTo("0.45");
        assertThat(JevPredictionRunner.latestJump(mids(55, 50, 58, 65), atSec(57), CFG)).isNotNull();
        assertThat(JevPredictionRunner.latestJump(mids(55, 51, 58, 70), atSec(57), CFG)).isNull();
        // UP 从 71¢ 跌 = DOWN 从 29¢ 涨，不算；UP 从 70¢、50¢ 跌 = DOWN 从 30¢、50¢ 涨，算
        assertThat(JevPredictionRunner.latestJump(mids(55, 71, 63, 56), atSec(57), CFG)).isNull();
        OddsJump down30 = JevPredictionRunner.latestJump(mids(55, 70, 62, 55), atSec(57), CFG);
        assertThat(down30.side()).isEqualTo("DOWN");
        assertThat(down30.sideFrom()).isEqualByComparingTo("0.30");
        assertThat(down30.sideTo()).isEqualByComparingTo("0.45");
        assertThat(JevPredictionRunner.latestJump(mids(55, 50, 42, 35), atSec(57), CFG).side()).isEqualTo("DOWN");
    }

    @Test
    void 找突变_正好15美分算_差一点不算_隔四秒的不配对_右端点要在最近三秒半里() {
        assertThat(JevPredictionRunner.latestJump(mids(55, 40, 47, 55), atSec(57), CFG)).isNotNull();
        assertThat(JevPredictionRunner.latestJump(mids(55, 40, 47, 54), atSec(57), CFG)).isNull();
        // 每秒涨 5¢：3 秒内最多 15¢ 算，4 秒涨 20¢ 那对不配
        OddsJump steady = JevPredictionRunner.latestJump(mids(53, 30, 35, 40, 45, 50), atSec(57), CFG);
        assertThat(steady.toMs() - steady.fromMs()).isEqualTo(3000);
        assertThat(steady.to().subtract(steady.from())).isEqualByComparingTo("0.15");
        // 53 秒涨到 58¢ 随即回落：57 秒时右端点已经不在最近 3.5 秒里，56 秒时还在
        List<Point> spike = mids(51, 40, 50, 58, 45, 45, 45, 45);
        assertThat(JevPredictionRunner.latestJump(spike, atSec(57), CFG)).isNull();
        assertThat(JevPredictionRunner.latestJump(spike, atSec(56), CFG)).isNotNull();
        // 几对都满足取幅度大的，一样大取用时短的
        OddsJump best = JevPredictionRunner.latestJump(mids(54, 35, 40, 50, 55), atSec(57), CFG);
        assertThat(best.from()).isEqualByComparingTo("0.35");
        assertThat(best.to()).isEqualByComparingTo("0.55");
        OddsJump shortest = JevPredictionRunner.latestJump(mids(54, 40, 40, 58, 58), atSec(57), CFG);
        assertThat(shortest.fromMs()).isEqualTo((WS + 55) * 1000);
        assertThat(shortest.toMs()).isEqualTo((WS + 56) * 1000);
    }

    /** 每秒跳一次，从开盘后 fromSec 秒跳到 toSec 秒 */
    private void tickEverySecond(AtomicLong clock, long fromSec, long toSec) {
        for (long s = fromSec; s <= toSec; s++) {
            clock.set(atSec(s));
            runner.tick();
        }
    }

    /** 唤醒当场跑，开关开着，库里查重说已有：只看跑了哪些局的哪些检查点 */
    private AtomicLong syncRunner(List<Point> upMids) {
        AtomicLong clock = new AtomicLong();
        runner.nowMs = clock::get;
        runner.launch = Runnable::run;
        when(cache.get(JevPredictionSwitch.KEY)).thenReturn("1");
        when(mapper.countCheckpoint(anyInt(), anyLong(), any())).thenReturn(1);
        when(cache.getPredictionUpMidPoints(anyLong())).thenReturn(upMids);
        return clock;
    }

    @Test
    void 开关没开过_到了整点也不跑() {
        runner.tick();

        verify(mapper, after(300).never()).countCheckpoint(anyInt(), anyLong(), any());
    }

    @Test
    void 开关打开_到了整点就给v5_3跑() {
        when(cache.get(JevPredictionSwitch.KEY)).thenReturn("1");
        when(mapper.countCheckpoint(13, WS, "T150")).thenReturn(1);

        runner.tick();

        verify(mapper, timeout(1000)).countCheckpoint(13, WS, "T150");
    }

    @Test
    void 还没开过带组的局_不跑() {
        AtomicLong clock = syncRunner(mids(55, 40, 40, 58));
        when(runs.active()).thenReturn(List.of());

        tickEverySecond(clock, 57, 60);

        verify(mapper, never()).countCheckpoint(anyInt(), anyLong(), any());
    }

    @Test
    void 突变在57秒_两组各跑一次_60秒的整点跳过不补_90秒照常() {
        AtomicLong clock = syncRunner(mids(40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 40, 50, 58));

        tickEverySecond(clock, 56, 95);

        verify(mapper).countCheckpoint(11, WS, "J57");
        verify(mapper).countCheckpoint(12, WS, "J57");
        verify(mapper, never()).countCheckpoint(13, WS, "T60");
        verify(mapper).countCheckpoint(13, WS, "T90");
        verify(mapper, times(3)).countCheckpoint(anyInt(), eq(WS), anyString());
    }

    @Test
    void 突变在45秒_60秒的整点照常() {
        AtomicLong clock = syncRunner(mids(40, 40, 40, 40, 40, 50, 58));

        tickEverySecond(clock, 45, 62);

        verify(mapper).countCheckpoint(11, WS, "J45");
        verify(mapper).countCheckpoint(12, WS, "J45");
        verify(mapper).countCheckpoint(13, WS, "T60");
        verify(mapper, times(3)).countCheckpoint(anyInt(), eq(WS), anyString());
    }

    @Test
    void 两次突变间隔不到5秒_只唤醒一次() {
        // 57 秒 UP 40¢ → 58¢，60 秒又砸回 40¢（DOWN 从 42¢ 涨到 60¢，也够突变）
        List<Point> upMids = mids(50, 40, 40, 40, 40, 40, 40, 50, 58, 58, 45, 40, 40);
        AtomicLong clock = syncRunner(upMids);
        assertThat(JevPredictionRunner.latestJump(upMids, atSec(60), CFG).side()).isEqualTo("DOWN");

        tickEverySecond(clock, 57, 61);

        verify(mapper).countCheckpoint(11, WS, "J57");
        verify(mapper, times(2)).countCheckpoint(anyInt(), eq(WS), startsWith("J"));
        verify(mapper, never()).countCheckpoint(13, WS, "T60");
    }

    @Test
    void 整点不挡突变() {
        AtomicLong clock = syncRunner(mids(55, 40, 40, 40, 40, 40, 50, 58));

        tickEverySecond(clock, 60, 61);

        verify(mapper).countCheckpoint(13, WS, "T60");
        verify(mapper).countCheckpoint(11, WS, "J61");
        verify(mapper).countCheckpoint(12, WS, "J61");
    }

    @Test
    void 开盘后30秒前_270秒后不找突变() {
        AtomicLong clock = syncRunner(mids(20, 40, 40, 58));
        when(cache.get(JevPredictionSwitch.KEY)).thenReturn("0");

        clock.set(atSec(29));
        runner.tick();
        clock.set(atSec(271));
        runner.tick();
        verify(cache, never()).getPredictionUpMidPoints(anyLong());

        clock.set(atSec(30));
        runner.tick();
        verify(cache).getPredictionUpMidPoints(atSec(30) - 5000);
    }

    // ==================== 突变唤醒：v5-1、v5-2 ====================

    @Test
    void 一次突变唤醒_两组各写一行_state和Jev只一次_v5_1记WATCH不买_v5_2判延续就买突变那一边() {
        when(writer.write(WS, JUMP_UP)).thenReturn(jumpSnap());
        when(judge.judge(any(), eq("UP"))).thenReturn(jumpJev("UP", 0.82, 0.1));

        runner.runWake(WS, "J57", JUMP_UP, JUMP_RUNS);

        verify(writer, times(1)).write(anyLong(), any());
        verify(judge, times(1)).judge(any(), any());
        verify(mapper).selectRoundBuy(11, WS);
        verify(mapper).selectRoundBuy(12, WS);
        assertThat(rows).hasSize(2);
        JevPredictionDecision code = row(11);
        assertThat(code.getCheckpoint()).isEqualTo("J57");
        assertThat(code.getWindowStart()).isEqualTo(WS);
        assertThat(code.getDecidedAt()).isEqualTo(now);
        assertThat(code.getSide()).isEqualTo("UP");
        assertThat(code.getAction()).isEqualTo(JevPredictionDecision.ACTION_STAY_OUT);
        assertThat(code.getReason()).isEqualTo("WATCH UP");
        assertThat(code.getBetId()).isNull();
        // 不看 Jev 也把回答写上
        assertThat(code.getAnswersJson()).contains("extend");
        assertThat(code.getPJev()).isEqualByComparingTo("0.7");
        assertThat(code.getJevChoice()).isNull();
        assertThat(code.getOddsJumpUp()).isEqualByComparingTo("0.18");
        assertThat(code.getOddsJumpDown()).isEqualByComparingTo("0");
        assertThat(code.getBinance10s()).isEqualByComparingTo("25");
        assertThat(code.getBinance30s()).isEqualByComparingTo("80");
        assertThat(code.getChainlinkGap()).isEqualByComparingTo("-18");
        assertThat(code.getPMkt()).isEqualByComparingTo("0.61");
        assertThat(code.getBookAgeMs()).isEqualTo(700);
        JevPredictionDecision jevRow = row(12);
        assertThat(jevRow.getAction()).isEqualTo(JevPredictionDecision.ACTION_BUY_UP);
        assertThat(jevRow.getReason()).isEqualTo("EXTEND UP 0.820 ask 0.62");
        assertThat(jevRow.getBetId()).isEqualTo(1112L);
        assertThat(jevRow.getStake()).isEqualByComparingTo("5");
        assertThat(jevRow.getAnswersJson()).isEqualTo(code.getAnswersJson());
        verify(sim).buy(112L, "UP", new BigDecimal("5"));
        verify(sim, never()).buy(eq(111L), any(), any());
        verify(sim, never()).gameBalance(111L);
    }

    @Test
    void v5_2判会被打回_买另一边() {
        when(writer.write(WS, JUMP_UP)).thenReturn(jumpSnap());
        when(judge.judge(any(), eq("UP"))).thenReturn(jumpJev("UP", 0.2, 0.75));

        runner.runWake(WS, "J57", JUMP_UP, List.of(JEV));

        JevPredictionDecision d = row(12);
        // 看的还是突变那一边，买的是另一边
        assertThat(d.getSide()).isEqualTo("UP");
        assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_BUY_DOWN);
        assertThat(d.getReason()).isEqualTo("REJECT DOWN 0.750 ask 0.40");
        verify(sim).buy(112L, "DOWN", new BigDecimal("5"));
    }

    @Test
    void v5_2两道都不到线_不买() {
        when(writer.write(WS, JUMP_UP)).thenReturn(jumpSnap());
        when(judge.judge(any(), eq("UP"))).thenReturn(jumpJev("UP", 0.45, 0.3));

        runner.runWake(WS, "J57", JUMP_UP, List.of(JEV));

        assertThat(row(12).getAction()).isEqualTo(JevPredictionDecision.ACTION_STAY_OUT);
        assertThat(row(12).getReason()).isEqualTo("NO_CALL UP 0.450 0.300");
        verify(sim, never()).buy(anyLong(), any(), any());
    }

    @Test
    void 等成交时卖价贵了容差以内_照样买_reason记实际价() {
        when(writer.write(WS, JUMP_UP)).thenReturn(jumpSnap());
        when(judge.judge(any(), eq("UP"))).thenReturn(jumpJev("UP", 0.82, 0.1));
        when(cache.getPredictionAsk("UP")).thenReturn(new BigDecimal("0.65"));
        when(sim.buy(anyLong(), any(), any())).thenAnswer(inv ->
                bet((Long) inv.getArgument(0) + 1000, "ACTIVE", inv.getArgument(1), "0.6500"));

        runner.runWake(WS, "J57", JUMP_UP, JUMP_RUNS);

        assertThat(row(12).getReason()).isEqualTo("EXTEND UP 0.820 ask 0.62→0.65");
        assertThat(row(12).getAvgPrice()).isEqualByComparingTo("0.65");
    }

    @Test
    void 等成交时那边没人卖了_没抢到() {
        when(writer.write(WS, JUMP_UP)).thenReturn(jumpSnap());
        when(judge.judge(any(), eq("UP"))).thenReturn(jumpJev("UP", 0.82, 0.1));
        when(cache.getPredictionAsk("UP")).thenReturn(null);

        runner.runWake(WS, "J57", JUMP_UP, List.of(JEV));

        verify(sim, never()).buy(anyLong(), any(), any());
        assertThat(row(12).getReason()).isEqualTo("MISSED UP ask 0.62→none");
    }

    // ==================== v5-1 盯 ====================

    /**
     * 盯用：唤醒当场跑、开关开着；UP 中间价按 timeline 走，读到的是到那一刻为止的；state 带着 tick 找到的那次突变；
     * 盘口 0.7 秒前更新；v5-2 两道都不到线不买
     */
    private AtomicLong watchRunner(List<Point> timeline) {
        AtomicLong clock = new AtomicLong();
        runner.nowMs = clock::get;
        runner.launch = Runnable::run;
        when(cache.get(JevPredictionSwitch.KEY)).thenReturn("1");
        when(cache.getPredictionUpMidPoints(anyLong())).thenAnswer(inv -> timeline.stream()
                .filter(p -> p.timeMs() >= (Long) inv.getArgument(0) && p.timeMs() <= clock.get()).toList());
        when(cache.getPredictionBookUpdatedAt()).thenAnswer(inv -> clock.get() - 700);
        when(writer.write(eq(WS), any())).thenAnswer(inv -> snapshot(0.1, 0.52, clock.get() - 700, inv.getArgument(1), BOOK));
        when(judge.judge(any(), any())).thenReturn(jumpJev("UP", 0.5, 0.2));
        return clock;
    }

    /** 落库的 W 行 */
    private List<JevPredictionDecision> watchRows() {
        return rows.stream().filter(d -> d.getCheckpoint().startsWith("W")).toList();
    }

    @Test
    void 盯_第3秒又走10美分就买它_W行记那一秒() {
        // 57 秒 UP 40¢ → 58¢，之后 61¢、64¢，60 秒 68¢
        AtomicLong clock = watchRunner(mids(55, 40, 40, 58, 61, 64, 68, 68, 68, 68, 68));

        tickEverySecond(clock, 57, 64);

        assertThat(row(11, "J57").getReason()).isEqualTo("WATCH UP");
        assertThat(row(12, "J57").getReason()).isEqualTo("NO_CALL UP 0.500 0.200");
        assertThat(watchRows()).hasSize(1);
        JevPredictionDecision w = row(11, "W60");
        assertThat(w.getWindowStart()).isEqualTo(WS);
        assertThat(w.getDecidedAt()).isEqualTo(atSec(60));
        assertThat(w.getSide()).isEqualTo("UP");
        assertThat(w.getAction()).isEqualTo(JevPredictionDecision.ACTION_BUY_UP);
        assertThat(w.getReason()).isEqualTo("EXTEND UP 0.100 ask 0.62");
        assertThat(w.getBetId()).isEqualTo(1111L);
        // 不写 state、不问 Jev，盘口照记
        assertThat(w.getStateJson()).isEqualTo("{}");
        assertThat(w.getAnswersJson()).isNull();
        assertThat(w.getPJev()).isNull();
        assertThat(w.getPMkt()).isEqualByComparingTo("0.61");
        assertThat(w.getUpAsk()).isEqualByComparingTo("0.62");
        assertThat(w.getBookAgeMs()).isEqualTo(700);
        verify(sim, times(1)).buy(111L, "UP", new BigDecimal("5"));
        verify(judge, times(1)).judge(any(), any());
    }

    @Test
    void 盯_吐回整个跳幅就买另一边() {
        // 57 秒 UP 40¢ → 58¢，59 秒跌回 40¢
        AtomicLong clock = watchRunner(mids(55, 40, 40, 58, 50, 40));

        tickEverySecond(clock, 57, 59);

        JevPredictionDecision w = row(11, "W59");
        assertThat(w.getSide()).isEqualTo("UP");
        assertThat(w.getAction()).isEqualTo(JevPredictionDecision.ACTION_BUY_DOWN);
        assertThat(w.getReason()).isEqualTo("REJECT DOWN 0.180 ask 0.40");
        verify(sim).buy(111L, "DOWN", new BigDecimal("5"));
    }

    @Test
    void 盯_15秒没触发_记NO_TRIGGER不买() {
        // 57 秒 UP 40¢ → 58¢，之后一直 60¢
        AtomicLong clock = watchRunner(mids(55, 40, 40, 58, 60, 60, 60, 60, 60, 60, 60, 60, 60, 60, 60, 60, 60, 60, 60));

        tickEverySecond(clock, 57, 71);
        assertThat(watchRows()).isEmpty();

        tickEverySecond(clock, 72, 72);
        JevPredictionDecision w = row(11, "W72");
        assertThat(w.getAction()).isEqualTo(JevPredictionDecision.ACTION_STAY_OUT);
        assertThat(w.getReason()).isEqualTo("NO_TRIGGER UP 0.020");
        assertThat(w.getBetId()).isNull();
        verify(sim, never()).gameBalance(111L);
        verify(sim, never()).buy(anyLong(), any(), any());
    }

    @Test
    void 盯的时候来了新突变_换成盯新的() {
        // 57 秒 40¢ → 58¢；回落到 45¢ 没吐回整个跳幅；63 秒 45¢ → 60¢ 又一次突变；64 秒 68¢（按旧的起点已经又走了 10¢），65 秒 70¢
        AtomicLong clock = watchRunner(mids(55, 40, 40, 58, 55, 50, 45, 45, 45, 60, 68, 70, 70));

        tickEverySecond(clock, 57, 66);

        assertThat(row(11, "J57").getReason()).isEqualTo("WATCH UP");
        assertThat(row(11, "J63").getReason()).isEqualTo("WATCH UP");
        // 按新的起点 60¢ 算，65 秒才又走了 10¢
        assertThat(watchRows()).extracting(JevPredictionDecision::getCheckpoint).containsExactly("W65");
        assertThat(row(11, "W65").getReason()).isEqualTo("EXTEND UP 0.100 ask 0.62");
    }

    @Test
    void 这一局买过了_记HOLD不盯_另一组照买() {
        AtomicLong clock = watchRunner(mids(55, 40, 40, 58, 61, 64, 68, 68));
        when(mapper.selectRoundBuy(11, WS)).thenReturn(buyRow(JevPredictionDecision.ACTION_BUY_DOWN, 7));
        when(judge.judge(any(), eq("UP"))).thenReturn(jumpJev("UP", 0.82, 0.1));

        tickEverySecond(clock, 57, 61);

        verify(judge, times(1)).judge(any(), eq("UP"));
        JevPredictionDecision code = row(11, "J57");
        assertThat(code.getAction()).isEqualTo(JevPredictionDecision.ACTION_HOLD);
        // 这一次看的是 UP，买过的是 DOWN
        assertThat(code.getSide()).isEqualTo("UP");
        assertThat(code.getReason()).isEqualTo("HOLD DOWN");
        assertThat(code.getBetId()).isEqualTo(7L);
        assertThat(code.getStake()).isEqualByComparingTo("5");
        assertThat(code.getShares()).isEqualByComparingTo("12.5");
        assertThat(code.getAvgPrice()).isEqualByComparingTo("0.40");
        assertThat(code.getPJev()).isEqualByComparingTo("0.7");
        assertThat(row(12, "J57").getAction()).isEqualTo(JevPredictionDecision.ACTION_BUY_UP);
        // 60 秒又走了 10¢ 也不买
        assertThat(watchRows()).isEmpty();
        verify(sim, never()).buy(eq(111L), any(), any());
        verify(sim, never()).gameBalance(111L);
    }

    @Test
    void Jev失败_v5_1照盯_v5_2记ERROR() {
        AtomicLong clock = watchRunner(mids(55, 40, 40, 58, 61, 64, 68));
        when(judge.judge(any(), any())).thenThrow(new IllegalStateException("Jev 回包缺题 win，只有 []"));

        tickEverySecond(clock, 57, 60);

        JevPredictionDecision code = row(11, "J57");
        assertThat(code.getReason()).isEqualTo("WATCH UP");
        assertThat(code.getAnswersJson()).isNull();
        assertThat(code.getPJev()).isNull();
        assertThat(code.getError()).isNull();
        JevPredictionDecision jevRow = row(12, "J57");
        assertThat(jevRow.getAction()).isEqualTo(JevPredictionDecision.ACTION_ERROR);
        assertThat(jevRow.getError()).contains("缺题");
        assertThat(jevRow.getPModel()).isEqualByComparingTo("0.52");
        assertThat(jevRow.getSide()).isEqualTo("UP");
        assertThat(row(11, "W60").getReason()).isEqualTo("EXTEND UP 0.100 ask 0.62");
        verify(sim).buy(eq(111L), any(), any());
        verify(sim, never()).buy(eq(112L), any(), any());
    }

    /** v5-1 在盯 UP：起点 58¢、跳幅 18¢ */
    private Watch watchUp() {
        return new Watch(CODE, WS, "UP", new BigDecimal("0.58"), new BigDecimal("0.18"), now - 3000);
    }

    @Test
    void 盯到了_等一秒后卖价贵过3美分_没抢到() {
        // 触发时读到 0.62，等完读到 0.66
        when(cache.getPredictionAsk("UP")).thenReturn(new BigDecimal("0.62"), new BigDecimal("0.66"));

        runner.runWatch(WS, "W60", watchUp(), new BigDecimal("0.68"));

        verify(sim, never()).buy(anyLong(), any(), any());
        JevPredictionDecision w = row(11);
        assertThat(w.getAction()).isEqualTo(JevPredictionDecision.ACTION_STAY_OUT);
        assertThat(w.getReason()).isEqualTo("MISSED UP ask 0.62→0.66");
        // 行上记的是触发时看到的那份盘口
        assertThat(w.getUpAsk()).isEqualByComparingTo("0.62");
    }

    // ==================== 整点唤醒：v5-3 ====================

    @Test
    void v5_3空仓_看领先方_条件都过就买() {
        timerWake();
        when(judge.judge(any(), eq("UP"))).thenReturn(good("UP"));

        runner.runWake(WS, "T150", null, List.of(TIMER));

        verify(sim).recentBets(113L, 10);
        verify(mapper, never()).selectRoundBuy(anyInt(), anyLong());
        JevPredictionDecision d = row(13);
        assertThat(d.getSide()).isEqualTo("UP");
        assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_BUY_UP);
        assertThat(d.getReason()).isEqualTo("BUY UP 0.800 ask 0.90");
        assertThat(d.getAnswersJson()).contains("latest_against");
        assertThat(d.getBetId()).isEqualTo(1113L);
        assertThat(d.getOddsJumpUp()).isNull();
        assertThat(d.getLeadSigma()).isEqualByComparingTo("1.2");
        verify(sim).buy(113L, "UP", new BigDecimal("5"));
    }

    /** v5-3 这一回合在持一注 DOWN（数学上是 UP 领先），前一回合还有一注没结算 */
    private void holdingDown() {
        PredictionBetResponse prev = bet(8, "ACTIVE", "UP", "0.55");
        prev.setWindowStart(WS - 300);
        when(sim.recentBets(113L, 10)).thenReturn(List.of(bet(9, "ACTIVE", "DOWN", "0.40"), prev));
        timerWake();
    }

    @Test
    void v5_3持仓_看手里那一边_没到卖出条件就拿着() {
        holdingDown();
        when(judge.judge(any(), eq("DOWN"))).thenReturn(timerJev("DOWN", 0.4, 0.2));

        runner.runWake(WS, "T180", null, List.of(TIMER));

        verify(judge).judge(any(), eq("DOWN"));
        verify(sim, never()).sell(anyLong(), anyLong(), any());
        verify(sim, never()).buy(anyLong(), any(), any());
        verify(sim, never()).gameBalance(anyLong());
        JevPredictionDecision d = row(13);
        assertThat(d.getSide()).isEqualTo("DOWN");
        assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_HOLD);
        assertThat(d.getReason()).isEqualTo("HOLD DOWN");
        assertThat(d.getBetId()).isEqualTo(9L);
        assertThat(d.getStake()).isEqualByComparingTo("5");
        assertThat(d.getAvgPrice()).isEqualByComparingTo("0.40");
        // 上涨概率 = 1 − DOWN 会赢
        assertThat(d.getPJev()).isEqualByComparingTo("0.6");
    }

    @Test
    void v5_3持仓_会赢低到线就按买一价全卖_买价低了容差以内记实际价() {
        holdingDown();
        when(judge.judge(any(), eq("DOWN"))).thenReturn(timerJev("DOWN", 0.08, 0.2));
        when(cache.getPredictionBid("DOWN")).thenReturn(new BigDecimal("0.08"));

        runner.runWake(WS, "T180", null, List.of(TIMER));

        verify(sim).sell(eq(113L), eq(9L), isNull());
        JevPredictionDecision d = row(13);
        assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_SELL);
        assertThat(d.getReason()).isEqualTo("SELL DOWN 0.080 bid 0.10→0.08");
        assertThat(d.getBetId()).isEqualTo(9L);
    }

    @Test
    void v5_3持仓_等一秒后买价低过3美分_没卖成() {
        holdingDown();
        when(judge.judge(any(), eq("DOWN"))).thenReturn(timerJev("DOWN", 0.08, 0.2));
        when(cache.getPredictionBid("DOWN")).thenReturn(new BigDecimal("0.06"));

        runner.runWake(WS, "T180", null, List.of(TIMER));

        verify(sim, never()).sell(anyLong(), anyLong(), any());
        assertThat(row(13).getAction()).isEqualTo(JevPredictionDecision.ACTION_HOLD);
        assertThat(row(13).getReason()).isEqualTo("MISSED SELL bid 0.10→0.06");
    }

    @Test
    void v5_3持仓_该卖但没人接盘() {
        holdingDown();
        when(writer.write(WS, null)).thenReturn(snapshot(1.2, 0.74, now - 700, null,
                new Book(new BigDecimal("0.90"), new BigDecimal("0.88"), new BigDecimal("0.12"), null)));
        when(judge.judge(any(), eq("DOWN"))).thenReturn(timerJev("DOWN", 0.05, 0.2));

        runner.runWake(WS, "T180", null, List.of(TIMER));

        verify(sim, never()).sell(anyLong(), anyLong(), any());
        verify(cache, never()).getPredictionBookUpdatedAt();
        assertThat(row(13).getAction()).isEqualTo(JevPredictionDecision.ACTION_HOLD);
        assertThat(row(13).getReason()).isEqualTo("NO_BID DOWN");
    }

    @Test
    void v5_3卖掉以后同一回合再买() {
        holdingDown();
        when(judge.judge(any(), eq("DOWN"))).thenReturn(timerJev("DOWN", 0.08, 0.2));
        when(judge.judge(any(), eq("UP"))).thenReturn(good("UP"));

        runner.runWake(WS, "T180", null, List.of(TIMER));
        // 卖掉的那注不再是 ACTIVE：又空仓了，看领先方
        when(sim.recentBets(113L, 10)).thenReturn(List.of(bet(9, "SOLD", "DOWN", "0.40")));
        runner.runWake(WS, "T210", null, List.of(TIMER));

        assertThat(rows).extracting(JevPredictionDecision::getAction)
                .containsExactly(JevPredictionDecision.ACTION_SELL, JevPredictionDecision.ACTION_BUY_UP);
        assertThat(rows.get(1).getCheckpoint()).isEqualTo("T210");
        assertThat(rows.get(1).getBetId()).isEqualTo(1113L);
        verify(sim, times(1)).sell(eq(113L), eq(9L), isNull());
        verify(sim, times(1)).buy(113L, "UP", new BigDecimal("5"));
    }

    // ==================== 钱不够 ====================

    @Test
    void 某一组钱不够又没有等结算的注单_只停这一组() {
        when(writer.write(WS, JUMP_UP)).thenReturn(jumpSnap());
        when(judge.judge(any(), eq("UP"))).thenReturn(jumpJev("UP", 0.82, 0.1));
        when(sim.gameBalance(112L)).thenReturn(BROKE);

        runner.runWake(WS, "J57", JUMP_UP, JUMP_RUNS);

        assertThat(row(12).getReason()).isEqualTo(PredictionRules.NO_BALANCE);
        assertThat(row(11).getReason()).isEqualTo("WATCH UP");
        verify(cache, never()).set(JevPredictionSwitch.KEY, "0");
        // 下一次突变只叫醒 v5-1
        AtomicLong clock = syncRunner(mids(58, 40, 40, 50, 58));
        tickEverySecond(clock, 61, 61);
        verify(mapper).countCheckpoint(11, WS, "J61");
        verify(mapper, never()).countCheckpoint(12, WS, "J61");
    }

    @Test
    void 钱不够但还有注单没结算_不停() {
        when(writer.write(WS, JUMP_UP)).thenReturn(jumpSnap());
        when(judge.judge(any(), eq("UP"))).thenReturn(jumpJev("UP", 0.82, 0.1));
        when(sim.gameBalance(112L)).thenReturn(BROKE);
        PredictionBetResponse prev = bet(8, "ACTIVE", "UP", "0.55");
        prev.setWindowStart(WS - 300);
        when(sim.recentBets(112L, 10)).thenReturn(List.of(prev));

        runner.runWake(WS, "J57", JUMP_UP, JUMP_RUNS);

        assertThat(row(12).getReason()).isEqualTo(PredictionRules.NO_BALANCE);
        AtomicLong clock = syncRunner(mids(58, 40, 40, 50, 58));
        tickEverySecond(clock, 61, 61);
        verify(mapper).countCheckpoint(12, WS, "J61");
    }

    @Test
    void 三组都停了才关开关() {
        when(sim.gameBalance(anyLong())).thenReturn(BROKE);
        when(writer.write(WS, JUMP_UP)).thenReturn(jumpSnap());
        when(judge.judge(any(), eq("UP"))).thenReturn(jumpJev("UP", 0.82, 0.1));
        runner.runWake(WS, "J57", JUMP_UP, JUMP_RUNS);
        assertThat(row(12).getReason()).isEqualTo(PredictionRules.NO_BALANCE);

        timerWake();
        when(judge.judge(any(), eq("UP"))).thenReturn(good("UP"));
        runner.runWake(WS, "T60", null, List.of(TIMER));
        assertThat(row(13).getReason()).isEqualTo(PredictionRules.NO_BALANCE);
        verify(cache, never()).set(JevPredictionSwitch.KEY, "0");

        // v5-1 盯到了也付不起
        runner.runWatch(WS, "W60", watchUp(), new BigDecimal("0.68"));
        assertThat(row(11).getReason()).isEqualTo(PredictionRules.NO_BALANCE);
        verify(cache).set(JevPredictionSwitch.KEY, "0");
    }

    @Test
    void v5_3停了_整点不再唤醒不问Jev_突变照常() {
        timerWake();
        when(judge.judge(any(), eq("UP"))).thenReturn(good("UP"));
        when(sim.gameBalance(113L)).thenReturn(BROKE);

        runner.runWake(WS, "T150", null, List.of(TIMER));

        verify(cache, never()).set(JevPredictionSwitch.KEY, "0");
        AtomicLong clock = syncRunner(mids(176, 40, 40, 40, 40, 50, 58));
        tickEverySecond(clock, 180, 181);
        verify(mapper, never()).countCheckpoint(13, WS, "T180");
        verify(mapper).countCheckpoint(11, WS, "J181");
        verify(mapper).countCheckpoint(12, WS, "J181");
        verify(judge, times(1)).judge(any(), any());
    }

    // ==================== 不问、失败、查重 ====================

    @Test
    void 盘口太旧_不问Jev不动_买过的那组记HOLD() {
        when(writer.write(WS, JUMP_UP)).thenReturn(snapshot(0.1, 0.52, now - 9_000, JUMP_UP, BOOK));
        when(mapper.selectRoundBuy(11, WS)).thenReturn(buyRow(JevPredictionDecision.ACTION_BUY_UP, 7));

        runner.runWake(WS, "J57", JUMP_UP, JUMP_RUNS);

        verify(judge, never()).judge(any(), any());
        assertThat(row(11).getAction()).isEqualTo(JevPredictionDecision.ACTION_HOLD);
        assertThat(row(11).getBetId()).isEqualTo(7L);
        assertThat(row(12).getAction()).isEqualTo(JevPredictionDecision.ACTION_STAY_OUT);
        for (int runNo : List.of(11, 12)) {
            JevPredictionDecision d = row(runNo);
            assertThat(d.getReason()).isEqualTo("STALE_BOOK 9000");
            assertThat(d.getBookAgeMs()).isEqualTo(9000);
            assertThat(d.getPModel()).isEqualByComparingTo("0.52");
            assertThat(d.getSide()).isNull();
        }
    }

    @Test
    void Chainlink停了_不问Jev_照常跳过不算出错() {
        when(writer.write(WS, JUMP_UP)).thenReturn(new Snapshot(Map.of("market", "..."),
                new Raw(0.1, 0.52, BOOK, now - 700, 8_355, JUMP_UP, null, null, null)));

        runner.runWake(WS, "J57", JUMP_UP, JUMP_RUNS);

        verify(judge, never()).judge(any(), any());
        for (int runNo : List.of(11, 12)) {
            JevPredictionDecision d = row(runNo);
            assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_STAY_OUT);
            assertThat(d.getReason()).isEqualTo("STALE_CHAINLINK 8355");
            assertThat(d.getError()).isNull();
            // 突变的大小照记
            assertThat(d.getOddsJumpUp()).isEqualByComparingTo("0.18");
        }
    }

    @Test
    void 等成交时盘口停了_不成交() {
        when(writer.write(WS, JUMP_UP)).thenReturn(jumpSnap());
        when(judge.judge(any(), eq("UP"))).thenReturn(jumpJev("UP", 0.82, 0.1));
        when(cache.getPredictionBookUpdatedAt()).thenReturn(now - 8_000);

        runner.runWake(WS, "J57", JUMP_UP, JUMP_RUNS);

        verify(sim, never()).buy(anyLong(), any(), any());
        assertThat(row(12).getAction()).isEqualTo(JevPredictionDecision.ACTION_STAY_OUT);
        assertThat(row(12).getReason()).isEqualTo("STALE_WHILE_ASKING");
        // 行上记的是决策那一刻的盘口年龄
        assertThat(row(12).getBookAgeMs()).isEqualTo(700);
    }

    @Test
    void 写state失败_两组都落ERROR行() {
        when(writer.write(WS, JUMP_UP)).thenThrow(new IllegalStateException("开盘价未到"));

        runner.runWake(WS, "J57", JUMP_UP, JUMP_RUNS);

        assertThat(rows).hasSize(2).allSatisfy(d -> {
            assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_ERROR);
            assertThat(d.getError()).contains("开盘价未到");
            assertThat(d.getStateJson()).isEqualTo("{}");
            assertThat(d.getPModel()).isNull();
        });
        verify(judge, never()).judge(any(), any());
        verify(sim, never()).buy(anyLong(), any(), any());
    }

    @Test
    void 下单失败_只落这一组的ERROR行() {
        when(writer.write(WS, JUMP_UP)).thenReturn(jumpSnap());
        when(judge.judge(any(), eq("UP"))).thenReturn(jumpJev("UP", 0.82, 0.1));
        when(sim.buy(eq(112L), any(), any())).thenThrow(new IllegalStateException("回合已锁"));

        runner.runWake(WS, "J270", JUMP_UP, JUMP_RUNS);

        assertThat(row(12).getAction()).isEqualTo(JevPredictionDecision.ACTION_ERROR);
        assertThat(row(12).getError()).contains("回合已锁");
        assertThat(row(12).getPJev()).isEqualByComparingTo("0.7");
        assertThat(row(11).getReason()).isEqualTo("WATCH UP");
        assertThat(row(11).getError()).isNull();
    }

    @Test
    void 这一局这一格写过就不重跑_另一组照跑() {
        when(writer.write(WS, JUMP_UP)).thenReturn(jumpSnap());
        when(judge.judge(any(), eq("UP"))).thenReturn(jumpJev("UP", 0.82, 0.1));
        when(mapper.countCheckpoint(11, WS, "J57")).thenReturn(1);

        runner.runWake(WS, "J57", JUMP_UP, JUMP_RUNS);

        assertThat(rows).extracting(JevPredictionDecision::getRunNo).containsExactly(12);
        verify(writer, times(1)).write(anyLong(), any());
        verify(sim).buy(eq(112L), any(), any());
    }

    // ==================== 回填 ====================

    @Test
    void 回填结果与盈亏_按行所属那一局的账户查_开关关着照样回填() {
        runner.nowMs = () -> (WS + 600) * 1000;
        when(cache.get(JevPredictionSwitch.KEY)).thenReturn("0");
        when(account.userId(1)).thenReturn(41L);
        JevPredictionDecision buyRow = new JevPredictionDecision();
        buyRow.setId(1L);
        buyRow.setRunNo(1);
        buyRow.setWindowStart(WS);
        buyRow.setAction(JevPredictionDecision.ACTION_BUY_UP);
        buyRow.setBetId(9L);
        JevPredictionDecision holdRow = new JevPredictionDecision();
        holdRow.setId(2L);
        holdRow.setWindowStart(WS);
        holdRow.setAction(JevPredictionDecision.ACTION_HOLD);
        holdRow.setBetId(9L);
        when(mapper.selectPendingSettle(anyLong(), anyLong())).thenReturn(List.of(buyRow, holdRow));
        PredictionRoundResponse round = new PredictionRoundResponse();
        round.setStatus("SETTLED");
        round.setOutcome("UP");
        when(sim.round(WS)).thenReturn(round);
        PredictionBetResponse won = new PredictionBetResponse();
        won.setId(9L);
        won.setStatus("WON");
        won.setContracts(new BigDecimal("20"));
        won.setAvgPrice(new BigDecimal("0.50"));
        won.setCost(new BigDecimal("10"));
        won.setPayout(new BigDecimal("20"));
        when(sim.recentBets(eq(41L), anyInt())).thenReturn(List.of(won));

        runner.settleSweep();

        assertThat(buyRow.getOutcome()).isEqualTo("UP");
        assertThat(buyRow.getPnl()).isEqualByComparingTo("9.65");
        assertThat(holdRow.getOutcome()).isEqualTo("UP");
        assertThat(holdRow.getPnl()).isNull();
        verify(mapper).updateById(buyRow);
        verify(mapper).updateById(holdRow);
    }

    @Test
    void 回合没结算就跳过() {
        runner.nowMs = () -> (WS + 600) * 1000;
        JevPredictionDecision row = new JevPredictionDecision();
        row.setId(3L);
        row.setWindowStart(WS);
        row.setAction(JevPredictionDecision.ACTION_STAY_OUT);
        when(mapper.selectPendingSettle(anyLong(), anyLong())).thenReturn(List.of(row));
        PredictionRoundResponse locked = new PredictionRoundResponse();
        locked.setStatus("LOCKED");
        when(sim.round(WS)).thenReturn(locked);

        runner.settleSweep();

        assertThat(row.getOutcome()).isNull();
        verify(mapper, never()).updateById(any(JevPredictionDecision.class));
    }

    private static JevPredictionDecision decidedAt(long id, long ws, long decidedAtMs) {
        JevPredictionDecision d = new JevPredictionDecision();
        d.setId(id);
        d.setWindowStart(ws);
        d.setDecidedAt(decidedAtMs);
        d.setAction(JevPredictionDecision.ACTION_STAY_OUT);
        return d;
    }

    @Test
    void 补唤醒后5到45秒的价_越过收盘前1秒的留空_都算不出的不写() {
        long sweepAt = (WS + 400) * 1000;
        runner.nowMs = () -> sweepAt;
        // 本回合开盘前 10 秒到收盘，每秒一个 UP 中间价，价 = 0.5 + 开盘后秒数 / 1000；下一回合还没有采样
        List<Point> upMids = new ArrayList<>();
        for (long s = -10; s < 300; s++) {
            upMids.add(new Point((WS + s) * 1000, BigDecimal.valueOf(500 + s, 3)));
        }
        when(cache.getPredictionUpMidPoints(sweepAt - 360_000)).thenReturn(upMids);
        when(mapper.selectPendingAfterPrice(sweepAt - 300_000, sweepAt - 46_000)).thenReturn(List.of(
                decidedAt(1, WS, (WS + 57) * 1000 + 300),
                decidedAt(2, WS, (WS + 270) * 1000 + 100),
                decidedAt(3, WS, (WS + 290) * 1000),
                decidedAt(4, WS + 300, (WS + 330) * 1000)));

        runner.settleSweep();

        ArgumentCaptor<JevPredictionDecision> updates = ArgumentCaptor.forClass(JevPredictionDecision.class);
        verify(mapper, times(3)).updateById(updates.capture());
        JevPredictionDecision j57 = updates.getAllValues().get(0);
        assertThat(j57.getId()).isEqualTo(1L);
        assertThat(j57.getUpMid5s()).isEqualByComparingTo("0.562");
        assertThat(j57.getUpMid10s()).isEqualByComparingTo("0.567");
        assertThat(j57.getUpMid15s()).isEqualByComparingTo("0.572");
        assertThat(j57.getUpMid45s()).isEqualByComparingTo("0.602");
        // 只写这几列
        assertThat(j57.getAction()).isNull();
        assertThat(j57.getOutcome()).isNull();
        // 270 秒 + 45 秒过了收盘，留空
        JevPredictionDecision t270 = updates.getAllValues().get(1);
        assertThat(t270.getId()).isEqualTo(2L);
        assertThat(t270.getUpMid5s()).isEqualByComparingTo("0.775");
        assertThat(t270.getUpMid10s()).isEqualByComparingTo("0.780");
        assertThat(t270.getUpMid15s()).isEqualByComparingTo("0.785");
        assertThat(t270.getUpMid45s()).isNull();
        // 290 秒只有 5 秒那一刻还在收盘前
        JevPredictionDecision at290 = updates.getAllValues().get(2);
        assertThat(at290.getId()).isEqualTo(3L);
        assertThat(at290.getUpMid5s()).isEqualByComparingTo("0.795");
        assertThat(at290.getUpMid10s()).isNull();
        assertThat(at290.getUpMid15s()).isNull();
    }
}
