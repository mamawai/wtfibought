package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.mapper.AiTraderWatchMapper;
import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.market.TradeEvent;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibagent.toolkit.CrossAssetToolkit;
import com.mawai.wiibagent.toolkit.DerivativesToolkit;
import com.mawai.wiibagent.toolkit.IndicatorToolkit;
import com.mawai.wiibagent.toolkit.WhaleToolkit;
import com.mawai.wiibquant.market.service.CrossAssetService;
import com.mawai.wiibquant.market.service.KlineFetcher;
import com.mawai.wiibquant.market.service.MarketDataService;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibquant.market.service.NewsCache;
import com.mawai.wiibquant.market.service.NewsFlashLocalizer;
import com.mawai.wiibagent.toolkit.NewsToolkit;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.learning.ExperienceLedger;
import com.mawai.wiibagent.llm.SearchEvent;
import com.mawai.wiibagent.llm.ResponsesChatModel;
import com.mawai.wiibagent.llm.SseChatModel;
import com.sun.net.httpserver.HttpServer;
import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.trader.DecisionText;
import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibagent.trader.TraderModelFactory;
import com.mawai.wiibagent.trader.prompt.EconCalendarAssembler;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
import com.mawai.wiibagent.trader.prompt.TraderPromptAssembler;
import com.mawai.wiibagent.trader.trade.AccountGates;
import com.mawai.wiibagent.trader.trade.EntryContext;
import com.mawai.wiibagent.trader.trade.TradeGuard;
import com.mawai.wiibagent.trader.trade.TradeTools;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
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
        final List<JsonNode> data = new ArrayList<>();

        @Override
        public boolean send(String event, ObjectNode d) {
            events.add(event);
            data.add(d);
            return true;
        }

        JsonNode last(String event) {
            return data.get(events.lastIndexOf(event));
        }
    }

    @BeforeAll
    static void initTableInfoCache() {
        // LambdaUpdateWrapper 纯单测需要实体 TableInfo 缓存（正常由 MP 启动时注册）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTrader.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderDecision.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderPlan.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderWatch.class);
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
    private final TraderPlanStore planStore = spy(new TraderPlanStore(planMapper, prompts, new org.springframework.transaction.support.TransactionTemplate(mock(org.springframework.transaction.PlatformTransactionManager.class))));
    private final DecisionText decisionText = new DecisionText(prompts);
    /** 默认（未打桩）返回 null = 本局无统计不注入 */
    private final PlayStatsAssembler playStats = mock(PlayStatsAssembler.class);
    private final TraderLiveHub hub = new TraderLiveHub();
    private final ExperienceLedger ledger = mock(ExperienceLedger.class);

    private final AiTraderWatchMapper watchMapper = mock(AiTraderWatchMapper.class);
    private final TraderWatchStore watchStore = new TraderWatchStore(watchMapper,
            mock(org.springframework.context.ApplicationEventPublisher.class));

    /** 与 runner 共用同一份 decisionMapper 和 playStats：selectList 打桩、insert 断言都还落在原处 */
    private final WakeObservation wakeObservation =
            new WakeObservation(decisionMapper, decisionText, playStats, prompts, mock(PositionPath.class),
                    mock(CrossAssetService.class), binanceRestClient);
    private final WakeFlows flows = new WakeFlows(prompts, wakeObservation, new TriggerText(prompts));
    private final TraderPromptAssembler promptAssembler = new TraderPromptAssembler(traderMapper, prompts, ledger);
    private final UnreportedEvents unreported = new UnreportedEvents(traderMapper);
    private final AccountGates gates = new AccountGates();
    private final WakeBriefing briefing = new WakeBriefing(simTradeClient, planStore, watchStore, gates, unreported,
            wakeObservation, promptAssembler, mock(EconCalendarAssembler.class));
    private final WakeSession session = new WakeSession(
            new IndicatorToolkit(new KlineFetcher(binanceRestClient, 60_000)),
            new MarketToolkit(mock(MarketDataService.class)),
            mock(DerivativesToolkit.class), mock(WhaleToolkit.class),
            new NewsToolkit(mock(NewsCache.class), mock(NewsFlashLocalizer.class)),
            mock(CrossAssetToolkit.class), new LocalizedToolCallbacks(prompts), prompts);

    private final TraderWakeupRunner runner = new TraderWakeupRunner(
            modelFactory, promptAssembler, simTradeClient, binanceRestClient,
            traderMapper, decisionMapper, planStore, watchStore, langResolver,
            prompts, new MessageCatalog(), briefing, flows, session, new TriggerText(prompts), gates, unreported, hub,
            mock(EntryContext.class), decisionText, ledger, mock(BinanceProperties.class), mock(PresetExecutor.class));

    /** 例行轮：整盘 */
    private void wake(AiTrader t, long boundary) {
        runner.wake(t, boundary, round(t, null));
    }

    private void wakeManual(AiTrader t, long boundary) {
        runner.wakeManual(t, boundary, round(t, null));
    }

    /** 被触发叫醒的单币轮：只处理 BTCUSDT */
    private void wakeTriggered(AiTrader t, List<WakeTrigger> triggers) {
        runner.wakeTriggered(t, triggers, round(t, "BTCUSDT"));
    }

    /** 在跑的一轮：symbol 为 null 是整盘轮；没有别的轮同时在跑 */
    private static WakeRound round(AiTrader t, String symbol) {
        return new WakeRound(WakeScope.of(t, symbol), Set::of);
    }

    /** 库里的那一行 trader，只有未报事件那一列跟着写入变 */
    private final AiTrader stored = new AiTrader();

    {
        // 测试边界是固定历史时刻，墙钟钉在边界后 1s——预算充足，各用例不受真实时间影响
        runner.nowMs = () -> 1785171600000L + 1_000L;
        flows.nowMs = runner.nowMs;
        briefing.nowMs = runner.nowMs;
        when(langResolver.of(anyLong())).thenReturn(AgentLang.ZH);
        // 权益从余额明细里读，各用例只桩明细
        when(simTradeClient.getEquity(anyLong())).thenCallRealMethod();
        // 库是 mock 的：未报事件那一列写进去什么，下一次就读到什么
        when(traderMapper.selectById(7L)).thenReturn(stored);
        when(traderMapper.update(any(), any())).thenAnswer(call -> {
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<AiTrader> w = call.getArgument(1);
            // 用例里再打桩时这里会被空参数调一次
            if (w != null && w.getSqlSet().contains("unreported_events")) {
                stored.setUnreportedEvents((String) w.getParamNameValuePairs().values().stream()
                        .filter(String.class::isInstance).findFirst().orElse(null));
            }
            return 1;
        });
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

    private static FuturesOrderResponse filledReceipt() {
        FuturesOrderResponse response = new FuturesOrderResponse();
        response.setStatus("FILLED"); response.setOrderId(1L);
        response.setFilledPrice(new BigDecimal("100000")); response.setCommission(new BigDecimal("0.4"));
        response.setUpdatedAt(java.time.LocalDateTime.now());
        return response;
    }

    private void stubHealthyAccount() {
        when(simTradeClient.getBrackets(any())).thenReturn(Map.of("BTCUSDT", List.of(
                new com.mawai.wiibquant.external.sim.SimTradeApi.Bracket(1, BigDecimal.ZERO, new BigDecimal("1000000000"), 150, BigDecimal.ZERO, BigDecimal.ZERO))));
        when(planMapper.selectByIdForUpdate(anyLong())).thenAnswer(call ->
                planMapper.selectList((com.baomidou.mybatisplus.core.conditions.Wrapper<AiTraderPlan>) null).stream()
                        .filter(p -> p.getId().equals(call.getArgument(0))).findFirst().orElse(null));
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of());
        when(simTradeClient.getBalanceDetail(99L)).thenReturn(Map.of("balance", "10000", "frozenBalance", "0", "equity", "10000"));
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

    private ChatModel onlineModel(java.util.function.IntFunction<AssistantMessage> reply) {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AtomicInteger calls = new AtomicInteger();
        when(model.stream(any(Prompt.class))).thenAnswer(inv -> frameOf(reply.apply(calls.incrementAndGet())));
        when(modelFactory.modelFor(any())).thenReturn(model);
        when(modelFactory.webSearchEnabled(any())).thenReturn(true);
        return model;
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROUTINE", "MANUAL", "ALERT", "EVENT", "WATCH", "MIXED"})
    void onlineWakeAssessesSearchOncePerBatchAndKeepsOnlyFinalDecision(String kind) {
        stubHealthyAccount();
        ChatModel model = onlineModel(n -> new AssistantMessage(switch (n) {
            case 1 -> "事实核查完成。";
            case 2 -> "现有材料足够，不需要联网。";
            default -> "[本轮结论]\nBTCUSDT：继续等待。";
        }));
        long at = 1785171600000L;
        AlertTrigger alert = new AlertTrigger("BTCUSDT", new BigDecimal("1.2"), new BigDecimal("100000"), AlertTrigger.DOWN, at);
        EventTrigger event = new EventTrigger(new TradeEvent(TradeEvent.LIMIT_FILLED, 99L, 9L, 100L, "BTCUSDT", "LONG",
                new BigDecimal("0.01"), new BigDecimal("100000"), null, at));
        AiTraderWatch watch = new AiTraderWatch();
        watch.setId(31L);
        watch.setSymbol("BTCUSDT");
        watch.setForSymbol("BTCUSDT");
        watch.setKind("CLOSE_ABOVE");
        watch.setLevel(new BigDecimal("99000"));
        watch.setPeriod("1h");
        watch.setNote("观察突破是否成立");
        WatchTrigger hit = new WatchTrigger(watch, new BigDecimal("100000"), at);
        switch (kind) {
            case "ROUTINE" -> wake(trader(), at);
            case "MANUAL" -> wakeManual(trader(), at);
            case "ALERT" -> wakeTriggered(trader(),List.of(alert));
            case "EVENT" -> wakeTriggered(trader(),List.of(event));
            case "WATCH" -> wakeTriggered(trader(),List.of(hit));
            case "MIXED" -> wakeTriggered(trader(),List.of(alert, event, hit));
        }

        // 整盘轮：核查事实 → 要不要搜 → 交易；单币轮：处理触发 → 回头查外部信息 → 给这个币收尾
        boolean book = kind.equals("ROUTINE") || kind.equals("MANUAL");
        Map<String, Object> coin = Map.of("symbol", "BTCUSDT", "mark", prompts.get(AgentLang.ZH, "trader.mark.conclusion"));
        String searchStage = book ? prompts.get(AgentLang.ZH, "trader.wake.searchQuestion")
                : prompts.get(AgentLang.ZH, "trader.wake.single.search", coin);
        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(3)).stream(sent.capture());
        List<Message> history = sent.getAllValues().getLast().getInstructions();
        assertThat(history.stream().filter(UserMessage.class::isInstance).map(Message::getText).toList())
                .hasSize(3).filteredOn(text -> text.equals(searchStage)).hasSize(1);
        assertThat(sent.getAllValues().getLast().getUserMessage().getText()).contains(book
                ? prompts.get(AgentLang.ZH, "trader.wake.routineScope")
                : prompts.get(AgentLang.ZH, "trader.wake.single.closing", coin));
        verify(model, never()).call(any(Prompt.class));
        ArgumentCaptor<AiTraderDecision> saved = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
        assertThat(saved.getValue().getReasoning()).isEqualTo("[本轮结论]\nBTCUSDT：继续等待。");
        assertThat(saved.getValue().getTraceJson()).contains("现有材料足够，不需要联网");
        assertThat(MAPPER.readTree(saved.getValue().getTraceJson()).get("stages")).hasSize(2);
        assertThat(MAPPER.readTree(saved.getValue().getActionsJson())).isEmpty();
    }

    @Test
    void earlyOpeningRejectionsKeepOtherActionsInOrderAndDecisionCanOpen() {
        stubHealthyAccount();
        String openArgs = """
                {"symbol":"BTCUSDT","side":"LONG","entries":[{"quantity":0.01}],"leverage":10,
                 "stopLossPrice":95000,"playType":"BREAKOUT","signalsUsed":"突破前高",
                 "invalidationCondition":"1h收盘跌破95000"}""";
        String reverseArgs = """
                {"positionId":5,"quantity":0.01,"leverage":10,"stopLossPrice":105000,"playType":"REVERSAL",
                 "signalsUsed":"跌破支撑","invalidationCondition":"重回支撑"}""";
        onlineModel(n -> switch (n) {
            case 1 -> AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall("open-early", "function", "open_position", openArgs),
                    new AssistantMessage.ToolCall("cancel-first", "function", "cancel_order", "{\"orderIds\":[77],\"reason\":\"计划失效\"}"))).build();
            case 2 -> new AssistantMessage("事实：已撤单77，提前开仓被拒绝。");
            case 3 -> AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall("reverse-early", "function", "reverse_position", reverseArgs),
                    new AssistantMessage.ToolCall("cancel-second", "function", "cancel_order", "{\"orderIds\":[78],\"reason\":\"计划失效\"}"))).build();
            case 4 -> new AssistantMessage("无需搜索，已撤单78，反手未执行。");
            case 5 -> AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall("open-final", "function", "open_position", openArgs))).build();
            default -> new AssistantMessage("[本轮结论]\nBTCUSDT：已撤单77、78，并开多。反手未执行。");
        });
        when(simTradeClient.cancelOrder(99L, 77L)).thenReturn(filledReceipt());
        when(simTradeClient.cancelOrder(99L, 78L)).thenReturn(filledReceipt());
        when(simTradeClient.openPosition(eq(99L), any())).thenReturn(filledReceipt());

        wake(trader(), 1785171600000L);

        verify(simTradeClient).openPosition(eq(99L), any());
        verify(simTradeClient, never()).closePosition(anyLong(), any());
        var order = org.mockito.Mockito.inOrder(simTradeClient);
        order.verify(simTradeClient).cancelOrder(99L, 77L);
        order.verify(simTradeClient).cancelOrder(99L, 78L);
        order.verify(simTradeClient).openPosition(eq(99L), any());
        ArgumentCaptor<AiTraderDecision> saved = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(saved.capture());
        JsonNode actions = MAPPER.readTree(saved.getValue().getActionsJson());
        assertThat(actions).hasSize(5);
        assertThat(actions.get(0).path("tool").asString()).isEqualTo("open_position");
        assertThat(actions.get(0).has("rejected")).isTrue();
        assertThat(actions.get(1).path("tool").asString()).isEqualTo("cancel_order");
        assertThat(actions.get(1).has("rejected")).isFalse();
        assertThat(actions.get(2).path("tool").asString()).isEqualTo("reverse_position");
        assertThat(actions.get(2).has("rejected")).isTrue();
        assertThat(actions.get(3).path("tool").asString()).isEqualTo("cancel_order");
        assertThat(actions.get(4).path("tool").asString()).isEqualTo("open_position");
        assertThat(actions.get(4).has("rejected")).isFalse();
        assertThat(saved.getValue().getReasoning()).isEqualTo("[本轮结论]\nBTCUSDT：已撤单77、78，并开多。反手未执行。");
    }

    @Test
    void decisionRefreshAfterWebSearchSeesNewPositionWithoutExtraPlanReconciliation() {
        stubHealthyAccount();
        ChatModel model = onlineModel(n -> {
            if (n == 2) {
                return AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("search", "function", "web_search",
                                "{\"query\":\"核实公告是否已生效\"}"))).build();
            }
            if (n == 3) {
                when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(crossPosition("2000", "123")));
                when(binanceRestClient.getPremiumIndex("BTCUSDT"))
                        .thenReturn("{\"markPrice\":\"100615\",\"nextFundingTime\":0,\"lastFundingRate\":\"0.0001\"}");
            }
            return new AssistantMessage(n == 4 ? "已确认新持仓，继续持有。" : "本段完成。");
        });
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("公告没有写明生效时间，暂时无法确认。"),
                ChatGenerationMetadata.builder().metadata(SearchEvent.KEY, List.of(SearchEvent.searched("公告", List.of(
                        new SearchEvent.Source("https://example.com/notice", "公告"))))).build()))));

        wake(trader(), 1785171600000L);

        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(4)).stream(sent.capture());
        assertThat(sent.getAllValues().getLast().getUserMessage().getText())
                .contains("\"positionId\":349", "\"unrealizedPnl\":123", "100615");
        ArgumentCaptor<Prompt> search = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(search.capture());
        assertThat(search.getValue().getSystemMessage().getText()).isEqualTo(prompts.get(AgentLang.ZH, "trader.wake.searchInstruction"));
        ArgumentCaptor<AiTraderDecision> saved = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(saved.capture());
        JsonNode actions = MAPPER.readTree(saved.getValue().getActionsJson());
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).path("tool").asString()).isEqualTo("web_search");
        assertThat(saved.getValue().getTraceJson()).contains("https://example.com/notice", "暂时无法确认");
        verify(planStore).reconcile(eq(7L), eq(1), any(), any(), any(), any(), eq(1785171600000L), eq(AgentLang.ZH));
    }

    /** 预算在哪一段、哪一次调用上用完：几个数都跟着上限走 */
    static Stream<Arguments> budgetBoundaries() {
        int limit = TraderWakeupRunner.MAX_MODEL_CALLS;
        return Stream.of(
                Arguments.of(0, limit - 1, false, limit),
                Arguments.of(0, limit, true, limit + 1),
                Arguments.of(1, limit - 1, false, limit),
                Arguments.of(1, limit, true, limit + 1),
                Arguments.of(2, limit, true, limit + 1),
                Arguments.of(2, limit, false, limit));
    }

    @ParameterizedTest
    @MethodSource("budgetBoundaries")
    void onlineBudgetEndsOnceAtEveryStage(int completedStages, int boundary, boolean toolAtBoundary, int expectedCalls) {
        stubHealthyAccount();
        ChatModel model = onlineModel(n -> {
            if (n <= completedStages) {
                return new AssistantMessage("本段完成。");
            }
            if (n > boundary || (n == boundary && !toolAtBoundary)) {
                return new AssistantMessage("[本轮结论]\nBTCUSDT：保持等待，未交易。");
            }
            return AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall("account-" + n, "function", "get_account", "{}"))).build();
        });

        wake(trader(), 1785171600000L);

        verify(model, times(expectedCalls)).stream(any(Prompt.class));
        ArgumentCaptor<AiTraderDecision> saved = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
        assertThat(saved.getValue().getReasoning()).isEqualTo("[本轮结论]\nBTCUSDT：保持等待，未交易。");
        JsonNode stages = MAPPER.readTree(saved.getValue().getTraceJson()).get("stages");
        assertThat(stages).hasSize(completedStages + (completedStages < 2 || toolAtBoundary ? 1 : 0));
        if (completedStages == 0) {
            assertThat(stages.toString()).contains(prompts.get(AgentLang.ZH, "trader.wake.searchSkipped"));
        }
        verify(simTradeClient, never()).openPosition(anyLong(), any());
    }

    @Test
    void cancellingWakeInterruptsSearchConnectionAndDoesNotExecuteFollowingTrade() throws Exception {
        stubHealthyAccount();
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        CompletableFuture<Boolean> searchEnded = new CompletableFuture<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/v1/responses", exchange -> {
                requests.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (var out = exchange.getResponseBody()) {
                    out.write("data: {\"type\":\"response.in_progress\"}\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    received.countDown();
                    while (!release.await(50, TimeUnit.MILLISECONDS)) {
                        out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                } catch (IOException e) {
                    disconnected.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            server.start();
            ResponsesChatModel transport = new ResponsesChatModel("test-key", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-model", null, null, mock(ToolCallingManager.class), true, Duration.ofSeconds(30));
            ChatModel model = onlineModel(n -> n < 3 ? new AssistantMessage("本段完成。")
                    : AssistantMessage.builder().content("").toolCalls(List.of(
                            new AssistantMessage.ToolCall("search", "function", "web_search", "{\"query\":\"核实公告\"}"),
                            new AssistantMessage.ToolCall("open", "function", "open_position", """
                                    {"symbol":"BTCUSDT","side":"LONG","entries":[{"quantity":0.01}],"leverage":10,
                                     "stopLossPrice":95000,"playType":"BREAKOUT","signalsUsed":"突破前高",
                                     "invalidationCondition":"跌回前高"}"""))).build());
            when(model.call(any(Prompt.class))).thenAnswer(inv -> {
                try {
                    return transport.call(inv.getArgument(0, Prompt.class));
                } finally {
                    searchEnded.complete(Thread.currentThread().isInterrupted());
                }
            });
            Thread wake = Thread.startVirtualThread(() -> wake(trader(), 1785171600000L));
            try {
                assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
                wake.interrupt();
                wake.join(5_000);
                assertThat(wake.isAlive()).isFalse();
                assertThat(searchEnded.get(5, TimeUnit.SECONDS)).isTrue();
                assertThat(disconnected.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(requests.get()).isEqualTo(1);
                verify(model, times(3)).stream(any(Prompt.class));
                verify(simTradeClient, never()).openPosition(anyLong(), any());
                ArgumentCaptor<AiTraderDecision> saved = ArgumentCaptor.forClass(AiTraderDecision.class);
                verify(decisionMapper).insert(saved.capture());
                assertThat(saved.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
                assertThat(saved.getValue().getReasoning()).isNull();
            } finally {
                wake.interrupt();
                release.countDown();
                server.stop(0);
            }
        }
    }

    @Test
    void fullLoopOpensPositionAndPersistsDecision() {
        stubHealthyAccount();
        // 先建好再 stub：thenReturn 参数里嵌套 when() 是 UnfinishedStubbing
        ChatModel model = modelOpeningThenSummary("""
                {"symbol":"BTCUSDT","side":"LONG","entries":[{"quantity":0.01}],"leverage":10,
                 "stopLossPrice":95000,"takeProfits":[{"price":110000,"percent":100}],
                 "playType":"BREAKOUT","signalsUsed":"突破前高+量比1.8",
                 "invalidationCondition":"1h收盘跌回前高98000下方"}""");
        when(modelFactory.modelFor(any())).thenReturn(model);
        FuturesOrderResponse resp = filledReceipt();
        when(simTradeClient.openPosition(eq(99L), any())).thenReturn(resp);
        // 真库 insert 回填自增 id，run_end 帧要带它
        when(decisionMapper.insert(any(AiTraderDecision.class))).thenAnswer(inv -> {
            inv.<AiTraderDecision>getArgument(0).setId(1234L);
            return 1;
        });
        // 现场出口唤醒前就连上
        Frames owner = new Frames();
        hub.subscribeTrader(7L, owner);

        wake(trader(), 1785171600000L);

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
        JsonNode trace = MAPPER.readTree(d.getTraceJson());
        assertThat(trace.path("v").asInt(0)).isEqualTo(1);
        assertThat(trace.path("kind").asString(null)).isEqualTo(AiTraderDecision.KIND_TRADE);
        assertThat(trace.get("prompt").path("system").asString(null)).isNotBlank();
        assertThat(trace.get("prompt").path("instruction").asString(null)).contains("【当前账户】");
        JsonNode calls = trace.get("calls");
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).get("toolCalls").get(0).path("name").asString(null)).isEqualTo("open_position");
        assertThat(calls.get(0).get("toolCalls").get(0).get("args").path("playType").asString(null)).isEqualTo("BREAKOUT");
        assertThat(calls.get(0).get("results").get(0).path("status").asString(null)).isEqualTo("ok");
        assertThat(calls.get(1).path("text").asString(null)).isEqualTo("突破前高放量，做多并挂好止损，本轮结束。");
        assertThat(trace.get("end").path("status").asString(null)).isEqualTo(AiTraderDecision.STATUS_OK);

        // 现场帧序
        assertThat(owner.events).containsExactly("run_start", "prompt", "model_start", "model_end", "tool_result",
                "model_start", "token", "model_end", "run_end");
        assertThat(owner.last("run_end").path("decisionId").asLong()).isEqualTo(1234L);
        assertThat(owner.last("tool_result").path("name").asString(null)).isEqualTo("open_position");
        assertThat(owner.last("token").path("text").asString(null)).contains("本轮结束");
    }

    @Test
    void fullLoopReverseThenAccountKeepsActionOrderAndOpeningCitation() {
        stubHealthyAccount();
        FuturesPositionDTO old = crossPosition("100", "0");
        old.setLeverage(10);
        var positions = new java.util.concurrent.atomic.AtomicReference<>(List.of(old));
        when(simTradeClient.getAllPositions(99L)).thenAnswer(inv -> positions.get());
        when(simTradeClient.closePosition(eq(99L), any())).thenAnswer(inv -> {
            positions.set(List.of());
            FuturesOrderResponse r = filledReceipt();
            r.setStatus("FILLED");
            r.setPositionId(349L);
            r.setQuantity(old.getQuantity());
            return r;
        });
        when(simTradeClient.openPosition(eq(99L), any())).thenAnswer(inv -> {
            FuturesPositionDTO next = crossPosition("100", "0");
            next.setId(350L);
            next.setSide("SHORT");
            next.setLeverage(10);
            positions.set(List.of(next));
            FuturesOrderResponse r = filledReceipt();
            r.setStatus("FILLED");
            r.setPositionId(350L);
            return r;
        });
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AssistantMessage calls = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "reverse_position", """
                        {"positionId":349,"quantity":0.01,"leverage":10,"stopLossPrice":105000,
                         "playType":"REVERSAL","signalsUsed":"反弹结束跌破支撑","invalidationCondition":"收复支撑"}
                        """), new AssistantMessage.ToolCall("c2", "function", "get_account", "{}"))).build();
        when(model.stream(any(Prompt.class))).thenReturn(frameOf(calls),
                frameOf(new AssistantMessage("[本轮结论]\n[BTCUSDT]\n参考账本 #3，反手做空。")));
        when(modelFactory.modelFor(any())).thenReturn(model);

        wake(trader(), 1785171600000L);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        JsonNode actions = MAPPER.readTree(dec.getValue().getActionsJson());
        assertThat(actions).hasSize(2);
        assertThat(actions.get(0).path("tool").asString()).isEqualTo("reverse_position");
        assertThat(actions.get(0).path("status").asString()).isEqualTo("ok");
        assertThat(actions.get(0).path("result").path("close").path("result").path("positionId").asLong()).isEqualTo(349L);
        assertThat(actions.get(0).path("result").path("open").path("result").path("entries").get(0).path("positionId").asLong()).isEqualTo(350L);
        assertThat(actions.get(1).path("tool").asString()).isEqualTo("get_account");
        verify(ledger).recordCitations(eq(7L), eq(1), eq(java.util.Set.of(3L)), eq(true));
    }

    /** 结论块里引用的账本 #编号各计一次引用：本轮开了仓算开仓引用 */
    @Test
    void ledgerCitationsCountedAsOpenWhenPositionOpened() {
        stubHealthyAccount();
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AssistantMessage openCall = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "open_position", """
                        {"symbol":"BTCUSDT","side":"LONG","entries":[{"quantity":0.01}],"leverage":10,
                         "stopLossPrice":95000,"takeProfits":[{"price":110000,"percent":100}],
                         "playType":"BREAKOUT","signalsUsed":"突破前高+量比1.8",
                         "invalidationCondition":"1h收盘跌回前高98000下方"}""")))
                .build();
        when(model.stream(any(Prompt.class))).thenReturn(
                frameOf(openCall),
                frameOf(new AssistantMessage("放量突破。[本轮结论]\n判断：账本 #3 的入场条件成立，#7 的限制没触发，#3 再提一次\n动作：开多\n等待：无")));
        when(modelFactory.modelFor(any())).thenReturn(model);
        when(simTradeClient.openPosition(eq(99L), any())).thenReturn(filledReceipt());

        wake(trader(), 1785171600000L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<Long>> ids = ArgumentCaptor.forClass((Class) java.util.Collection.class);
        verify(ledger).recordCitations(eq(7L), eq(1), ids.capture(), eq(true));
        assertThat(ids.getValue()).containsExactly(3L, 7L);
    }

    /** 编号超过 18 位不算引用 */
    @Test
    void overlongLedgerIdsAreIgnored() {
        stubHealthyAccount();
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AssistantMessage check = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "get_account", "{}")))
                .build();
        when(model.stream(any(Prompt.class))).thenReturn(
                frameOf(check),
                frameOf(new AssistantMessage("[本轮结论]\n判断：账本 #12345678901234567890 说的\n动作：HOLD\n等待：无")));
        when(modelFactory.modelFor(any())).thenReturn(model);

        wake(trader(), 1785171600000L);

        verify(ledger, never()).recordCitations(anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    /** 没开仓的轮算观望引用；正文里（结论块之外）的 #编号不算 */
    @Test
    void ledgerCitationsCountedAsHoldWithoutOpen() {
        stubHealthyAccount();
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AssistantMessage check = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "get_account", "{}")))
                .build();
        when(model.stream(any(Prompt.class))).thenReturn(
                frameOf(check),
                frameOf(new AssistantMessage("先看 #9 不相关。[本轮结论]\n判断：账本 #2 说回踩没确认不追\n动作：HOLD\n等待：回踩 99000")));
        when(modelFactory.modelFor(any())).thenReturn(model);

        wake(trader(), 1785171600000L);

        verify(ledger).recordCitations(eq(7L), eq(1), eq(java.util.Set.of(2L)), eq(false));
    }

    /** 唤醒"最近决策"回注同样过 stale：被忽略交易的分段不注入；行本身保留——行头时刻是唤醒事实 */
    @Test
    void recentDecisionsInjectionScrubsStaleSegments() {
        stubHealthyAccount();
        long prevWake = 1785171600000L - 3600_000L;
        AiTraderDecision prev = new AiTraderDecision();
        prev.setWakeTime(prevWake);
        prev.setCreatedAt(TradePairing.localOf(prevWake + 60_000L));
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
        AiTrader twoCoins = trader();
        twoCoins.setSymbols("BTCUSDT,ETHUSDT");

        wake(twoCoins, 1785171600000L);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model, org.mockito.Mockito.atLeastOnce()).stream(prompt.capture());
        String injected = prompt.getAllValues().get(0).getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText).reduce("", String::concat);
        // 被忽略的那段剔掉之后，BTC 就没有结论段了
        assertThat(injected).contains("站上 1925").doesNotContain("回踩 63400").contains("[BTCUSDT] 还没有结论段");
        // 行头时刻保留：警报开场白的"上次唤醒在X"要用真时刻，剔的是内容不是行
        assertThat(injected).contains(BeijingTime.shortFormat(prevWake));
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

        wake(trader(), 1785171600000L);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
        assertThat(dec.getValue().getError()).contains("上限");
        // 被拦下的那次调用在过程轨迹里标成没执行，不显示成正常
        JsonNode calls = MAPPER.readTree(dec.getValue().getTraceJson()).path("calls");
        JsonNode blocked = calls.get(TraderWakeupRunner.MAX_MODEL_CALLS - 1).path("results").get(0);
        assertThat(blocked.path("status").asString()).isEqualTo("not_attempted");
        assertThat(calls.get(0).path("results").get(0).path("status").asString()).isEqualTo("ok");
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

        wake(trader(), 1785171600000L);

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

        wake(trader(), 1785171600000L);

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

        wake(trader(), 1785171600000L);

        verify(simTradeClient).closePosition(eq(99L), any());
    }

    @Test
    void guardRejectionIsRecordedAndNoOrderSent() {
        stubHealthyAccount();
        // 杠杆50违规 → TradeGuard 拒绝 → 模型收到拒绝原因后给总结
        ChatModel model = modelOpeningThenSummary("""
                {"symbol":"BTCUSDT","side":"LONG","entries":[{"quantity":0.01}],"leverage":50,
                 "stopLossPrice":95000,"takeProfits":[],
                 "playType":"BREAKOUT","signalsUsed":"x","invalidationCondition":"跌回箱体"}""");
        when(modelFactory.modelFor(any())).thenReturn(model);

        wake(trader(), 1785171600000L);

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
        when(modelFactory.modelFor(any())).thenThrow(
                new SseChatModel.UpstreamRejected(401, "Responses API HTTP 401: invalid key"));

        wake(trader(), 1785171600000L);

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

        wake(t, 1785171600000L);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
        // 公开行只存归类文案，上游原文不落库
        assertThat(dec.getValue().getError())
                .isEqualTo(prompts.get(AgentLang.ZH, "llm.error.unknown", Map.of("type", "IllegalStateException")));
        verify(traderMapper).update(any(), any()); // 连败暂停走列级更新
    }

    @Test
    void liquidationEndsRoundWithoutModelCall() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of());
        when(simTradeClient.getBalanceDetail(99L)).thenReturn(Map.of("balance", "50", "frozenBalance", "0", "equity", "50"));

        wake(trader(), 1785171600000L);

        verify(modelFactory, never()).modelFor(any());
        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getReasoning()).contains("爆仓终局");
        verify(traderMapper).update(any(), any()); // LIQUIDATED 列级更新
        // 爆仓后不会再有下一轮来对账：这一轮就把计划对一遍，了结的单子才归得了档、进得了复盘
        verify(planStore).reconcile(eq(7L), eq(1), any(), any(), any(), any(), eq(1785171600000L), eq(AgentLang.ZH));
    }

    /** 权益要在动作落地后记录：本轮的开平仓立刻体现在净值曲线上，不许滞后一根K线。 */
    @Test
    void equityRecordedAfterActionsNotWakeSnapshot() {
        // 唤醒时空仓权益 10000；agent 开全仓多单扣手续费 5 → 动作后 sim 报的权益是 9995，应记 9995
        when(simTradeClient.getAllPositions(99L)).thenReturn(
                List.of(), List.of(crossPosition("1000", "0")));
        when(simTradeClient.getBalanceDetail(99L)).thenReturn(
                Map.of("balance", "10000", "frozenBalance", "0", "equity", "10000"),
                Map.of("balance", "9995", "frozenBalance", "0", "equity", "9995"));
        when(simTradeClient.getPendingOrders(99L, null)).thenReturn(List.of());
        when(binanceRestClient.getPremiumIndex("BTCUSDT"))
                .thenReturn("{\"markPrice\":\"100000\",\"nextFundingTime\":0,\"lastFundingRate\":\"0.0001\"}");
        when(decisionMapper.selectList(any())).thenReturn(List.of());
        when(planMapper.selectList(any())).thenReturn(List.of());
        ChatModel model = modelOpeningThenSummary("""
                {"symbol":"BTCUSDT","side":"LONG","entries":[{"quantity":0.01}],"leverage":10,
                 "stopLossPrice":95000,"takeProfits":[{"price":110000,"percent":100}],
                 "playType":"BREAKOUT","signalsUsed":"突破前高",
                 "invalidationCondition":"1h收盘跌回98000下方"}""");
        when(modelFactory.modelFor(any())).thenReturn(model);
        when(simTradeClient.openPosition(eq(99L), any())).thenReturn(filledReceipt());

        wake(trader(), 1785171600000L);

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
        // 这一轮只查账户不交易，余额明细只在取权益时被调：第二次调用 = 动作后的权益刷新，精准打在那一步上
        when(simTradeClient.getBalanceDetail(99L))
                .thenReturn(Map.of("balance", "10000", "frozenBalance", "0", "equity", "10000"))
                .thenThrow(new RuntimeException("sim 502"));
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);

        wake(trader(), 1785171600000L);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
        assertThat(dec.getValue().getReasoning()).contains("本轮 HOLD");
        assertThat(dec.getValue().getEquity()).isEqualByComparingTo("10000"); // 刷新前的快照
        verify(traderMapper, never()).update(any(), any()); // 没有 recordFailure，连败不涨
    }

    /** 失败的轮落库前也重取一次权益：这一行的权益是落库那一刻的，曲线按落库时刻排才对得上 */
    @Test
    void failedRoundRecordsEquityTakenBeforeInsert() {
        stubHealthyAccount();
        when(simTradeClient.getBalanceDetail(99L)).thenReturn(
                Map.of("balance", "10000", "frozenBalance", "0", "equity", "10000"),
                Map.of("balance", "9800", "frozenBalance", "0", "equity", "9800"));
        when(modelFactory.modelFor(any())).thenThrow(new IllegalStateException("上游超时"));

        wake(trader(), 1785171600000L);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
        assertThat(dec.getValue().getEquity()).isEqualByComparingTo("9800");
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

        assertThatCode(() -> wake(t, 1785171600000L)).doesNotThrowAnyException();

        verify(decisionMapper, times(1)).insert(any(AiTraderDecision.class));
    }

    /** 开仓成功 → 计划落库：论点/失效条件/止损止盈快照 + 开仓所在唤醒边界。 */
    @Test
    void openPersistsPlanWithInvalidationCondition() {
        stubHealthyAccount();
        ChatModel model = modelOpeningThenSummary("""
                {"symbol":"BTCUSDT","side":"LONG","entries":[{"quantity":0.01}],"leverage":10,
                 "stopLossPrice":95000,"takeProfits":[{"price":110000,"percent":100}],
                 "playType":"BREAKOUT","signalsUsed":"突破前高+量比1.8",
                 "invalidationCondition":"1h收盘跌回前高98000下方"}""");
        when(modelFactory.modelFor(any())).thenReturn(model);
        when(simTradeClient.openPosition(eq(99L), any())).thenReturn(filledReceipt());
        when(planMapper.selectOne(any())).thenReturn(null);

        wake(trader(), 1785171600000L);

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
        when(simTradeClient.getBalanceDetail(99L)).thenReturn(Map.of("balance", "9995", "frozenBalance", "0", "equity", "9990"));
        when(simTradeClient.getPendingOrders(99L, null)).thenReturn(List.of());
        when(decisionMapper.selectList(any())).thenReturn(List.of());
        AiTraderPlan plan = new AiTraderPlan();
        plan.setId(21L);
        plan.setSymbol("BTCUSDT");
        plan.setSide("LONG");
        plan.setStatus(AiTraderPlan.STATUS_LIVE);
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

        wake(trader(), 1785171600000L);

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

    /**
     * 上一轮结论按币取最近一段：最近一轮只写了 BTC 和 ETH，SOL 沿用更早那轮写的段、带它自己的时刻；
     * BTC 更早的旧段不带；从没写过的 XRP 点名；段身不截断。更早的轮次另在轨迹里各留一行（时刻/状态/权益/工具名）
     */
    @Test
    void lastConclusionTakesEachSymbolsLatestSegment() {
        stubHealthyAccount();
        long boundary = 1785171600000L;
        AiTraderDecision latest = new AiTraderDecision();
        latest.setWakeTime(boundary - 3600_000L);
        latest.setKind(AiTraderDecision.KIND_TRADE);
        latest.setStatus(AiTraderDecision.STATUS_OK);
        latest.setEquity(new BigDecimal("10123.45"));
        // 300 字铺垫 + 两个币段各 400 字判断：整块远超旧口径的 1000 字尾截
        latest.setReasoning("行情铺垫".repeat(75) + "\n[本轮结论]\n总评：只动了两个币\n"
                + "[BTCUSDT]\n判断：" + "x".repeat(400) + "\n动作：HOLD\n等待：BTC等待A\n"
                + "[ETHUSDT]\n判断：" + "y".repeat(400) + "\n动作：HOLD\n等待：ETH等待B");
        AiTraderDecision older = new AiTraderDecision();
        older.setWakeTime(boundary - 7200_000L);
        older.setKind(AiTraderDecision.KIND_TRADE);
        older.setStatus(AiTraderDecision.STATUS_OK);
        older.setEquity(new BigDecimal("10050"));
        older.setReasoning("[本轮结论]\n总评：旧总评\n[BTCUSDT]\n等待：旧等待条件XYZ\n[SOLUSDT]\n动作：HOLD\n等待：SOL等待C");
        older.setActionsJson("[{\"tool\":\"klines\",\"args\":{}},{\"tool\":\"klines\",\"args\":{}}]");
        when(decisionMapper.selectList(any())).thenReturn(List.of(latest, older));
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);
        AiTrader fourCoins = trader();
        fourCoins.setSymbols("BTCUSDT,ETHUSDT,SOLUSDT,XRPUSDT");

        wake(fourCoins, boundary);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model, atLeastOnce()).stream(prompt.capture());
        // 写了等待行就分两段：结论在第一段、轨迹在第二段，拿最后一次调用的输入看全
        String injected = prompt.getAllValues().getLast().getInstructions().stream()
                .map(Message::getText).reduce("", String::concat);
        assertThat(injected)
                .contains("【上一轮结论】每个币最近一次写下的")
                .contains("上一次看整盘的一轮（" + BeijingTime.shortFormat(boundary - 3600_000L) + "，当时权益10123）写下的：\n"
                        + "总评：只动了两个币").doesNotContain("旧总评")
                .contains("[BTCUSDT]（" + BeijingTime.shortFormat(boundary - 3600_000L) + " 写下）\n判断：" + "x".repeat(400))
                .contains("ETH等待B")
                .contains("[SOLUSDT]（" + BeijingTime.shortFormat(boundary - 7200_000L) + " 写下）\n动作：HOLD\n等待：SOL等待C")
                .contains("[XRPUSDT] 还没有结论段")
                .doesNotContain("旧等待条件XYZ")
                // 等待行也按每币最近一段取：SOL 的来自更早那轮
                .contains("- SOLUSDT 你上一轮等的「SOL等待C」")
                .contains("工具：klines×2");
    }

    /**
     * 总评和主人留言的对账行只取看整盘的那一轮：最近一条是只写了 SOL 段的单币轮，
     * 引子仍取再往前那轮整盘的；SOL 段取单币轮写的，BTC 段沿用整盘轮的
     */
    @Test
    void lastConclusionIntroComesFromBookRound() {
        stubHealthyAccount();
        long boundary = 1785171600000L;
        AiTraderDecision single = new AiTraderDecision();
        single.setWakeTime(boundary - 600_000L);
        single.setKind(AiTraderDecision.KIND_ALERT);
        single.setSymbol("SOLUSDT");
        single.setStatus(AiTraderDecision.STATUS_OK);
        single.setEquity(new BigDecimal("10200"));
        single.setReasoning("[本轮结论]\n[SOLUSDT]\n判断：单币轮写的\n动作：HOLD\n等待：无");
        AiTraderDecision book = new AiTraderDecision();
        book.setWakeTime(boundary - 3600_000L);
        book.setKind(AiTraderDecision.KIND_TRADE);
        book.setStatus(AiTraderDecision.STATUS_OK);
        book.setEquity(new BigDecimal("10123.45"));
        book.setReasoning("[本轮结论]\n主人留言：已执行，止损上移到保本\n总评：整盘轮写的\n"
                + "[BTCUSDT]\n判断：整盘轮写的BTC\n动作：HOLD\n等待：无\n"
                + "[SOLUSDT]\n判断：整盘轮写的旧SOL\n动作：HOLD\n等待：无");
        when(decisionMapper.selectList(any())).thenAnswer(call -> bookOnly(call.getArgument(0))
                ? List.of(book) : List.of(single, book));
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);
        AiTrader t = trader();
        t.setSymbols("BTCUSDT,SOLUSDT");

        wake(t, boundary);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model, atLeastOnce()).stream(prompt.capture());
        String injected = prompt.getAllValues().getLast().getInstructions().stream()
                .map(Message::getText).reduce("", String::concat);
        assertThat(injected)
                .contains("上一次看整盘的一轮（" + BeijingTime.shortFormat(boundary - 3600_000L) + "，当时权益10123）写下的：\n"
                        + "主人留言：已执行，止损上移到保本\n总评：整盘轮写的")
                .contains("[SOLUSDT]（" + BeijingTime.shortFormat(boundary - 600_000L) + " 写下）\n判断：单币轮写的")
                .contains("[BTCUSDT]（" + BeijingTime.shortFormat(boundary - 3600_000L) + " 写下）\n判断：整盘轮写的BTC")
                .doesNotContain("整盘轮写的旧SOL");
    }

    /** 还没有整盘轮写过结论、单币轮写过段：各币段照常列出，没写过的币点名，不说本局还没有上一轮结论 */
    @Test
    void lastConclusionListsSegmentsWithoutBookRound() {
        stubHealthyAccount();
        long boundary = 1785171600000L;
        AiTraderDecision single = new AiTraderDecision();
        single.setWakeTime(boundary - 600_000L);
        single.setKind(AiTraderDecision.KIND_ALERT);
        single.setSymbol("SOLUSDT");
        single.setStatus(AiTraderDecision.STATUS_OK);
        single.setEquity(new BigDecimal("10200"));
        single.setReasoning("[本轮结论]\n[SOLUSDT]\n判断：单币轮写的\n动作：HOLD\n等待：无");
        when(decisionMapper.selectList(any())).thenAnswer(call -> bookOnly(call.getArgument(0))
                ? List.of() : List.of(single));
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);
        AiTrader t = trader();
        t.setSymbols("BTCUSDT,SOLUSDT");

        wake(t, boundary);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model, atLeastOnce()).stream(prompt.capture());
        String injected = prompt.getAllValues().getLast().getInstructions().stream()
                .map(Message::getText).reduce("", String::concat);
        assertThat(injected)
                .contains("【上一轮结论】每个币最近一次写下的")
                .contains("[SOLUSDT]（" + BeijingTime.shortFormat(boundary - 600_000L) + " 写下）\n判断：单币轮写的")
                .contains("[BTCUSDT] 还没有结论段")
                .doesNotContain("本局还没有上一轮结论").doesNotContain("上一次看整盘的一轮");
    }

    /** 只取看整盘那几轮的查询：symbol 为空，不是"为空或等于某个币" */
    private static boolean bookOnly(LambdaQueryWrapper<AiTraderDecision> q) {
        String sql = q.getSqlSegment();
        return sql.contains("symbol IS NULL") && !sql.contains("OR symbol");
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
        closed.setClosedBy("STOP_LOSS");
        when(simTradeClient.getClosedPositions(99L, 200)).thenReturn(List.of(closed));
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);

        wake(trader(), 1785171600000L);

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

    /**
     * 限价计划没仓位 id，但两次唤醒之间成交又被止损：事件报真实结局，不能说成挂单已不在；
     * 计划挂出之前就平掉的同币同向旧仓不算这张计划的结局
     */
    @Test
    void limitFilledAndClosedBetweenWakesReportedAsClosed() {
        stubHealthyAccount();
        long placed = 1785171600000L - 900_000L;
        AiTraderPlan plan = new AiTraderPlan();
        plan.setId(22L);
        plan.setSymbol("BTCUSDT");
        plan.setSide("SHORT");
        plan.setStatus(AiTraderPlan.STATUS_LIVE);
        plan.setPlayType("BREAKOUT");
        plan.setInvalidationCondition("15m收盘站回76860");
        plan.setOpenedWakeTime(placed);
        // 对账按计划行的创建时刻切"立案之后"：更早平掉的同键旧仓不是它的
        plan.setCreatedAt(java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(placed), java.time.ZoneId.systemDefault()));
        when(planMapper.selectList(any())).thenReturn(List.of(plan));
        FuturesPositionDTO older = new FuturesPositionDTO();
        older.setId(1059L);
        older.setSymbol("BTCUSDT");
        older.setSide("SHORT");
        older.setCreatedAt(java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(placed - 60_000L), java.time.ZoneId.systemDefault()));
        older.setUpdatedAt(java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(placed - 30_000L), java.time.ZoneId.systemDefault()));
        older.setClosedPrice(new BigDecimal("77500"));
        older.setClosedPnl(new BigDecimal("-30.10"));
        FuturesPositionDTO closed = new FuturesPositionDTO();
        closed.setId(1060L);
        closed.setSymbol("BTCUSDT");
        closed.setSide("SHORT");
        closed.setCreatedAt(java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(placed + 240_000L), java.time.ZoneId.systemDefault()));
        closed.setUpdatedAt(java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(placed + 600_000L), java.time.ZoneId.systemDefault()));
        closed.setClosedPrice(new BigDecimal("77163.8"));
        closed.setClosedPnl(new BigDecimal("66.43"));
        closed.setClosedBy("STOP_LOSS");
        when(simTradeClient.getClosedPositions(99L, 200)).thenReturn(List.of(older, closed));
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);

        wake(trader(), 1785171600000L);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model, atLeastOnce()).stream(prompt.capture());
        String user = prompt.getAllValues().get(0).getInstructions().stream()
                .filter(m -> m instanceof org.springframework.ai.chat.messages.UserMessage)
                .map(org.springframework.ai.chat.messages.Message::getText).reduce("", String::concat);
        assertThat(user).contains("【自上次唤醒以来】").contains("止损带走").contains("66.43")
                .contains("15m收盘站回76860").doesNotContain("挂单已不在").doesNotContain("-30.10");
    }

    /** 持仓被止损、同键挂单成交成新仓：计划换绑到新仓，事件块先报旧仓结局再报成交，不能只说成交 */
    @Test
    void replacedPositionCloseReportedBeforeFill() {
        stubHealthyAccount();
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(crossPosition("2000", "0")));
        AiTraderPlan plan = new AiTraderPlan();
        plan.setId(21L);
        plan.setSymbol("BTCUSDT");
        plan.setSide("LONG");
        plan.setStatus(AiTraderPlan.STATUS_LIVE);
        plan.setPositionId(42L);
        plan.setPlayType("PULLBACK");
        plan.setInvalidationCondition("1h收盘跌回64200");
        plan.setOpenedWakeTime(1785171600000L - 3600_000L);
        when(planMapper.selectList(any())).thenReturn(List.of(plan));
        FuturesPositionDTO closed = new FuturesPositionDTO();
        closed.setId(42L);
        closed.setSymbol("BTCUSDT");
        closed.setSide("LONG");
        closed.setClosedPrice(new BigDecimal("63800"));
        closed.setClosedPnl(new BigDecimal("-120.5"));
        closed.setClosedBy("STOP_LOSS");
        when(simTradeClient.getClosedPositions(99L, 200)).thenReturn(List.of(closed));
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);

        wake(trader(), 1785171600000L);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model, atLeastOnce()).stream(prompt.capture());
        String user = prompt.getAllValues().get(0).getInstructions().stream()
                .filter(m -> m instanceof org.springframework.ai.chat.messages.UserMessage)
                .map(org.springframework.ai.chat.messages.Message::getText).reduce("", String::concat);
        assertThat(user).contains("止损带走").contains("-120.50").contains("1h收盘跌回64200").contains("限价单已成交");
        assertThat(user.indexOf("止损带走")).isLessThan(user.indexOf("限价单已成交"));
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

        wake(trader(), 1785171600000L);

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
        AiTraderPlan stale = new AiTraderPlan();
        stale.setId(11L);
        stale.setSymbol("BTCUSDT");
        stale.setSide("LONG");
        stale.setStatus(AiTraderPlan.STATUS_LIVE);
        stale.setInvalidationCondition("x");
        stale.setOpenedWakeTime(1785168000000L);
        when(planMapper.selectList(any())).thenReturn(List.of(stale));
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);

        wake(trader(), 1785171600000L);

        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(planMapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(AiTraderPlan.STATUS_CLOSED);
        assertThat(cap.getValue().getClosedWakeTime()).isEqualTo(1785171600000L);
    }

    /** 预算 = 距下一边界−5s，上限900s：唤醒决不占用下一根K线（超时级联跳过的根治）。 */
    @Test
    void wakeBudgetAlignsToNextBoundary() {
        long b = 1785171600000L;
        // 15m 迟到 213s：截在下一边界前 5s，只剩 682s
        assertThat(TraderWakeupRunner.wakeBudgetSeconds(b, 900_000, b + 213_000)).isEqualTo(682);
        // 1h 正点：3595s 被 900s 上限压住
        assertThat(TraderWakeupRunner.wakeBudgetSeconds(b, 3_600_000, b)).isEqualTo(900);
    }

    /** 非例行预算：上限 900s，且不超过一根心跳减 5s */
    @Test
    void triggeredBudgetCapsAtOneInterval() {
        assertThat(TraderWakeupRunner.triggeredBudgetSeconds(900_000)).isEqualTo(895);
        assertThat(TraderWakeupRunner.triggeredBudgetSeconds(3_600_000)).isEqualTo(900);
    }

    /** 事件迟到太多（距下一边界<30s）：放弃本轮记 SKIPPED，不调模型、不算失败。 */
    @Test
    void lateWakeSkippedWhenBudgetBelowFloor() {
        AiTrader t = trader(); // 1h：边界+3580s 时触发 → 预算 15s < 30s
        runner.nowMs = () -> 1785171600000L + 3_580_000L;

        wake(t, 1785171600000L);

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

        wakeManual(t, 1785171600000L);

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
        flows.nowMs = () -> pressed;

        String opening = flows.routineInstruction(trader(), List.of(), pressed, null, "", "", "", "", null, AgentLang.ZH);

        assertThat(opening).contains("已收盘（" + BeijingTime.shortFormat(boundary) + "）");
    }

    /** 持仓事件开场白：首行报事件本身（币/方向/量/价），提醒未收盘，但不说"止损单仍在保护你"——仓位可能已经没了 */
    @Test
    void eventOpeningStatesTheEventWithoutStopLossReassurance() {
        TradeEvent e = new TradeEvent(TradeEvent.STOP_LOSS, 42L, 9L, null, "ETHUSDT", "SHORT",
                new BigDecimal("0.50"), new BigDecimal("2377.0"), new BigDecimal("-13.1"), 1785171600000L + 600_000L);

        String opening = flows.singleInstruction(trader(), "ETHUSDT", List.of(new EventTrigger(e)), e.at(), null, "", "", "", AgentLang.ZH, false);

        assertThat(opening).contains("持仓事件（")
                .contains("ETHUSDT SHORT 止损单已触发，平掉 0.5 @2377")
                .contains("尚未收盘")
                .contains("这一轮只处理 ETHUSDT")
                .contains("这个币的仓位已经没了")
                .doesNotContain("止损单仍在")
                .doesNotContain("接连发生了");
        assertThat(new EventTrigger(e).kind()).isEqualTo(AiTraderDecision.KIND_EVENT);
    }

    /** 同一个币的几件事一起叫醒一轮：开头说有几件事，逐条按先后列出，各带自己的时刻；每种事各问一句 */
    @Test
    void batchedTriggersListedInOrder() {
        long base = 1785171600000L;
        AlertTrigger alert = new AlertTrigger("BTCUSDT", new BigDecimal("1.2"), new BigDecimal("63120"), AlertTrigger.DOWN, base + 600_000L);
        EventTrigger event = new EventTrigger(new TradeEvent(TradeEvent.LIMIT_FILLED, 99L, 9L, 100L, "BTCUSDT", "LONG",
                new BigDecimal("0.5"), new BigDecimal("63000"), null, base + 660_000L));

        String opening = flows.singleInstruction(trader(), "BTCUSDT", List.of(alert, event), base + 700_000L, null, "", "", "", AgentLang.ZH, false);

        assertThat(opening).contains("接连发生了 2 件事");
        assertThat(opening.indexOf("- 行情波动警报")).isLessThan(opening.indexOf("- 持仓事件（"));
        assertThat(opening).contains("BTCUSDT LONG 限价开仓单已成交，0.5 @63000");
        assertThat(opening.indexOf("上面是这个币的波动警报")).isLessThan(opening.indexOf("上面是你挂的限价单成交"));
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

        wake(trader(), 1785171600000L);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getActionsJson()).contains("klines").contains("BTCUSDT");
        assertThat(dec.getValue().getToolCalls()).isGreaterThanOrEqualTo(1);
    }

    /** 波动警报唤醒：kind=ALERT、wake_time=触发时刻；开场白含警报事实（未收盘不作数/被叫醒两个方向都不是理由） */
    @Test
    void alertWakeUsesAlertInstructionAndKind() {
        stubHealthyAccount();
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(crossPosition("1000", "-5")));
        when(simTradeClient.getBalanceDetail(99L)).thenReturn(Map.of("balance", "9995", "frozenBalance", "0", "equity", "9990"));
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);
        long triggeredAt = 1785171600000L + 600_000L; // 1h 边界后 10 分钟哨兵触发
        runner.nowMs = () -> triggeredAt;

        wakeTriggered(trader(),List.of(new AlertTrigger("BTCUSDT", new BigDecimal("1.2"),
                new BigDecimal("63120"), AlertTrigger.DOWN, triggeredAt)));

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
                .contains("尚未收盘").contains("被叫醒本身不是动作的理由");
    }

    /** 权益回落警报唤醒：kind=DRAWDOWN，是一轮整盘的；开场白开头是权益回落的事实，多问那一句，不带主人留言 */
    @Test
    void equityDropWakeIsAWholeBoardRoundWithoutTheOwnerNote() {
        stubHealthyAccount();
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);
        long at = 1785171600000L + 600_000L;
        runner.nowMs = () -> at;
        AiTrader t = trader();
        t.setOwnerNote("先别开新仓");
        t.setOwnerNoteRounds(1);

        runner.wakeTriggered(t, List.of(new EquityDropTrigger(new BigDecimal("10000"), at - 3_600_000L,
                new BigDecimal("8500"), new BigDecimal("15"), new BigDecimal("15"), at)), round(t, null));

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getKind()).isEqualTo(AiTraderDecision.KIND_DRAWDOWN);
        // 看整盘的一轮，决策行不记币
        assertThat(dec.getValue().getSymbol()).isNull();
        assertThat(dec.getValue().getWakeTime()).isEqualTo(at);
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);

        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(model, atLeastOnce()).stream(sent.capture());
        assertThat(userText(sent.getAllValues().getFirst()))
                .startsWith("权益回落警报（").contains("最高到过 10000 USDT").contains("回落 15%").contains("尚未收盘")
                .contains("你现在持有和挂着的方向，行情是不是已经变了")
                .contains("只处置你自己留下的事")
                .doesNotContain("先别开新仓");
    }

    /** 权益回落警报叫醒的那一轮没跑成：记一行，下一次整盘轮的开场白补报，那一轮成功了清掉 */
    @Test
    void failedEquityDropRoundIsReportedByTheNextWholeBoardRound() {
        stubHealthyAccount();
        when(modelFactory.modelFor(any())).thenThrow(new IllegalStateException("上游 502"));
        long at = 1785171600000L + 600_000L;
        runner.nowMs = () -> at;

        runner.wakeTriggered(trader(), List.of(new EquityDropTrigger(new BigDecimal("10000"), at - 3_600_000L,
                new BigDecimal("8500"), new BigDecimal("15"), new BigDecimal("15"), at)), round(trader(), null));

        assertThat(stored.getUnreportedEvents()).contains("权益回落警报在").contains("没跑成");

        ChatModel ok = modelCheckingThenSummary();
        org.mockito.Mockito.doReturn(ok).when(modelFactory).modelFor(any());
        runner.nowMs = () -> 1785175200000L + 1_000L;
        wake(trader(), 1785175200000L);

        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(ok, atLeastOnce()).stream(sent.capture());
        assertThat(userText(sent.getAllValues().getFirst())).contains("【自上次唤醒以来】")
                .contains("权益回落警报在").contains("最高到过 10000 USDT").contains("回落 15%");
        assertThat(stored.getUnreportedEvents()).isNull();
    }

    /** 持仓事件唤醒：kind=EVENT、wake_time=事件时刻；开场白首行是事件本身，不带警报那句"止损单仍在保护你" */
    @Test
    void eventWakeUsesEventInstructionAndKind() {
        stubHealthyAccount();
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of());
        when(simTradeClient.getBalanceDetail(99L)).thenReturn(Map.of("balance", "9987", "frozenBalance", "0", "equity", "9987"));
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);
        long at = 1785171600000L + 600_000L; // 1h 边界后 10 分钟止损被打掉
        runner.nowMs = () -> at;

        wakeTriggered(trader(),List.of(new EventTrigger(new TradeEvent(TradeEvent.STOP_LOSS, 99L, 9L, null,
                "BTCUSDT", "LONG", new BigDecimal("0.01"), new BigDecimal("62800"), new BigDecimal("-13"), at))));

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getKind()).isEqualTo(AiTraderDecision.KIND_EVENT);
        // 单币轮的决策行记着它只处理的那个币
        assertThat(dec.getValue().getSymbol()).isEqualTo("BTCUSDT");
        assertThat(dec.getValue().getWakeTime()).isEqualTo(at);
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, atLeastOnce()).stream(prompts.capture());
        String firstCall = prompts.getAllValues().get(0).getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText)
                .reduce("", String::concat);
        assertThat(firstCall).contains("持仓事件（").contains("BTCUSDT LONG 止损单已触发，平掉 0.01 @62800")
                .contains("尚未收盘").doesNotContain("止损单仍在").contains("被叫醒本身不是动作的理由");
    }

    /** 盯盘条件命中唤醒：kind=WATCH，开场白首行念回条件、命中价和它自己的备注 */
    @Test
    void watchWakeUsesWatchInstructionAndKind() {
        stubHealthyAccount();
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of());
        when(simTradeClient.getBalanceDetail(99L)).thenReturn(Map.of("balance", "9987", "frozenBalance", "0", "equity", "9987"));
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);
        long at = 1785171600000L + 600_000L;
        runner.nowMs = () -> at;
        AiTraderWatch w = new AiTraderWatch();
        w.setId(31L);
        w.setTraderId(7L);
        w.setRoundNo(1);
        w.setSymbol("BTCUSDT");
        w.setForSymbol("BTCUSDT");
        w.setKind("CLOSE_ABOVE");
        w.setLevel(new BigDecimal("63000"));
        w.setPeriod("1h");
        w.setNote("突破确认，看回踩做多");

        wakeTriggered(trader(),List.of(new WatchTrigger(w, new BigDecimal("63120"), at)));

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getKind()).isEqualTo(AiTraderDecision.KIND_WATCH);
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, atLeastOnce()).stream(prompts.capture());
        String firstCall = prompts.getAllValues().get(0).getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText)
                .reduce("", String::concat);
        assertThat(firstCall).contains("盯盘条件命中").contains("BTCUSDT 1h 收盘 63120 站上 63000")
                .contains("突破确认，看回踩做多").contains("被叫醒本身不是动作的理由");
    }

    /** 这根收盘刚命中的收盘类条件：例行轮开场白的【自上次唤醒以来】里报，报过就在库里标成已告知 */
    @Test
    void routineWakeReportsCloseHitsAtThisBoundary() {
        stubHealthyAccount();
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);
        when(watchMapper.selectList(any())).thenReturn(List.of(closeHit()));

        wake(trader(), 1785171600000L);

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, atLeastOnce()).stream(prompts.capture());
        String firstCall = prompts.getAllValues().get(0).getInstructions().stream()
                .map(org.springframework.ai.chat.messages.Message::getText)
                .reduce("", String::concat);
        assertThat(firstCall).contains("【自上次唤醒以来】")
                .contains("BTCUSDT 1h 收盘 63120 站上 63000").contains("突破确认，看回踩做多");
        verify(watchMapper).update(any(), any());
    }

    /**
     * 整盘的联网轮进行中，一个币来了触发：作为一条新消息插进对话（那件事本身、这个币现在的样子），
     * 并且当场放开这个币的开仓，不必等到交易段
     */
    @Test
    void triggerArrivingMidRoundIsInterjectedAndUnlocksOpeningForThatSymbol() {
        stubHealthyAccount();
        String openArgs = """
                {"symbol":"BTCUSDT","side":"LONG","entries":[{"quantity":0.01}],"leverage":10,
                 "stopLossPrice":95000,"playType":"BREAKOUT","signalsUsed":"突破前高",
                 "invalidationCondition":"1h收盘跌破95000"}""";
        WakeRound round = round(trader(), null);
        AlertTrigger alert = new AlertTrigger("BTCUSDT", new BigDecimal("1.2"), new BigDecimal("100000"),
                AlertTrigger.UP, 1785171600000L + 60_000L);
        ChatModel model = onlineModel(n -> switch (n) {
            case 1 -> {
                // 第一次调模型期间警报到了
                round.offer(alert);
                yield AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("acct", "function", "get_account", "{}"))).build();
            }
            case 2 -> AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall("open", "function", "open_position", openArgs))).build();
            case 3 -> new AssistantMessage("事实核查完成，已按警报开多。");
            case 4 -> new AssistantMessage("不需要联网。");
            default -> new AssistantMessage("[本轮结论]\n[BTCUSDT]\n判断：突破\n动作：开多\n等待：无");
        });
        when(simTradeClient.openPosition(eq(99L), any())).thenReturn(filledReceipt());

        runner.wake(trader(), 1785171600000L, round);

        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(5)).stream(sent.capture());
        // 第二次调模型之前插进去了
        List<Message> second = sent.getAllValues().get(1).getInstructions();
        assertThat(second.getLast()).isInstanceOf(UserMessage.class);
        assertThat(second.getLast().getText()).contains("你这一轮进行中，又发生了一件事")
                .contains("行情波动警报").contains("BTCUSDT 现在的持仓、挂单和为它布的盯盘条件")
                .contains("BTCUSDT 的开仓、加仓、反手现在就可以做");
        // 还在第一段，这个币的开仓没被"尚未进入交易决策阶段"拒
        verify(simTradeClient).openPosition(eq(99L), any());
        // 都读到了，没有剩下要另起一轮的
        assertThat(round.take().isEmpty()).isTrue();
    }

    /** 调用预算只剩最后一次时到来的触发不取：这一轮已经调不了工具，留着等它结束后另起一轮 */
    @Test
    void triggerArrivingWhenOnlyTheFinalCallIsLeftStaysUnread() {
        stubHealthyAccount();
        WakeRound round = round(trader(), null);
        AlertTrigger alert = new AlertTrigger("BTCUSDT", new BigDecimal("1.2"), new BigDecimal("100000"),
                AlertTrigger.UP, 1785171600000L + 60_000L);
        int limit = TraderWakeupRunner.MAX_MODEL_CALLS;
        ChatModel model = onlineModel(n -> {
            if (n == limit - 1) {
                // 最后一次能执行工具的调用期间警报到了
                round.offer(alert);
            }
            return n < limit ? AssistantMessage.builder().content("").toolCalls(List.of(
                    new AssistantMessage.ToolCall("acct" + n, "function", "get_account", "{}"))).build()
                    : new AssistantMessage("[本轮结论]\n[BTCUSDT]\n判断：观望\n动作：无\n等待：无");
        });

        runner.wake(trader(), 1785171600000L, round);

        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(model, atLeastOnce()).stream(sent.capture());
        assertThat(userText(sent.getAllValues().getLast())).doesNotContain("你这一轮进行中，又发生了一件事");
        assertThat(round.take().triggers()).containsExactly(alert);
    }

    /** 插话时这个币的现状没查到：话照发，只是不带现状，这一轮不作废 */
    @Test
    void interjectionStillGoesOutWhenTheAccountLookupFails() {
        stubHealthyAccount();
        WakeRound round = round(trader(), null);
        AlertTrigger alert = new AlertTrigger("BTCUSDT", new BigDecimal("1.2"), new BigDecimal("100000"),
                AlertTrigger.UP, 1785171600000L + 60_000L);
        ChatModel model = onlineModel(n -> {
            if (n == 1) {
                round.offer(alert);
                // 接下来取账户的那一下失败，之后恢复
                when(simTradeClient.getAllPositions(99L)).thenThrow(new IllegalStateException("sim down"))
                        .thenReturn(List.of());
            }
            return new AssistantMessage(n < 4 ? "这一段没有要做的。" : "[本轮结论]\n[BTCUSDT]\n判断：观望\n动作：无\n等待：无");
        });

        runner.wake(trader(), 1785171600000L, round);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_OK);
        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(model, atLeastOnce()).stream(sent.capture());
        assertThat(userText(sent.getAllValues().get(1))).contains("你这一轮进行中，又发生了一件事").contains("行情波动警报")
                .doesNotContain("BTCUSDT 现在的持仓、挂单和为它布的盯盘条件");
    }

    /** 开场白里已经报过的盯盘命中，再从插话那条路过来不说第二遍 */
    @Test
    void watchHitAlreadyInOpeningIsNotInterjectedAgain() {
        stubHealthyAccount();
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);
        when(watchMapper.selectList(any())).thenReturn(List.of(closeHit()));
        WakeRound round = round(trader(), null);
        round.offer(new WatchTrigger(closeHit(), new BigDecimal("63120"), 1785171600000L));

        runner.wake(trader(), 1785171600000L, round);

        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(model, atLeastOnce()).stream(sent.capture());
        List<Message> first = sent.getAllValues().getFirst().getInstructions();
        assertThat(first.stream().filter(UserMessage.class::isInstance)).hasSize(1);
        assertThat(first.getLast().getText()).contains("BTCUSDT 1h 收盘 63120 站上 63000").doesNotContain("又发生了一件事");
    }

    /** 一轮进行中插话告诉它的盯盘命中，这一轮成功后标的是插进去那一刻，不是这一轮的 wake_time */
    @Test
    @SuppressWarnings("unchecked")
    void watchHitToldMidRoundIsMarkedWithTheMomentItWasTold() {
        stubHealthyAccount();
        WakeRound round = round(trader(), null);
        onlineModel(n -> {
            if (n == 1) {
                round.offer(new WatchTrigger(closeHit(), new BigDecimal("63120"), 1785171600000L + 500L));
            }
            return new AssistantMessage(n < 4 ? "这一段没有要做的。" : "[本轮结论]\n[BTCUSDT]\n判断：观望\n动作：无\n等待：无");
        });

        runner.wake(trader(), 1785171600000L, round);

        ArgumentCaptor<LambdaUpdateWrapper<AiTraderWatch>> marked = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(watchMapper).update(isNull(), marked.capture());
        marked.getValue().getSqlSet();
        assertThat(marked.getValue().getParamNameValuePairs().values())
                .contains(1785171600000L + 1_000L).doesNotContain(1785171600000L);
    }

    /** 这一轮失败了，开场白里念过的盯盘命中不算告诉过，库里不标 */
    @Test
    void failedRoundLeavesWatchHitsUnreported() {
        stubHealthyAccount();
        ChatModel failing = modelFailing();
        when(modelFactory.modelFor(any())).thenReturn(failing);
        when(watchMapper.selectList(any())).thenReturn(List.of(closeHit()));

        wake(trader(), 1785171600000L);

        verify(watchMapper, never()).update(any(), any());
    }

    /** 失败轮对出来的对账事件存着，下一轮开场白接着报，那一轮成功了才清 */
    @Test
    void reconcileEventsOfFailedRoundAreReportedAgain() {
        stubHealthyAccount();
        AiTraderPlan plan = new AiTraderPlan();
        plan.setSymbol("BTCUSDT");
        plan.setSide("LONG");
        doReturn(new TraderPlanStore.Reconcile(List.of(), List.of(new TraderPlanStore.Cancelled(plan))),
                new TraderPlanStore.Reconcile(List.of(), List.of()))
                .when(planStore).reconcile(anyLong(), anyInt(), any(), any(), any(), any(), anyLong(), any());
        ChatModel failing = modelFailing();
        ChatModel ok = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(failing, ok);
        String line = prompts.get(AgentLang.ZH, "trader.wake.eventCancelled", Map.of("symbol", "BTCUSDT", "side", "LONG"));

        wake(trader(), 1785171600000L);
        assertThat(stored.getUnreportedEvents()).contains("BTCUSDT").contains("计划归档");

        wake(trader(), 1785175200000L);

        ArgumentCaptor<Prompt> sent = ArgumentCaptor.forClass(Prompt.class);
        verify(ok, atLeastOnce()).stream(sent.capture());
        assertThat(sent.getAllValues().getFirst().getContents()).contains(line);
        // 成功的那一轮报完，库里清掉
        assertThat(stored.getUnreportedEvents()).isNull();
    }

    /** 这根收盘刚命中、还没告诉过模型的一条收盘类盯盘条件 */
    private static AiTraderWatch closeHit() {
        AiTraderWatch hit = new AiTraderWatch();
        hit.setId(31L);
        hit.setTraderId(7L);
        hit.setRoundNo(1);
        hit.setSymbol("BTCUSDT");
        hit.setForSymbol("BTCUSDT");
        hit.setKind("CLOSE_ABOVE");
        hit.setLevel(new BigDecimal("63000"));
        hit.setPeriod("1h");
        hit.setNote("突破确认，看回踩做多");
        hit.setStatus(AiTraderWatch.STATUS_FIRED);
        hit.setFiredAt(1785171600000L);
        hit.setFiredPrice(new BigDecimal("63120"));
        hit.setCreatedWakeTime(1785168000000L);
        return hit;
    }

    /** 合并轨迹按调用 id 对：没留下富记录的那次交易调用标成未执行，紧跟着同名重试的那次拿回自己的富记录；数据工具的占位不带状态 */
    @Test
    void mergeActionsPairsRichRecordByCallId() {
        ObjectNode klines = MAPPER.createObjectNode().put("tool", "klines").put("callId", "c1");
        ObjectNode closeUnrecorded = MAPPER.createObjectNode().put("tool", "close_position").put("callId", "c2");
        ObjectNode closeRetried = MAPPER.createObjectNode().put("tool", "close_position").put("callId", "c3");
        ObjectNode closeRich = MAPPER.createObjectNode().put("tool", "close_position").put("callId", "c3").put("status", "ok");

        List<ObjectNode> merged = WakeSession.mergeActions(
                List.of(klines, closeUnrecorded, closeRetried), List.of(closeRich));

        assertThat(merged).extracting(a -> a.path("callId").asString()).containsExactly("c1", "c2", "c3");
        assertThat(merged.get(0).has("status")).isFalse();
        assertThat(merged.get(1).path("status").asString()).isEqualTo("not_attempted");
        assertThat(merged.get(2)).isSameAs(closeRich);
    }

    /** 参数绑不上的平仓没进方法，模型改好参数重试同一个工具：调用 id 从 ReactLoop 一路带到两边，记录各归各的那次 */
    @Test
    void retryAfterUnboundArgsKeepsEachCallsOwnRecord() {
        stubHealthyAccount();
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(crossPosition("2000", "0")));
        when(simTradeClient.closePosition(eq(99L), any())).thenReturn(filledReceipt());
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.stream(any(Prompt.class))).thenReturn(
                frameOf(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function",
                        "close_position", "{\"positionId\":\"abc\",\"quantity\":0.1,\"reason\":\"到目标位\"}"))).build()),
                frameOf(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("c2", "function",
                        "close_position", "{\"positionId\":349,\"quantity\":0.1,\"reason\":\"到目标位\"}"))).build()),
                frameOf(new AssistantMessage("平掉一部分，本轮结束。")));
        when(modelFactory.modelFor(any())).thenReturn(model);

        wake(trader(), 1785171600000L);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        JsonNode actions = MAPPER.readTree(dec.getValue().getActionsJson());
        assertThat(actions.get(0).path("callId").asString()).isEqualTo("c1");
        assertThat(actions.get(0).path("status").asString()).isEqualTo("not_attempted");
        assertThat(actions.get(1).path("callId").asString()).isEqualTo("c2");
        assertThat(actions.get(1).path("status").asString()).isEqualTo("ok");
    }

    /** 超时那一刻平仓还在等 sim：等会话线程停了再收动作轨迹，工具自己记的"结果未知"收得进来 */
    @Test
    void timeoutCollectsInFlightTradeCallAfterSessionStops() {
        stubHealthyAccount();
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(crossPosition("2000", "0")));
        // sim 一直不回，直到被超时打断
        when(simTradeClient.closePosition(eq(99L), any())).thenAnswer(inv -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new ResourceAccessException("interrupted");
        });
        ChatModel model = modelCallingThenSummary("close_position",
                "{\"positionId\":349,\"quantity\":0.1,\"reason\":\"到目标位\"}");
        when(modelFactory.modelFor(any())).thenReturn(model);
        AiTrader trader = trader();

        runner.doWake(trader, round(trader, null),1785171600000L, 2, TraderWakeupRunner.baseDecision(trader, 1785171600000L),
                List.of(), AgentLang.ZH);

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
        JsonNode action = MAPPER.readTree(dec.getValue().getActionsJson()).get(0);
        assertThat(action.path("tool").asString()).isEqualTo("close_position");
        assertThat(action.path("status").asString()).isEqualTo("unknown");
    }

    /** 会话线程卡在不理中断的调用里：最多再等 stopWait 就收尾，唤醒不会一直挂着 */
    @Test
    void timeoutStopsWaitingForStuckSession() {
        stubHealthyAccount();
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(crossPosition("2000", "0")));
        CountDownLatch release = new CountDownLatch(1);
        // sim 一直不回，打断了也不理，放行了才返回
        when(simTradeClient.closePosition(eq(99L), any())).thenAnswer(inv -> {
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    // 不理中断
                }
            }
            throw new IllegalStateException("released");
        });
        ChatModel model = modelCallingThenSummary("close_position",
                "{\"positionId\":349,\"quantity\":0.1,\"reason\":\"到目标位\"}");
        when(modelFactory.modelFor(any())).thenReturn(model);
        AiTrader trader = trader();
        session.stopWait = Duration.ofMillis(200);

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> runner.doWake(trader, round(trader, null),1785171600000L, 2,
                    TraderWakeupRunner.baseDecision(trader, 1785171600000L), List.of(), AgentLang.ZH));
        } finally {
            release.countDown();
        }

        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
    }

    /** 一调就报错的模型 */
    private static ChatModel modelFailing() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.stream(any(Prompt.class))).thenReturn(Flux.error(new NonTransientAiException("boom")));
        return model;
    }

    /**
     * 开场白对决策行的查询（recent 回注窗口、上次 OK 唤醒时刻）只认唤醒行白名单：REVIEW 的产出已经走
     * memory 注入，混进 5 条窗口就是重复占字数；ALERT/MANUAL/EVENT/WATCH 是真实交易决策必须保留。
     */
    @Test
    void recentDecisionsQueryExcludesReviewRows() {
        stubHealthyAccount();
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);

        wake(trader(), 1785171600000L);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiTraderDecision>> q =
                ArgumentCaptor.forClass((Class) com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(decisionMapper, atLeastOnce()).selectList(q.capture());
        for (var wrapper : q.getAllValues()) {
            assertThat(wrapper.getSqlSegment()).contains("kind IN");
            var values = wrapper.getParamNameValuePairs().values();
            assertThat(values).contains(AiTraderDecision.KIND_TRADE, AiTraderDecision.KIND_ALERT,
                    AiTraderDecision.KIND_MANUAL, AiTraderDecision.KIND_EVENT, AiTraderDecision.KIND_WATCH,
                    AiTraderDecision.KIND_DRAWDOWN);
            assertThat(values).doesNotContain(AiTraderDecision.KIND_REVIEW);
        }
    }

    /** 一条 Prompt 里全部 user 消息的文本拼起来 */
    private static String userText(Prompt prompt) {
        return prompt.getInstructions().stream().filter(m -> m instanceof UserMessage)
                .map(Message::getText).reduce("", String::concat);
    }

    /** 带计划的持仓：第一段要处置它 */
    private void stubHeldPosition() {
        when(simTradeClient.getAllPositions(99L)).thenReturn(List.of(crossPosition("1000", "-5")));
        when(simTradeClient.getBalanceDetail(99L)).thenReturn(Map.of("balance", "9995", "frozenBalance", "0", "equity", "9990"));
        when(simTradeClient.getPendingOrders(99L, null)).thenReturn(List.of());
        when(binanceRestClient.getPremiumIndex("BTCUSDT"))
                .thenReturn("{\"markPrice\":\"100000\",\"nextFundingTime\":0,\"lastFundingRate\":\"0.0001\"}");
        when(decisionMapper.selectList(any())).thenReturn(List.of());
        AiTraderPlan plan = new AiTraderPlan();
        plan.setId(21L);
        plan.setSymbol("BTCUSDT");
        plan.setSide("LONG");
        plan.setStatus(AiTraderPlan.STATUS_LIVE);
        plan.setPositionId(349L);
        plan.setPlayType("PULLBACK");
        plan.setInvalidationCondition("1h收盘跌破98000");
        plan.setOpenedWakeTime(1785171600000L - 3600_000L);
        when(planMapper.selectList(any())).thenReturn(List.of(plan));
    }

    /**
     * 有承诺要处置就分两段：第一段只有承诺和问题，答完才追加第二段（整盘事实 + 布防 + 收尾），第一段的往返都在第二段的输入里；
     * 结论取第二段写的；现场多一道 stage 帧，轨迹记下它从第几次调用起
     */
    @Test
    void twoStageAppendsSecondOpeningAfterFirstAnswer() {
        stubHeldPosition();
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AssistantMessage check = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "get_account", "{}")))
                .build();
        when(model.stream(any(Prompt.class))).thenReturn(
                frameOf(check),
                frameOf(new AssistantMessage("第一段：失效条件没触发，持仓维持。")),
                frameOf(new AssistantMessage("第二段看完。[本轮结论]\n[BTCUSDT]\n判断：持有\n动作：HOLD\n等待：1h 收盘站上 102000")));
        when(modelFactory.modelFor(any())).thenReturn(model);
        when(decisionMapper.insert(any(AiTraderDecision.class))).thenAnswer(inv -> {
            inv.<AiTraderDecision>getArgument(0).setId(1234L);
            return 1;
        });
        Frames owner = new Frames();
        hub.subscribeTrader(7L, owner);

        wake(trader(), 1785171600000L);

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(3)).stream(prompts.capture());
        // 第一段：承诺块在、问题是"处置"，整盘事实（快照）不在
        String first = userText(prompts.getAllValues().get(0));
        assertThat(first).contains("【你的承诺】").contains("1h收盘跌破98000").contains("只处置你自己留下的事")
                .doesNotContain("行情快照");
        // 第三次调用的输入：末尾是第二段开场白，前面第一段的答复还在
        List<Message> third = prompts.getAllValues().get(2).getInstructions();
        assertThat(third.getLast()).isInstanceOf(UserMessage.class);
        assertThat(third.getLast().getText()).startsWith("第二段。").contains("行情快照").contains("set_watch");
        assertThat(third).anyMatch(m -> "第一段：失效条件没触发，持仓维持。".equals(m.getText()));

        // 决策正文两段都要：第一段为什么处置、第二段的结论
        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getReasoning())
                .isEqualTo("第一段：失效条件没触发，持仓维持。\n\n第二段看完。[本轮结论]\n[BTCUSDT]\n判断：持有\n动作：HOLD\n等待：1h 收盘站上 102000");
        JsonNode trace = MAPPER.readTree(dec.getValue().getTraceJson());
        assertThat(trace.get("stages")).hasSize(1);
        assertThat(trace.get("stages").get(0).path("n").asInt()).isEqualTo(2);
        assertThat(trace.get("stages").get(0).path("call").asInt()).isEqualTo(3);
        assertThat(trace.get("calls")).hasSize(3);
        // 现场：分界帧在第二次调用结束之后、第三次开始之前
        assertThat(owner.events).containsSubsequence("model_end", "stage", "model_start");
        assertThat(owner.last("stage").path("call").asInt()).isEqualTo(3);
        assertThat(owner.last("stage").path("instruction").asString(null)).startsWith("第二段。");
    }

    /** 没有承诺、没留言、不是非例行：只有一段，整盘事实和布防问题都在第一条开场白里，现场没有分界帧 */
    @Test
    void quietWakeStaysSingleStage() {
        stubHealthyAccount();
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);
        Frames owner = new Frames();
        hub.subscribeTrader(7L, owner);

        wake(trader(), 1785171600000L);

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).stream(prompts.capture());
        String first = userText(prompts.getAllValues().get(0));
        assertThat(first).contains("行情快照").contains("用 set_watch 布下去")
                .doesNotContain("【你的承诺】").doesNotContain("第二段");
        assertThat(owner.events).doesNotContain("stage");
    }

    /** 承诺块：在盯的条件带离现价多远，上一轮的等待行念回来；那个币布了条件就不注"没布" */
    @Test
    void commitmentsListWatchDistanceAndLastWaitingLine() {
        stubHealthyAccount();
        AiTraderWatch w = new AiTraderWatch();
        w.setId(31L);
        w.setTraderId(7L);
        w.setRoundNo(1);
        w.setSymbol("BTCUSDT");
        w.setForSymbol("BTCUSDT");
        w.setKind("TOUCH_BELOW");
        w.setLevel(new BigDecimal("95000"));
        w.setNote("回到前低看止跌");
        w.setStatus(AiTraderWatch.STATUS_ARMED);
        w.setCreatedWakeTime(1785168000000L);
        // 只有查在盯的那条 SQL 回它：命中未报那条查的是 FIRED。参数值要先拼 SQL 段才填进来
        when(watchMapper.selectList(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<AiTraderWatch> query = inv.getArgument(0);
            query.getSqlSegment();
            return query.getParamNameValuePairs().containsValue(AiTraderWatch.STATUS_ARMED) ? List.of(w) : List.of();
        });
        AiTraderDecision prev = new AiTraderDecision();
        prev.setWakeTime(1785171600000L - 3600_000L);
        prev.setKind(AiTraderDecision.KIND_TRADE);
        prev.setStatus(AiTraderDecision.STATUS_OK);
        prev.setEquity(new BigDecimal("10000"));
        prev.setReasoning("[本轮结论]\n[BTCUSDT]\n判断：震荡\n动作：HOLD\n等待：回踩 99000 做多");
        when(decisionMapper.selectList(any())).thenReturn(List.of(prev));
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);

        wake(trader(), 1785171600000L);

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, atLeastOnce()).stream(prompts.capture());
        String first = userText(prompts.getAllValues().get(0));
        assertThat(first).contains("【你的承诺】")
                .contains("- BTCUSDT 触及 95000 以下（备注：回到前低看止跌）：未命中；现价 100000，在 95000 上方 5.26%")
                .contains("- BTCUSDT 你上一轮等的「回踩 99000 做多」")
                .doesNotContain("没有布盯盘条件");
    }

    /** 等待行写了条件、那个币一条盯盘条件都没布：承诺块注明；写"无"的不算承诺，不分段 */
    @Test
    void waitingLineWithoutWatchIsFlagged() {
        stubHealthyAccount();
        AiTraderDecision prev = new AiTraderDecision();
        prev.setWakeTime(1785171600000L - 3600_000L);
        prev.setKind(AiTraderDecision.KIND_TRADE);
        prev.setStatus(AiTraderDecision.STATUS_OK);
        prev.setEquity(new BigDecimal("10000"));
        prev.setReasoning("[本轮结论]\n[BTCUSDT]\n动作：HOLD\n等待：回踩 99000 做多");
        when(decisionMapper.selectList(any())).thenReturn(List.of(prev));
        ChatModel model = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(model);

        wake(trader(), 1785171600000L);

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(3)).stream(prompts.capture());
        assertThat(userText(prompts.getAllValues().get(0)))
                .contains("- BTCUSDT 你上一轮等的「回踩 99000 做多」（这个币没有布盯盘条件）");

        prev.setReasoning("[本轮结论]\n[BTCUSDT]\n动作：HOLD\n等待：无。");
        ChatModel quiet = modelCheckingThenSummary();
        when(modelFactory.modelFor(any())).thenReturn(quiet);
        wake(trader(), 1785171600000L);
        verify(quiet, times(2)).stream(any(Prompt.class));
    }

    /** 预算在第一段用尽：第二段照样追加、换成只许收尾那版，模型据此直接写结论；撞上限照样留痕 */
    @Test
    void budgetExhaustedInStageOneStillGetsClosingStage() {
        stubHeldPosition();
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AtomicInteger n = new AtomicInteger();
        when(model.stream(any(Prompt.class))).thenAnswer(inv -> {
            int i = n.incrementAndGet();
            return i <= TraderWakeupRunner.MAX_MODEL_CALLS
                    ? frameOf(AssistantMessage.builder().content("")
                            .toolCalls(List.of(new AssistantMessage.ToolCall("c" + i, "function", "get_account", "{}"))).build())
                    : frameOf(new AssistantMessage("不查了。[本轮结论]\n[BTCUSDT]\n判断：持有\n动作：HOLD\n等待：无"));
        });
        when(modelFactory.modelFor(any())).thenReturn(model);

        wake(trader(), 1785171600000L);

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(TraderWakeupRunner.MAX_MODEL_CALLS + 1)).stream(prompts.capture());
        assertThat(prompts.getAllValues().getLast().getInstructions().getLast().getText())
                .contains("预算已用完").contains("set_watch 也不行");
        ArgumentCaptor<AiTraderDecision> dec = ArgumentCaptor.forClass(AiTraderDecision.class);
        verify(decisionMapper).insert(dec.capture());
        assertThat(dec.getValue().getReasoning()).contains("[本轮结论]");
        assertThat(dec.getValue().getError()).contains("上限");
    }
}
