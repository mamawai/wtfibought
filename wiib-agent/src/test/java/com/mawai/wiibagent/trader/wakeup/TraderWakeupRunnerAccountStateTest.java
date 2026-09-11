package com.mawai.wiibagent.trader.wakeup;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账户状态 JSON：挂单段随计划带上挂出时刻与已挂时长；持仓行带杠杆/标记价/强平价；修订史时间是时刻不是毫秒。
 * 挂单回注若只有 playType/失效条件，模型无从知道这张限价单挂了多久——
 * 线上一张限价单挂 5 小时后在瀑布里成交、2 分钟止损，就是这个信息缺口。
 */
class TraderWakeupRunnerAccountStateTest {

    private static final long BOUNDARY = 1_787_000_400_000L;
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final PromptCatalog prompts = new PromptCatalog();

    @Test
    void pendingOpenOrderCarriesPlacedAtAndPendingFor() {
        long placed = BOUNDARY - (5 * 60 + 19) * 60_000L;
        AiTraderPlan plan = new AiTraderPlan();
        plan.setSymbol("BTCUSDT");
        plan.setSide("LONG");
        plan.setPlayType("BREAKOUT");
        plan.setInvalidationCondition("15m 收盘跌破 79420");
        plan.setOpenedWakeTime(placed);

        FuturesOrderResponse order = new FuturesOrderResponse();
        order.setOrderId(112L);
        order.setSymbol("BTCUSDT");
        order.setOrderSide("OPEN_LONG");
        order.setQuantity(new BigDecimal("1.36"));
        order.setLimitPrice(new BigDecimal("79970"));
        order.setLeverage(50);

        String json = TraderWakeupRunner.accountStateJson(prompts, AgentLang.ZH, new BigDecimal("15833"),
                List.of(), List.of(order), List.of(plan), BOUNDARY);

        JSONObject planJson = JSON.parseObject(json).getJSONArray("pendingOrders")
                .getJSONObject(0).getJSONObject("plan");
        assertThat(planJson.getString("placedAt")).isEqualTo(FMT.format(Instant.ofEpochMilli(placed)));
        assertThat(planJson.getString("pendingFor"))
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

        String json = TraderWakeupRunner.accountStateJson(prompts, AgentLang.ZH, new BigDecimal("15833"),
                List.of(p), List.of(), List.of(), BOUNDARY);

        assertThat(json).contains("\"leverage\":10").contains("\"markPrice\":64000")
                .contains("\"liquidationPrice\":58000").contains("\"equity\":15833.00");
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

        String json = TraderWakeupRunner.accountStateJson(prompts, AgentLang.ZH, new BigDecimal("15833"),
                List.of(p), List.of(), List.of(plan), BOUNDARY);

        assertThat(json).contains(FMT.format(Instant.ofEpochMilli(1785169800000L)))
                .doesNotContain("1785169800000");
    }
}
