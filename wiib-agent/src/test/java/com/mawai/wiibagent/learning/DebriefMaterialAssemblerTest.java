package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibagent.trader.DecisionText;
import com.mawai.wiibagent.trader.TradePairing;
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
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单笔复盘开场素材：各轮块每一轮给全（开场白开头、这笔仓位、对这笔单子的动作、各段原文、总评、这个币的结论）；
 * 几轮同时在跑时按实际开始排、标出重叠、动作带时刻；账本块列现役条目和开仓那轮引用了哪几条。
 */
class DebriefMaterialAssemblerTest {

    private static final long OPENED = 1785110400000L;
    private static final long H = 3_600_000L;
    private static final long M5 = 300_000L;
    private static final long CLOSED = OPENED + 2 * H;
    private static final long PLAN_ID = 21L;

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
        p.setCreatedAt(TradePairing.localOf(OPENED + 60_000L));
        p.setUpdatedAt(TradePairing.localOf(CLOSED));
        return p;
    }

    private static AiTraderPlan plan() {
        AiTraderPlan plan = new AiTraderPlan();
        plan.setId(PLAN_ID);
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

    /** 这一轮实际从 startedAt 跑了 latencyMs：落库时刻就是结束 */
    private static AiTraderDecision ran(AiTraderDecision d, long startedAt, long latencyMs) {
        d.setLatencyMs((int) latencyMs);
        d.setCreatedAt(TradePairing.localOf(startedAt + latencyMs));
        return d;
    }

    /** 过程轨迹：开场白、每段从第几次调用开始、每次调用写的字（空串是只调了工具没写字） */
    private static String trace(String opening, String stages, String... callTexts) {
        ObjectNode t = MAPPER.createObjectNode();
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

    /**
     * 清单查询回这几行。按编号取全文、找平仓后第一轮都走 selectOne：点名要过程轨迹的是取全文，按编号回那一行；另一个回 after
     */
    private void stored(AiTraderDecision after, AiTraderDecision... rows) {
        // doReturn/doAnswer 桩的，同一个测试里可以再桩一次
        doReturn(List.of(rows)).when(decisionMapper).selectList(any());
        doAnswer(call -> {
            LambdaQueryWrapper<AiTraderDecision> q = call.getArgument(0);
            if (!q.getSqlSelect().contains("trace_json")) {
                return after;
            }
            // 参数值要先拼 SQL 段才填进来
            q.getSqlSegment();
            Object id = q.getParamNameValuePairs().values().iterator().next();
            return Arrays.stream(rows).filter(r -> r.getId().equals(id)).findFirst().orElseThrow();
        }).when(decisionMapper).selectOne(any());
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
        open.setTraceJson(trace(opening(
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
        stored(afterClose, open, afterClose);

        String rounds = assembler.assemble(trader(), pos(), plan(), AgentLang.ZH).roundsBlock();

        assertThat(rounds).startsWith("【各轮决策】（这笔从开仓那轮到平仓后第一个看过这个币的那一轮；");
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
     * 订单不属于这笔计划的开仓是另一笔单子，平仓后的也不算（一轮跑到一半平了仓，之后被拒的再开也不算）；模型把参数类型传错的不算这笔的，也不报错。
     * 这一轮跑到一半仓位平掉了要写明；平仓之后的轮，这笔仓位那里写已经平掉
     */
    @Test
    void reentryIsAnotherTradeAndMidRoundCloseIsMarked() {
        AiTraderDecision rejected = row(901L, OPENED, AiTraderDecision.KIND_TRADE,
                "[{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"},\"rejected\":\"保证金不够\"},"
                        + "{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"},\"status\":\"error\",\"error\":\"sim 拒了\"},"
                        + "{\"tool\":\"cancel_order\",\"args\":{\"orderIds\":[{\"id\":12}]},\"status\":\"not_attempted\"},"
                        + "{\"tool\":\"set_stop_loss\",\"args\":{\"positionId\":\"#42\"},\"status\":\"not_attempted\"}]", null);
        String reentry = "[{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"},\"rejected\":\"当天不再开\",\"at\":"
                + (CLOSED + 60_000L) + "},"
                + "{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"},\"status\":\"ok\","
                + "\"result\":{\"status\":\"ok\",\"entries\":[{\"orderId\":55,\"status\":\"filled\"}]},\"at\":" + (CLOSED + 120_000L) + "}]";
        AiTraderDecision stopped = ran(row(905L, CLOSED - M5, AiTraderDecision.KIND_EVENT, reentry, null), CLOSED - M5, 2 * M5);
        stopped.setTraceJson(trace("没有账户段的开场白", "[]", "看了一眼"));
        AiTraderDecision afterClose = row(910L, CLOSED + M5, AiTraderDecision.KIND_TRADE,
                "[{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"},\"rejected\":\"当天不再开\"}]", null);
        afterClose.setTraceJson(trace(opening("新一根 K线已收盘。", "{\"equity\":900,\"positions\":[]}", ""), "[]", "空仓"));
        stored(afterClose, rejected, stopped, afterClose);

        String rounds = assembler.assemble(trader(), pos(), plan(), AgentLang.ZH).roundsBlock();

        assertThat(rounds).contains("〔这一轮对这笔单子做的动作〕\n"
                + "- open_position(rejected) 参数：{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"}\n  没做成的原因：保证金不够\n"
                + "- open_position(error) 参数：{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"}\n  没做成的原因：sim 拒了\n"
                + "〔这一轮给 BTCUSDT 布的盯盘〕\n无\n");
        assertThat(rounds).contains("决策 #905 ｜ " + BeijingTime.shortFormat(CLOSED - M5) + "–" + BeijingTime.shortFormat(CLOSED + M5)
                + " ｜ 持仓变动 ════\n"
                + "〔这一轮对这笔单子做的动作〕\n无\n"
                + "（这一轮进行到一半，" + BeijingTime.shortFormat(CLOSED) + " 这笔仓位平仓了：止损带走。这一轮从 "
                + BeijingTime.shortFormat(CLOSED - M5) + " 跑到 " + BeijingTime.shortFormat(CLOSED + M5) + "）\n"
                + "〔这一轮给 BTCUSDT 布的盯盘〕\n无\n"
                + "〔第 1 段写的〕\n看了一眼\n");
        assertThat(rounds).contains("决策 #910 ｜ " + BeijingTime.shortFormat(CLOSED + M5) + " ｜ 例行唤醒 ════\n"
                + "〔开场白开头：为什么醒、自上次唤醒以来发生的事〕\n新一根 K线已收盘。\n"
                + "〔这一轮开始时这笔仓位〕\n（这一轮开始时这笔仓位已经平掉）\n账户：{\"equity\":900}\n"
                + "〔这一轮对这笔单子做的动作〕\n无\n");
        // 一轮刚好在另一轮开始那一刻结束，不算同时在跑
        assertThat(rounds).doesNotContain("｜ 和第");
    }

    /**
     * 没存过程轨迹的行只有动作和存下来的正文：没走完的轮标题带状态，结论里没写这个币、结论没按币分段各有各的说明；
     * 预案行只留对这笔单子做了动作的，撤单预案按它撤的那张单认
     */
    @Test
    void rowsWithoutTraceFallBackToStoredText() {
        AiTraderDecision failed = row(901L, OPENED, AiTraderDecision.KIND_TRADE, null,
                "过程\n" + mark() + "\n总评：观望\n[ETHUSDT]\n判断：跟随");
        failed.setStatus("ERROR");
        AiTraderDecision unsegmented = row(902L, OPENED + M5, AiTraderDecision.KIND_ALERT, null,
                "过程\n" + mark() + "\n总评：全部观望");
        // 预案行的动作记录：工具名是 preset，参数是布预案时写的动作，结果是一句话
        AiTraderDecision cancel = row(908L, OPENED + 2 * M5, AiTraderDecision.KIND_PRESET,
                "[{\"tool\":\"preset\",\"args\":{\"type\":\"CANCEL_ORDER\",\"symbol\":\"BTCUSDT\",\"side\":\"LONG\",\"orderId\":12},"
                        + "\"status\":\"ok\",\"result\":\"撤了\"}]",
                "到 100800 撤掉 99500 那档：已执行");
        AiTraderDecision preset = row(906L, OPENED + H, AiTraderDecision.KIND_PRESET,
                "[{\"tool\":\"preset\",\"args\":{\"positionId\":42,\"closePercent\":50},\"status\":\"ok\",\"result\":\"平了一半\"}]",
                "到 101000 平一半：已执行");
        stored(null, failed, unsegmented, cancel, preset,
                row(907L, OPENED + H + M5, AiTraderDecision.KIND_PRESET,
                        "[{\"tool\":\"preset\",\"args\":{\"positionId\":77},\"status\":\"ok\",\"result\":\"平了\"}]", "别的仓位的预案"));

        String rounds = assembler.assemble(trader(), pos(), plan(), AgentLang.ZH).roundsBlock();

        assertThat(rounds).doesNotContain("#907");
        assertThat(rounds).contains("════ 第 1/4 轮 ｜ 决策 #901 ｜ " + BeijingTime.shortFormat(OPENED) + " ｜ 例行唤醒/ERROR ════\n"
                + "〔这一轮对这笔单子做的动作〕\n无\n"
                + "〔这一轮给 BTCUSDT 布的盯盘〕\n无\n"
                + "〔这一轮写的（结论块之前）〕\n过程\n"
                + "〔本轮总评〕\n总评：观望\n"
                + "〔BTCUSDT 的结论〕\n（这一轮的结论里没有写这个币）\n");
        assertThat(rounds).contains("════ 第 2/4 轮 ｜ 决策 #902 ｜ " + BeijingTime.shortFormat(OPENED + M5) + " ｜ 剧烈波动 ════\n")
                .contains("〔本轮总评〕\n总评：全部观望\n〔BTCUSDT 的结论〕\n（这一轮的结论没按币分段，全文在上面的总评里）\n");
        assertThat(rounds).contains("════ 第 3/4 轮 ｜ 决策 #908 ｜ " + BeijingTime.shortFormat(OPENED + 2 * M5) + " ｜ 预案执行 ════\n"
                + "〔这一轮对这笔单子做的动作〕\n"
                + "- preset 参数：{\"type\":\"CANCEL_ORDER\",\"symbol\":\"BTCUSDT\",\"side\":\"LONG\",\"orderId\":12}\n  回执：\"撤了\"\n");
        assertThat(rounds).contains("════ 第 4/4 轮 ｜ 决策 #906 ｜ " + BeijingTime.shortFormat(OPENED + H) + " ｜ 预案执行 ════\n"
                + "〔这一轮对这笔单子做的动作〕\n"
                + "- preset 参数：{\"positionId\":42,\"closePercent\":50}\n  回执：\"平了一半\"\n"
                + "〔这一轮给 BTCUSDT 布的盯盘〕\n无\n"
                + "〔这一轮写的〕\n到 101000 平一半：已执行\n"
                + "\n———— " + BeijingTime.shortFormat(CLOSED) + " 平仓（止损带走）————\n");
    }

    /**
     * 取哪些轮：只要看整盘的轮、预案行和只处理这个币的轮；平仓后第一轮也只认这两种。
     * 范围按实际跑的时段算：结束不早于这笔的起点，起点取计划立案那一轮和仓位开仓时刻里早的那个（续立计划的立案时刻晚于开仓）。
     * 取每一轮全文要点名过程轨迹
     */
    @Test
    @SuppressWarnings("unchecked")
    void roundsQueryKeepsThisSymbolAndOverlapsTheTrade() {
        AiTraderPlan carried = plan();
        carried.setOpenedWakeTime(OPENED + H);
        stored(null, row(901L, OPENED + H, AiTraderDecision.KIND_TRADE, null, "例行"));

        assembler.assemble(trader(), pos(), carried, AgentLang.ZH);

        ArgumentCaptor<LambdaQueryWrapper<AiTraderDecision>> list = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(decisionMapper).selectList(list.capture());
        String sql = list.getValue().getSqlSegment();
        assertThat(sql).contains("(symbol IS NULL OR symbol =").contains("wake_time <=").contains("created_at >=")
                .doesNotContain("BETWEEN");
        assertThat(list.getValue().getParamNameValuePairs().values())
                .contains("BTCUSDT", CLOSED, TradePairing.localOf(OPENED + 60_000L));
        ArgumentCaptor<LambdaQueryWrapper<AiTraderDecision>> one = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(decisionMapper, atLeastOnce()).selectOne(one.capture());
        LambdaQueryWrapper<AiTraderDecision> after = one.getAllValues().getFirst();
        assertThat(after.getSqlSegment()).contains("(symbol IS NULL OR symbol =").contains("wake_time >");
        assertThat(one.getAllValues().getLast().getSqlSelect()).contains("trace_json");
    }

    /**
     * 几轮同时在跑：按实际开始排（例行轮的 wake_time 是边界，可能比先开跑的单币轮早），标题写实际起止、只处理哪个币、和第几轮同时在跑；
     * 动作前面是做成的时刻，反手拆出来的腿用外层那条的时刻；盯盘按为哪个币布的认
     */
    @Test
    void overlappingRoundsFollowRealStartWithActionTimes() {
        long bookStart = OPENED + 30_000L;
        long singleStart = OPENED + 5_000L;
        AiTraderDecision book = ran(row(901L, OPENED, AiTraderDecision.KIND_TRADE,
                "[{\"tool\":\"set_stop_loss\",\"args\":{\"positionId\":42,\"stopLossPrice\":99500},\"status\":\"ok\",\"at\":" + (OPENED + 400_000L) + "},"
                        + "{\"tool\":\"set_watch\",\"args\":{\"symbol\":\"BTCUSDT\",\"forSymbol\":\"ETHUSDT\",\"watches\":[]},\"status\":\"ok\",\"at\":" + (OPENED + 450_000L) + "},"
                        + "{\"tool\":\"reverse_position\",\"status\":\"partial\",\"result\":{"
                        + "\"close\":{\"tool\":\"close_position\",\"status\":\"ok\",\"args\":{\"positionId\":42}},"
                        + "\"open\":{\"tool\":\"open_position\",\"status\":\"error\",\"args\":{\"symbol\":\"BTCUSDT\",\"side\":\"SHORT\"}}},"
                        + "\"at\":" + (OPENED + 500_000L) + "}]", null), bookStart, 600_000L);
        AiTraderDecision single = ran(row(902L, singleStart, AiTraderDecision.KIND_ALERT,
                "[{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"},\"status\":\"ok\","
                        + "\"result\":{\"status\":\"ok\",\"entries\":[{\"orderId\":11,\"status\":\"filled\"}]},\"at\":" + (OPENED + 60_000L) + "},"
                        + "{\"tool\":\"set_watch\",\"args\":{\"symbol\":\"ETHUSDT\",\"watches\":[]},\"status\":\"ok\",\"at\":" + (OPENED + 200_000L) + "}]",
                null), singleStart, 300_000L);
        single.setSymbol("BTCUSDT");
        stored(null, book, single);

        String rounds = assembler.assemble(trader(), pos(), plan(), AgentLang.ZH).roundsBlock();

        String singleTitle = "════ 第 1/2 轮 ｜ 决策 #902 ｜ " + BeijingTime.shortFormat(singleStart) + "–"
                + BeijingTime.shortFormat(singleStart + 300_000L) + " ｜ 剧烈波动 ｜ 只处理 BTCUSDT ｜ 和第 2 轮同时在跑 ════\n";
        String bookTitle = "════ 第 2/2 轮 ｜ 决策 #901 ｜ " + BeijingTime.shortFormat(bookStart) + "–"
                + BeijingTime.shortFormat(bookStart + 600_000L) + " ｜ 例行唤醒 ｜ 和第 1 轮同时在跑 ════\n";
        assertThat(rounds.indexOf(singleTitle)).isPositive().isLessThan(rounds.indexOf(bookTitle));
        assertThat(rounds).contains(singleTitle + "〔这一轮对这笔单子做的动作〕\n- " + BeijingTime.shortSeconds(OPENED + 60_000L)
                        + " open_position 参数：{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"}\n")
                .contains("〔这一轮给 BTCUSDT 布的盯盘〕\n- " + BeijingTime.shortSeconds(OPENED + 200_000L)
                        + " set_watch 参数：{\"symbol\":\"ETHUSDT\",\"watches\":[]}\n");
        assertThat(rounds).contains(bookTitle + "〔这一轮对这笔单子做的动作〕\n"
                + "- " + BeijingTime.shortSeconds(OPENED + 400_000L) + " set_stop_loss 参数：{\"positionId\":42,\"stopLossPrice\":99500}\n"
                + "- " + BeijingTime.shortSeconds(OPENED + 500_000L) + " close_position 参数：{\"positionId\":42}\n"
                + "〔这一轮给 BTCUSDT 布的盯盘〕\n无\n");
    }

    /** 开仓前就在跑的那一轮：对这笔仓位做的算；这笔起点之前那次被拒的开仓是上一笔之后的再入场，不算 */
    @Test
    void roundRunningBeforeTheTradeKeepsOnlyItsActionsOnThisTrade() {
        AiTraderDecision early = ran(row(900L, OPENED - M5, AiTraderDecision.KIND_ALERT,
                "[{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"},\"rejected\":\"冷却中\",\"at\":"
                        + (OPENED - 240_000L) + "},"
                        + "{\"tool\":\"set_stop_loss\",\"args\":{\"positionId\":42,\"stopLossPrice\":99000},\"status\":\"ok\",\"at\":"
                        + (OPENED + 120_000L) + "}]", null), OPENED - M5, 2 * M5);
        early.setSymbol("BTCUSDT");
        stored(null, early, row(901L, OPENED, AiTraderDecision.KIND_TRADE, null, null));

        String rounds = assembler.assemble(trader(), pos(), plan(), AgentLang.ZH).roundsBlock();

        assertThat(rounds).contains("决策 #900").contains("〔这一轮对这笔单子做的动作〕\n- " + BeijingTime.shortSeconds(OPENED + 120_000L)
                + " set_stop_loss 参数：{\"positionId\":42,\"stopLossPrice\":99000}\n").doesNotContain("open_position(rejected)");
    }

    /**
     * 平仓后第一轮按实际开始认：例行轮的 wake_time 是边界，等数据公布晚了才开跑。
     * 边界在平仓前、开跑在平仓后的例行轮就是平仓后第一轮，下一个钟头的不要；边界在平仓后、开跑更晚的例行轮让位给先开跑的单币轮
     */
    @Test
    void firstRoundAfterTheCloseGoesByRealStart() {
        AiTraderDecision open = row(901L, OPENED, AiTraderDecision.KIND_TRADE, null, null);
        AiTraderDecision gated = ran(row(911L, CLOSED - 10_000L, AiTraderDecision.KIND_TRADE, null, null), CLOSED + 21_000L, M5);
        AiTraderDecision nextHour = ran(row(912L, CLOSED + H, AiTraderDecision.KIND_TRADE, null, null), CLOSED + H, M5);
        stored(nextHour, open, gated, nextHour);

        String rounds = assembler.assemble(trader(), pos(), plan(), AgentLang.ZH).roundsBlock();

        assertThat(rounds).contains("决策 #911").doesNotContain("决策 #912");
        assertThat(rounds.indexOf("平仓（止损带走）")).isLessThan(rounds.indexOf("决策 #911"));

        AiTraderDecision lateRoutine = ran(row(913L, CLOSED + 10_000L, AiTraderDecision.KIND_TRADE, null, null), CLOSED + 40_000L, M5);
        AiTraderDecision single = ran(row(914L, CLOSED + 20_000L, AiTraderDecision.KIND_ALERT, null, null), CLOSED + 20_000L, M5);
        single.setSymbol("BTCUSDT");
        stored(lateRoutine, open, lateRoutine, single);

        assertThat(assembler.assemble(trader(), pos(), plan(), AgentLang.ZH).roundsBlock())
                .contains("决策 #914").doesNotContain("决策 #913");
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
        when(tradeRecords.openDecisions(any(), any())).thenReturn(Map.of(PLAN_ID, open));

        assertThat(assembler.assemble(trader(), pos(), plan(), AgentLang.ZH).ledgerBlock()).isEqualTo(
                "【经验账本】（现役 2 条；归类时用 #编号）\n"
                        + "#62 规律 入场条件 限制型 PULLBACK ｜ 破位不挂内侧限价\n"
                        + "#61 执行缺口 执行缺口 执行型 通用 ｜ 止损不小于缓冲\n"
                        + "开仓那轮的结论引用了：#62\n");

        // 认不出开仓那一轮（续立行、补归档行）：算没引用
        when(tradeRecords.openDecisions(any(), any())).thenReturn(Map.of());
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
