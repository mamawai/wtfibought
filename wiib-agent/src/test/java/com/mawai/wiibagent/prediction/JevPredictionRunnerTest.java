package com.mawai.wiibagent.prediction;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.dto.PredictionBetResponse;
import com.mawai.wiibcommon.dto.PredictionRoundResponse;
import com.mawai.wiibcommon.entity.JevPredictionDecision;
import com.mawai.wiibcommon.entity.JevPredictionRun;
import com.mawai.wiibagent.llm.jev.JevClient.Answer;
import com.mawai.wiibagent.llm.jev.JevPlatformConfig;
import com.mawai.wiibagent.mapper.JevPredictionDecisionMapper;
import com.mawai.wiibagent.prediction.PredictionJudge.Judgment;
import com.mawai.wiibagent.prediction.PredictionRules.Book;
import com.mawai.wiibagent.prediction.PredictionStateWriter.Raw;
import com.mawai.wiibagent.prediction.PredictionStateWriter.Snapshot;
import com.mawai.wiibquant.external.sim.SimPredictionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 检查点时机、开关、空仓买入、没钱关开关（有注单等结算不关）、持仓按公平价卖出、盘口太旧不问、失败落 ERROR 行、按局回填 */
class JevPredictionRunnerTest {

    /** 对齐 5 分钟边界的窗口起点 */
    private static final long WS = 1_790_016_000L;
    /** 和 application.yml 的起手值一致，几个测试共用 */
    static final JevPredictionConfig CFG = new JevPredictionConfig(
            List.of(30, 45, 60, 75, 90, 105, 120, 135, 150, 165, 180, 195, 210, 225, 240, 255, 270),
            new BigDecimal("5"), 0.05, 0.10, 0.06, new BigDecimal("0.03"), 5000);
    private static final Book BOOK = PredictionRulesTest.BOOK;

    private PredictionStateWriter writer;
    private PredictionJudge judge;
    private JevPredictionAccount account;
    private JevPredictionRuns runs;
    private SimPredictionClient sim;
    private CacheService cache;
    private JevPredictionDecisionMapper mapper;
    private JevPredictionRunner runner;
    private final long now = (WS + 150) * 1000;

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
        when(mapper.countCheckpoint(anyLong(), any())).thenReturn(0);
        when(runs.current()).thenReturn(new JevPredictionRun(2, null, 0L));
        when(account.userId(2)).thenReturn(42L);
        when(sim.gameBalance(42L)).thenReturn(new BigDecimal("100"));
        when(cache.getPredictionAsk("UP")).thenReturn(BOOK.upAsk());
        when(cache.getPredictionBid("UP")).thenReturn(BOOK.upBid());
        when(cache.getPredictionAsk("DOWN")).thenReturn(BOOK.downAsk());
        when(cache.getPredictionBid("DOWN")).thenReturn(BOOK.downBid());
        when(cache.getPredictionBookUpdatedAt()).thenReturn(now - 700);
    }

    private Snapshot snapshot(Long bookUpdatedAt) {
        return new Snapshot(Map.of("market", "..."), new Raw(1.2, 0.74, BOOK, bookUpdatedAt));
    }

    /** Jev 说 UP 有 pJev 的胜率 */
    private static Judgment jev(double pJev) {
        Answer up = new Answer("noul", pJev, null, null, null, null);
        return new Judgment(0.74, pJev, Map.of(PredictionQuestions.UP_WINS, up), "jev-1.13.0", 820, 140);
    }

    private static PredictionBetResponse bet(long id, String status, String side) {
        PredictionBetResponse b = new PredictionBetResponse();
        b.setId(id);
        b.setWindowStart(WS);
        b.setStatus(status);
        b.setSide(side);
        b.setCost(new BigDecimal("5"));
        b.setContracts(new BigDecimal("10"));
        b.setAvgPrice(new BigDecimal("0.50"));
        return b;
    }

    private JevPredictionDecision inserted() {
        ArgumentCaptor<JevPredictionDecision> captor = ArgumentCaptor.forClass(JevPredictionDecision.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    void 检查点时机() {
        assertThat(JevPredictionRunner.checkpointFor(29, CFG)).isNull();
        assertThat(JevPredictionRunner.checkpointFor(30, CFG)).isEqualTo("T30");
        assertThat(JevPredictionRunner.checkpointFor(44, CFG)).isEqualTo("T30");
        assertThat(JevPredictionRunner.checkpointFor(150, CFG)).isEqualTo("T150");
        assertThat(JevPredictionRunner.checkpointFor(284, CFG)).isEqualTo("T270");
        assertThat(JevPredictionRunner.checkpointFor(285, CFG)).isNull();
        assertThat(JevPredictionRunner.windowStart((WS + 299) * 1000)).isEqualTo(WS);
    }

    @Test
    void 开关没开过_到了检查点也不跑() {
        runner.tick();

        verify(mapper, after(300).never()).countCheckpoint(anyLong(), any());
    }

    @Test
    void 开关打开_到了检查点就跑() {
        when(cache.get(JevPredictionSwitch.KEY)).thenReturn("1");
        when(mapper.countCheckpoint(WS, "T150")).thenReturn(1);

        runner.tick();

        verify(mapper, timeout(1000)).countCheckpoint(WS, "T150");
    }

    @Test
    void 空仓_Jev的胜率够高就买并落库() {
        when(writer.write(WS)).thenReturn(snapshot(now - 700));
        when(judge.judge(any())).thenReturn(jev(0.8));
        PredictionBetResponse placed = bet(9, "ACTIVE", "UP");
        placed.setCost(new BigDecimal("10"));
        when(sim.buy(eq(42L), eq("UP"), any())).thenReturn(placed);

        runner.runCheckpoint(WS, "T150");

        JevPredictionDecision d = inserted();
        assertThat(d.getRunNo()).isEqualTo(2);
        assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_BUY_UP);
        assertThat(d.getPModel()).isEqualByComparingTo("0.74");
        assertThat(d.getPJev()).isEqualByComparingTo("0.8");
        assertThat(d.getPMkt()).isEqualByComparingTo("0.61");
        assertThat(d.getBookAgeMs()).isEqualTo(700);
        assertThat(d.getAnswersJson()).contains("up_wins");
        assertThat(d.getBetId()).isEqualTo(9L);
        assertThat(d.getStake()).isEqualByComparingTo("10");
        assertThat(d.getReason()).startsWith("BUY UP 0.164");
        assertThat(d.getError()).isNull();
        // 0.8 − 0.62 − 0.0165 = 0.1635 到 0.10，下两倍
        verify(sim).buy(eq(42L), eq("UP"), eq(new BigDecimal("10")));
    }

    @Test
    void 钱包付不起一注_不下单并关掉开关() {
        when(writer.write(WS)).thenReturn(snapshot(now - 700));
        when(judge.judge(any())).thenReturn(jev(0.8));
        when(sim.gameBalance(42L)).thenReturn(new BigDecimal("0.5"));

        runner.runCheckpoint(WS, "T150");

        verify(sim, never()).buy(anyLong(), any(), any());
        verify(cache).set(JevPredictionSwitch.KEY, "0");
        assertThat(inserted().getReason()).isEqualTo(PredictionRules.NO_BALANCE);
    }

    @Test
    void 钱包不够但上一回合的注单还没结算_不关开关() {
        when(writer.write(WS)).thenReturn(snapshot(now - 700));
        when(judge.judge(any())).thenReturn(jev(0.8));
        when(sim.gameBalance(42L)).thenReturn(new BigDecimal("0.5"));
        PredictionBetResponse prev = bet(8, "ACTIVE", "UP");
        prev.setWindowStart(WS - 300);
        when(sim.recentBets(42L, 10)).thenReturn(List.of(prev));

        runner.runCheckpoint(WS, "T30");

        verify(sim, never()).buy(anyLong(), any(), any());
        verify(cache, never()).set(JevPredictionSwitch.KEY, "0");
        assertThat(inserted().getReason()).isEqualTo(PredictionRules.NO_BALANCE);
    }

    @Test
    void 持仓_照样问Jev记分_市场给多了就卖() {
        PredictionBetResponse active = bet(9, "ACTIVE", "UP");
        when(sim.recentBets(42L, 10)).thenReturn(List.of(active));
        // 公平 0.45，买一 0.60 扣费 0.0168 = 0.583，高出 0.133 ≥ 0.06
        when(writer.write(WS)).thenReturn(new Snapshot(Map.of("market", "..."), new Raw(-0.1, 0.45, BOOK, now - 700)));
        when(judge.judge(any())).thenReturn(new Judgment(0.45, 0.4, Map.of(), "jev-1.13.0", 700, 140));

        runner.runCheckpoint(WS, "T210");

        verify(sim).sell(42L, 9L, null);
        verify(sim, never()).buy(anyLong(), any(), any());
        JevPredictionDecision d = inserted();
        assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_SELL);
        assertThat(d.getBetId()).isEqualTo(9L);
        assertThat(d.getReason()).isEqualTo("SELL over 0.133 bid 0.60");
        assertThat(d.getPJev()).isEqualByComparingTo("0.4");
    }

    @Test
    void 盘口太旧_不问Jev不动() {
        when(writer.write(WS)).thenReturn(snapshot(now - 9_000));

        runner.runCheckpoint(WS, "T150");

        verify(judge, never()).judge(any());
        JevPredictionDecision d = inserted();
        assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_STAY_OUT);
        assertThat(d.getReason()).isEqualTo("STALE_BOOK 9000");
        assertThat(d.getBookAgeMs()).isEqualTo(9000);
        assertThat(d.getPModel()).isEqualByComparingTo("0.74");
    }

    @Test
    void 问的时候盘口停了_不下单() {
        when(writer.write(WS)).thenReturn(snapshot(now - 700));
        when(judge.judge(any())).thenReturn(jev(0.8));
        when(cache.getPredictionBookUpdatedAt()).thenReturn(now - 8_000);

        runner.runCheckpoint(WS, "T150");

        verify(sim, never()).buy(anyLong(), any(), any());
        JevPredictionDecision d = inserted();
        assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_STAY_OUT);
        assertThat(d.getReason()).isEqualTo("STALE_WHILE_ASKING");
        assertThat(d.getPJev()).isEqualByComparingTo("0.8");
    }

    @Test
    void 写state失败落ERROR行() {
        when(writer.write(WS)).thenThrow(new IllegalStateException("开盘价未到"));

        runner.runCheckpoint(WS, "T150");

        JevPredictionDecision d = inserted();
        assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_ERROR);
        assertThat(d.getError()).contains("开盘价未到");
        assertThat(d.getStateJson()).isEqualTo("{}");
        assertThat(d.getPModel()).isNull();
        verify(sim, never()).buy(anyLong(), any(), any());
    }

    @Test
    void 问Jev失败_ERROR行带数学部分() {
        when(writer.write(WS)).thenReturn(snapshot(now - 700));
        when(judge.judge(any())).thenThrow(new IllegalStateException("Jev 回包缺题 up_wins"));

        runner.runCheckpoint(WS, "T150");

        JevPredictionDecision d = inserted();
        assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_ERROR);
        assertThat(d.getError()).contains("缺题");
        assertThat(d.getPModel()).isEqualByComparingTo("0.74");
        assertThat(d.getPMkt()).isEqualByComparingTo("0.61");
        assertThat(d.getPJev()).isNull();
        assertThat(d.getStateJson()).contains("market");
        verify(sim, never()).buy(anyLong(), any(), any());
    }

    @Test
    void 库里已有这一行就不重跑() {
        when(mapper.countCheckpoint(WS, "T150")).thenReturn(1);

        runner.runCheckpoint(WS, "T150");

        verify(writer, never()).write(anyLong());
        verify(mapper, never()).insert(any(JevPredictionDecision.class));
    }

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
}
