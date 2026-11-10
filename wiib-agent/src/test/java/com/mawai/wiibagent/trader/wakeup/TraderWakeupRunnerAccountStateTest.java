package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.learning.CaseFileBuilder;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账户状态 JSON：挂单段带上各自的挂出时刻与已挂时长；持仓行带杠杆/标记价/强平价；修订史时间是时刻不是毫秒。
 * 挂单回注若只有 playType/失效条件，模型无从知道这张限价单挂了多久——
 * 线上一张限价单挂 5 小时后在瀑布里成交、2 分钟止损，就是这个信息缺口。
 */
class TraderWakeupRunnerAccountStateTest {

    private static final long BOUNDARY = 1_787_000_400_000L;
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final PromptCatalog prompts = new PromptCatalog();

    /** 挂出时刻按订单自己的 createdAt，不按计划的（同键几张挂单共用一份计划） */
    @Test
    void pendingOpenOrderCarriesPlacedAtAndPendingFor() {
        long placed = BOUNDARY - (5 * 60 + 19) * 60_000L;
        AiTraderPlan plan = new AiTraderPlan();
        plan.setSymbol("BTCUSDT");
        plan.setSide("LONG");
        plan.setPlayType("BREAKOUT");
        plan.setInvalidationCondition("15m 收盘跌破 79420");
        plan.setOpenedWakeTime(placed - 3600_000L);

        FuturesOrderResponse order = new FuturesOrderResponse();
        order.setOrderId(112L);
        order.setSymbol("BTCUSDT");
        order.setOrderSide("OPEN_LONG");
        order.setQuantity(new BigDecimal("1.36"));
        order.setLimitPrice(new BigDecimal("79970"));
        order.setLeverage(50);
        order.setCreatedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(placed), ZoneId.systemDefault()));

        String json = WakeAccountState.accountStateJson(prompts, AgentLang.ZH, new BigDecimal("15833"),
                List.of(), List.of(order), List.of(plan), BOUNDARY, Map.of());

        JsonNode planJson = MAPPER.readTree(json).get("pendingOrders")
                .get(0).get("plan");
        assertThat(planJson.path("placedAt").asString(null)).isEqualTo(FMT.format(Instant.ofEpochMilli(placed)));
        assertThat(planJson.path("pendingFor").asString(null))
                .isEqualTo(prompts.get(AgentLang.ZH, "trader.wake.held.hours", Map.of("n", 5L)));
    }

    /** 持仓行带杠杆/标记价/强平价（同币杠杆一致的硬规则要看得见现有杠杆），权益两位小数 */
    @Test
    void positionRowCarriesLeverageMarkAndLiquidation() {
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setId(349L);
        p.setSymbol("BTCUSDT");
        p.setSide("LONG");
        p.setQuantity(new BigDecimal("0.2"));
        p.setEntryPrice(new BigDecimal("63500"));
        p.setLeverage(10);
        p.setMarkPrice(new BigDecimal("64000"));
        p.setLiquidationPrice(new BigDecimal("58000"));
        p.setUnrealizedPnl(new BigDecimal("100"));

        String json = WakeAccountState.accountStateJson(prompts, AgentLang.ZH, new BigDecimal("15833"),
                List.of(p), List.of(), List.of(), BOUNDARY, Map.of());

        assertThat(json).contains("\"leverage\":10").contains("\"markPrice\":64000")
                .contains("\"liquidationPrice\":58000").contains("\"equity\":15833.00");
    }

    /**
     * 风险事实：浮盈几 R 按计划原始止损算；离止损多远按标记价；止损打掉亏权益百分之几按当前止损单，
     * 止损挪过成本的是负数；合计一行在顶层
     */
    @Test
    void positionRowCarriesRiskFacts() {
        FuturesPositionDTO longPos = riskPosition(349L, "BTCUSDT", "LONG", "100", "104", "98", "10");
        FuturesPositionDTO shortPos = riskPosition(350L, "ETHUSDT", "SHORT", "50", "48", "49", "4");
        AiTraderPlan plan = new AiTraderPlan();
        plan.setSymbol("BTCUSDT");
        plan.setSide("LONG");
        plan.setStopLossPrice(new BigDecimal("96"));
        plan.setOpenedWakeTime(BOUNDARY - 3600_000L);

        JsonNode out = MAPPER.readTree(WakeAccountState.accountStateJson(prompts, AgentLang.ZH, new BigDecimal("10000"),
                List.of(longPos, shortPos), List.of(), List.of(plan), BOUNDARY, Map.of()));

        JsonNode btc = out.get("positions").get(0);
        assertThat(btc.get("unrealizedR").decimalValue()).isEqualByComparingTo("1.00");
        assertThat(btc.get("stopDistancePct").decimalValue()).isEqualByComparingTo("5.77");
        assertThat(btc.get("lossAtStopPctOfEquity").decimalValue()).isEqualByComparingTo("0.20");
        JsonNode eth = out.get("positions").get(1);
        assertThat(eth.has("unrealizedR")).as("没有计划的仓位不带 R").isFalse();
        assertThat(eth.get("lossAtStopPctOfEquity").decimalValue()).isEqualByComparingTo("-0.04");
        assertThat(out.get("lossAtAllStopsPctOfEquity").decimalValue()).isEqualByComparingTo("0.16");
    }

    private static FuturesPositionDTO riskPosition(long id, String symbol, String side, String entry, String mark,
                                                   String stop, String qty) {
        FuturesStopLoss sl = new FuturesStopLoss();
        sl.setPrice(new BigDecimal(stop));
        sl.setQuantity(new BigDecimal(qty));
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setId(id);
        p.setSymbol(symbol);
        p.setSide(side);
        p.setQuantity(new BigDecimal(qty));
        p.setEntryPrice(new BigDecimal(entry));
        p.setMarkPrice(new BigDecimal(mark));
        p.setStopLosses(List.of(sl));
        p.setCreatedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(BOUNDARY - 3600_000L), ZoneId.systemDefault()));
        return p;
    }

    /** 持仓的开仓时刻和持有时长按 sim 仓位的 createdAt，不按计划的 openedWakeTime（限价挂出到成交隔着时间） */
    @Test
    void positionOpenedAtUsesSimCreatedAt() {
        long opened = BOUNDARY - 2 * 3600_000L;
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setId(349L);
        p.setSymbol("BTCUSDT");
        p.setSide("LONG");
        p.setCreatedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(opened), ZoneId.systemDefault()));
        AiTraderPlan plan = new AiTraderPlan();
        plan.setSymbol("BTCUSDT");
        plan.setSide("LONG");
        plan.setOpenedWakeTime(BOUNDARY - 5 * 3600_000L);

        String json = WakeAccountState.accountStateJson(prompts, AgentLang.ZH, new BigDecimal("15833"),
                List.of(p), List.of(), List.of(plan), BOUNDARY, Map.of());

        JsonNode planJson = MAPPER.readTree(json).get("positions").get(0).get("plan");
        assertThat(planJson.path("openedAt").asString(null)).isEqualTo(FMT.format(Instant.ofEpochMilli(opened)));
        assertThat(planJson.path("heldFor").asString(null))
                .isEqualTo(prompts.get(AgentLang.ZH, "trader.wake.held.hours", Map.of("n", 2L)));
    }

    /** 修订史的 time 库里是 epoch 毫秒，注入时转成时刻 */
    @Test
    void revisionTimeRenderedAsClock() {
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setId(349L);
        p.setSymbol("BTCUSDT");
        p.setSide("LONG");
        p.setQuantity(new BigDecimal("0.2"));
        p.setEntryPrice(new BigDecimal("63500"));
        AiTraderPlan plan = new AiTraderPlan();
        plan.setSymbol("BTCUSDT");
        plan.setSide("LONG");
        plan.setPlayType("REVERSAL");
        plan.setOpenedWakeTime(BOUNDARY - 3600_000L);
        plan.setRevisionsJson("[{\"time\":1785169800000,\"type\":\"移动止盈\",\"change\":\"66000→68000\",\"reason\":\"趋势加速\"}]");

        String json = WakeAccountState.accountStateJson(prompts, AgentLang.ZH, new BigDecimal("15833"),
                List.of(p), List.of(), List.of(plan), BOUNDARY, Map.of());

        assertThat(json).contains(FMT.format(Instant.ofEpochMilli(1785169800000L)))
                .doesNotContain("1785169800000");
    }

    /** 持仓行带 sinceLastWake：按 positionId 对上，时刻是时刻不是毫秒，涨跌两位小数；没算出来的持仓不带 */
    @Test
    void positionRowCarriesSinceLastWake() {
        long m5 = 300_000L;
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setId(349L);
        p.setSymbol("BTCUSDT");
        p.setSide("LONG");
        FuturesPositionDTO other = new FuturesPositionDTO();
        other.setId(350L);
        other.setSymbol("ETHUSDT");
        other.setSide("SHORT");
        CaseFileBuilder.MarketWindow w = new CaseFileBuilder.MarketWindow("BTCUSDT", BOUNDARY - 3 * m5, BOUNDARY,
                new BigDecimal("100190"), new BigDecimal("100420"), BOUNDARY - 2 * m5, new BigDecimal("99610"),
                BOUNDARY - m5, new BigDecimal("99880"));

        String json = WakeAccountState.accountStateJson(prompts, AgentLang.ZH, new BigDecimal("15833"),
                List.of(p, other), List.of(), List.of(), BOUNDARY, Map.of(349L, w));

        assertThat(json).contains("\"sinceLastWake\":{\"from\":\"" + FMT.format(Instant.ofEpochMilli(BOUNDARY - 3 * m5))
                + "\",\"to\":\"" + FMT.format(Instant.ofEpochMilli(BOUNDARY))
                + "\",\"open\":100190,\"high\":100420,\"highAt\":\"" + FMT.format(Instant.ofEpochMilli(BOUNDARY - 2 * m5))
                + "\",\"low\":99610,\"lowAt\":\"" + FMT.format(Instant.ofEpochMilli(BOUNDARY - m5))
                + "\",\"close\":99880,\"changePct\":-0.31}");
        assertThat(MAPPER.readTree(json).get("positions").get(1).has("sinceLastWake")).isFalse();
        // set_watch 的 AT_TIME 按它写
        assertThat(MAPPER.readTree(json).get("nowBeijing").asString(null)).isEqualTo(BeijingTime.format(BOUNDARY));
    }
}
