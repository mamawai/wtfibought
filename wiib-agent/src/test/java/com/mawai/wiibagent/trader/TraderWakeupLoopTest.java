package com.mawai.wiibagent.trader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibagent.learning.ReviewMaterialAssembler;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.toolkit.IndicatorToolkit;
import com.mawai.wiibquant.market.service.KlineFetcher;
import com.mawai.wiibquant.market.service.MarketDataService;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibquant.market.service.NewsCache;
import com.mawai.wiibquant.market.service.NewsFlashLocalizer;
import com.mawai.wiibagent.toolkit.NewsToolkit;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 唤醒回路 mock 回路测试（纯 mock 无外部依赖，常规套件必跑；勿再用 *IT 命名——surefire 默认不收）：mock ChatModel 走一遍真 ReactLoop 工具循环——
 * 开仓工具真被调用（经 TradeGuard）、决策行真落库（含论点标签/动作轨迹/权益/过程轨迹）、现场帧按序推给订阅者。
 * 循环开着 streaming，模型桩打在 {@code stream()} 上：一帧一个 ChatResponse，tool_call 帧正文空。
 */
class TraderWakeupLoopTest {

    /** 收帧的出口：事件名与 data 按序存 */
    private static final class Frames implements TraderLiveHub.Sink {
        final List<String> events = new ArrayList<>();
        final List<JSONObject> data = new ArrayList<>();

        @Override
        public boolean send(String event, JSONObject d) {
            events.add(event);
            data.add(d);
            return true;
        }

        JSONObject last(String event) {
            return data.get(events.lastIndexOf(event));
        }
    }

    @BeforeAll
    static void initTableInfoCache() {
        // LambdaUpdateWrapper 纯单测需要实体 TableInfo 缓存（正常由 MP 启动时注册）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTrader.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderDecision.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderPlan.class);
    }

    private final TraderModelFactory modelFactory = mock(TraderModelFactory.class);
    private final SimTradeClient simTradeClient = mock(SimTradeClient.class);
    private final BinanceRestClient binanceRestClient = mock(BinanceRestClient.class);
    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);
    private final AiTraderPlanMapper planMapper = mock(AiTraderPlanMapper.class);

    /** 语言解析走真实词表的中文侧：本类钉的是唤醒回路行为，不是文案 */
    private final UserLangResolver langResolver = mock(UserLangResolver.class);

    private final PromptCatalog prompts = new PromptCatalog();
    /** 默认（未打桩）返回 null = 本局无统计不注入 */
    private final PlayStatsAssembler playStats = mock(PlayStatsAssembler.class);
    private final TraderLiveHub hub = new TraderLiveHub();

    private final TraderWakeupRunner runner = new TraderWakeupRunner(
            modelFactory, new TraderPromptAssembler(traderMapper, prompts),
            simTradeClient, binanceRestClient,
            new IndicatorToolkit(new KlineFetcher(binanceRestClient, 60_000)),
            new MarketToolkit(mock(MarketDataService.class)),
            new NewsToolkit(mock(NewsCache.class), mock(NewsFlashLocalizer.class)),
            traderMapper, decisionMapper, new TraderPlanStore(planMapper, prompts), langResolver,
            prompts, new MessageCatalog(), new LocalizedToolCallbacks(prompts),
            new ReviewMaterialAssembler(decisionMapper, planMapper, simTradeClient,
                    mock(KlineHistoryStore.class), prompts),
            mock(EconCalendarAssembler.class), playStats, hub);

    {
        // 测试边界是固定历史时刻，墙钟钉在边界后 1s——预算充足，各用例不受真实时间影响
        runner.nowMs = () -> 1785171600000L + 1_000L;
        when(langResolver.of(anyLong())).thenReturn(AgentLang.ZH);
    }

    private AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(3L);
        t.setName("测试员");
        t.setStatus(AiTrader.STATUS_RUNNING);
        t.setSymbols("BTCUSDT");
        t.setIntervalCode("1h");
        t.setRoundNo(1);
        t.setSimUserId(99L);
        t.setConsecutiveFailures(0);
        // 显式声明规格：本类各用例的开仓量是 0.01×100000/10=保证金1%，区间放宽到 1~50% 容得下；
        // 杠杆仍走默认 3~20，护栏拒绝用例（leverage=50）才拦得住
        t.setMarginPctMin(new BigDecimal("1"));
        t.setMarginPctMax(new BigDecimal("50"));
        return t;
    }

    private static Flux<ChatResponse> frameOf(AssistantMessage message) {
        return Flux.just(new ChatResponse(List.of(new Generation(message))));
    }

    /** 模型第一轮调 open_position、第二轮给总结文本。 */
    private ChatModel modelOpeningThenSummary(String openArgsJson) {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AssistantMessage openCall = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "open_position", openArgsJson)))
                .build();
        when(model.stream(any(Prompt.class))).thenReturn(
                frameOf(openCall),
                frameOf(new AssistantMessage("突破前高放量，做多并挂好止损，本轮结束。")));
        return model;
    }

    private void stubHealthyAccount() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of());
        when(simTradeClient.getBalanceDetail(99L)).thenReturn(Map.of("balance", "10000", "frozenBalance", "0"));
        when(simTradeClient.getPendingOrders(99L, null)).thenReturn(List.of());
        when(binanceRestClient.getPremiumIndex("BTCUSDT"))
                .thenReturn("{\"markPrice\":\"100000\",\"nextFundingTime\":0,\"lastFundingRate\":\"0.0001\"}");
        when(decisionMapper.selectList(any())).thenReturn(List.of());
        when(planMapper.selectList(any())).thenReturn(List.of());
    }

    /** 模型第一轮调指定工具、第二轮给总结。 */
    private ChatModel modelCallingThenSummary(String tool, String argsJson) {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AssistantMessage call = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", tool, argsJson)))
                .build();
        when(model.stream(any(Prompt.class))).thenReturn(
                frameOf(call),
                frameOf(new AssistantMessage("已提请主人确认，本轮其余保持不动。")));
        return model;
    }

    /** 模型第一轮调 get_account、第二轮给总结（HOLD 场景）。 */
    private ChatModel modelCheckingThenSummary() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AssistantMessage check = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "get_account", "{}")))
                .build();
        when(model.stream(any(Prompt.class))).thenReturn(
                frameOf(check),
                frameOf(new AssistantMessage("持仓符合计划，本轮 HOLD。")));
        return model;
    }

    private static FuturesPositionDTO crossPosition(String margin, String unrealizedPnl) {
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setId(349L);
        p.setSymbol("BTCUSDT");
        p.setSide("LONG");
        p.setMarginMode(FuturesPosition.CROSS);
        p.setQuantity(new BigDecimal("0.2"));
        p.setEntryPrice(new BigDecimal("100000"));
        p.setMargin(new BigDecimal(margin));
        p.setUnrealizedPnl(new BigDecimal(unrealizedPnl));
        return p;
    }

    @Test
    void fullLoopOpensPositionAndPersistsDecision() {
        stubHealthyAccount();
        // 先建好再 stub：thenReturn 参数里嵌套 when() 是 UnfinishedStubbing
        ChatModel model = modelOpeningThenSummary("""
                {"symbol":"BTCUSDT","side":"LONG","orderType":"MARKET","quantity":0.01,"leverage":10,
                 "limitPrice":null,"stopLossPrice":95000,"takeProfitPrice":110000,
                 "playType":"BREAKOUT","signalsUsed":"突破前高+量比1.8",
                 "invalidationCondition":"1h收盘跌回前高98000下方"}""");
        when(modelFactory.modelFor(any())).thenReturn(model);
        FuturesOrderResponse resp = new FuturesOrderResponse();
        when(simTradeClient.openPosition(eq(99L), any())).thenReturn(resp);
        // 真库 insert 回填自增 id，run_end 帧要带它
        when(decisionMapper.insert(any(AiTraderDecision.class))).thenAnswer(inv -> {
            inv.<AiTraderDecision>getArgument(0).setId(1234L);
            return 1;
        });
        // 现场出口唤醒前就连上
        Frames owner = new Frames();
        hub.subscribeTrader(7L, owner);

        runner.wake(trader(), 1785171600000L);

        // 真开仓：请求带止损与止盈
        ArgumentCaptor<FuturesOpenRequest> open = ArgumentCaptor.forClass(FuturesOpenRequest.class);
        verify(simTradeClient).openPosition(eq(99L), open.capture());
        assertThat(open.getValue().getStopLosses()).hasSize(1);
        assertThat(open.getValue().getTakeProfits()).hasSize(1);
        assertThat(open.getValue().getMemo()).contains("BREAKOUT");
        // 全仓必须显式声明：sim 缺省虽也是 CROSS，但权益口径依赖这个事实，不许靠远端默认值
        assertThat(open.getValue().getMarginMode()).isEqualTo(FuturesPosition.CROSS);

        // 决策行：OK + 推理全文 + 论点标签进动作轨迹 + 权益
        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        AiTraderDecision d = dec.getValue();
        assertThat(d.getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
        assertThat(d.getReasoning()).contains("本轮结束");
        assertThat(d.getActionsJson()).contains("open_position").contains("BREAKOUT");
        assertThat(d.getEquity()).isEqualByComparingTo("10000");
        assertThat(d.getToolCalls()).isGreaterThanOrEqualTo(1);

        // 过程轨迹落库：提示词、第 1 次调用想调的工具与回执、第 2 次调用的正文、收尾
        JSONObject trace = JSON.parseObject(d.getTraceJson());
        assertThat(trace.getIntValue("v")).isEqualTo(1);
        assertThat(trace.getString("kind")).isEqualTo(AiTraderDecision.KIND_TRADE);
        assertThat(trace.getJSONObject("prompt").getString("system")).isNotBlank();
        assertThat(trace.getJSONObject("prompt").getString("instruction")).contains("【当前账户】");
        JSONArray calls = trace.getJSONArray("calls");
        assertThat(calls).hasSize(2);
        assertThat(calls.getJSONObject(0).getJSONArray("toolCalls").getJSONObject(0).getString("name")).isEqualTo("open_position");
        assertThat(calls.getJSONObject(0).getJSONArray("toolCalls").getJSONObject(0).getJSONObject("args").getString("playType")).isEqualTo("BREAKOUT");
        assertThat(calls.getJSONObject(0).getJSONArray("results").getJSONObject(0).getString("status")).isEqualTo("ok");
        assertThat(calls.getJSONObject(1).getString("text")).isEqualTo("突破前高放量，做多并挂好止损，本轮结束。");
        assertThat(trace.getJSONObject("end").getString("status")).isEqualTo(AiTraderDecision.STATUS_OK);

        // 现场帧序
        assertThat(owner.events).containsExactly("run_start", "prompt", "model_start", "model_end", "tool_result",
                "model_start", "token", "model_end", "run_end");
        assertThat(owner.last("run_end").getLong("decisionId")).isEqualTo(1234L);
        assertThat(owner.last("tool_result").getString("name")).isEqualTo("open_position");
        assertThat(owner.last("token").getString("text")).contains("本轮结束");
    }

    /** 唤醒"最近决策"回注同样过 stale：被忽略交易的分段不注入；行本身保留——行头时刻是唤醒事实 */
    @Test
    void recentDecisionsInjectionScrubsStaleSegments() {
        stubHealthyAccount();
        long prevWake = 1785171600000L - 3600_000L;
        AiTraderDecision prev = new AiTraderDecision();
        prev.setWakeTime(prevWake);
        prev.setKind(AiTraderDecision.KIND_TRADE);
        prev.setStatus(AiTraderDecision.STATUS_OK);
        prev.setEquity(new BigDecimal("10000"));
        prev.setReasoning("[本轮结论]\n[BTCUSDT]\n动作：HOLD\n等待：回踩 63400 做多"
                + "\n[ETHUSDT]\n动作：HOLD\n等待：站上 1925 做多");
        when(decisionMapper.selectList(any())).thenReturn(List.of(prev));
        AiTraderPlan stale = new AiTraderPlan();
        stale.setSymbol("BTCUSDT");
        stale.setSide("LONG");
        stale.setStatus(AiTraderPlan.STATUS_CLOSED);
        stale.setStale(true);
        stale.setOpenedWakeTime(prevWake - 7200_000L);
        stale.setClosedWakeTime(prevWake + 1800_000L);
        stale.setPositionId(42L);
        when(planMapper.selectList(any())).thenReturn(List.of(stale));
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.wake(trader(), 1785171600000L);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model, org.mockito.Mockito.atLeastOnce()).stream(prompt.capture());
        String injected = prompt.getAllValues().get(0).getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText).reduce("", String::concat);
        assertThat(injected).contains("站上 1925").doesNotContain("回踩 63400");
        // 行头时刻保留：警报开场白的"上次唤醒在X"要用真时刻，剔的是内容不是行
        assertThat(injected).contains(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(prevWake)));
    }

    /**
     * 永远只想再查一次账户、永不给总结的模型 → 只能靠保险丝收束（每轮独立 call id，同真实模型口径）。
     * contentOf 决定第 i 轮的正文——真实 Responses 调工具那一轮正文往往就是空串。
     */
    private ChatModel modelLoopingToolCalls(java.util.function.IntFunction<String> contentOf) {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AtomicInteger n = new AtomicInteger();
        when(model.stream(any(Prompt.class))).thenAnswer(inv -> {
            int i = n.incrementAndGet();
            AssistantMessage call = AssistantMessage.builder().content(contentOf.apply(i))
                    .toolCalls(List.of(new AssistantMessage.ToolCall("c" + i, "function", "get_account", "{}")))
                    .build();
            return frameOf(call);
        });
        return model;
    }

    /** 调用上限到顶不许默默截断：决策行必须标注提前收束（否则时间线上像正常决策） */
    @Test
    void callCapAnnotatedOnDecision() {
        stubHealthyAccount();
        ChatModel model = modelLoopingToolCalls(i -> "再查一次(" + i + ")");
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.wake(trader(), 1785171600000L);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
        assertThat(dec.getValue().getError()).contains("上限");
    }

    /**
     * 保险丝收束时最后一条是纯 tool_call（正文空）：取正文不能只看最后一条，
     * 否则决策行 status=OK 却一个字都没有——竞技场时间线上就是一条没有正文的"正常决策"。
     * 往前找最近一条有正文的助手消息。
     */
    @Test
    void callCapFallsBackToLastTextualReasoning() {
        stubHealthyAccount();
        // 首轮留下正文，其后全是空正文的纯 tool_call
        ChatModel model = modelLoopingToolCalls(i -> i == 1 ? "账户没问题，倾向继续持有等突破确认。" : "");
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.wake(trader(), 1785171600000L);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
        assertThat(dec.getValue().getReasoning()).contains("倾向继续持有");
    }

    /** 全程一个字都没说过：给一句说明而不是留空串——空串在时间线上无法与"模型真没话说"区分 */
    @Test
    void callCapWithoutAnyTextGivesExplanationNotBlank() {
        stubHealthyAccount();
        ChatModel model = modelLoopingToolCalls(i -> "");
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.wake(trader(), 1785171600000L);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getReasoning()).isNotBlank();
    }

    /** close_position 进来就是市价单，中间没有别的跳转 */
    @Test
    void reduceExecutesDirectly() {
        stubHealthyAccount();
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(crossPosition("2000", "0")));
        ChatModel model = modelCallingThenSummary("close_position",
                "{\"positionId\":349,\"quantity\":0.1,\"reason\":\"到目标位\"}");
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.wake(trader(), 1785171600000L);

        verify(simTradeClient).closePosition(eq(99L), any());
    }

    @Test
    void guardRejectionIsRecordedAndNoOrderSent() {
        stubHealthyAccount();
        // 杠杆50违规 → TradeGuard 拒绝 → 模型收到拒绝原因后给总结
        ChatModel model = modelOpeningThenSummary("""
                {"symbol":"BTCUSDT","side":"LONG","orderType":"MARKET","quantity":0.01,"leverage":50,
                 "limitPrice":null,"stopLossPrice":95000,"takeProfitPrice":null,
                 "playType":"BREAKOUT","signalsUsed":"x","invalidationCondition":"跌回箱体"}""");
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.wake(trader(), 1785171600000L);

        verify(simTradeClient, never()).openPosition(anyLong(), any());
        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
        assertThat(dec.getValue().getActionsJson()).contains("rejected").contains("杠杆");
    }

    /** key 失效（401）第一败就暂停，不等攒满 5 败：判的是异常本身，不是成文后的话 */
    @Test
    void keyInvalidPausesOnFirstFailure() {
        stubHealthyAccount();
        when(modelFactory.modelFor(any())).thenThrow(new IllegalStateException("Responses API HTTP 401: invalid key"));

        runner.wake(trader(), 1785171600000L);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiTrader>> u =
                ArgumentCaptor.forClass((Class) com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(traderMapper).update(any(), u.capture());
        assertThat(u.getValue().getParamNameValuePairs().values())
                .contains(AiTrader.STATUS_PAUSED, prompts.get(AgentLang.ZH, "trader.error.keyInvalid"));
    }

    @Test
    void modelFailureRecordsErrorAndPausesAfterFifthConsecutive() {
        stubHealthyAccount();
        when(modelFactory.modelFor(any())).thenThrow(new IllegalStateException("上游401"));
        AiTrader t = trader();
        t.setConsecutiveFailures(4); // 本次是第5败

        runner.wake(t, 1785171600000L);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
        // 公开行只存归类文案，上游原文不落库
        assertThat(dec.getValue().getError()).contains("API key").doesNotContain("上游401");
        verify(traderMapper).update(any(), any()); // 连败暂停走列级更新
    }

    @Test
    void liquidationEndsRoundWithoutModelCall() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of());
        when(simTradeClient.getBalanceDetail(99L)).thenReturn(Map.of("balance", "50", "frozenBalance", "0"));

        runner.wake(trader(), 1785171600000L);

        verify(modelFactory, never()).modelFor(any());
        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getReasoning()).contains("爆仓终局");
        verify(traderMapper).update(any(), any()); // LIQUIDATED 列级更新
    }

    /**
     * 全仓保证金从没离开余额（占用制），权益再加仓位 margin 就是同一笔钱计两遍——
     * 竞技场"亏损却显示 +1281"的根因。口径对齐 sim AssetValuationService：全仓仓位只计浮盈亏。
     */
    @Test
    void crossMarginNotDoubleCountedInEquity() {
        // 余额 9995（开仓只扣过手续费）+ 全仓仓位(margin=1000, 浮亏5) → 真实权益 9990；旧口径算出 10990
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(crossPosition("1000", "-5")));
        when(simTradeClient.getBalanceDetail(99L)).thenReturn(Map.of("balance", "9995", "frozenBalance", "0"));
        when(simTradeClient.getBalance(99L)).thenReturn(new BigDecimal("9995"));
        when(simTradeClient.getPendingOrders(99L, null)).thenReturn(List.of());
        when(decisionMapper.selectList(any())).thenReturn(List.of());
        // 先建好再 stub：thenReturn 参数里嵌套 when() 是 UnfinishedStubbing
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.wake(trader(), 1785171600000L);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
        assertThat(dec.getValue().getEquity()).isEqualByComparingTo("9990");
    }

    /** 权益要在动作落地后记录：本轮的开平仓立刻体现在净值曲线上，不许滞后一根K线。 */
    @Test
    void equityRecordedAfterActionsNotWakeSnapshot() {
        // 唤醒时空仓余额 10000；agent 开全仓多单扣手续费 5 → 动作后余额 9995 + 仓位浮盈亏 0，应记 9995
        when(simTradeClient.getAllPositions(99L)).thenReturn(
                List.of(), List.of(crossPosition("1000", "0")));
        when(simTradeClient.getBalanceDetail(99L)).thenReturn(
                Map.of("balance", "10000", "frozenBalance", "0"),
                Map.of("balance", "9995", "frozenBalance", "0"));
        when(simTradeClient.getPendingOrders(99L, null)).thenReturn(List.of());
        when(binanceRestClient.getPremiumIndex("BTCUSDT"))
                .thenReturn("{\"markPrice\":\"100000\",\"nextFundingTime\":0,\"lastFundingRate\":\"0.0001\"}");
        when(decisionMapper.selectList(any())).thenReturn(List.of());
        when(planMapper.selectList(any())).thenReturn(List.of());
        ChatModel model = modelOpeningThenSummary("""
                {"symbol":"BTCUSDT","side":"LONG","orderType":"MARKET","quantity":0.01,"leverage":10,
                 "limitPrice":null,"stopLossPrice":95000,"takeProfitPrice":110000,
                 "playType":"BREAKOUT","signalsUsed":"突破前高",
                 "invalidationCondition":"1h收盘跌回98000下方"}""");
        when(modelFactory.modelFor(any())).thenReturn(model);
        when(simTradeClient.openPosition(eq(99L), any())).thenReturn(new FuturesOrderResponse());

        runner.wake(trader(), 1785171600000L);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getEquity()).isEqualByComparingTo("9995");
    }

    /**
     * 会话已成功 = 这轮就是 OK：动作后的权益刷新是锦上添花，sim 抖一下不许把整轮判 ERROR、
     * 更不许计连败（连 5 次自动 PAUSED，而每一轮其实都成功、单也都下出去了）。
     * 权益回落到会话开始前那次快照。
     */
    @Test
    void equityRefreshFailureKeepsRoundOk() {
        stubHealthyAccount();
        // getBalanceDetail 只有 computeEquity 在调（TradeTools 走的是 getBalance），
        // 所以第二次调用 = 动作后的权益刷新，精准打在那一步上
        when(simTradeClient.getBalanceDetail(99L))
                .thenReturn(Map.of("balance", "10000", "frozenBalance", "0"))
                .thenThrow(new RuntimeException("sim 502"));
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.wake(trader(), 1785171600000L);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
        assertThat(dec.getValue().getReasoning()).contains("本轮 HOLD");
        assertThat(dec.getValue().getEquity()).isEqualByComparingTo("10000"); // 刷新前的快照
        verify(traderMapper, never()).update(any(), any()); // 没有 recordFailure，连败不涨
    }

    /**
     * 决策行落库后的收尾 DB 操作（clearFailures）抖一下，不许把同一个 decision 再 insert 一次：
     * MP 自增主键 insert 后会把 id 回填进实体，二次 insert 必撞主键，异常直接逃出唤醒回路——
     * 调度器的虚拟线程只 catch InterruptedException，兜不住。
     */
    @Test
    void decisionInsertedOnceWhenPostInsertDbFails() {
        stubHealthyAccount();
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);
        // 照搬 MP + 真库的行为：insert 成功回填自增 id，带着 id 再 insert 就是主键冲突
        when(decisionMapper.insert(any(AiTraderDecision.class))).thenAnswer(inv -> {
            AiTraderDecision d = inv.getArgument(0);
            if (d.getId() != null) {
                throw new RuntimeException("Duplicate entry '" + d.getId() + "' for key 'PRIMARY'");
            }
            d.setId(1234L);
            return 1;
        });
        when(traderMapper.update(any(), any())).thenThrow(new RuntimeException("连接池耗尽"));
        AiTrader t = trader();
        t.setConsecutiveFailures(2); // >0 才会真走 clearFailures 的列级更新

        assertThatCode(() -> runner.wake(t, 1785171600000L)).doesNotThrowAnyException();

        verify(decisionMapper, times(1)).insert(any(AiTraderDecision.class));
    }

    /** 开仓成功 → 计划落库：论点/失效条件/止损止盈快照 + 开仓所在唤醒边界。 */
    @Test
    void openPersistsPlanWithInvalidationCondition() {
        stubHealthyAccount();
        ChatModel model = modelOpeningThenSummary("""
                {"symbol":"BTCUSDT","side":"LONG","orderType":"MARKET","quantity":0.01,"leverage":10,
                 "limitPrice":null,"stopLossPrice":95000,"takeProfitPrice":110000,
                 "playType":"BREAKOUT","signalsUsed":"突破前高+量比1.8",
                 "invalidationCondition":"1h收盘跌回前高98000下方"}""");
        when(modelFactory.modelFor(any())).thenReturn(model);
        when(simTradeClient.openPosition(eq(99L), any())).thenReturn(new FuturesOrderResponse());
        when(planMapper.selectOne(any())).thenReturn(null);

        runner.wake(trader(), 1785171600000L);

        ArgumentCaptor<AiTraderPlan> plan = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).insert(plan.capture());
        AiTraderPlan p = plan.getValue();
        assertThat(p.getTraderId()).isEqualTo(7L);
        assertThat(p.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(p.getSide()).isEqualTo("LONG");
        assertThat(p.getInvalidationCondition()).contains("98000");
        assertThat(p.getStopLossPrice()).isEqualByComparingTo("95000");
        assertThat(p.getOpenedWakeTime()).isEqualTo(1785171600000L);
    }

    /** 持仓的计划必须回注开场白（user 消息）：醒来的模型不再是失忆的新人——恐慌平仓的根治；system 里不再有账户 JSON */
    @Test
    void positionPlanInjectedIntoOpening() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(crossPosition("1000", "-5")));
        when(simTradeClient.getBalanceDetail(99L)).thenReturn(Map.of("balance", "9995", "frozenBalance", "0"));
        when(simTradeClient.getBalance(99L)).thenReturn(new BigDecimal("9995"));
        when(simTradeClient.getPendingOrders(99L, null)).thenReturn(List.of());
        when(decisionMapper.selectList(any())).thenReturn(List.of());
        AiTraderPlan plan = new AiTraderPlan();
        plan.setId(21L);
        plan.setSymbol("BTCUSDT");
        plan.setSide("LONG");
        plan.setPlayType("REVERSAL");
        plan.setSignalsUsed("4h RSI 底背离");
        plan.setInvalidationCondition("1h收盘跌回64200箱体内");
        plan.setOpenedWakeTime(1785171600000L - 3600_000);
        // 修订历史也要回注：无记忆的模型必须看到"上轮为什么动了目标"
        plan.setRevisionsJson("[{\"time\":1785169800000,\"type\":\"移动止盈\","
                + "\"change\":\"66000→68000\",\"reason\":\"趋势加速看下一压力位\"}]");
        when(planMapper.selectList(any())).thenReturn(List.of(plan));
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.wake(trader(), 1785171600000L);

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, atLeastOnce()).stream(prompts.capture());
        List<org.springframework.ai.chat.messages.Message> first = prompts.getAllValues().get(0).getInstructions();
        String system = first.stream().filter(m -> m instanceof org.springframework.ai.chat.messages.SystemMessage)
                .map(org.springframework.ai.chat.messages.Message::getText).reduce("", String::concat);
        String user = first.stream().filter(m -> m instanceof org.springframework.ai.chat.messages.UserMessage)
                .map(org.springframework.ai.chat.messages.Message::getText).reduce("", String::concat);
        assertThat(user).contains("【当前账户】").contains("1h收盘跌回64200箱体内").contains("REVERSAL")
                .contains("趋势加速看下一压力位");
        assertThat(system).doesNotContain("\"equity\"").doesNotContain("1h收盘跌回64200箱体内");
    }

    /** 上一轮结论整块原样回注（多币段一个不少、不截断）；更早的轮次只留一行轨迹（时刻/状态/权益/工具名） */
    @Test
    void latestConclusionInjectedWholeAndOlderRowsAsOneLine() {
        stubHealthyAccount();
        long boundary = 1785171600000L;
        AiTraderDecision latest = new AiTraderDecision();
        latest.setWakeTime(boundary - 3600_000L);
        latest.setKind(AiTraderDecision.KIND_TRADE);
        latest.setStatus(AiTraderDecision.STATUS_OK);
        latest.setEquity(new BigDecimal("10123.45"));
        // 300 字铺垫 + 三个币段各 400 字判断：整块远超旧口径的 1000 字尾截
        latest.setReasoning("行情铺垫".repeat(75) + "\n[本轮结论]\n"
                + "[BTCUSDT]\n判断：" + "x".repeat(400) + "\n动作：HOLD\n等待：BTC等待A\n"
                + "[ETHUSDT]\n判断：" + "y".repeat(400) + "\n动作：HOLD\n等待：ETH等待B\n"
                + "[SOLUSDT]\n判断：" + "z".repeat(400) + "\n动作：HOLD\n等待：SOL等待C");
        AiTraderDecision older = new AiTraderDecision();
        older.setWakeTime(boundary - 7200_000L);
        older.setKind(AiTraderDecision.KIND_TRADE);
        older.setStatus(AiTraderDecision.STATUS_OK);
        older.setEquity(new BigDecimal("10050"));
        older.setReasoning("[本轮结论]\n[BTCUSDT]\n等待：旧等待条件XYZ");
        older.setActionsJson("[{\"tool\":\"klines\",\"args\":{}},{\"tool\":\"klines\",\"args\":{}}]");
        when(decisionMapper.selectList(any())).thenReturn(List.of(latest, older));
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.wake(trader(), boundary);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model, atLeastOnce()).stream(prompt.capture());
        String injected = prompt.getAllValues().get(0).getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText).reduce("", String::concat);
        assertThat(injected)
                .contains("【上一轮结论】")
                .contains("BTC等待A").contains("ETH等待B").contains("SOL等待C")
                .doesNotContain("旧等待条件XYZ")
                .contains("工具：klines×2");
    }

    /**
     * 仓位在两次唤醒之间被止损带走：唤醒开头把计划归档，开场白的事件块配上 sim 已平仓位把结局说清
     * （了结方式/成交价/盈亏/当时的失效条件），模型不必靠"仓位不见了"自己猜。
     */
    @Test
    void closedPositionEventInjectedIntoOpening() {
        stubHealthyAccount();
        AiTraderPlan plan = new AiTraderPlan();
        plan.setId(21L);
        plan.setSymbol("BTCUSDT");
        plan.setSide("LONG");
        plan.setStatus(AiTraderPlan.STATUS_LIVE);
        plan.setPositionId(42L);
        plan.setPlayType("REVERSAL");
        plan.setInvalidationCondition("1h收盘跌回64200");
        plan.setOpenedWakeTime(1785171600000L - 3600_000L);
        when(planMapper.selectList(any())).thenReturn(List.of(plan));
        FuturesPositionDTO closed = new FuturesPositionDTO();
        closed.setId(42L);
        closed.setSymbol("BTCUSDT");
        closed.setSide("LONG");
        closed.setClosedPrice(new BigDecimal("63800"));
        closed.setClosedPnl(new BigDecimal("-120.5"));
        com.mawai.wiibcommon.entity.FuturesStopLoss sl = new com.mawai.wiibcommon.entity.FuturesStopLoss();
        sl.setPrice(new BigDecimal("63800"));
        closed.setStopLosses(List.of(sl));
        when(simTradeClient.getClosedPositions(99L, 200)).thenReturn(List.of(closed));
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.wake(trader(), 1785171600000L);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model, atLeastOnce()).stream(prompt.capture());
        String user = prompt.getAllValues().get(0).getInstructions().stream()
                .filter(m -> m instanceof org.springframework.ai.chat.messages.UserMessage)
                .map(org.springframework.ai.chat.messages.Message::getText).reduce("", String::concat);
        assertThat(user).contains("【自上次唤醒以来】").contains("止损带走").contains("-120.50")
                .contains("1h收盘跌回64200");
        // 事件块排在账户之前
        assertThat(user.indexOf("【自上次唤醒以来】")).isLessThan(user.indexOf("【当前账户】"));
    }

    /** 战绩块归观察包：排在轨迹之后、行情快照之前 */
    @Test
    void playStatsInjectedAfterTrajectoryBeforeSnapshot() {
        stubHealthyAccount();
        when(playStats.assemble(any(), any())).thenReturn("STATS_BLOCK\n");
        AiTraderDecision prev = new AiTraderDecision();
        prev.setWakeTime(1785171600000L - 3600_000L);
        prev.setKind(AiTraderDecision.KIND_TRADE);
        prev.setStatus(AiTraderDecision.STATUS_OK);
        prev.setEquity(new BigDecimal("10000"));
        prev.setReasoning("[本轮结论]\n[BTCUSDT]\n动作：HOLD\n等待：无");
        when(decisionMapper.selectList(any())).thenReturn(List.of(prev));
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.wake(trader(), 1785171600000L);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model, atLeastOnce()).stream(prompt.capture());
        String user = prompt.getAllValues().get(0).getInstructions().stream()
                .filter(m -> m instanceof org.springframework.ai.chat.messages.UserMessage)
                .map(org.springframework.ai.chat.messages.Message::getText).reduce("", String::concat);
        assertThat(user.indexOf("【最近唤醒轨迹】")).isLessThan(user.indexOf("STATS_BLOCK"));
        assertThat(user.indexOf("STATS_BLOCK")).isLessThan(user.indexOf("行情快照"));
    }

    /** 仓位已了结（止损/止盈/平仓殊途同归）→ 计划完成使命，唤醒时懒归档（不删：复盘原料）。 */
    @Test
    void stalePlanArchivedWhenPositionGone() {
        stubHealthyAccount();
        when(simTradeClient.getBalance(99L)).thenReturn(new BigDecimal("10000"));
        AiTraderPlan stale = new AiTraderPlan();
        stale.setId(11L);
        stale.setSymbol("BTCUSDT");
        stale.setSide("LONG");
        stale.setInvalidationCondition("x");
        stale.setOpenedWakeTime(1785168000000L);
        when(planMapper.selectList(any())).thenReturn(List.of(stale));
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.wake(trader(), 1785171600000L);

        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(AiTraderPlan.STATUS_CLOSED);
        assertThat(cap.getValue().getClosedWakeTime()).isEqualTo(1785171600000L);
    }

    /** 预算 = 距下一边界−5s，上限600s：唤醒决不占用下一根K线（超时级联跳过的根治）。 */
    @Test
    void wakeBudgetAlignsToNextBoundary() {
        long b = 1785171600000L;
        // 5m 正点触发：300−5=295s
        assertThat(TraderWakeupRunner.wakeBudgetSeconds(b, 300_000, b)).isEqualTo(295);
        // 5m 迟到213s（今天09:20实测场景）：只剩 82s
        assertThat(TraderWakeupRunner.wakeBudgetSeconds(b, 300_000, b + 213_000)).isEqualTo(82);
        // 1h 正点：3595s 被 600s 上限压住
        assertThat(TraderWakeupRunner.wakeBudgetSeconds(b, 3_600_000, b)).isEqualTo(600);
    }

    /** 事件迟到太多（距下一边界<30s）：放弃本轮记 SKIPPED，不调模型、不算失败。 */
    @Test
    void lateWakeSkippedWhenBudgetBelowFloor() {
        AiTrader t = trader(); // 1h：边界+3580s 时触发 → 预算 15s < 30s
        runner.nowMs = () -> 1785171600000L + 3_580_000L;

        runner.wake(t, 1785171600000L);

        verify(modelFactory, never()).modelFor(any());
        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_SKIPPED);
        assertThat(dec.getValue().getError()).contains("过晚");
        verify(traderMapper, never()).update(any(), any()); // 不计连败
    }

    /**
     * 手动唤醒落 MANUAL 行：回路同例行，但时间线要看得出扳机在人手里（借预算不足路径免 mock 模型）。
     * wakeTime 是按下按钮那一刻而不是对齐边界——回注查询 wake_time < 本次 才带得上同一根K线上的例行决策
     */
    @Test
    void manualWakeStampsManualKind() {
        AiTrader t = trader();
        runner.nowMs = () -> 1785171600000L + 3_580_000L; // 预算 15s < 30s → SKIPPED 落库

        runner.wakeManual(t, 1785171600000L);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getKind()).isEqualTo(AiTraderDecision.KIND_MANUAL);
        assertThat(dec.getValue().getWakeTime()).isEqualTo(1785171600000L + 3_580_000L);
    }

    /** 手动轮开场白：头部"已收盘的那根"按对齐边界说，不是按下按钮那一刻 */
    @Test
    void manualWakeOpeningHeaderUsesAlignedBoundary() {
        long boundary = 1785171600000L;
        long pressed = boundary + 37 * 60_000L;
        runner.nowMs = () -> pressed;

        String opening = runner.routineInstruction(trader(), pressed, "", "", null, AgentLang.ZH, "");

        assertThat(opening).contains("已收盘（" + java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
                .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(boundary)) + "）");
    }

    /** 数据工具（klines等）没有自己的记录点，必须经轨迹收集器进 actionsJson——"调用了哪些工具"要完整。 */
    @Test
    void dataToolCallsTracedIntoActions() {
        stubHealthyAccount();
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AssistantMessage klinesCall = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "klines",
                        "{\"symbol\":\"BTCUSDT\",\"interval\":\"1h\",\"limit\":50}")))
                .build();
        when(model.stream(any(Prompt.class))).thenReturn(
                frameOf(klinesCall),
                frameOf(new AssistantMessage("看完K线，本轮 HOLD。")));
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.wake(trader(), 1785171600000L);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getActionsJson()).contains("klines").contains("BTCUSDT");
        assertThat(dec.getValue().getToolCalls()).isGreaterThanOrEqualTo(1);
    }

    /** 波动警报唤醒：kind=ALERT、wake_time=触发时刻；开场白含警报事实与反锚定（未收盘不作数/不必动作） */
    @Test
    void alertWakeUsesAlertInstructionAndKind() {
        stubHealthyAccount();
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(crossPosition("1000", "-5")));
        when(simTradeClient.getBalance(99L)).thenReturn(new BigDecimal("9995"));
        when(simTradeClient.getBalanceDetail(99L)).thenReturn(Map.of("balance", "9995", "frozenBalance", "0"));
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);
        long triggeredAt = 1785171600000L + 600_000L; // 1h 边界后 10 分钟哨兵触发
        runner.nowMs = () -> triggeredAt;

        runner.wakeAlert(trader(), new AlertTrigger("BTCUSDT", new BigDecimal("1.2"),
                new BigDecimal("63120"), AlertTrigger.DOWN, triggeredAt));

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getKind()).isEqualTo(AiTraderDecision.KIND_ALERT);
        assertThat(dec.getValue().getWakeTime()).isEqualTo(triggeredAt);
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, atLeastOnce()).stream(prompts.capture());
        String firstCall = prompts.getAllValues().get(0).getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText)
                .reduce("", String::concat);
        assertThat(firstCall).contains("行情波动警报").contains("1.2%").contains("下跌")
                .contains("尚未收盘").contains("不因为被叫醒而必须动作");
    }

    /**
     * recent 回注窗口只认交易行白名单（TRADE/ALERT/MANUAL）：REVIEW/LEARN 的产出已经走
     * memory/learning_notes 注入，混进 5 条窗口就是重复占字数；ALERT/MANUAL 是真实交易决策必须保留。
     */
    @Test
    void recentDecisionsQueryExcludesReviewRows() {
        stubHealthyAccount();
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);

        runner.wake(trader(), 1785171600000L);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiTraderDecision>> q =
                ArgumentCaptor.forClass((Class) com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(decisionMapper).selectList(q.capture());
        assertThat(q.getValue().getSqlSegment()).contains("kind IN");
        var values = q.getValue().getParamNameValuePairs().values();
        assertThat(values).contains(AiTraderDecision.KIND_TRADE,
                AiTraderDecision.KIND_ALERT, AiTraderDecision.KIND_MANUAL);
        assertThat(values).doesNotContain(AiTraderDecision.KIND_REVIEW, AiTraderDecision.KIND_LEARN);
    }

    @Test
    void skippedWakeLeavesTrace() {
        runner.recordSkipped(trader(), 1785171600000L);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_SKIPPED);
    }
}
