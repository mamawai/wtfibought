package com.mawai.wiibagent.trader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
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

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
    /** stale 过滤走真实实现：chat 面的剔段/剔轮断言要打在真逻辑上 */
    private final DecisionText decisionText = new DecisionText(new PromptCatalog());

    private final TraderChatService service =
            new TraderChatService(traderService, modelFactory, planStore, simTradeClient, decisionText,
                    new PromptCatalog());

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

    private static JSONObject parse(String json) {
        return JSON.parseObject(json);
    }

    /**
     * 每个查询都只按"当前是谁"取 trader。工具签名里没有用户参数，这一层再按 userId 取一次，
     * 越权就无从谈起——而这条测试正是那句话的凭证。
     */
    @ParameterizedTest
    @ValueSource(strings = {"overview", "positions", "decisions", "plans"})
    void 查询只认自己的trader(String which) {
        // 各查询依赖的列表 mock 默认就返回空集合，这里只关心"取的是谁的 trader"
        when(traderService.mine(ME)).thenReturn(running());
        when(traderService.mine(OTHERS)).thenReturn(null);
        when(traderService.latestEquity(any())).thenReturn(new BigDecimal("10500"));

        assertThat(parse(call(which, ME)).getBooleanValue("hasTrader")).isTrue();
        // 别人的 userId 拿不到任何东西，而不是拿到我的
        assertThat(parse(call(which, OTHERS)).getBooleanValue("hasTrader")).isFalse();
    }

    private String call(String which, long userId) {
        return switch (which) {
            case "overview" -> service.overview(userId, AgentLang.ZH);
            case "positions" -> service.positions(userId, AgentLang.ZH);
            case "decisions" -> service.decisions(userId, null, AgentLang.ZH);
            default -> service.plans(userId, AgentLang.ZH);
        };
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
        when(traderService.decisions(eq(7L), anyInt(), any(), any(), any(), any()))
                .thenReturn(List.of(segmented, unsegmentedClose));

        JSONArray out = parse(service.decisions(ME, null, AgentLang.ZH)).getJSONArray("decisions");

        assertThat(out).hasSize(1);
        String reasoning = out.getJSONObject(0).getString("reasoning");
        assertThat(reasoning).doesNotContain("BTCUSDT").contains("[ETHUSDT]").contains("跌破 1888");
        assertThat(out.getJSONObject(0).getJSONArray("tools")).containsExactly("klines");
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

        JSONArray closed = parse(service.plans(ME, AgentLang.ZH)).getJSONArray("recentClosedPlans");

        assertThat(closed).hasSize(1);
        assertThat(closed.getJSONObject(0).getString("symbol")).isEqualTo("ETHUSDT");
    }
}
