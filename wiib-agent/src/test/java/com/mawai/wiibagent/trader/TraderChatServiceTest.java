package com.mawai.wiibagent.trader;

import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
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
 * 这里钉的核心是<b>归属</b>：工具签名里没有用户参数，谁的 trader 全靠这一层按 userId 取。
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
        t.setMarginPctMin(new BigDecimal("5"));
        t.setMarginPctMax(new BigDecimal("20"));
        t.setMemory("教训：别追高");
        return t;
    }

    private static JsonNode parse(String json) {
        return MAPPER.readTree(json);
    }

    /**
     * 每个查询都只按"当前是谁"取 trader。工具签名里没有用户参数，这一层再按 userId 取一次，
     * 越权就无从谈起——而这条测试正是那句话的凭证。
     */
    @ParameterizedTest
    @ValueSource(strings = {"overview", "positions", "decisions", "plans", "trades"})
    void 查询只认自己的trader(String which) {
        // 各查询依赖的列表 mock 默认就返回空集合，这里只关心"取的是谁的 trader"
        when(traderService.mine(ME)).thenReturn(running());
        when(traderService.mine(OTHERS)).thenReturn(null);
        when(traderService.latestEquity(any())).thenReturn(new BigDecimal("10500"));
        when(actionService.wakeState(any(), any())).thenReturn(wake(null, null, null));

        assertThat(parse(call(which, ME)).path("hasTrader").asBoolean(false)).isTrue();
        // 别人的 userId 拿不到任何东西，而不是拿到我的
        assertThat(parse(call(which, OTHERS)).path("hasTrader").asBoolean(false)).isFalse();
    }

    private String call(String which, long userId) {
        return switch (which) {
            case "overview" -> service.overview(userId, AgentLang.ZH);
            case "positions" -> service.positions(userId, AgentLang.ZH);
            case "decisions" -> service.decisions(userId, null, null, AgentLang.ZH);
            case "trades" -> service.trades(userId, null, AgentLang.ZH);
            default -> service.plans(userId, AgentLang.ZH);
        };
    }

    /** overview 带唤醒三项，按工具语言取拦因；暂停的 trader 不给下次例行时刻（例行唤醒不会发生） */
    @Test
    void overview带上次下次唤醒与拦因_暂停不给下次时刻() {
        AiTrader paused = running();
        paused.setStatus(AiTrader.STATUS_PAUSED);
        when(traderService.mine(ME)).thenReturn(paused);
        when(actionService.wakeState(paused, AgentLang.EN)).thenReturn(wake(1_000L, 2_000L, "paused"));

        JsonNode out = parse(service.overview(ME, AgentLang.EN));

        assertThat(out.path("lastWakeAt").isString()).isTrue();
        assertThat(out.has("nextWakeAt")).isFalse();   // MAPPER 不序列化 null 字段
        assertThat(out.path("wakeBlockedReason").asString(null)).isEqualTo("paused");
    }

    /** kind 过滤落到查询层，大小写不敏感；空=全部；不认识的值回说明而不是空名单 */
    @Test
    void decisions按kind过滤() {
        when(traderService.mine(ME)).thenReturn(running());

        service.decisions(ME, 3, "review", AgentLang.ZH);
        service.decisions(ME, null, null, AgentLang.ZH);
        JsonNode unknown = parse(service.decisions(ME, null, "RETRO", AgentLang.ZH));

        verify(traderService).decisions(7L, 3, null, null, null, null, "REVIEW");
        verify(traderService).decisions(7L, TraderChatService.DEFAULT_DECISIONS, null, null, null, null, null);
        assertThat(unknown.path("error").asString(null)).contains("RETRO");
        assertThat(unknown.has("decisions")).isFalse();
    }

    /** trades：stale 那笔不进教材；count/wins/totalClosedPnl 只算返回的几笔；主动平仓带它写的理由 */
    @Test
    void trades滤stale并汇总返回的几笔() {
        when(traderService.mine(ME)).thenReturn(running());
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

        JsonNode out = parse(service.trades(ME, null, AgentLang.ZH));

        assertThat(out.path("count").asInt()).isEqualTo(2);
        assertThat(out.path("wins").asInt()).isEqualTo(1);
        assertThat(out.path("totalClosedPnl").decimalValue()).isEqualByComparingTo("8.5");
        JsonNode first = out.get("trades").get(0);
        assertThat(first.path("playType").asString(null)).isEqualTo("BREAKOUT");
        assertThat(first.path("closeManner").asString(null)).isEqualTo("manual");
        assertThat(first.path("closeReason").asString(null)).isEqualTo("失效条件触发");
        // 没配到计划的那笔照给，只是没有论点字段
        assertThat(out.get("trades").get(1).has("playType")).isFalse();
    }

    private static TradeRecordService.TradeRecord trade(long id, String pnl, AiTraderPlan plan,
                                                        TradeRecordService.DecisionRef close) {
        return new TradeRecordService.TradeRecord(id, "BTCUSDT", "LONG", 5, new BigDecimal("100"),
                new BigDecimal("100").add(new BigDecimal(pnl)), new BigDecimal(pnl), 1_000L, 2_000L,
                "manual", plan, null, close);
    }

    // ==================== stale：忽略的交易从 chat 教材消失（口径3：chat 跟随忽略） ====================

    private static AiTraderDecision decision(long wakeTime, String reasoning, String actionsJson) {
        AiTraderDecision d = new AiTraderDecision();
        d.setWakeTime(wakeTime);
        d.setKind(AiTraderDecision.KIND_TRADE);
        d.setStatus(AiTraderDecision.STATUS_OK);
        d.setReasoning(reasoning);
        d.setActionsJson(actionsJson);
        return d;
    }

    /** decisions：新格式剔 stale 分段、错误格式落在 stale 生命期内整行剔——与复盘时间线同一套识别逻辑 */
    @Test
    void decisions剔stale分段与错误格式整轮() {
        when(traderService.mine(ME)).thenReturn(running());
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
        when(traderService.decisions(eq(7L), anyInt(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(segmented, unsegmentedClose));

        JsonNode out = parse(service.decisions(ME, null, null, AgentLang.ZH)).get("decisions");

        assertThat(out).hasSize(1);
        String reasoning = out.get(0).path("reasoning").asString(null);
        assertThat(reasoning).doesNotContain("BTCUSDT").contains("[ETHUSDT]").contains("跌破 1888");
        assertThat(out.get(0).get("tools")).extracting(JsonNode::asString).containsExactly("klines");
    }

    /** plans：recentClosedPlans 滤 stale，宁缺不顶替 */
    @Test
    void plans滤掉stale的最近归档计划() {
        when(traderService.mine(ME)).thenReturn(running());
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

        JsonNode closed = parse(service.plans(ME, AgentLang.ZH)).get("recentClosedPlans");

        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).path("symbol").asString(null)).isEqualTo("ETHUSDT");
    }
}
