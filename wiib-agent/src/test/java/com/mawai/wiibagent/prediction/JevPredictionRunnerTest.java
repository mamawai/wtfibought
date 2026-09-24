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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检查点时机、开关、Jev 选买等一会儿再成交（差过容差或没价算没抢到，容差以内记实际价）、没钱关开关（有注单等结算不关）、
 * 持仓 Jev 选卖就卖、持仓没人接盘 / 盘口太旧 / Chainlink 停了不问、等成交时盘口停了不动、失败落 ERROR 行、按局回填
 */
class JevPredictionRunnerTest {

    /** 对齐 5 分钟边界的窗口起点 */
    private static final long WS = 1_790_016_000L;
    /** 和 application.yml 一致，只是成交不等，几个测试共用 */
    static final JevPredictionConfig CFG = new JevPredictionConfig(
            List.of(30, 45, 60, 75, 90, 105, 120, 135, 150, 165, 180, 195, 210, 225, 240, 255, 270),
            new BigDecimal("5"), 0.5, 0, new BigDecimal("0.03"), 5000);
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

    /** 数学上涨概率 0.74，Chainlink 0.8 秒前刚更新 */
    private Snapshot snapshot(Long bookUpdatedAt) {
        return new Snapshot(Map.of("market", "..."), new Raw(1.2, 0.74, BOOK, bookUpdatedAt, 800));
    }

    /** Jev 以 p 的把握选 choice（只放选中那一项的概率），上涨概率记分 0.8、数学 pModel */
    private static Judgment jev(double pModel, String choice, double p) {
        Answer decision = new Answer("choice", null, choice, null, null, Map.of(choice, p));
        Answer up = new Answer("noul", 0.8, null, null, null, null);
        return new Judgment(pModel, 0.8, decision, Map.of(PredictionQuestions.UP_WINS, up), "jev-1.13.0", 820, 140);
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
    void 空仓_Jev选买_价没变差就买并落库() {
        when(writer.write(WS, null)).thenReturn(snapshot(now - 700));
        when(judge.judge(any(), eq(false))).thenReturn(jev(0.74, "BUY_UP", 0.7));
        PredictionBetResponse placed = bet(9, "ACTIVE", "UP");
        placed.setAvgPrice(new BigDecimal("0.62"));
        when(sim.buy(eq(42L), eq("UP"), any())).thenReturn(placed);

        runner.runCheckpoint(WS, "T150");

        JevPredictionDecision d = inserted();
        assertThat(d.getRunNo()).isEqualTo(2);
        assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_BUY_UP);
        assertThat(d.getJevChoice()).isEqualTo("BUY_UP");
        assertThat(d.getJevChoiceP()).isEqualByComparingTo("0.7");
        assertThat(d.getPModel()).isEqualByComparingTo("0.74");
        assertThat(d.getPJev()).isEqualByComparingTo("0.8");
        assertThat(d.getPMkt()).isEqualByComparingTo("0.61");
        assertThat(d.getBookAgeMs()).isEqualTo(700);
        assertThat(d.getAnswersJson()).contains("up_wins");
        // 数学估计 0.74 − 0.62 − 0.0165
        assertThat(d.getEdge()).isEqualByComparingTo("0.1035");
        assertThat(d.getBetId()).isEqualTo(9L);
        assertThat(d.getStake()).isEqualByComparingTo("5");
        assertThat(d.getReason()).isEqualTo("BUY UP 0.700 ask 0.62");
        assertThat(d.getError()).isNull();
        verify(sim).buy(eq(42L), eq("UP"), eq(new BigDecimal("5")));
    }

    @Test
    void 空仓_等成交时卖价贵了容差以内_照样买_reason记实际价() {
        when(writer.write(WS, null)).thenReturn(snapshot(now - 700));
        when(judge.judge(any(), eq(false))).thenReturn(jev(0.74, "BUY_UP", 0.7));
        when(cache.getPredictionAsk("UP")).thenReturn(new BigDecimal("0.65"));
        PredictionBetResponse placed = bet(9, "ACTIVE", "UP");
        placed.setAvgPrice(new BigDecimal("0.6500"));
        when(sim.buy(eq(42L), eq("UP"), any())).thenReturn(placed);

        runner.runCheckpoint(WS, "T150");

        JevPredictionDecision d = inserted();
        assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_BUY_UP);
        assertThat(d.getReason()).isEqualTo("BUY UP 0.700 ask 0.62→0.65");
        assertThat(d.getAvgPrice()).isEqualByComparingTo("0.65");
    }

    @Test
    void 空仓_等成交时卖价贵过容差或没人卖了_没抢到不买() {
        when(writer.write(WS, null)).thenReturn(snapshot(now - 700));
        when(judge.judge(any(), eq(false))).thenReturn(jev(0.74, "BUY_UP", 0.7));
        when(cache.getPredictionAsk("UP")).thenReturn(new BigDecimal("0.66"));

        runner.runCheckpoint(WS, "T150");

        verify(sim, never()).buy(anyLong(), any(), any());
        JevPredictionDecision d = inserted();
        assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_STAY_OUT);
        assertThat(d.getReason()).isEqualTo("MISSED UP ask 0.62→0.66");
        // 行上记的是 Jev 看到的那份盘口
        assertThat(d.getUpAsk()).isEqualByComparingTo("0.62");
    }

    @Test
    void 空仓_等成交时那边没人卖了_没抢到不买() {
        when(writer.write(WS, null)).thenReturn(snapshot(now - 700));
        when(judge.judge(any(), eq(false))).thenReturn(jev(0.74, "BUY_UP", 0.7));
        when(cache.getPredictionAsk("UP")).thenReturn(null);

        runner.runCheckpoint(WS, "T150");

        verify(sim, never()).buy(anyLong(), any(), any());
        assertThat(inserted().getReason()).isEqualTo("MISSED UP ask 0.62→none");
    }

    @Test
    void 空仓_Jev选不买_不动() {
        when(writer.write(WS, null)).thenReturn(snapshot(now - 700));
        when(judge.judge(any(), eq(false))).thenReturn(jev(0.74, "PASS", 0.6));

        runner.runCheckpoint(WS, "T150");

        verify(sim, never()).buy(anyLong(), any(), any());
        JevPredictionDecision d = inserted();
        assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_STAY_OUT);
        assertThat(d.getReason()).isEqualTo("PASS 0.600");
        assertThat(d.getJevChoice()).isEqualTo("PASS");
        assertThat(d.getEdge()).isNull();
    }

    @Test
    void 钱包付不起一注_不下单并关掉开关() {
        when(writer.write(WS, null)).thenReturn(snapshot(now - 700));
        when(judge.judge(any(), eq(false))).thenReturn(jev(0.74, "BUY_UP", 0.7));
        when(sim.gameBalance(42L)).thenReturn(new BigDecimal("0.5"));

        runner.runCheckpoint(WS, "T150");

        verify(sim, never()).buy(anyLong(), any(), any());
        verify(cache).set(JevPredictionSwitch.KEY, "0");
        assertThat(inserted().getReason()).isEqualTo(PredictionRules.NO_BALANCE);
    }

    @Test
    void 钱包不够但上一回合的注单还没结算_不关开关() {
        when(writer.write(WS, null)).thenReturn(snapshot(now - 700));
        when(judge.judge(any(), eq(false))).thenReturn(jev(0.74, "BUY_UP", 0.7));
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
    void 持仓_问离场题_Jev选卖_价没变差就卖() {
        PredictionBetResponse active = bet(9, "ACTIVE", "UP");
        when(sim.recentBets(42L, 10)).thenReturn(List.of(active));
        when(writer.write(WS, active)).thenReturn(new Snapshot(Map.of("market", "..."), new Raw(-0.1, 0.45, BOOK, now - 700, 800)));
        when(judge.judge(any(), eq(true))).thenReturn(jev(0.45, "SELL", 0.7));

        runner.runCheckpoint(WS, "T210");

        verify(sim).sell(42L, 9L, null);
        verify(sim, never()).buy(anyLong(), any(), any());
        JevPredictionDecision d = inserted();
        assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_SELL);
        assertThat(d.getBetId()).isEqualTo(9L);
        assertThat(d.getReason()).isEqualTo("SELL 0.700 bid 0.60");
        assertThat(d.getJevChoice()).isEqualTo("SELL");
        // 卖出扣费 0.60 − 0.0168 比数学 0.45 多拿 0.1332
        assertThat(d.getEdge()).isEqualByComparingTo("0.1332");
    }

    @Test
    void 持仓_Jev选拿着_不卖() {
        PredictionBetResponse active = bet(9, "ACTIVE", "UP");
        when(sim.recentBets(42L, 10)).thenReturn(List.of(active));
        when(writer.write(WS, active)).thenReturn(snapshot(now - 700));
        when(judge.judge(any(), eq(true))).thenReturn(jev(0.74, "HOLD", 0.8));

        runner.runCheckpoint(WS, "T210");

        verify(sim, never()).sell(anyLong(), anyLong(), any());
        JevPredictionDecision d = inserted();
        assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_HOLD);
        assertThat(d.getReason()).isEqualTo("HOLD 0.800");
    }

    @Test
    void 持仓_等成交时买价跌了容差以内_照样卖_reason记实际价() {
        PredictionBetResponse active = bet(9, "ACTIVE", "UP");
        when(sim.recentBets(42L, 10)).thenReturn(List.of(active));
        when(writer.write(WS, active)).thenReturn(snapshot(now - 700));
        when(judge.judge(any(), eq(true))).thenReturn(jev(0.45, "SELL", 0.7));
        when(cache.getPredictionBid("UP")).thenReturn(new BigDecimal("0.58"));

        runner.runCheckpoint(WS, "T210");

        verify(sim).sell(42L, 9L, null);
        JevPredictionDecision d = inserted();
        assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_SELL);
        assertThat(d.getReason()).isEqualTo("SELL 0.700 bid 0.60→0.58");
    }

    @Test
    void 持仓_等成交时买价跌过容差_没卖成() {
        PredictionBetResponse active = bet(9, "ACTIVE", "UP");
        when(sim.recentBets(42L, 10)).thenReturn(List.of(active));
        when(writer.write(WS, active)).thenReturn(snapshot(now - 700));
        when(judge.judge(any(), eq(true))).thenReturn(jev(0.45, "SELL", 0.7));
        when(cache.getPredictionBid("UP")).thenReturn(new BigDecimal("0.56"));

        runner.runCheckpoint(WS, "T210");

        verify(sim, never()).sell(anyLong(), anyLong(), any());
        JevPredictionDecision d = inserted();
        assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_HOLD);
        assertThat(d.getReason()).isEqualTo("MISSED SELL bid 0.60→0.56");
    }

    @Test
    void 持仓_没人接盘_不问Jev只能拿着() {
        PredictionBetResponse active = bet(9, "ACTIVE", "UP");
        when(sim.recentBets(42L, 10)).thenReturn(List.of(active));
        Book noUpBid = new Book(new BigDecimal("0.62"), null, new BigDecimal("0.40"), new BigDecimal("0.38"));
        when(writer.write(WS, active)).thenReturn(new Snapshot(Map.of("market", "..."), new Raw(1.2, 0.74, noUpBid, now - 700, 800)));

        runner.runCheckpoint(WS, "T255");

        verify(judge, never()).judge(any(), anyBoolean());
        JevPredictionDecision d = inserted();
        assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_HOLD);
        assertThat(d.getReason()).isEqualTo("NO_BID");
    }

    @Test
    void 盘口太旧_不问Jev不动() {
        when(writer.write(WS, null)).thenReturn(snapshot(now - 9_000));

        runner.runCheckpoint(WS, "T150");

        verify(judge, never()).judge(any(), anyBoolean());
        JevPredictionDecision d = inserted();
        assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_STAY_OUT);
        assertThat(d.getReason()).isEqualTo("STALE_BOOK 9000");
        assertThat(d.getBookAgeMs()).isEqualTo(9000);
        assertThat(d.getPModel()).isEqualByComparingTo("0.74");
    }

    @Test
    void Chainlink停了_不问Jev_照常跳过不算出错() {
        when(writer.write(WS, null)).thenReturn(new Snapshot(Map.of("market", "..."), new Raw(1.2, 0.74, BOOK, now - 700, 8_355)));

        runner.runCheckpoint(WS, "T90");

        verify(judge, never()).judge(any(), anyBoolean());
        JevPredictionDecision d = inserted();
        assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_STAY_OUT);
        assertThat(d.getReason()).isEqualTo("STALE_CHAINLINK 8355");
        assertThat(d.getError()).isNull();
    }

    @Test
    void 等成交时盘口停了_不下单() {
        when(writer.write(WS, null)).thenReturn(snapshot(now - 700));
        when(judge.judge(any(), eq(false))).thenReturn(jev(0.74, "BUY_UP", 0.7));
        when(cache.getPredictionBookUpdatedAt()).thenReturn(now - 8_000);

        runner.runCheckpoint(WS, "T150");

        verify(sim, never()).buy(anyLong(), any(), any());
        JevPredictionDecision d = inserted();
        assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_STAY_OUT);
        assertThat(d.getReason()).isEqualTo("STALE_WHILE_ASKING");
        assertThat(d.getJevChoice()).isEqualTo("BUY_UP");
        // 行上记的是决策那一刻的盘口年龄
        assertThat(d.getBookAgeMs()).isEqualTo(700);
    }

    @Test
    void 写state失败落ERROR行() {
        when(writer.write(WS, null)).thenThrow(new IllegalStateException("开盘价未到"));

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
        when(writer.write(WS, null)).thenReturn(snapshot(now - 700));
        when(judge.judge(any(), eq(false))).thenThrow(new IllegalStateException("Jev 回包缺题 up_wins"));

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
    void 下单失败_落ERROR行带Jev的选择() {
        when(writer.write(WS, null)).thenReturn(snapshot(now - 700));
        when(judge.judge(any(), eq(false))).thenReturn(jev(0.74, "BUY_UP", 0.7));
        when(sim.buy(eq(42L), eq("UP"), any())).thenThrow(new IllegalStateException("回合已锁"));

        runner.runCheckpoint(WS, "T270");

        JevPredictionDecision d = inserted();
        assertThat(d.getAction()).isEqualTo(JevPredictionDecision.ACTION_ERROR);
        assertThat(d.getError()).contains("回合已锁");
        assertThat(d.getJevChoice()).isEqualTo("BUY_UP");
    }

    @Test
    void 库里已有这一行就不重跑() {
        when(mapper.countCheckpoint(WS, "T150")).thenReturn(1);

        runner.runCheckpoint(WS, "T150");

        verify(writer, never()).write(anyLong(), any());
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
