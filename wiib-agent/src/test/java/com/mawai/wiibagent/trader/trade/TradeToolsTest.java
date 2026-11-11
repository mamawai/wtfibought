package com.mawai.wiibagent.trader.trade;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.dto.FuturesStopLossRequest;
import com.mawai.wiibcommon.dto.FuturesTakeProfitRequest;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.mapper.AiTraderWatchMapper;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.context.ApplicationEventPublisher;
import java.util.Arrays;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 交易工具直测：止损只许收紧、止盈分档与可选、预案动作对着账户的校验、计划不可改写只可修订留痕。
 * 这些约束是"计划=事前承诺"的工具层执行——提示词只能劝，工具拒绝才是真禁止。
 */
class TradeToolsTest {

    private static final PromptCatalog PROMPTS = new PromptCatalog();
    private static final MessageCatalog MESSAGES = new MessageCatalog();

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderPlan.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderWatch.class);
    }

    /** 本轮截止时间：默认给足，只有专门验"超时后拒发"的用例才把它设到过去 */
    private static final long DEADLINE = System.currentTimeMillis() + 600_000;

    private final SimTradeClient simTradeClient = mock(SimTradeClient.class);
    private final AiTraderPlanMapper planMapper = mock(AiTraderPlanMapper.class);
    private final AiTraderWatchMapper watchMapper = mock(AiTraderWatchMapper.class);
    private final TraderWatchStore watchStore = new TraderWatchStore(watchMapper, mock(ApplicationEventPublisher.class));
    private final TraderPlanStore planStore = new TraderPlanStore(planMapper, PROMPTS);
    private final TradeTools tools = new TradeTools(simTradeClient, 99L, Set.of("BTCUSDT"), Set.of("BTCUSDT", "ETHUSDT"),
            positions -> new BigDecimal("10000"), sym -> new BigDecimal("100000"),
            planStore, watchStore,
            new TradeTools.WakeCtx(7L, 1, "1h", 1785171600000L, DEADLINE,
                    new TraderRiskConfig(1, 20, new BigDecimal("50"), true, true), AgentLang.ZH),
            PROMPTS, MESSAGES, mock(EntryContext.class));

    /** 多单：入场10万，当前止损9.5万、止盈11万 */
    private FuturesPositionDTO longPosition() {
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setId(5L);
        p.setSymbol("BTCUSDT");
        p.setSide("LONG");
        p.setQuantity(new BigDecimal("0.01"));
        p.setEntryPrice(new BigDecimal("100000"));
        p.setStopLosses(List.of(new FuturesStopLoss("s1", new BigDecimal("95000"), new BigDecimal("0.01"))));
        p.setTakeProfits(List.of(new FuturesTakeProfit("t1", new BigDecimal("110000"), new BigDecimal("0.01"))));
        p.setCreatedAt(LocalDateTime.of(2026, 8, 6, 18, 0));
        return p;
    }

    private AiTraderPlan existingPlan() {
        AiTraderPlan plan = new AiTraderPlan();
        plan.setId(21L);
        plan.setTraderId(7L);
        plan.setRoundNo(1);
        plan.setSymbol("BTCUSDT");
        plan.setSide("LONG");
        plan.setPlayType("BREAKOUT");
        plan.setSignalsUsed("突破前高");
        plan.setInvalidationCondition("1h收盘跌回98000下方");
        plan.setStopLossPrice(new BigDecimal("95000"));
        plan.setTakeProfitsJson("[{\"price\":110000,\"percent\":100}]");
        plan.setOpenedWakeTime(1785168000000L);
        return plan;
    }

    /** 不带预案动作的盯盘条件 */
    private static WatchSpec watch(String kind, Double level, String period, String at, String note) {
        return new WatchSpec(kind, level, period, at, note, null);
    }

    private static TargetRung rung(String price, String percent) {
        return new TargetRung(new BigDecimal(price), new BigDecimal(percent));
    }

    /** 一档 100% 的止盈 */
    private static List<TargetRung> oneRung(String price) {
        return List.of(rung(price, "100"));
    }

    /** 盯盘条件：护栏逐条拒（同一路超过一条、kind、周期、已越过的价位、过去的时刻、白名单），通过的整组落库并进动作轨迹 */
    @Test
    void setWatchGuardAndReplace() {
        // 触及和收盘算同一路，向上、向下、到时刻各最多一条
        assertThat(tools.setWatch("BTCUSDT", List.of(
                watch("TOUCH_ABOVE", 101000.0, null, null, "多"),
                watch("CLOSE_ABOVE", 102000.0, "15m", null, "追"))))
                .startsWith("REJECTED").contains("向上").contains("只能布一条");
        assertThat(tools.setWatch("BTCUSDT", List.of(
                watch("TOUCH_BELOW", 99000.0, null, null, "回踩"),
                watch("CLOSE_BELOW", 98000.0, "1h", null, "破位"))))
                .startsWith("REJECTED").contains("向下");
        assertThat(tools.setWatch("BTCUSDT", List.of(
                watch("AT_TIME", null, null, "2099-01-01 00:00", "公布前"),
                watch("AT_TIME", null, null, "2099-01-01 01:00", "公布后"))))
                .startsWith("REJECTED").contains("到时刻");
        assertThat(tools.setWatch("BTCUSDT", List.of(watch("ABOVE", 101000.0, null, null, "")))).contains("kind 必须是");
        assertThat(tools.setWatch("BTCUSDT", List.of(watch("CLOSE_ABOVE", 101000.0, "3h", null, "")))).contains("period");
        // 现价 100000：TOUCH_ABOVE 99000 已被越过，布下去立刻触发
        assertThat(tools.setWatch("BTCUSDT", List.of(watch("TOUCH_ABOVE", 99000.0, null, null, "")))).contains("已被现价");
        assertThat(tools.setWatch("BTCUSDT", List.of(watch("AT_TIME", null, null, "2020-01-01 00:00", "")))).contains("已过去");
        // 平台没在采的币布不了；不可交易但平台在采的（ETHUSDT）可以，见下面
        assertThat(tools.setWatch("PEPEUSDT", List.of())).startsWith("REJECTED").contains("平台没在采").contains("BTCUSDT, ETHUSDT");
        // 漏传 watches 不是清空，是拒
        assertThat(tools.setWatch("BTCUSDT", null)).startsWith("REJECTED").contains("watches 必填");
        verify(watchMapper, never()).insert(any(AiTraderWatch.class));
        verify(watchMapper, never()).update(any(), any());

        // 向上、向下、到时刻各一条（心跳 1h 布 1h 收盘也行）
        String ok = tools.setWatch("BTCUSDT", List.of(
                watch("TOUCH_ABOVE", 101000.0, null, null, "突破看追多"),
                watch("CLOSE_BELOW", 98000.0, "1h", null, "区间失守"),
                watch("AT_TIME", null, null, "2099-01-01 00:00", "数据公布后复查")));

        assertThat(ok).contains("\"armed\":3");
        verify(watchMapper, times(3)).insert(any(AiTraderWatch.class));
        assertThat(tools.actions()).anySatisfy(a -> {
            assertThat(a.path("tool").asString(null)).isEqualTo("set_watch");
            assertThat(a.path("status").asString(null)).isEqualTo("ok");
            assertThat(a.path("args").path("watches").size()).isEqualTo(3);
        });
        assertThat(tools.setWatch("ETHUSDT", List.of(watch("CLOSE_BELOW", 2000.0, "1h", null, "大盘破位"))))
                .contains("\"armed\":1");
    }

    /**
     * 模型传进来的是 JSON：框架要能把 watches 数组落成 WatchSpec 列表，schema 里也得有字段名。
     * 必填的只有 kind 和 note，action 里只有 type：不带动作的条件不用填 action
     */
    @Test
    void setWatchBindsJsonArgumentsThroughToolCallback() {
        ToolCallback cb = Arrays.stream(ToolCallbacks.from(tools))
                .filter(c -> "set_watch".equals(c.getToolDefinition().name())).findFirst().orElseThrow();
        assertThat(cb.getToolDefinition().inputSchema()).contains("watches").contains("kind").contains("level").contains("note")
                .contains("action").contains("closePercent");
        JsonNode item = MAPPER.readTree(cb.getToolDefinition().inputSchema()).at("/properties/watches/items");
        assertThat(item.path("required")).extracting(JsonNode::asString).containsExactlyInAnyOrder("kind", "note");
        assertThat(item.at("/properties/action/required")).extracting(JsonNode::asString).containsExactly("type");
        assertThat(item.at("/properties/action/description").asString()).contains("Leave it out");

        String r = cb.call("{\"symbol\":\"BTCUSDT\",\"watches\":[{\"kind\":\"TOUCH_BELOW\",\"level\":95000,\"note\":\"回踩需求区\"}]}");

        assertThat(r).contains("\"armed\":1");
        // 字串和数字都绑进了记录（字段怎么落库归 TraderWatchStoreTest）
        ArgumentCaptor<AiTraderWatch> row = ArgumentCaptor.forClass(AiTraderWatch.class);
        verify(watchMapper).insert(row.capture());
        assertThat(row.getValue().getKind()).isEqualTo("TOUCH_BELOW");
        assertThat(row.getValue().getLevel()).isEqualByComparingTo("95000");
    }

    /** 预案：动作对着账户查——指的仓位 / 挂单得在，移止损不能比现挂的松；通过的落库时带上那个仓位的币和方向 */
    @Test
    void setWatchActionCheckedAgainstAccount() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));

        // 形状不对：CLOSE 没给占比
        assertThat(tools.setWatch("ETHUSDT", List.of(preset(new WatchAction("CLOSE", 5L, null, null, null)))))
                .startsWith("REJECTED").contains("action 不合规");
        assertThat(tools.setWatch("ETHUSDT", List.of(preset(new WatchAction("CLOSE", 6L, null, new BigDecimal("50"), null)))))
                .startsWith("REJECTED").contains("positionId不存在");
        // 现挂的止损 95000，预案要移到 94000 是放宽
        assertThat(tools.setWatch("ETHUSDT", List.of(preset(new WatchAction("MOVE_STOP", 5L, new BigDecimal("94000"), null, null)))))
                .startsWith("REJECTED").contains("只许收紧");
        assertThat(tools.setWatch("ETHUSDT", List.of(preset(new WatchAction("CANCEL_ORDER", null, null, null, 600L)))))
                .startsWith("REJECTED").contains("orderId不在");
        verify(watchMapper, never()).insert(any(AiTraderWatch.class));

        // ETH 跌破 1900 就把 BTC 多单的止损收到 97000：条件的币不是动作的币
        String ok = tools.setWatch("ETHUSDT", List.of(preset(new WatchAction("MOVE_STOP", 5L, new BigDecimal("97000"), null, null))));

        assertThat(ok).contains("\"armed\":1");
        ArgumentCaptor<AiTraderWatch> row = ArgumentCaptor.forClass(AiTraderWatch.class);
        verify(watchMapper).insert(row.capture());
        JsonNode action = MAPPER.readTree(row.getValue().getActionJson());
        assertThat(action.path("type").asString()).isEqualTo("MOVE_STOP");
        assertThat(action.path("positionId").asLong()).isEqualTo(5L);
        assertThat(action.path("stopLossPrice").asDecimal()).isEqualByComparingTo("97000");
        assertThat(action.path("symbol").asString()).isEqualTo("BTCUSDT");
        assertThat(action.path("side").asString()).isEqualTo("LONG");
    }

    /** ETH 触及 1900 以下这条条件，带一个预案动作 */
    private static WatchSpec preset(WatchAction action) {
        return new WatchSpec("TOUCH_BELOW", 1900.0, null, null, "大盘破位", action);
    }

    /** 放宽止损=放大风险=移动球门柱，工具层一票否决 */
    @Test
    void stopLossWidenRejected() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));

        String r = tools.setStopLoss(5L, 94000.0, "给它多一点空间");

        assertThat(r).startsWith("REJECTED").contains("收紧");
        verify(simTradeClient, never()).setStopLoss(anyLong(), any());
    }

    /** 收紧止损放行 + 计划留修订（理由进历史，下轮无记忆的模型看得见） */
    @Test
    void stopLossTightenExecutesAndRevisesPlan() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));
        when(planMapper.selectOne(any())).thenReturn(existingPlan());

        String r = tools.setStopLoss(5L, 98000.0, "价格+2R，上移锁保本");

        assertThat(r).contains("ok");
        verify(simTradeClient).setStopLoss(eq(99L), any());
        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).updateById(cap.capture());
        assertThat(cap.getValue().getRevisionsJson()).contains("移动止损").contains("95000→98000").contains("锁保本");
        // 计划本体价格字段是原始快照，修订只进历史
        assertThat(cap.getValue().getStopLossPrice()).isEqualByComparingTo("95000");
    }

    // ---------- 无基线时的方向校验：只许收紧管不住"第一次挂" ----------

    /**
     * 仓位原本没挂止损时 tightest 为 null，"只许收紧"整条判定被短路，任意价格放行。
     * 把 LONG 止损设在现价之上，下一 tick 立刻市价平仓——正是工具描述里明令禁止的
     * "panic exit in disguise"。方向校验必须独立于基线存在与否。
     */
    @Test
    void stopLossAboveMarkRejectedForLongWithoutBaseline() {
        FuturesPositionDTO p = longPosition();
        p.setStopLosses(List.of());
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(p));

        String r = tools.setStopLoss(5L, 105000.0, "先挂上面看看");

        assertThat(r).startsWith("REJECTED").contains("现价");
        verify(simTradeClient, never()).setStopLoss(anyLong(), any());
    }

    /** 有基线也拦：105000 比现有止损 95000 更"紧"，但在现价之上照样是秒触发的伪装平仓 */
    @Test
    void stopLossAboveMarkRejectedEvenWhenTighterThanBaseline() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));

        String r = tools.setStopLoss(5L, 105000.0, "收紧一点");

        assertThat(r).startsWith("REJECTED").contains("现价");
        verify(simTradeClient, never()).setStopLoss(anyLong(), any());
    }

    /** 空单反过来：止损挂到现价之下＝立刻市价买回平仓 */
    @Test
    void stopLossBelowMarkRejectedForShortWithoutBaseline() {
        FuturesPositionDTO p = longPosition();
        p.setSide("SHORT");
        p.setStopLosses(List.of());
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(p));

        String r = tools.setStopLoss(5L, 95000.0, "锁一点利润");

        assertThat(r).startsWith("REJECTED").contains("现价");
        verify(simTradeClient, never()).setStopLoss(anyLong(), any());
    }

    /** 方向对的首次补挂必须放行——给裸奔仓位补保护单是该鼓励的动作 */
    @Test
    void firstStopLossOnUnprotectedPositionPasses() {
        FuturesPositionDTO p = longPosition();
        p.setStopLosses(List.of());
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(p));
        when(planMapper.selectOne(any())).thenReturn(existingPlan());

        String r = tools.setStopLoss(5L, 97000.0, "裸奔仓位补挂保护");

        assertThat(r).contains("ok");
        verify(simTradeClient).setStopLoss(eq(99L), any());
    }

    /** 止盈哪一档挂到现价另一侧都是秒触发的平仓：多单现价 100000，一档 95000 拒 */
    @Test
    void takeProfitRungOnWrongSideOfMarkRejected() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));

        String r = tools.setTakeProfit(5L, List.of(rung("120000", "50"), rung("95000", "50")), "落袋为安");

        assertThat(r).startsWith("REJECTED").contains("现价").contains("95000");
        verify(simTradeClient, never()).setTakeProfit(anyLong(), any());
    }

    /** 止盈可以往近挪（110000 → 105000，仍在现价有利一侧），修订史记下前后 */
    @Test
    void takeProfitMayMoveNearerAndRevisesPlan() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));
        when(planMapper.selectOne(any())).thenReturn(existingPlan());

        String r = tools.setTakeProfit(5L, oneRung("105000"), "动能衰减，先落袋");

        assertThat(r).contains("ok");
        verify(simTradeClient).setTakeProfit(eq(99L), any());
        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).updateById(cap.capture());
        assertThat(cap.getValue().getRevisionsJson()).contains("移动止盈")
                .contains("110000(100%)→105000(100%)").contains("动能衰减");
    }

    /**
     * 止损一律覆盖全仓：覆盖量归代码从仓位现取，
     * 模型说了不算——它照抄旧数量（加仓后仓位已变大）就会让一半仓位裸奔。
     */
    @Test
    void stopLossAlwaysCoversWholePosition() {
        FuturesPositionDTO p = longPosition();
        // 加仓后仓位涨到 0.02，而旧止损档还停在 0.01
        p.setQuantity(new BigDecimal("0.02"));
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(p));
        when(planMapper.selectOne(any())).thenReturn(existingPlan());

        String r = tools.setStopLoss(5L, 98000.0, "价格+2R，上移锁保本");

        assertThat(r).contains("ok");
        ArgumentCaptor<FuturesStopLossRequest> cap = ArgumentCaptor.forClass(FuturesStopLossRequest.class);
        verify(simTradeClient).setStopLoss(eq(99L), cap.capture());
        assertThat(cap.getValue().getStopLosses()).singleElement()
                .extracting(FuturesStopLossRequest.StopLossItem::getQuantity, as(BIG_DECIMAL))
                .isEqualByComparingTo("0.02");
    }

    /** 止盈分档：占比按仓位当前数量折成数量、按步长向下取整，合计 100% 时最后一档拿余数 */
    @Test
    void takeProfitLadderQuantitiesFollowCurrentPosition() {
        FuturesPositionDTO p = longPosition();
        p.setQuantity(new BigDecimal("0.01"));
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(p));

        // 0.01 的 30% = 0.003；后两档 35% = 0.0035 向下取到 0.003，最后一档拿余数 0.004
        String r = tools.setTakeProfit(5L, List.of(rung("105000", "30"), rung("110000", "35"), rung("120000", "35")), "分三档");

        assertThat(r).contains("ok");
        ArgumentCaptor<FuturesTakeProfitRequest> cap = ArgumentCaptor.forClass(FuturesTakeProfitRequest.class);
        verify(simTradeClient).setTakeProfit(eq(99L), cap.capture());
        assertThat(cap.getValue().getTakeProfits())
                .extracting(i -> i.getPrice().toPlainString() + "×" + i.getQuantity().toPlainString())
                .containsExactly("105000×0.003", "110000×0.003", "120000×0.004");
    }

    /** 传空数组就是把止盈全部撤掉 */
    @Test
    void emptyTakeProfitsClearsThem() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));
        when(planMapper.selectOne(any())).thenReturn(existingPlan());

        String r = tools.setTakeProfit(5L, List.of(), "不设目标");

        assertThat(r).contains("ok");
        ArgumentCaptor<FuturesTakeProfitRequest> cap = ArgumentCaptor.forClass(FuturesTakeProfitRequest.class);
        verify(simTradeClient).setTakeProfit(eq(99L), cap.capture());
        assertThat(cap.getValue().getTakeProfits()).isEmpty();
        ArgumentCaptor<AiTraderPlan> plan = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).updateById(plan.capture());
        assertThat(plan.getValue().getRevisionsJson()).contains("110000(100%)→无");
    }

    /**
     * 工具描述不许和系统提示词对着干：提示词写的是"账户情况已经给足，无需 get_account 复查，
     * 把工具调用预算花在行情求证上"，描述里再写 ALWAYS check 就是两条强指令打架——
     * 模型要么白烧保险丝预算复查账户，要么开始整体折价工具描述的权威性。
     */
    @Test
    void getAccountDescriptionDoesNotContradictSystemPrompt() throws Exception {
        String desc = TradeTools.class.getMethod("getAccount")
                .getAnnotation(org.springframework.ai.tool.annotation.Tool.class).description();

        assertThat(desc).doesNotContain("ALWAYS");
        // 只客观描述返回什么 + 点明账户状态每轮已注入，通常不必再调
        assertThat(desc).contains("already");
    }

    /** 已有计划的仓不许 write_plan——否则它就是改论点的后门 */
    @Test
    void writePlanRejectedWhenPlanExists() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));
        when(planMapper.selectOne(any())).thenReturn(existingPlan());

        String r = tools.writePlan(5L, "RANGE", "x", "跌破区间下沿");

        assertThat(r).startsWith("REJECTED").contains("已有计划");
        verify(planMapper, never()).insert(any(AiTraderPlan.class));
    }

    /** 无计划持仓补立：字段取自真实仓位（入场价/当前止损/当前止盈档），修订史起点=补立 */
    @Test
    void writePlanBackfillsPlanlessPosition() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));
        when(planMapper.selectOne(any())).thenReturn(null);

        String r = tools.writePlan(5L, "RANGE", "区间下沿获支撑", "1h收盘跌破区间下沿94000");

        assertThat(r).contains("ok");
        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).insert(cap.capture());
        AiTraderPlan p = cap.getValue();
        assertThat(p.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(p.getSide()).isEqualTo("LONG");
        assertThat(p.getEntryPrice()).isEqualByComparingTo("100000");
        assertThat(p.getStopLossPrice()).isEqualByComparingTo("95000");
        assertThat(TargetRung.of(p)).singleElement().satisfies(t -> {
            assertThat(t.price()).isEqualByComparingTo("110000");
            assertThat(t.percent()).isEqualByComparingTo("100");
        });
        assertThat(p.getInvalidationCondition()).contains("94000");
        assertThat(p.getRevisionsJson()).contains("补立");
        // 补立同样绑仓位：id 已经 findPosition 对 sim 校验过
        assertThat(p.getPositionId()).isEqualTo(5L);
    }

    /** 市价开仓响应即带仓位 id，计划落库时直接绑定——配对从"事后算"变成"当场存"的源头 */
    @Test
    void marketOpenPersistsPositionIdFromResponse() {
        FuturesOrderResponse resp = new FuturesOrderResponse();
        resp.setOrderId(888L);
        resp.setPositionId(42L);
        when(simTradeClient.openPosition(eq(99L), any())).thenReturn(resp);
        when(planMapper.selectOne(any())).thenReturn(null);

        String r = openOnce(tools);

        assertThat(r).contains("888");
        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).insert(cap.capture());
        assertThat(cap.getValue().getPositionId()).isEqualTo(42L);
        // 上下文异步补写，落库那一刻为空
        assertThat(cap.getValue().getEntryCtxJson()).isNull();
    }

    /**
     * 回执带 facts：市价开 taker 0.04% + 平仓 taker 0.04%，名义 1000 → 往返 0.8；
     * 止损 5000 = 5% = 2.5 倍 1h ATR(2000)；止损打掉亏 50+0.8、到目标赚 100−0.8，R=1.95；亏 50.8 占权益 10000 的 0.51%
     */
    @Test
    void openReceiptCarriesFacts() {
        EntryContext entryContext = mock(EntryContext.class);
        when(entryContext.atr14(eq("BTCUSDT"), eq("1h"), anyLong())).thenReturn(new BigDecimal("2000"));
        TradeTools withAtr = new TradeTools(simTradeClient, 99L, Set.of("BTCUSDT"), Set.of("BTCUSDT", "ETHUSDT"),
                positions -> new BigDecimal("10000"), sym -> new BigDecimal("100000"),
                planStore, watchStore,
                new TradeTools.WakeCtx(7L, 1, "1h", 1785171600000L, DEADLINE,
                        new TraderRiskConfig(1, 20, new BigDecimal("50"), true, true), AgentLang.ZH),
                PROMPTS, MESSAGES, entryContext);
        FuturesOrderResponse resp = new FuturesOrderResponse();
        resp.setOrderId(890L);
        when(simTradeClient.openPosition(eq(99L), any())).thenReturn(resp);
        when(planMapper.selectOne(any())).thenReturn(null);

        JsonNode receipt = MAPPER.readTree(openOnce(withAtr));

        assertThat(receipt.path("orderId").asLong()).isEqualTo(890L);
        JsonNode facts = receipt.path("facts");
        assertThat(facts.path("entryRef").asDecimal()).isEqualByComparingTo("100000");
        assertThat(facts.path("notional").asDecimal()).isEqualByComparingTo("1000");
        assertThat(facts.path("feeRoundTrip").asDecimal()).isEqualByComparingTo("0.8");
        assertThat(facts.path("stopDistancePct").asDecimal()).isEqualByComparingTo("5");
        assertThat(facts.path("atrInterval").asString(null)).isEqualTo("1h");
        assertThat(facts.path("stopDistanceAtr").asDecimal()).isEqualByComparingTo("2.5");
        assertThat(facts.path("lossAtStop").asDecimal()).isEqualByComparingTo("50.8");
        assertThat(facts.path("gainAtTargets").asDecimal()).isEqualByComparingTo("99.2");
        assertThat(facts.path("rewardRiskAfterFees").asDecimal()).isEqualByComparingTo("1.95");
        assertThat(facts.path("targetsCoverPct").asDecimal()).isEqualByComparingTo("100");
        assertThat(facts.path("riskPctOfEquity").asDecimal()).isEqualByComparingTo("0.51");
    }

    /** 限价单的事实按限价算、开仓腿按 maker 0.02%：名义 960 → 往返 0.58；ATR 取不到就没有那两项 */
    @Test
    void limitReceiptUsesLimitPriceAndMakerFee() {
        FuturesOrderResponse resp = new FuturesOrderResponse();
        resp.setOrderId(891L);
        when(simTradeClient.openPosition(eq(99L), any())).thenReturn(resp);
        when(planMapper.selectOne(any())).thenReturn(null);

        JsonNode facts = MAPPER.readTree(tools.openPosition("BTCUSDT", "LONG", "LIMIT", 0.01, 10,
                96000.0, 95000.0, oneRung("110000"), "PULLBACK", "回踩支撑", "1h收盘跌破95000")).path("facts");

        assertThat(facts.path("entryRef").asDecimal()).isEqualByComparingTo("96000");
        assertThat(facts.path("feeRoundTrip").asDecimal()).isEqualByComparingTo("0.58");
        assertThat(facts.path("stopDistancePct").asDecimal()).isEqualByComparingTo("1.04");
        assertThat(facts.has("stopDistanceAtr")).isFalse();
        assertThat(facts.has("atrInterval")).isFalse();
    }

    /** 不设止盈也能开：sim 那边一张止盈单都没有，计划没有目标位，回执没有盈利和盈亏比两项 */
    @Test
    void openWithoutTakeProfit() {
        FuturesOrderResponse resp = new FuturesOrderResponse();
        resp.setOrderId(892L);
        resp.setPositionId(42L);
        when(simTradeClient.openPosition(eq(99L), any())).thenReturn(resp);
        when(planMapper.selectOne(any())).thenReturn(null);

        JsonNode receipt = MAPPER.readTree(tools.openPosition("BTCUSDT", "LONG", "MARKET", 0.01, 10,
                null, 95000.0, null, "TREND_FOLLOW", "趋势延续", "1h收盘跌破95000"));

        ArgumentCaptor<FuturesOpenRequest> req = ArgumentCaptor.forClass(FuturesOpenRequest.class);
        verify(simTradeClient).openPosition(eq(99L), req.capture());
        assertThat(req.getValue().getTakeProfits()).isEmpty();
        assertThat(req.getValue().getStopLosses()).hasSize(1);
        ArgumentCaptor<AiTraderPlan> plan = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).insert(plan.capture());
        assertThat(plan.getValue().getTakeProfitsJson()).isNull();
        assertThat(receipt.path("facts").has("lossAtStop")).isTrue();
        assertThat(receipt.path("facts").has("gainAtTargets")).isFalse();
        assertThat(receipt.path("facts").has("rewardRiskAfterFees")).isFalse();
    }

    /** 开仓带分档止盈：sim 收到的是每档的价位和折好的数量，计划里存的是价位和占比 */
    @Test
    void openWithTakeProfitLadder() {
        FuturesOrderResponse resp = new FuturesOrderResponse();
        resp.setOrderId(893L);
        when(simTradeClient.openPosition(eq(99L), any())).thenReturn(resp);
        when(planMapper.selectOne(any())).thenReturn(null);

        tools.openPosition("BTCUSDT", "LONG", "MARKET", 0.01, 10,
                null, 95000.0, List.of(rung("105000", "50"), rung("110000", "30")),
                "BREAKOUT", "突破前高", "1h收盘跌回箱体内");

        ArgumentCaptor<FuturesOpenRequest> req = ArgumentCaptor.forClass(FuturesOpenRequest.class);
        verify(simTradeClient).openPosition(eq(99L), req.capture());
        assertThat(req.getValue().getTakeProfits())
                .extracting(t -> t.getPrice().toPlainString() + "×" + t.getQuantity().toPlainString())
                .containsExactly("105000×0.005", "110000×0.003");
        ArgumentCaptor<AiTraderPlan> plan = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).insert(plan.capture());
        assertThat(TargetRung.text(TargetRung.of(plan.getValue()))).isEqualTo("105000(50%) / 110000(30%)");
    }

    /** 加仓带的止盈档追加在仓位现有档位后面，合计超过 sim 的 4 档就拒 */
    @Test
    void addOnTakeProfitRungsCappedBySimLimit() {
        FuturesPositionDTO held = longPosition();
        held.setTakeProfits(List.of(
                new FuturesTakeProfit("t1", new BigDecimal("105000"), new BigDecimal("0.005")),
                new FuturesTakeProfit("t2", new BigDecimal("110000"), new BigDecimal("0.005"))));
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(held));

        String r = tools.openPosition("BTCUSDT", "LONG", "MARKET", 0.01, 10, null, 95000.0,
                List.of(rung("105000", "30"), rung("110000", "30"), rung("120000", "40")),
                "PULLBACK", "回踩确认支撑", "1h收盘跌破97000");

        assertThat(r).startsWith("REJECTED").contains("已经挂着 2 档").contains("最多 4 档");
        verify(simTradeClient, never()).openPosition(anyLong(), any());
    }

    /** 开仓上下文异步补写：计划先落库拿 id，虚拟线程抓到后按列写回这条计划的 entry_ctx_json */
    @Test
    void entryContextWrittenAsyncAfterPlanPersisted() {
        EntryContext entryContext = mock(EntryContext.class);
        when(entryContext.capture(eq("BTCUSDT"), eq("MARKET"), eq(new BigDecimal("100000")),
                eq(BigDecimal.valueOf(95000.0)), eq(10), anyLong()))
                .thenReturn("{\"regime\":\"RANGE\"}");
        when(planMapper.insert(any(AiTraderPlan.class))).thenAnswer(inv -> {
            inv.<AiTraderPlan>getArgument(0).setId(43L);
            return 1;
        });
        TradeTools withCtx = new TradeTools(simTradeClient, 99L, Set.of("BTCUSDT"), Set.of("BTCUSDT", "ETHUSDT"),
                positions -> new BigDecimal("10000"), sym -> new BigDecimal("100000"),
                planStore, watchStore,
                new TradeTools.WakeCtx(7L, 1, "1h", 1785171600000L, DEADLINE,
                        new TraderRiskConfig(1, 20, new BigDecimal("50"), true, true), AgentLang.ZH),
                PROMPTS, MESSAGES, entryContext);
        FuturesOrderResponse resp = new FuturesOrderResponse();
        resp.setOrderId(889L);
        resp.setPositionId(43L);
        when(simTradeClient.openPosition(eq(99L), any())).thenReturn(resp);
        when(planMapper.selectOne(any())).thenReturn(null);

        openOnce(withCtx);

        ArgumentCaptor<AiTraderPlan> inserted = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getEntryCtxJson()).isNull();
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<AiTraderPlan>> up = ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(planMapper, timeout(2000)).update(isNull(), up.capture());
        assertThat(up.getValue().getSqlSet()).contains("entry_ctx_json");
        // 条件值要先拼一次 where 才落进参数表
        assertThat(up.getValue().getSqlSegment()).contains("id = ");
        assertThat(up.getValue().getParamNameValuePairs()).containsValue("{\"regime\":\"RANGE\"}").containsValue(43L);
    }

    // ---------- 下单结果未知：幂等键 + 同键重发 ----------

    /** 常规开仓参数（护栏全过），下单链路的用例都用它 */
    private String openOnce(TradeTools t) {
        return t.openPosition("BTCUSDT", "LONG", "MARKET", 0.01, 10,
                null, 95000.0, oneRung("110000"), "BREAKOUT", "突破前高", "1h收盘跌回箱体内");
    }

    /**
     * 读超时和 sim 回的"处理中"都只说明结果未知——sim 那边很可能已经成交了。
     * 必须拿同一个 clientRequestId 重发去问结果（sim 侧幂等，重发不会多成交），
     * 而不是当失败回给模型让它重下一单。
     */
    @Test
    void timeoutResentWithSameRequestIdUntilResultKnown() {
        List<String> keys = new ArrayList<>();
        when(simTradeClient.openPosition(eq(99L), any())).thenAnswer(inv -> {
            keys.add(((FuturesOpenRequest) inv.getArgument(1)).getClientRequestId());
            if (keys.size() == 1) {
                throw new ResourceAccessException("I/O error on POST request: 读超时");
            }
            if (keys.size() == 2) {
                throw new SimTradeClient.SimBizException(ErrorCode.ORDER_IN_FLIGHT.getCode(),
                        "请求处理中，请稍后用同一 clientRequestId 重试");
            }
            FuturesOrderResponse resp = new FuturesOrderResponse();
            resp.setOrderId(888L);
            return resp;
        });

        String r = openOnce(tools);

        assertThat(r).contains("888");
        assertThat(keys).hasSize(3).doesNotContainNull();
        // 三次同一个键：sim 侧幂等据此判定是同一笔，只会成交一次
        assertThat(Set.copyOf(keys)).hasSize(1);
        assertThat(tools.actions()).singleElement()
                .satisfies(a -> assertThat(a.path("status").asString(null)).isEqualTo("ok"));
    }

    /**
     * 重发到头仍问不到结果：不能回 ERROR——模型看见失败会重下一单，那就是双仓。
     * 必须明说结果未知并让它先去核对账户。
     */
    @Test
    void unknownOutcomeTellsModelToCheckAccountInsteadOfRetrying() {
        when(simTradeClient.openPosition(eq(99L), any()))
                .thenThrow(new ResourceAccessException("I/O error on POST request: 读超时"));

        String r = openOnce(tools);

        assertThat(r).startsWith("UNKNOWN").contains("可能已经成交")
                .contains("get_account").contains("切勿直接重复下单");
        verify(simTradeClient, times(3)).openPosition(eq(99L), any());
        assertThat(tools.actions()).singleElement()
                .satisfies(a -> assertThat(a.path("status").asString(null)).isEqualTo("unknown"));
        // 结果未知就不落计划：没确认成交的仓位不该有事前承诺记录，模型要补走 write_plan
        verify(planMapper, never()).insert(any(AiTraderPlan.class));
    }

    /** 明确的业务失败（没成交）不重发，原样回给模型 */
    @Test
    void businessFailureNotResent() {
        when(simTradeClient.openPosition(eq(99L), any()))
                .thenThrow(new IllegalStateException("sim api 业务失败 code=1751 msg=余额不足"));

        String r = openOnce(tools);

        assertThat(r).startsWith("ERROR").contains("余额不足");
        verify(simTradeClient, times(1)).openPosition(eq(99L), any());
    }

    /**
     * 本轮预算已耗尽：唤醒回路那边正在超时作废，cancel(true) 未必立刻停住图里的工具调用，
     * 写工具必须自己拒发——否则作废的一轮还会往 sim 塞单，下一轮开局就是没人认领的仓位。
     */
    @Test
    void expiredRoundRejectsAllWriteTools() {
        TradeTools late = new TradeTools(simTradeClient, 99L, Set.of("BTCUSDT"), Set.of("BTCUSDT", "ETHUSDT"),
                positions -> new BigDecimal("10000"), sym -> new BigDecimal("100000"),
                planStore, watchStore,
                new TradeTools.WakeCtx(7L, 1, "1h", 1785171600000L, System.currentTimeMillis() - 1,
                        new TraderRiskConfig(1, 20, new BigDecimal("50"), true, true), AgentLang.ZH),
                PROMPTS, MESSAGES, mock(EntryContext.class));

        assertThat(openOnce(late)).startsWith("REJECTED").contains("本轮已超时");
        assertThat(late.closePosition(5L, 0.01, "失效条件触发")).contains("本轮已超时");
        assertThat(late.setStopLoss(5L, 98000.0, "上移锁保本")).contains("本轮已超时");
        assertThat(late.setTakeProfit(5L, oneRung("120000"), "趋势加速")).contains("本轮已超时");
        assertThat(late.cancelOrder(11L)).contains("本轮已超时");
        // 连持仓查询都不该发出：超时后一次 sim 调用都不欠
        verifyNoInteractions(simTradeClient);
    }

    /**
     * 真跑事故复现：模型重试时丢了 symbol 参数，空 symbol 打到上游拉回全市场数组炸掉解析。
     * 白名单必须挡在行情查询之前——模型要收到的是可修正的拒因，不是解析异常。
     */
    @Test
    void blankSymbolRejectedBeforeMarketFetch() {
        TradeTools strict = new TradeTools(simTradeClient, 99L, Set.of("BTCUSDT"), Set.of("BTCUSDT", "ETHUSDT"),
                positions -> new BigDecimal("10000"),
                sym -> { throw new IllegalStateException("不该发起行情查询"); },
                planStore, watchStore,
                new TradeTools.WakeCtx(7L, 1, "1h", 1785171600000L, DEADLINE,
                        new TraderRiskConfig(1, 20, new BigDecimal("50"), true, true), AgentLang.ZH),
                PROMPTS, MESSAGES, mock(EntryContext.class));

        String r = strict.openPosition(null, "SHORT", "MARKET", 0.64, 20,
                null, 64980.0, oneRung("64640"), "BREAKOUT", "突破", "收回箱体");

        assertThat(r).startsWith("REJECTED").contains("白名单").contains("完整给出全部参数");
    }

    /**
     * 真跑事故复现：模型重试时丢参数。stopLossPrice 曾是 primitive double，漏传绑成 0.0，
     * 护栏的 null 检查永不触发、LONG 的方向校验「0 >= 入场价」为假——直接放行一张
     * 止损价为0（永不触发）的裸多单，工具还回执成功。必须拒。
     */
    @Test
    void missingStopLossRejectedInsteadOfOpeningNakedLong() {
        String r = tools.openPosition("BTCUSDT", "LONG", "MARKET", 0.01, 10,
                null, null, oneRung("110000"), "BREAKOUT", "突破前高", "1h收盘跌回箱体内");

        assertThat(r).startsWith("REJECTED").contains("止损");
        verify(simTradeClient, never()).openPosition(anyLong(), any());
    }

    /**
     * 同向已有仓位＝加仓，现在直接成交：审批闸门拆掉后这条路径不该再被拦下，
     * 且计划要走加仓覆盖（coversPlan）而不是新立一份。
     */
    @Test
    void addOnFillsImmediately() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));
        FuturesOrderResponse resp = new FuturesOrderResponse();
        resp.setOrderId(777L);
        resp.setPositionId(5L);
        when(simTradeClient.openPosition(eq(99L), any())).thenReturn(resp);
        when(planMapper.selectOne(any())).thenReturn(existingPlan());

        String r = tools.openPosition("BTCUSDT", "LONG", "MARKET", 0.01, 10,
                null, 95000.0, oneRung("110000"), "PULLBACK", "回踩确认支撑", "1h收盘跌破97000");

        assertThat(r).doesNotStartWith("REJECTED").contains("777");
        assertThat(tools.actions().get(0).path("status").asString(null)).isEqualTo("ok");
        verify(simTradeClient).openPosition(eq(99L), any());
        // coversPlan 由同向持仓/开仓挂单推出：覆盖走 updateById，判成新立就会多插一行
        verify(planMapper).updateById(any(AiTraderPlan.class));
        verify(planMapper, never()).insert(any(AiTraderPlan.class));
        // 现有保护单的数量没超过仓位，不重挂
        verify(simTradeClient, never()).setStopLoss(anyLong(), any());
        verify(simTradeClient, never()).setTakeProfit(anyLong(), any());
    }

    /** 分批止盈走了一半（仓位剩 0.005，止损止盈单还挂着 0.01）再加仓：先按仓位现量把保护单重挂，再发加仓单 */
    @Test
    void addOnRefitsStaleProtectiveOrdersBeforeSending() {
        FuturesPositionDTO held = longPosition();
        held.setQuantity(new BigDecimal("0.005"));
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(held));
        FuturesOrderResponse resp = new FuturesOrderResponse();
        resp.setOrderId(778L);
        resp.setPositionId(5L);
        when(simTradeClient.openPosition(eq(99L), any())).thenReturn(resp);
        when(planMapper.selectOne(any())).thenReturn(existingPlan());

        tools.openPosition("BTCUSDT", "LONG", "MARKET", 0.01, 10,
                null, 95000.0, null, "PULLBACK", "回踩确认支撑", "1h收盘跌破97000");

        InOrder sent = inOrder(simTradeClient);
        ArgumentCaptor<FuturesStopLossRequest> sl = ArgumentCaptor.forClass(FuturesStopLossRequest.class);
        ArgumentCaptor<FuturesTakeProfitRequest> tp = ArgumentCaptor.forClass(FuturesTakeProfitRequest.class);
        sent.verify(simTradeClient).setStopLoss(eq(99L), sl.capture());
        sent.verify(simTradeClient).setTakeProfit(eq(99L), tp.capture());
        sent.verify(simTradeClient).openPosition(eq(99L), any());
        assertThat(sl.getValue().getStopLosses()).singleElement()
                .satisfies(i -> assertThat(i.getQuantity()).isEqualByComparingTo("0.005"));
        assertThat(tp.getValue().getTakeProfits()).singleElement()
                .satisfies(i -> assertThat(i.getQuantity()).isEqualByComparingTo("0.005"));
    }

    /** 同币同向还挂着限价单时再挂一张：计划走覆盖，不能把挂着那张的计划归档掉 */
    @Test
    void secondOrderWhileSameSidePendingCoversPlan() {
        FuturesOrderResponse pending = new FuturesOrderResponse();
        pending.setOrderId(600L);
        pending.setSymbol("BTCUSDT");
        pending.setOrderSide("OPEN_LONG");
        pending.setLimitPrice(new BigDecimal("98000"));
        pending.setLeverage(10);
        when(simTradeClient.getPendingOrders(eq(99L), any())).thenReturn(List.of(pending));
        FuturesOrderResponse resp = new FuturesOrderResponse();
        resp.setOrderId(601L);
        when(simTradeClient.openPosition(eq(99L), any())).thenReturn(resp);
        when(planMapper.selectOne(any())).thenReturn(existingPlan());

        String r = tools.openPosition("BTCUSDT", "LONG", "LIMIT", 0.02, 10,
                97000.0, 95000.0, oneRung("110000"), "PULLBACK", "二次回踩", "1h收盘跌破96000");

        assertThat(r).doesNotStartWith("REJECTED").contains("601");
        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).updateById(cap.capture());
        assertThat(cap.getValue().getId()).isEqualTo(21L);
        assertThat(cap.getValue().getRevisionsJson()).contains("1h收盘跌回98000下方");
        verify(planMapper, never()).insert(any(AiTraderPlan.class));
    }

    /** 平仓同理：进来就是市价单，不再有转请求那一跳 */
    @Test
    void closeFillsImmediately() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));
        FuturesOrderResponse resp = new FuturesOrderResponse();
        resp.setOrderId(778L);
        when(simTradeClient.closePosition(eq(99L), any())).thenReturn(resp);

        String r = tools.closePosition(5L, 0.01, "失效条件触发");

        assertThat(r).doesNotStartWith("REJECTED").contains("778");
        verify(simTradeClient).closePosition(eq(99L), any());
    }

    /** 平不存在的仓位：与 sl/tp 同一句拒因，不把模型抄错的 id 打到 sim */
    @Test
    void closeUnknownPositionRejectedBeforeSim() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));

        String r = tools.closePosition(999L, 0.01, "x");

        assertThat(r).startsWith("REJECTED").contains("positionId不存在");
        verify(simTradeClient, never()).closePosition(anyLong(), any());
    }

    /** 平仓理由进计划修订史：归档后复盘看得到"为什么平"，不再只留在 actions_json 里。计划按仓位币向找 */
    @Test
    void closeLeavesReasonInPlanRevisions() {
        FuturesPositionDTO pos = longPosition();
        pos.setId(42L);
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(pos));
        AiTraderPlan plan = existingPlan();
        plan.setPositionId(42L);
        when(planMapper.selectOne(any())).thenReturn(plan);
        FuturesOrderResponse resp = new FuturesOrderResponse();
        resp.setOrderId(779L);
        when(simTradeClient.closePosition(eq(99L), any())).thenReturn(resp);

        String r = tools.closePosition(42L, 0.01, "失效条件触发：1h收盘跌破64200");

        assertThat(r).contains("779");
        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).updateById(cap.capture());
        assertThat(cap.getValue().getRevisionsJson()).contains("平仓").contains("1h收盘跌破64200").contains("0.01");
    }

    /**
     * 保证金占比按开仓那一刻现算的权益：同一轮先平后开，护栏要按平完之后的权益算。
     * 按 10000 权益合法的数量（保证金 4000=40%），权益只剩 5000 时就是 80%，拒且给出上限处的数量
     */
    @Test
    void marginCapUsesEquityAtOpenTime() {
        TradeTools poorer = new TradeTools(simTradeClient, 99L, Set.of("BTCUSDT"), Set.of("BTCUSDT", "ETHUSDT"),
                positions -> new BigDecimal("5000"), sym -> new BigDecimal("100000"),
                planStore, watchStore,
                new TradeTools.WakeCtx(7L, 1, "1h", 1785171600000L, DEADLINE,
                        new TraderRiskConfig(1, 20, new BigDecimal("50"), true, true), AgentLang.ZH),
                PROMPTS, MESSAGES, mock(EntryContext.class));

        String r = poorer.openPosition("BTCUSDT", "LONG", "MARKET", 0.4, 10,
                null, 95000.0, null, "BREAKOUT", "突破前高", "1h收盘跌回箱体内");

        assertThat(r).startsWith("REJECTED").contains("数量最多");
        verify(simTradeClient, never()).openPosition(anyLong(), any());
    }

    /** 同轮内平掉再开同向仓＝重开不是加仓：sim 上无同向持仓/挂单，计划走新立，旧计划由 TraderPlanStore.open 归档 */
    @Test
    void reentryWithoutExposureOpensNewPlan() {
        when(planMapper.selectOne(any())).thenReturn(existingPlan());
        FuturesOrderResponse resp = new FuturesOrderResponse();
        resp.setOrderId(889L);
        resp.setPositionId(43L);
        when(simTradeClient.openPosition(eq(99L), any())).thenReturn(resp);

        String r = openOnce(tools);

        assertThat(r).contains("889");
        ArgumentCaptor<AiTraderPlan> archived = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).updateById(archived.capture());
        assertThat(archived.getValue().getId()).isEqualTo(21L);
        assertThat(archived.getValue().getStatus()).isEqualTo(AiTraderPlan.STATUS_CLOSED);
        ArgumentCaptor<AiTraderPlan> inserted = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getPositionId()).isEqualTo(43L);
        assertThat(inserted.getValue().getRevisionsJson()).isNull();
    }
}
