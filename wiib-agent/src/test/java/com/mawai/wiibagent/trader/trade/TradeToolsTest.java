package com.mawai.wiibagent.trader.trade;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.BIG_DECIMAL;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 持仓管理工具的纪律约束直测：止损只许收紧、止盈只许远离入场、计划不可改写只可修订留痕。
 * 这些约束是"计划=事前承诺"的工具层执行——提示词只能劝，工具拒绝才是真禁止。
 */
class TradeToolsTest {

    private static final PromptCatalog PROMPTS = new PromptCatalog();
    private static final MessageCatalog MESSAGES = new MessageCatalog();

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderPlan.class);
    }

    /** 本轮截止时间：默认给足，只有专门验"超时后拒发"的用例才把它设到过去 */
    private static final long DEADLINE = System.currentTimeMillis() + 600_000;

    private final SimTradeClient simTradeClient = mock(SimTradeClient.class);
    private final AiTraderPlanMapper planMapper = mock(AiTraderPlanMapper.class);
    private final TradeTools tools = new TradeTools(simTradeClient, 99L, Set.of("BTCUSDT"),
            positions -> new BigDecimal("10000"), sym -> new BigDecimal("100000"),
            new TraderPlanStore(planMapper, PROMPTS),
            new TradeTools.WakeCtx(7L, 1, 1785171600000L, DEADLINE,
                    new TraderRiskConfig(1, 20, new BigDecimal("1"), new BigDecimal("50"),
                            true, true), AgentLang.ZH), PROMPTS, MESSAGES);

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
        plan.setTakeProfitPrice(new BigDecimal("110000"));
        plan.setOpenedWakeTime(1785168000000L);
        return plan;
    }

    /** 放宽止损=放大风险=移动球门柱，工具层一票否决 */
    @Test
    void stopLossWidenRejected() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));

        String r = tools.setStopLoss(5L, 94000, "给它多一点空间");

        assertThat(r).startsWith("REJECTED").contains("收紧");
        verify(simTradeClient, never()).setStopLoss(anyLong(), any());
    }

    /** 收紧止损放行 + 计划留修订（理由进历史，下轮无记忆的模型看得见） */
    @Test
    void stopLossTightenExecutesAndRevisesPlan() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));
        when(planMapper.selectOne(any())).thenReturn(existingPlan());

        String r = tools.setStopLoss(5L, 98000, "价格+2R，上移锁保本");

        assertThat(r).contains("ok");
        verify(simTradeClient).setStopLoss(eq(99L), any());
        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).updateById(cap.capture());
        assertThat(cap.getValue().getRevisionsJson()).contains("移动止损").contains("锁保本");
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

        String r = tools.setStopLoss(5L, 105000, "先挂上面看看");

        assertThat(r).startsWith("REJECTED").contains("现价");
        verify(simTradeClient, never()).setStopLoss(anyLong(), any());
    }

    /** 有基线也拦：105000 比现有止损 95000 更"紧"，但在现价之上照样是秒触发的伪装平仓 */
    @Test
    void stopLossAboveMarkRejectedEvenWhenTighterThanBaseline() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));

        String r = tools.setStopLoss(5L, 105000, "收紧一点");

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

        String r = tools.setStopLoss(5L, 95000, "锁一点利润");

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

        String r = tools.setStopLoss(5L, 97000, "裸奔仓位补挂保护");

        assertThat(r).contains("ok");
        verify(simTradeClient).setStopLoss(eq(99L), any());
    }

    /** 止盈同理：无基线时把 LONG 目标挂到现价下方＝"止盈带走"马甲下的秒平仓 */
    @Test
    void takeProfitBelowMarkRejectedForLongWithoutBaseline() {
        FuturesPositionDTO p = longPosition();
        p.setTakeProfits(List.of());
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(p));

        String r = tools.setTakeProfit(5L, 95000, "落袋为安");

        assertThat(r).startsWith("REJECTED").contains("现价");
        verify(simTradeClient, never()).setTakeProfit(anyLong(), any());
    }

    /** 多单下调止盈=把目标降到现价上方一点点秒触发="止盈带走"马甲下的恐慌平仓，拒 */
    @Test
    void takeProfitTowardEntryRejectedForLong() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));

        String r = tools.setTakeProfit(5L, 105000, "想早点落袋");

        assertThat(r).startsWith("REJECTED").contains("止盈");
        verify(simTradeClient, never()).setTakeProfit(anyLong(), any());
    }

    /** 有利方向移动目标（让利润奔跑）放行 + 修订留痕 */
    @Test
    void takeProfitAwayFromEntryExecutesAndRevisesPlan() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));
        when(planMapper.selectOne(any())).thenReturn(existingPlan());

        String r = tools.setTakeProfit(5L, 120000, "趋势加速，目标看下一压力位120000");

        assertThat(r).contains("ok");
        verify(simTradeClient).setTakeProfit(eq(99L), any());
        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).updateById(cap.capture());
        assertThat(cap.getValue().getRevisionsJson()).contains("移动止盈").contains("趋势加速");
    }

    /**
     * 止损一律覆盖全仓：本版 trader 的 sl/tp 都是全仓单，覆盖量归代码从仓位现取，
     * 模型说了不算——它照抄旧数量（加仓后仓位已变大）就会让一半仓位裸奔。
     */
    @Test
    void stopLossAlwaysCoversWholePosition() {
        FuturesPositionDTO p = longPosition();
        // 加仓后仓位涨到 0.02，而旧止损档还停在 0.01
        p.setQuantity(new BigDecimal("0.02"));
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(p));
        when(planMapper.selectOne(any())).thenReturn(existingPlan());

        String r = tools.setStopLoss(5L, 98000, "价格+2R，上移锁保本");

        assertThat(r).contains("ok");
        ArgumentCaptor<FuturesStopLossRequest> cap = ArgumentCaptor.forClass(FuturesStopLossRequest.class);
        verify(simTradeClient).setStopLoss(eq(99L), cap.capture());
        assertThat(cap.getValue().getStopLosses()).singleElement()
                .extracting(FuturesStopLossRequest.StopLossItem::getQuantity, as(BIG_DECIMAL))
                .isEqualByComparingTo("0.02");
    }

    /** 止盈同理全仓覆盖 */
    @Test
    void takeProfitAlwaysCoversWholePosition() {
        FuturesPositionDTO p = longPosition();
        p.setQuantity(new BigDecimal("0.02"));
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(p));
        when(planMapper.selectOne(any())).thenReturn(existingPlan());

        String r = tools.setTakeProfit(5L, 120000, "趋势加速，目标看下一压力位");

        assertThat(r).contains("ok");
        ArgumentCaptor<FuturesTakeProfitRequest> cap = ArgumentCaptor.forClass(FuturesTakeProfitRequest.class);
        verify(simTradeClient).setTakeProfit(eq(99L), cap.capture());
        assertThat(cap.getValue().getTakeProfits()).singleElement()
                .extracting(FuturesTakeProfitRequest.TakeProfitItem::getQuantity, as(BIG_DECIMAL))
                .isEqualByComparingTo("0.02");
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

        String r = tools.writePlan(5L, "RANGE", "x", "跌破区间下沿", null);

        assertThat(r).startsWith("REJECTED").contains("已有计划");
        verify(planMapper, never()).insert(any(AiTraderPlan.class));
    }

    /** 无计划持仓补立：字段取自真实仓位（入场价/当前止损），修订史起点=补立 */
    @Test
    void writePlanBackfillsPlanlessPosition() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(longPosition()));
        when(planMapper.selectOne(any())).thenReturn(null);

        String r = tools.writePlan(5L, "RANGE", "区间下沿获支撑", "1h收盘跌破区间下沿94000", 108000.0);

        assertThat(r).contains("ok");
        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).insert(cap.capture());
        AiTraderPlan p = cap.getValue();
        assertThat(p.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(p.getSide()).isEqualTo("LONG");
        assertThat(p.getEntryPrice()).isEqualByComparingTo("100000");
        assertThat(p.getStopLossPrice()).isEqualByComparingTo("95000");
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
    }

    // ---------- 下单结果未知：幂等键 + 同键重发 ----------

    /** 常规开仓参数（护栏全过），下单链路的用例都用它 */
    private String openOnce(TradeTools t) {
        return t.openPosition("BTCUSDT", "LONG", "MARKET", 0.01, 10,
                null, 95000.0, 110000.0, "BREAKOUT", "突破前高", "1h收盘跌回箱体内");
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
                .satisfies(a -> assertThat(a.getString("status")).isEqualTo("ok"));
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
                .satisfies(a -> assertThat(a.getString("status")).isEqualTo("unknown"));
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
        TradeTools late = new TradeTools(simTradeClient, 99L, Set.of("BTCUSDT"),
                positions -> new BigDecimal("10000"), sym -> new BigDecimal("100000"),
                new TraderPlanStore(planMapper, PROMPTS),
                new TradeTools.WakeCtx(7L, 1, 1785171600000L, System.currentTimeMillis() - 1,
                        new TraderRiskConfig(1, 20, new BigDecimal("1"), new BigDecimal("50"),
                                true, true), AgentLang.ZH), PROMPTS, MESSAGES);

        assertThat(openOnce(late)).startsWith("REJECTED").contains("本轮已超时");
        assertThat(late.closePosition(5L, 0.01, "失效条件触发")).contains("本轮已超时");
        assertThat(late.setStopLoss(5L, 98000, "上移锁保本")).contains("本轮已超时");
        assertThat(late.setTakeProfit(5L, 120000, "趋势加速")).contains("本轮已超时");
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
        TradeTools strict = new TradeTools(simTradeClient, 99L, Set.of("BTCUSDT"),
                positions -> new BigDecimal("10000"),
                sym -> { throw new IllegalStateException("不该发起行情查询"); },
                new TraderPlanStore(planMapper, PROMPTS),
                new TradeTools.WakeCtx(7L, 1, 1785171600000L, DEADLINE,
                        new TraderRiskConfig(1, 20, new BigDecimal("1"), new BigDecimal("50"),
                                true, true), AgentLang.ZH), PROMPTS, MESSAGES);

        String r = strict.openPosition(null, "SHORT", "MARKET", 0.64, 20,
                null, 64980.0, 64640.0, "BREAKOUT", "突破", "收回箱体");

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
                null, null, 110000.0, "BREAKOUT", "突破前高", "1h收盘跌回箱体内");

        assertThat(r).startsWith("REJECTED").contains("止损");
        verify(simTradeClient, never()).openPosition(anyLong(), any());
    }

    /**
     * 同向已有仓位＝加仓，现在直接成交：审批闸门拆掉后这条路径不该再被拦下，
     * 且计划要走加仓覆盖（isAddOn）而不是新立一份。
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
                null, 95000.0, 110000.0, "PULLBACK", "回踩确认支撑", "1h收盘跌破97000");

        assertThat(r).doesNotStartWith("REJECTED").contains("777");
        assertThat(tools.actions().get(0).getString("status")).isEqualTo("ok");
        verify(simTradeClient).openPosition(eq(99L), any());
        // isAddOn 是从 sameSide 推出来的：覆盖走 updateById，判成新立就会多插一行
        verify(planMapper).updateById(any(AiTraderPlan.class));
        verify(planMapper, never()).insert(any(AiTraderPlan.class));
    }

    /** 平仓同理：进来就是市价单，不再有转请求那一跳 */
    @Test
    void closeFillsImmediately() {
        FuturesOrderResponse resp = new FuturesOrderResponse();
        resp.setOrderId(778L);
        when(simTradeClient.closePosition(eq(99L), any())).thenReturn(resp);

        String r = tools.closePosition(5L, 0.01, "失效条件触发");

        assertThat(r).doesNotStartWith("REJECTED").contains("778");
        verify(simTradeClient).closePosition(eq(99L), any());
    }

    /** 平仓理由进计划修订史：归档后复盘看得到"为什么平"，不再只留在 actions_json 里 */
    @Test
    void closeLeavesReasonInPlanRevisions() {
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
     * 按 10000 权益合法的数量（保证金 4000=40%），权益只剩 5000 时就是 80%，拒且给出允许的数量区间
     */
    @Test
    void marginBandUsesEquityAtOpenTime() {
        TradeTools poorer = new TradeTools(simTradeClient, 99L, Set.of("BTCUSDT"),
                positions -> new BigDecimal("5000"), sym -> new BigDecimal("100000"),
                new TraderPlanStore(planMapper, PROMPTS),
                new TradeTools.WakeCtx(7L, 1, 1785171600000L, DEADLINE,
                        new TraderRiskConfig(1, 20, new BigDecimal("1"), new BigDecimal("50"),
                                true, true), AgentLang.ZH), PROMPTS, MESSAGES);

        String r = poorer.openPosition("BTCUSDT", "LONG", "MARKET", 0.4, 10,
                null, 95000.0, null, "BREAKOUT", "突破前高", "1h收盘跌回箱体内");

        assertThat(r).startsWith("REJECTED").contains("数量应在");
        verify(simTradeClient, never()).openPosition(anyLong(), any());
    }

    /** 加仓覆盖：旧论点进修订历史（含理由），持有时长按最初开仓算；sim 并仓 id 不变，绑定跟着保留 */
    @Test
    void upsertExistingPlanKeepsOldThesisAsRevision() {
        AiTraderPlan old = existingPlan();
        old.setPositionId(42L);
        when(planMapper.selectOne(any())).thenReturn(old);
        AiTraderPlan neu = existingPlan();
        neu.setId(null);
        neu.setSignalsUsed("回踩确认支撑，加仓");
        neu.setInvalidationCondition("4h收盘跌破97000");
        neu.setOpenedWakeTime(1785171600000L);
        // 限价加仓挂单响应不带仓位 id：覆盖不能把已有绑定抹掉
        neu.setPositionId(null);

        new TraderPlanStore(planMapper, PROMPTS).upsert(neu, true, AgentLang.ZH);

        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).updateById(cap.capture());
        AiTraderPlan p = cap.getValue();
        assertThat(p.getInvalidationCondition()).isEqualTo("4h收盘跌破97000");
        assertThat(p.getRevisionsJson()).contains("加仓").contains("1h收盘跌回98000下方");
        assertThat(p.getOpenedWakeTime()).isEqualTo(1785168000000L);
        assertThat(p.getPositionId()).isEqualTo(42L);
    }

    /** 同轮内平掉再开同向仓＝重开不是加仓：旧计划归档留档、仓龄从新仓起算、修订史不继承（仓龄诚实） */
    @Test
    void upsertReentryArchivesOldPlanInsteadOfAddOnRevision() {
        AiTraderPlan old = existingPlan();
        when(planMapper.selectOne(any())).thenReturn(old);
        AiTraderPlan neu = existingPlan();
        neu.setId(null);
        neu.setSignalsUsed("重新突破，独立新仓");
        neu.setOpenedWakeTime(1785171600000L);

        new TraderPlanStore(planMapper, PROMPTS).upsert(neu, false, AgentLang.ZH);

        // 归档不删：论点→结局配对是 reviewer 的复盘原料
        ArgumentCaptor<AiTraderPlan> archived = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).updateById(archived.capture());
        assertThat(archived.getValue().getId()).isEqualTo(21L);
        assertThat(archived.getValue().getStatus()).isEqualTo(AiTraderPlan.STATUS_CLOSED);
        assertThat(archived.getValue().getClosedWakeTime()).isEqualTo(1785171600000L);

        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).insert(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(AiTraderPlan.STATUS_LIVE);
        assertThat(cap.getValue().getOpenedWakeTime()).isEqualTo(1785171600000L);
        assertThat(cap.getValue().getRevisionsJson()).isNull();
    }
}
