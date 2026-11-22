package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibagent.trader.DecisionText;
import com.mawai.wiibagent.trader.TradeRecordService;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderExperience;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 单笔复盘开场素材：各轮清单只列对这笔单子做的动作和该币的结论段，轮数多了没动作的只留编号；
 * 账本块列现役条目和开仓那轮引用了哪几条。
 */
class DebriefMaterialAssemblerTest {

    private static final long OPENED = 1785110400000L;
    private static final long H = 3_600_000L;
    private static final long M5 = 300_000L;
    private static final long CLOSED = OPENED + 2 * H;

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderDecision.class);
    }

    private final PromptCatalog prompts = new PromptCatalog();
    private final AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);
    private final ExperienceLedger ledger = mock(ExperienceLedger.class);
    private final TradeRecordService tradeRecords = mock(TradeRecordService.class);
    private final DebriefMaterialAssembler assembler = new DebriefMaterialAssembler(decisionMapper, prompts,
            new CaseFileBuilder(mock(KlineHistoryStore.class)), new CaseFileRenderer(prompts), ledger,
            new DecisionText(prompts), tradeRecords);

    private static AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setRoundNo(1);
        return t;
    }

    private static FuturesPositionDTO pos() {
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setId(42L);
        p.setSymbol("BTCUSDT");
        p.setSide("LONG");
        p.setStatus("CLOSED");
        p.setClosedBy("STOP_LOSS");
        p.setEntryPrice(new BigDecimal("100000"));
        p.setClosedPrice(new BigDecimal("99000"));
        p.setClosedPnl(new BigDecimal("-50"));
        p.setCreatedAt(Instant.ofEpochMilli(OPENED + 60_000L).atZone(ZoneId.systemDefault()).toLocalDateTime());
        p.setUpdatedAt(Instant.ofEpochMilli(CLOSED).atZone(ZoneId.systemDefault()).toLocalDateTime());
        return p;
    }

    private static AiTraderPlan plan() {
        AiTraderPlan plan = new AiTraderPlan();
        plan.setPositionId(42L);
        plan.setSymbol("BTCUSDT");
        plan.setSide("LONG");
        plan.setOpenedWakeTime(OPENED);
        return plan;
    }

    private static AiTraderDecision row(long id, long wakeTime, String kind, String actionsJson, String reasoning) {
        AiTraderDecision d = new AiTraderDecision();
        d.setId(id);
        d.setWakeTime(wakeTime);
        d.setKind(kind);
        d.setStatus(AiTraderDecision.STATUS_OK);
        d.setActionsJson(actionsJson);
        d.setReasoning(reasoning);
        return d;
    }

    private String conclusion(String btc) {
        return "求证过程\n" + prompts.get(AgentLang.ZH, "trader.mark.conclusion")
                + "\n总评：顺势\n[BTCUSDT]\n" + btc + "\n[ETHUSDT]\n判断：跟随";
    }

    /** 只列对这笔单子做的动作；别的仓位的动作、别的仓位的预案行不列；平仓那一刻单独一行 */
    @Test
    void roundsListActionsOnThisTradeAndItsConclusionSegment() {
        AiTraderDecision afterClose = row(910L, CLOSED + 1, AiTraderDecision.KIND_EVENT, null, conclusion("判断：被止损带走"));
        when(decisionMapper.selectOne(any())).thenReturn(afterClose);
        when(decisionMapper.selectList(any())).thenReturn(List.of(
                row(901L, OPENED, AiTraderDecision.KIND_TRADE,
                        "[{\"tool\":\"klines\",\"args\":{\"symbol\":\"BTCUSDT\"}},"
                                + "{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"},\"status\":\"ok\"}]",
                        conclusion("判断：突破\n动作：开多\n等待：无")),
                row(905L, OPENED + H, AiTraderDecision.KIND_TRADE,
                        "[{\"tool\":\"set_stop_loss\",\"args\":{\"positionId\":42,\"stopLossPrice\":99000,\"reason\":\"锁利\"},\"status\":\"ok\"},"
                                + "{\"tool\":\"close_position\",\"args\":{\"positionId\":77,\"quantity\":1},\"status\":\"ok\"}]",
                        conclusion("判断：续持")),
                row(906L, OPENED + H + M5, AiTraderDecision.KIND_PRESET,
                        "[{\"tool\":\"preset\",\"args\":{\"positionId\":77},\"status\":\"ok\"}]", "别的仓位的预案"),
                afterClose));

        String rounds = assembler.assemble(trader(), pos(), plan(), AgentLang.ZH).roundsBlock();

        assertThat(rounds).startsWith("【各轮决策】");
        assertThat(rounds).contains("- #901 " + BeijingTime.shortFormat(OPENED) + " TRADE ｜ 动作：open_position ｜ 结论：判断：突破 动作：开多 等待：无\n"
                + "- #905 " + BeijingTime.shortFormat(OPENED + H) + " TRADE ｜ 动作：set_stop_loss stopLossPrice=99000 reason=锁利 ｜ 结论：判断：续持\n"
                + "—— " + BeijingTime.shortFormat(CLOSED) + " 平仓（止损带走）——\n"
                + "- #910 " + BeijingTime.shortFormat(CLOSED + 1) + " EVENT ｜ 动作：无 ｜ 结论：判断：被止损带走\n");
        assertThat(rounds).doesNotContain("#906").doesNotContain("close_position");
    }

    /** 轮数超过上限：开仓那轮、有动作的、平仓之后的都带结论段，其余从最近的往回补满，剩下的只列编号和时刻 */
    @Test
    void longHoldListsQuietRoundsByIdOnly() {
        List<AiTraderDecision> rows = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            rows.add(row(900L + i, i == 44 ? CLOSED + 1 : OPENED + i * 60_000L, AiTraderDecision.KIND_TRADE,
                    i == 10 ? "[{\"tool\":\"set_stop_loss\",\"args\":{\"positionId\":42,\"stopLossPrice\":99000},\"status\":\"ok\"}]" : null,
                    conclusion("判断：第" + i + "轮")));
        }
        when(decisionMapper.selectOne(any())).thenReturn(rows.getLast());
        when(decisionMapper.selectList(any())).thenReturn(rows);

        String rounds = assembler.assemble(trader(), pos(), plan(), AgentLang.ZH).roundsBlock();

        // 留下的是第 0 轮和第 6 到 44 轮，第 1 到 5 轮只列编号
        assertThat(rounds).contains("结论：判断：第0轮\n").contains("结论：判断：第6轮\n").contains("结论：判断：第10轮\n")
                .doesNotContain("判断：第5轮");
        assertThat(rounds).contains("其余 5 轮没有对这笔单子的动作，只列决策编号和时刻：#901 " + BeijingTime.shortFormat(OPENED + 60_000L) + "、#902 ");
    }

    /** 账本块：现役条目一条一行，发现不列；开仓那轮该币那一段引用的编号单独一行，总评和别的币那段里的不算 */
    @Test
    void ledgerBlockListsActiveEntriesAndWhatTheOpeningRoundCited() {
        when(decisionMapper.selectList(any())).thenReturn(List.of());
        when(ledger.active(7L, 1)).thenReturn(List.of(
                exp(62L, AiTraderExperience.KIND_RULE, AiTraderExperience.SLOT_ENTRY, AiTraderExperience.DIR_RESTRICTION, "PULLBACK", "破位不挂内侧限价"),
                exp(61L, AiTraderExperience.KIND_GAP, AiTraderExperience.SLOT_EXEC, AiTraderExperience.DIR_EXECUTION, null, "止损不小于缓冲"),
                exp(5L, AiTraderExperience.KIND_FINDING, AiTraderExperience.SLOT_RISK, AiTraderExperience.DIR_EXECUTION, null, "指令第六条少赚")));
        AiTraderDecision open = row(901L, OPENED, AiTraderDecision.KIND_TRADE,
                "[{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"},\"status\":\"ok\"}]",
                "求证\n" + prompts.get(AgentLang.ZH, "trader.mark.conclusion")
                        + "\n总评：遵 #61\n[BTCUSDT]\n动作：遵 #62 开多\n[ETHUSDT]\n判断：遵 #61");
        when(tradeRecords.openDecisions(any(), any())).thenReturn(Map.of(OPENED, open));

        assertThat(assembler.assemble(trader(), pos(), plan(), AgentLang.ZH).ledgerBlock()).isEqualTo(
                "【经验账本】（现役 2 条；归类时用 #编号）\n"
                        + "#62 规律 入场条件 限制型 PULLBACK ｜ 破位不挂内侧限价\n"
                        + "#61 执行缺口 执行缺口 执行型 通用 ｜ 止损不小于缓冲\n"
                        + "开仓那轮的结论引用了：#62\n");

        // 计划行对上的那一轮没开这个币：不算引用
        open.setActionsJson("[{\"tool\":\"open_position\",\"args\":{\"symbol\":\"ETHUSDT\",\"side\":\"LONG\"},\"status\":\"ok\"}]");
        assertThat(assembler.assemble(trader(), pos(), plan(), AgentLang.ZH).ledgerBlock())
                .endsWith("开仓那轮的结论没有引用账本条目\n");
    }

    /** 事实表块：块头带读法，下面是这笔的事实表；账本空着也照说 */
    @Test
    void factsBlockCarriesGuideAndFactSheet() {
        when(decisionMapper.selectList(any())).thenReturn(List.of());

        DebriefMaterialAssembler.DebriefMaterial m = assembler.assemble(trader(), pos(), plan(), AgentLang.ZH);

        assertThat(m.factsBlock()).startsWith("【这笔单子的事实表】（代码算的事实。方括号里是仓位编号，归类里用它。目标写成")
                .contains("\n- [42] BTCUSDT LONG ");
        assertThat(m.roundsBlock()).contains("（这段时间没有唤醒记录）");
        assertThat(m.ledgerBlock()).contains("（账本为空）");
    }

    private static AiTraderExperience exp(long id, String kind, String slot, String dir, String play, String text) {
        AiTraderExperience e = new AiTraderExperience();
        e.setId(id);
        e.setKind(kind);
        e.setSlot(slot);
        e.setDirection(dir);
        e.setPlayType(play);
        e.setText(text);
        return e;
    }
}
