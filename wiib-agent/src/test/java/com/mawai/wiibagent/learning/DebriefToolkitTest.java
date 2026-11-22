package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.trader.wakeup.WakeTrace;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 单笔复盘的两个查历史工具：轮次详情先给总览（回执只带开头）、按调用序号再给全文，别人的轮和复盘行不给；
 * 历史单子不给编号是清单、给编号是那一笔的事实表和单笔复盘。
 */
class DebriefToolkitTest {

    private static final long T0 = 1785110400000L;
    private static final long H = 3_600_000L;

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderDecision.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderPlan.class);
    }

    private final AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);
    private final AiTraderPlanMapper planMapper = mock(AiTraderPlanMapper.class);
    private final SimTradeClient simTradeClient = mock(SimTradeClient.class);
    private final DebriefToolkit toolkit = new DebriefToolkit(decisionMapper, planMapper, simTradeClient,
            new CaseFileBuilder(mock(KlineHistoryStore.class)), new CaseFileRenderer(new PromptCatalog()));

    private static AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setRoundNo(1);
        t.setSimUserId(99L);
        return t;
    }

    private static AiTraderDecision round(String kind, String traceJson) {
        AiTraderDecision d = new AiTraderDecision();
        d.setId(901L);
        d.setTraderId(7L);
        d.setRoundNo(1);
        d.setWakeTime(T0);
        d.setKind(kind);
        d.setStatus(AiTraderDecision.STATUS_OK);
        d.setReasoning("这一轮的正文");
        d.setTraceJson(traceJson);
        return d;
    }

    /** 两次模型调用的轨迹：第一次调 klines（回执 500 字），第二次给结论 */
    private static String trace() {
        WakeTrace trace = new WakeTrace(7L, AiTraderDecision.KIND_TRADE, T0, 900, BigDecimal.TEN, 0, 0);
        trace.prompt("系统提示词", "开场白：账户与持仓");
        trace.callStart();
        trace.callEnd("先看结构", List.of(new AssistantMessage.ToolCall("c1", "function", "klines", "{\"symbol\":\"BTCUSDT\"}")));
        trace.toolResult("c1", "klines", "{\"rows\":\"" + "x".repeat(500) + "\"}");
        trace.callStart();
        trace.callEnd("结论：开多", List.of());
        trace.end(AiTraderDecision.STATUS_OK, null, BigDecimal.TEN, 10, 2, 100L);
        return trace.toJson();
    }

    @Test
    void roundDetailGivesOverviewThenOneCallInFull() {
        when(decisionMapper.selectOne(any())).thenReturn(round(AiTraderDecision.KIND_TRADE, trace()));

        String overview = toolkit.boundTo(trader(), AgentLang.ZH).roundDetail(901L, null);

        // 开场白给，系统提示词不给；回执只带开头 300 字
        assertThat(overview).contains("\"decisionId\":901").contains("\"opening\":\"开场白：账户与持仓\"")
                .contains("\"call\":1").contains("先看结构").contains("\"name\":\"klines\"").contains("结论：开多")
                .doesNotContain("系统提示词").doesNotContain("x".repeat(400));

        String call = toolkit.boundTo(trader(), AgentLang.ZH).roundDetail(901L, 1);

        assertThat(call).contains("\"call\":1").contains("x".repeat(500)).doesNotContain("opening");
        assertThat(toolkit.boundTo(trader(), AgentLang.ZH).roundDetail(901L, 9)).contains("\"available\":false");
    }

    /** 没存过程的行只有正文和动作；别的交易员的轮、复盘行不给 */
    @Test
    void roundDetailFallsBackToTextAndRefusesForeignOrReviewRows() {
        AiTraderDecision plain = round(AiTraderDecision.KIND_TRADE, null);
        plain.setActionsJson("[{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\"},\"rejected\":\"止损太窄\"}]");
        when(decisionMapper.selectOne(any())).thenReturn(plain);
        assertThat(toolkit.roundDetail(trader(), 901L, null)).contains("\"reasoning\":\"这一轮的正文\"")
                .contains("\"tool\":\"open_position\"").contains("\"status\":\"rejected\"");

        AiTraderDecision foreign = round(AiTraderDecision.KIND_TRADE, trace());
        foreign.setTraderId(8L);
        when(decisionMapper.selectOne(any())).thenReturn(foreign);
        assertThat(toolkit.roundDetail(trader(), 901L, null)).contains("\"available\":false");

        when(decisionMapper.selectOne(any())).thenReturn(round(AiTraderDecision.KIND_REVIEW, null));
        assertThat(toolkit.roundDetail(trader(), 901L, null)).contains("\"available\":false");
    }

    @Test
    void tradeHistoryListsClosedTradesThenOneFactSheetWithItsDebrief() {
        FuturesPositionDTO pos = new FuturesPositionDTO();
        pos.setId(42L);
        pos.setSymbol("BTCUSDT");
        pos.setSide("LONG");
        pos.setStatus("CLOSED");
        pos.setClosedBy("STOP_LOSS");
        pos.setEntryPrice(new BigDecimal("100000"));
        pos.setClosedPrice(new BigDecimal("99000"));
        pos.setClosedPnl(new BigDecimal("-50"));
        pos.setCreatedAt(Instant.ofEpochMilli(T0).atZone(ZoneId.systemDefault()).toLocalDateTime());
        pos.setUpdatedAt(Instant.ofEpochMilli(T0 + H).atZone(ZoneId.systemDefault()).toLocalDateTime());
        FuturesPositionDTO ignored = new FuturesPositionDTO();
        ignored.setId(43L);
        ignored.setSymbol("ETHUSDT");
        ignored.setSide("LONG");
        ignored.setCreatedAt(pos.getCreatedAt());
        ignored.setUpdatedAt(pos.getUpdatedAt());
        when(simTradeClient.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(pos, ignored));
        AiTraderPlan plan = new AiTraderPlan();
        plan.setPositionId(42L);
        plan.setPlayType("BREAKOUT");
        AiTraderPlan stale = new AiTraderPlan();
        stale.setPositionId(43L);
        stale.setStale(true);
        when(planMapper.selectList(any())).thenReturn(List.of(plan, stale));

        String list = toolkit.tradeHistory(trader(), AgentLang.ZH, null);

        // 主人标了不算数的那笔不列
        assertThat(list).contains("\"positionId\":42").contains("\"playType\":\"BREAKOUT\"")
                .contains("\"closeManner\":\"stopLoss\"").doesNotContain("ETHUSDT");

        AiTraderDecision debrief = new AiTraderDecision();
        debrief.setReasoning("【单笔复盘】\n路径：……");
        when(decisionMapper.selectOne(any())).thenReturn(debrief);

        String one = toolkit.tradeHistory(trader(), AgentLang.ZH, 42L);

        assertThat(one).contains("\"factSheet\":\"- [42] BTCUSDT LONG ").contains("\"debrief\":\"【单笔复盘】");
        assertThat(toolkit.tradeHistory(trader(), AgentLang.ZH, 43L)).contains("\"available\":false");
    }
}
