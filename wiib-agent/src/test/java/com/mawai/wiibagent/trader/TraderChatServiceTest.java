package com.mawai.wiibagent.trader;

import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话轨读 trader 的唯一入口：只查询、不动手。
 * <p>
 * 查询拿的是已经定位好的 trader，定位规则（谁的、哪一个）归 {@code SessionTraderLocatorTest}；
 * 动作（留言/唤醒/点播复盘）的准入与语义归 {@link TraderActionServiceTest}。
 */
class TraderChatServiceTest {

    private static final long ME = 1L;
    private static final long OTHERS = 2L;

    private final TraderService traderService = mock(TraderService.class);
    private final TraderPlanStore planStore = mock(TraderPlanStore.class);
    private final SimTradeClient simTradeClient = mock(SimTradeClient.class);
    private final TraderModelFactory modelFactory = mock(TraderModelFactory.class);
    private final TradeRecordService tradeRecordService = mock(TradeRecordService.class);
    private final TraderActionService actionService = mock(TraderActionService.class);
    /** stale 过滤走真实实现：chat 面的剔段/剔轮断言要打在真逻辑上 */
    private final DecisionText decisionText = new DecisionText(new PromptCatalog());

    private final TraderChatService service =
            new TraderChatService(traderService, modelFactory, planStore, simTradeClient, tradeRecordService,
                    actionService, decisionText, new PromptCatalog());

    /** 唤醒三项：overview 从动作服务取上次/下次唤醒与拦因 */
    private static TraderActionService.WakeState wake(Long lastWakeAt, Long nextWakeAt, String blockedReason) {
        return new TraderActionService.WakeState(lastWakeAt, nextWakeAt, blockedReason);
    }

    private AiTrader running() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(ME);
        t.setName("测试员");
        t.setStatus(AiTrader.STATUS_RUNNING);
        t.setRoundNo(1);
        t.setIntervalCode("1h");
        t.setSymbols("BTCUSDT");
        t.setLeverageMin(3);
        t.setLeverageMax(20);
        t.setMarginPctMax(new BigDecimal("20"));
        t.setMemory("教训：别追高");
        return t;
    }

    private static JsonNode parse(String json) {
        return MAPPER.readTree(json);
    }

    /** 五个查询都带上 trader 名字：模型清楚看的是哪一个，下次能直接点名 */
    @ParameterizedTest
    @ValueSource(strings = {"overview", "positions", "decisions", "plans", "trades"})
    void 五个查询都带trader名字(String which) {
        // 各查询依赖的列表 mock 默认就返回空集合
        when(traderService.latestEquity(any())).thenReturn(new BigDecimal("10500"));
        when(actionService.wakeState(any(), any())).thenReturn(wake(null, null, null));

        JsonNode out = parse(switch (which) {
            case "overview" -> service.overview(running(), AgentLang.ZH);
            case "positions" -> service.positions(running(), AgentLang.ZH);
            case "decisions" -> service.decisions(running(), null, null, AgentLang.ZH);
            case "trades" -> service.trades(running(), null, AgentLang.ZH);
            default -> service.plans(running(), AgentLang.ZH);
        });

        assertThat(out.path("hasTrader").asBoolean(false)).isTrue();
        assertThat(out.path("trader").asString(null)).isEqualTo("测试员");
    }

    /** trader_list：名下每个一行（名字、状态、本局收益率、心跳、币种、模型）；名下没有就回"还没有" */
    @Test
    void trader_list列出名下全部() {
        AiTrader beta = running();
        beta.setId(8L);
        beta.setName("Beta");
        beta.setStatus(AiTrader.STATUS_PAUSED);
        when(traderService.listMine(ME)).thenReturn(List.of(running(), beta));
        when(traderService.listMine(OTHERS)).thenReturn(List.of());
        when(traderService.latestEquity(any())).thenReturn(new BigDecimal("10500"));
        UserLlmEndpoint endpoint = new UserLlmEndpoint();
        endpoint.setModel("gpt-5");
        when(modelFactory.endpointFor(any())).thenReturn(endpoint);

        JsonNode rows = parse(service.list(ME, AgentLang.ZH)).get("traders");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).path("name").asString(null)).isEqualTo("测试员");
        assertThat(rows.get(0).path("pnlPct").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(rows.get(0).path("intervalCode").asString(null)).isEqualTo("1h");
        assertThat(rows.get(0).path("symbols").asString(null)).isEqualTo("BTCUSDT");
        assertThat(rows.get(0).path("model").asString(null)).isEqualTo("gpt-5");
        assertThat(rows.get(1).path("status").asString(null)).isEqualTo(AiTrader.STATUS_PAUSED);
        assertThat(parse(service.list(OTHERS, AgentLang.ZH)).path("hasTrader").asBoolean(true)).isFalse();
    }

    /** 点名点不中的回执：对话里把名下名单一并给回去；指令生成器里说清只能查哪一个 */
    @Test
    void 点名不中的回执带名字() {
        AiTrader beta = running();
        beta.setName("Beta");

        JsonNode out = parse(service.unknownName("Gamma", List.of(running(), beta), AgentLang.ZH));
        JsonNode only = parse(service.onlyThis(running(), AgentLang.ZH));

        assertThat(out.path("error").asString(null)).contains("Gamma");
        assertThat(out.get("traders")).extracting(JsonNode::asString).containsExactly("测试员", "Beta");
        assertThat(only.path("error").asString(null)).contains("测试员");
    }

    /** overview 带唤醒三项，按工具语言取拦因；暂停的 trader 不给下次例行时刻（例行唤醒不会发生） */
    @Test
    void overview带上次下次唤醒与拦因_暂停不给下次时刻() {
        AiTrader paused = running();
        paused.setStatus(AiTrader.STATUS_PAUSED);
        when(actionService.wakeState(paused, AgentLang.EN)).thenReturn(wake(1_000L, 2_000L, "paused"));

        JsonNode out = parse(service.overview(paused, AgentLang.EN));

        assertThat(out.path("lastWakeAt").isString()).isTrue();
        assertThat(out.has("nextWakeAt")).isFalse();   // MAPPER 不序列化 null 字段
        assertThat(out.path("wakeBlockedReason").asString(null)).isEqualTo("paused");
    }

    /** kind 过滤落到查询层，大小写不敏感；空=全部；不认识的值回说明而不是空名单 */
    @Test
    void decisions按kind过滤() {
        service.decisions(running(), 3, "review", AgentLang.ZH);
        service.decisions(running(), null, null, AgentLang.ZH);
        JsonNode unknown = parse(service.decisions(running(), null, "RETRO", AgentLang.ZH));

        verify(traderService).decisions(7L, 3, null, null, null, null, List.of("REVIEW"));
        verify(traderService).decisions(7L, TraderChatService.DEFAULT_DECISIONS, null, null, null, null, null);
        assertThat(unknown.path("error").asString(null)).contains("RETRO");
        assertThat(unknown.has("decisions")).isFalse();
    }

    /** trades：stale 那笔不进教材；count/wins/totalClosedPnl 只算返回的几笔；主动平仓带它写的理由 */
    @Test
    void trades滤stale并汇总返回的几笔() {
        AiTraderPlan ignored = new AiTraderPlan();
        ignored.setStale(true);
        AiTraderPlan kept = new AiTraderPlan();
        kept.setPlayType("BREAKOUT");
        kept.setInvalidationCondition("跌回 95");
        TradeRecordService.DecisionRef close = new TradeRecordService.DecisionRef(9L, 4_000L,
                AiTraderDecision.KIND_TRADE, "全文", "失效条件触发");
        when(tradeRecordService.closedTrades(any())).thenReturn(List.of(
                trade(1L, "30", ignored, null),
                trade(2L, "12.5", kept, close),
                trade(3L, "-4", null, null)));

        JsonNode out = parse(service.trades(running(), null, AgentLang.ZH));

        assertThat(out.path("count").asInt()).isEqualTo(2);
        assertThat(out.path("wins").asInt()).isEqualTo(1);
        assertThat(out.path("totalClosedPnl").decimalValue()).isEqualByComparingTo("8.5");
        JsonNode first = out.get("trades").get(0);
        assertThat(first.path("playType").asString(null)).isEqualTo("BREAKOUT");
        assertThat(first.path("closeManner").asString(null)).isEqualTo("manual");
        assertThat(first.path("closedByPreset").asBoolean()).isFalse();
        assertThat(first.path("closeReason").asString(null)).isEqualTo("失效条件触发");
        // 没配到计划的那笔照给，只是没有论点字段
        assertThat(out.get("trades").get(1).has("playType")).isFalse();
    }

    /** 它布的预案平掉的：标出来，理由给那一行预案执行（条件、备注、结果），不是空着 */
    @Test
    void trades预案平仓标出来并带预案那一行() {
        TradeRecordService.DecisionRef preset = new TradeRecordService.DecisionRef(9L, 4_000L,
                AiTraderDecision.KIND_PRESET, "BTCUSDT 跌破 95：已市价平掉", null);
        when(tradeRecordService.closedTrades(any())).thenReturn(List.of(trade(2L, "12.5", null, preset)));

        JsonNode first = parse(service.trades(running(), null, AgentLang.ZH)).get("trades").get(0);

        assertThat(first.path("closedByPreset").asBoolean()).isTrue();
        assertThat(first.path("closeReason").asString(null)).isEqualTo("BTCUSDT 跌破 95：已市价平掉");
    }

    private static TradeRecordService.TradeRecord trade(long id, String pnl, AiTraderPlan plan,
                                                        TradeRecordService.DecisionRef close) {
        return new TradeRecordService.TradeRecord(id, "BTCUSDT", "LONG", 5, new BigDecimal("100"),
                new BigDecimal("100").add(new BigDecimal(pnl)), new BigDecimal(pnl), 1_000L, 2_000L,
                "manual", plan, null, close, null, false, false, null, null, null);
    }

    // ==================== stale：忽略的交易从 chat 教材消失（口径3：chat 跟随忽略） ====================

    /** 一醒来就落库的一轮：落库时刻和 wake_time 同一刻 */
    private static AiTraderDecision decision(long wakeTime, String reasoning, String actionsJson) {
        AiTraderDecision d = new AiTraderDecision();
        d.setWakeTime(wakeTime);
        d.setCreatedAt(TradePairing.localOf(wakeTime));
        d.setKind(AiTraderDecision.KIND_TRADE);
        d.setStatus(AiTraderDecision.STATUS_OK);
        d.setReasoning(reasoning);
        d.setActionsJson(actionsJson);
        return d;
    }

    /**
     * decisions：新格式剔 stale 分段、错误格式落在 stale 生命期内整行剔——与复盘时间线同一套识别逻辑；
     * 只处理别的币的轮，错误格式也不为这个币的计划整行剔
     */
    @Test
    void decisions剔stale分段与错误格式整轮() {
        AiTraderPlan stale = new AiTraderPlan();
        stale.setSymbol("BTCUSDT");
        stale.setSide("LONG");
        stale.setStatus(AiTraderPlan.STATUS_CLOSED);
        stale.setStale(true);
        stale.setOpenedWakeTime(1000L);
        stale.setClosedWakeTime(5000L);
        stale.setPositionId(42L);
        when(planStore.listAll(7L, 1)).thenReturn(List.of(stale));
        AiTraderDecision segmented = decision(2000L,
                "[本轮结论]\n[BTCUSDT]\n动作：HOLD\n等待：回踩再看\n[ETHUSDT]\n动作：HOLD\n等待：跌破 1888 转空",
                // 同轮给 stale 仓位调过止损：工具名也得剔，数据工具照常
                "[{\"tool\":\"set_stop_loss\",\"args\":{\"positionId\":42,\"stopLossPrice\":99000},\"status\":\"ok\"},"
                        + "{\"tool\":\"klines\"}]");
        // 没分段的平仓轮：落在生命期 [1000, 5000] 内，整行不出
        AiTraderDecision unsegmentedClose = decision(4000L, "[本轮结论]\n动作：平仓\n等待：无",
                "[{\"tool\":\"close_position\",\"args\":{\"positionId\":42},\"status\":\"ok\"}]");
        // 只处理 SOL 的轮也没分段、也落在生命期里：和 BTC 那笔无关，照出
        AiTraderDecision solOnly = decision(4500L, "[本轮结论]\n动作：观望\n等待：无", null);
        solOnly.setSymbol("SOLUSDT");
        when(traderService.decisions(eq(7L), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(segmented, unsegmentedClose, solOnly));

        JsonNode out = parse(service.decisions(running(), null, null, AgentLang.ZH)).get("decisions");

        assertThat(out).hasSize(2);
        String reasoning = out.get(0).path("reasoning").asString(null);
        assertThat(reasoning).doesNotContain("BTCUSDT").contains("[ETHUSDT]").contains("跌破 1888");
        assertThat(out.get(0).get("tools")).extracting(JsonNode::asString).containsExactly("klines");
        assertThat(out.get(1).path("symbol").asString(null)).isEqualTo("SOLUSDT");
    }

    /**
     * 开仓之前就醒、跑到开仓之后才落库的那一轮，写的也是被忽略的那笔：按它实际跑的时段和计划生命期有没有交集判，不按醒来那一刻。
     * 决策列表带上只处理哪个币
     */
    @Test
    void decisions按一轮实际跑的时段剔stale分段() {
        AiTraderPlan stale = new AiTraderPlan();
        stale.setSymbol("BTCUSDT");
        stale.setSide("LONG");
        stale.setStatus(AiTraderPlan.STATUS_CLOSED);
        stale.setStale(true);
        stale.setOpenedWakeTime(1_785_000_600_000L);
        stale.setClosedWakeTime(1_785_003_600_000L);
        when(planStore.listAll(7L, 1)).thenReturn(List.of(stale));
        // 开仓前 5 分钟醒来、开仓后 5 分钟落库
        AiTraderDecision single = decision(1_785_000_300_000L,
                "[本轮结论]\n[BTCUSDT]\n动作：跟着那笔多单移止损\n等待：无", "[]");
        single.setSymbol("BTCUSDT");
        single.setCreatedAt(TradePairing.localOf(1_785_000_900_000L));
        when(traderService.decisions(eq(7L), anyInt(), any(), any(), any(), any(), any())).thenReturn(List.of(single));

        JsonNode out = parse(service.decisions(running(), null, null, AgentLang.ZH)).get("decisions");

        assertThat(out.get(0).path("reasoning").asString(null)).doesNotContain("跟着那笔多单");
        assertThat(out.get(0).path("symbol").asString(null)).isEqualTo("BTCUSDT");
    }

    /** 没做成的交易动作，工具名后面带状态：不带的话被拒的、没执行的看起来和做成的一样 */
    @Test
    void 没做成的动作工具名带状态() {
        when(planStore.listAll(7L, 1)).thenReturn(List.of());
        AiTraderDecision d = decision(2000L, "[本轮结论]\n动作：HOLD",
                "[{\"tool\":\"klines\"},"
                        + "{\"tool\":\"close_position\",\"args\":{\"positionId\":1},\"status\":\"ok\"},"
                        + "{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\"},\"rejected\":\"杠杆越界\"},"
                        + "{\"tool\":\"set_stop_loss\",\"args\":{\"positionId\":1},\"status\":\"not_attempted\"}]");
        when(traderService.decisions(eq(7L), anyInt(), any(), any(), any(), any(), any())).thenReturn(List.of(d));

        JsonNode out = parse(service.decisions(running(), null, null, AgentLang.ZH)).get("decisions");

        assertThat(out.get(0).get("tools")).extracting(JsonNode::asString).containsExactly(
                "klines", "close_position", "open_position(rejected)", "set_stop_loss(not_attempted)");
    }

    @Test
    void 反手旧仓被忽略仍保留新仓动作() {
        AiTraderPlan old = new AiTraderPlan();
        old.setPositionId(42L);
        old.setSymbol("BTCUSDT");
        old.setSide("LONG");
        old.setStale(true);
        AiTraderPlan next = new AiTraderPlan();
        next.setSymbol("BTCUSDT");
        next.setSide("SHORT");
        next.setOpenedWakeTime(2000L);
        JsonNode action = MAPPER.readTree("""
                {"tool":"reverse_position","args":{"positionId":42},"result":{
                  "close":{"tool":"close_position","args":{"positionId":42}},
                  "open":{"tool":"open_position","args":{"symbol":"BTCUSDT","side":"SHORT"}}
                }}
                """);
        assertThat(DecisionText.staleAction(action, 2000L, List.of(old, next))).isFalse();
        next.setStale(true);
        assertThat(DecisionText.staleAction(action, 2000L, List.of(old, next))).isTrue();
    }

    /** plans：recentClosedPlans 滤 stale，宁缺不顶替 */
    @Test
    void plans滤掉stale的最近归档计划() {
        AiTraderPlan ignored = new AiTraderPlan();
        ignored.setSymbol("BTCUSDT");
        ignored.setSide("LONG");
        ignored.setStatus(AiTraderPlan.STATUS_CLOSED);
        ignored.setStale(true);
        ignored.setOpenedWakeTime(1000L);
        AiTraderPlan kept = new AiTraderPlan();
        kept.setSymbol("ETHUSDT");
        kept.setSide("SHORT");
        kept.setStatus(AiTraderPlan.STATUS_CLOSED);
        kept.setOpenedWakeTime(2000L);
        when(planStore.recentClosed(7L, 1, 5)).thenReturn(List.of(ignored, kept));

        JsonNode closed = parse(service.plans(running(), AgentLang.ZH)).get("recentClosedPlans");

        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).path("symbol").asString(null)).isEqualTo("ETHUSDT");
    }
}
