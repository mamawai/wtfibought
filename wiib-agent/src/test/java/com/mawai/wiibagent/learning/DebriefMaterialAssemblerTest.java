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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 单笔复盘开场素材：各轮块每一轮给全（开场白开头、这笔仓位、对这笔单子的动作、各段原文、总评、这个币的结论）；
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
        plan.setEntriesJson("[{\"orderId\":11,\"quantity\":1,\"status\":\"FILLED\",\"filledPrice\":100000,\"closedAt\":" + (OPENED + 60_000L) + "},"
                + "{\"orderId\":12,\"limitPrice\":99500,\"quantity\":1,\"status\":\"CANCELLED\",\"closedAt\":" + (OPENED + H) + "}]");
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

    /** 过程轨迹：开场白、每段从第几次调用开始、每次调用写的字（空串是只调了工具没写字） */
    private static String trace(long startedAt, String opening, String stages, String... callTexts) {
        ObjectNode t = MAPPER.createObjectNode().put("startedAt", startedAt);
        t.putObject("prompt").put("instruction", opening);
        t.set("stages", MAPPER.readTree(stages));
        ArrayNode calls = t.putArray("calls");
        for (int i = 0; i < callTexts.length; i++) {
            calls.addObject().put("n", i + 1).put("text", callTexts[i]);
        }
        return t.toString();
    }

    /** 开场白：开头几行、账户段（标题、账户 JSON、今天净亏损了结那一行）、后面的上一轮结论。账户段的标题随便写一句，代码不按标题认 */
    private static String opening(String head, String accountJson, String lossesToday) {
        return head + "\n\n【当前账户】（标题随便写）\n" + accountJson + "\n"
                + lossesToday + "\n【上一轮结论】\n上一轮结论不带\n";
    }

    /** 清单查询回这几行，按编号取整行回对应的那一行 */
    private void stored(AiTraderDecision... rows) {
        when(decisionMapper.selectList(any())).thenReturn(List.of(rows));
        for (AiTraderDecision r : rows) {
            when(decisionMapper.selectById(r.getId())).thenReturn(r);
        }
    }

    private String mark() {
        return prompts.get(AgentLang.ZH, "trader.mark.conclusion");
    }

    /**
     * 一轮给全：开场白里账户段之前的原文、这笔仓位（不带计划）和这笔计划还挂着的档、对这笔单子的动作和回执、
     * 给这个币布的盯盘、各段写的原文、总评、这个币的结论段（换行照旧）。别的仓位、别的币、别的计划的都不带
     */
    @Test
    void roundCarriesEverythingAboutThisTrade() {
        AiTraderDecision open = row(901L, OPENED, AiTraderDecision.KIND_WATCH,
                "[{\"tool\":\"klines\",\"args\":{\"symbol\":\"BTCUSDT\"}},"
                        + "{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\",\"signalsUsed\":\"突破\"},\"status\":\"ok\","
                        + "\"result\":{\"status\":\"ok\",\"entries\":["
                        + "{\"orderId\":11,\"orderType\":\"MARKET\",\"quantity\":1,\"status\":\"filled\",\"filledPrice\":100000,\"userId\":9,\"stopLosses\":[{\"id\":\"x\"}]},"
                        + "{\"orderId\":12,\"orderType\":\"LIMIT\",\"limitPrice\":99500,\"quantity\":1,\"status\":\"pending\",\"groupId\":\"g\"}],"
                        + "\"facts\":{\"batch\":{\"rewardRiskAfterFees\":1.9}}}},"
                        + "{\"tool\":\"cancel_order\",\"args\":{\"reason\":\"离现价太远\",\"orderIds\":[12]},\"status\":\"ok\","
                        + "\"result\":{\"orders\":[{\"orderId\":12,\"status\":\"cancelled\"}]}},"
                        + "{\"tool\":\"set_watch\",\"args\":{\"symbol\":\"BTCUSDT\",\"watches\":[{\"kind\":\"TOUCH_BELOW\",\"level\":99000,\"note\":\"到这儿看要不要走\"}]},\"status\":\"ok\"},"
                        + "{\"tool\":\"set_watch\",\"args\":{\"symbol\":\"ETHUSDT\",\"watches\":[]},\"status\":\"ok\"},"
                        + "{\"tool\":\"close_position\",\"args\":{\"positionId\":77,\"quantity\":1},\"status\":\"ok\"}]",
                null);
        open.setTraceJson(trace(OPENED, opening(
                        "盯盘条件命中：BTCUSDT 触及 101000 以上。你布它时的备注：到这儿看量\n\n【自上次唤醒以来】\n- ETHUSDT LONG 已止损带走",
                        "{\"equity\":1000.5,\"lossAtAllStopsPctOfEquity\":3.2,\"lossIfPendingFillPctOfEquity\":1.1,"
                                + "\"positions\":[{\"positionId\":77,\"symbol\":\"ETHUSDT\"},"
                                + "{\"positionId\":42,\"symbol\":\"BTCUSDT\",\"markPrice\":100500,\"unrealizedR\":0.5,\"plan\":{\"signalsUsed\":\"计划不带\"}}],"
                                + "\"pendingOrders\":[{\"orderId\":12,\"limitPrice\":99500,\"quantity\":1,\"lossAtStopPctOfEquity\":0.8,\"stopLosses\":[1]},"
                                + "{\"orderId\":99,\"limitPrice\":1}]}",
                        "今天净亏损了结的仓位：ETHUSDT LONG 1 次\n"),
                "[{\"n\":2,\"call\":2},{\"n\":3,\"call\":4}]",
                "核查：没到失效线", "不用搜", "", "先说一句",
                "分析过程\n" + mark() + "\n总评：顺势\n[BTCUSDT]\n判断：突破\n动作：开多\n等待：回踩\n[ETHUSDT]\n判断：跟随"));
        AiTraderDecision afterClose = row(910L, CLOSED + M5, AiTraderDecision.KIND_EVENT, null, "已止损");
        when(decisionMapper.selectOne(any())).thenReturn(afterClose);
        stored(open, afterClose);

        String rounds = assembler.assemble(trader(), pos(), plan(), AgentLang.ZH).roundsBlock();

        assertThat(rounds).startsWith("【各轮决策】（这笔从开仓那轮到平仓后第一轮，");
        assertThat(rounds).contains("\n════ 第 1/2 轮 ｜ 决策 #901 ｜ " + BeijingTime.shortFormat(OPENED) + " ｜ 盯盘触发 ════\n"
                + "〔开场白开头：为什么醒、自上次唤醒以来发生的事〕\n"
                + "盯盘条件命中：BTCUSDT 触及 101000 以上。你布它时的备注：到这儿看量\n\n【自上次唤醒以来】\n- ETHUSDT LONG 已止损带走\n"
                + "〔这一轮开始时这笔仓位〕\n"
                + "{\"positionId\":42,\"symbol\":\"BTCUSDT\",\"markPrice\":100500,\"unrealizedR\":0.5}\n"
                + "还挂着的入场档：[{\"orderId\":12,\"limitPrice\":99500,\"quantity\":1,\"lossAtStopPctOfEquity\":0.8}]\n"
                + "账户：{\"equity\":1000.5,\"lossAtAllStopsPctOfEquity\":3.2,\"lossIfPendingFillPctOfEquity\":1.1}\n"
                + "今天净亏损了结的仓位：ETHUSDT LONG 1 次\n"
                + "〔这一轮对这笔单子做的动作〕\n"
                + "- open_position 参数：{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\",\"signalsUsed\":\"突破\"}\n"
                + "  回执：{\"status\":\"ok\",\"entries\":[{\"orderId\":11,\"orderType\":\"MARKET\",\"quantity\":1,\"status\":\"filled\",\"filledPrice\":100000},"
                + "{\"orderId\":12,\"orderType\":\"LIMIT\",\"limitPrice\":99500,\"quantity\":1,\"status\":\"pending\"}],"
                + "\"facts\":{\"batch\":{\"rewardRiskAfterFees\":1.9}}}\n"
                + "- cancel_order 参数：{\"reason\":\"离现价太远\",\"orderIds\":[12]}\n"
                + "  回执：{\"orders\":[{\"orderId\":12,\"status\":\"cancelled\"}]}\n"
                + "〔这一轮给 BTCUSDT 布的盯盘〕\n"
                + "- set_watch 参数：{\"symbol\":\"BTCUSDT\",\"watches\":[{\"kind\":\"TOUCH_BELOW\",\"level\":99000,\"note\":\"到这儿看要不要走\"}]}\n"
                + "〔第 1 段写的〕\n核查：没到失效线\n"
                + "〔第 2 段写的〕\n不用搜\n"
                + "〔第 3 段写的（结论块之前）〕\n先说一句\n\n分析过程\n"
                + "〔本轮总评〕\n总评：顺势\n"
                + "〔BTCUSDT 的结论〕\n判断：突破\n动作：开多\n等待：回踩\n"
                + "\n———— " + BeijingTime.shortFormat(CLOSED) + " 平仓（止损带走）————\n"
                + "\n════ 第 2/2 轮 ｜ 决策 #910 ｜ " + BeijingTime.shortFormat(CLOSED + M5) + " ｜ 持仓变动 ════\n");
    }

    /**
     * 认动作归不归这笔：一张单都没下出去的开仓（护栏拒的、sim 拒的）在平仓前算这笔的，带上没做成的原因；
     * 订单不属于这笔计划的开仓是另一笔单子，平仓后的也不算；模型把参数类型传错的不算这笔的，也不报错。
     * 这一轮跑到一半仓位平掉了要写明；平仓之后的轮，这笔仓位那里写已经平掉
     */
    @Test
    void reentryIsAnotherTradeAndMidRoundCloseIsMarked() {
        AiTraderDecision rejected = row(901L, OPENED, AiTraderDecision.KIND_TRADE,
                "[{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"},\"rejected\":\"保证金不够\"},"
                        + "{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"},\"status\":\"error\",\"error\":\"sim 拒了\"},"
                        + "{\"tool\":\"cancel_order\",\"args\":{\"orderIds\":[{\"id\":12}]},\"status\":\"not_attempted\"},"
                        + "{\"tool\":\"set_stop_loss\",\"args\":{\"positionId\":\"#42\"},\"status\":\"not_attempted\"}]", null);
        String reentry = "[{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"},\"status\":\"ok\","
                + "\"result\":{\"status\":\"ok\",\"entries\":[{\"orderId\":55,\"status\":\"filled\"}]}}]";
        AiTraderDecision stopped = row(905L, CLOSED - M5, AiTraderDecision.KIND_EVENT, reentry, null);
        stopped.setTraceJson(trace(CLOSED - M5, "没有账户段的开场白", "[]", "看了一眼"));
        stopped.setLatencyMs((int) (2 * M5));
        AiTraderDecision afterClose = row(910L, CLOSED + M5, AiTraderDecision.KIND_TRADE,
                "[{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"},\"rejected\":\"当天不再开\"}]", null);
        afterClose.setTraceJson(trace(CLOSED + M5, opening("新一根 K线已收盘。", "{\"equity\":900,\"positions\":[]}", ""), "[]", "空仓"));
        when(decisionMapper.selectOne(any())).thenReturn(afterClose);
        stored(rejected, stopped, afterClose);

        String rounds = assembler.assemble(trader(), pos(), plan(), AgentLang.ZH).roundsBlock();

        assertThat(rounds).contains("〔这一轮对这笔单子做的动作〕\n"
                + "- open_position(rejected) 参数：{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"}\n  没做成的原因：保证金不够\n"
                + "- open_position(error) 参数：{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"}\n  没做成的原因：sim 拒了\n"
                + "〔这一轮给 BTCUSDT 布的盯盘〕\n无\n");
        assertThat(rounds).contains("决策 #905 ｜ " + BeijingTime.shortFormat(CLOSED - M5) + " ｜ 持仓变动 ════\n"
                + "〔这一轮对这笔单子做的动作〕\n无\n"
                + "（这一轮进行到一半，" + BeijingTime.shortFormat(CLOSED) + " 这笔仓位平仓了：止损带走。这一轮从 "
                + BeijingTime.shortFormat(CLOSED - M5) + " 跑到 " + BeijingTime.shortFormat(CLOSED + M5) + "）\n"
                + "〔这一轮给 BTCUSDT 布的盯盘〕\n无\n"
                + "〔第 1 段写的〕\n看了一眼\n");
        assertThat(rounds).contains("决策 #910 ｜ " + BeijingTime.shortFormat(CLOSED + M5) + " ｜ 例行唤醒 ════\n"
                + "〔开场白开头：为什么醒、自上次唤醒以来发生的事〕\n新一根 K线已收盘。\n"
                + "〔这一轮开始时这笔仓位〕\n（这一轮开始时这笔仓位已经平掉）\n账户：{\"equity\":900}\n"
                + "〔这一轮对这笔单子做的动作〕\n无\n");
    }

    /**
     * 没存过程轨迹的行只有动作和存下来的正文：没走完的轮标题带状态，结论里没写这个币、结论没按币分段各有各的说明；
     * 预案行只留对这笔单子做了动作的
     */
    @Test
    void rowsWithoutTraceFallBackToStoredText() {
        AiTraderDecision failed = row(901L, OPENED, AiTraderDecision.KIND_TRADE, null,
                "过程\n" + mark() + "\n总评：观望\n[ETHUSDT]\n判断：跟随");
        failed.setStatus("ERROR");
        AiTraderDecision unsegmented = row(902L, OPENED + M5, AiTraderDecision.KIND_ALERT, null,
                "过程\n" + mark() + "\n总评：全部观望");
        // 预案行的动作记录：工具名是 preset，参数是布预案时写的动作，结果是一句话
        AiTraderDecision preset = row(906L, OPENED + H, AiTraderDecision.KIND_PRESET,
                "[{\"tool\":\"preset\",\"args\":{\"positionId\":42,\"closePercent\":50},\"status\":\"ok\",\"result\":\"平了一半\"}]",
                "到 101000 平一半：已执行");
        stored(failed, unsegmented, preset,
                row(907L, OPENED + H + M5, AiTraderDecision.KIND_PRESET,
                        "[{\"tool\":\"preset\",\"args\":{\"positionId\":77},\"status\":\"ok\",\"result\":\"平了\"}]", "别的仓位的预案"));

        String rounds = assembler.assemble(trader(), pos(), plan(), AgentLang.ZH).roundsBlock();

        assertThat(rounds).doesNotContain("#907");
        assertThat(rounds).contains("════ 第 1/3 轮 ｜ 决策 #901 ｜ " + BeijingTime.shortFormat(OPENED) + " ｜ 例行唤醒/ERROR ════\n"
                + "〔这一轮对这笔单子做的动作〕\n无\n"
                + "〔这一轮给 BTCUSDT 布的盯盘〕\n无\n"
                + "〔这一轮写的（结论块之前）〕\n过程\n"
                + "〔本轮总评〕\n总评：观望\n"
                + "〔BTCUSDT 的结论〕\n（这一轮的结论里没有写这个币）\n");
        assertThat(rounds).contains("════ 第 2/3 轮 ｜ 决策 #902 ｜ " + BeijingTime.shortFormat(OPENED + M5) + " ｜ 剧烈波动 ════\n")
                .contains("〔本轮总评〕\n总评：全部观望\n〔BTCUSDT 的结论〕\n（这一轮的结论没按币分段，全文在上面的总评里）\n");
        assertThat(rounds).contains("════ 第 3/3 轮 ｜ 决策 #906 ｜ " + BeijingTime.shortFormat(OPENED + H) + " ｜ 预案执行 ════\n"
                + "〔这一轮对这笔单子做的动作〕\n"
                + "- preset 参数：{\"positionId\":42,\"closePercent\":50}\n  回执：\"平了一半\"\n"
                + "〔这一轮给 BTCUSDT 布的盯盘〕\n无\n"
                + "〔这一轮写的〕\n到 101000 平一半：已执行\n"
                + "\n———— " + BeijingTime.shortFormat(CLOSED) + " 平仓（止损带走）————\n");
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
