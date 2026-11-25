package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.PromptI18nAssertions;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.toolkit.CrossAssetToolkit;
import com.mawai.wiibagent.toolkit.DerivativesToolkit;
import com.mawai.wiibagent.toolkit.IndicatorToolkit;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibagent.toolkit.NewsToolkit;
import com.mawai.wiibagent.toolkit.WhaleToolkit;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibquant.external.sim.SimTradeApi;
import com.mawai.wiibquant.whale.WhaleQueryService;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.mapper.AiTraderWatchMapper;
import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import com.mawai.wiibagent.trader.TraderModelFactory;
import com.mawai.wiibagent.trader.prompt.EconCalendarAssembler;
import com.mawai.wiibagent.trader.prompt.TraderPromptAssembler;
import com.mawai.wiibagent.learning.ExperienceLedger;
import com.mawai.wiibagent.llm.BoundedRuns;
import com.mawai.wiibagent.trader.DecisionText;
import com.mawai.wiibagent.trader.trade.AccountGate;
import com.mawai.wiibagent.trader.trade.EntryContext;
import com.mawai.wiibagent.trader.trade.TradeTools;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import com.mawai.wiibagent.trader.trade.TraderRiskConfig;
import com.mawai.wiibquant.market.service.CrossAssetService;
import com.mawai.wiibquant.market.service.KlineFetcher;
import com.mawai.wiibquant.market.service.MarketDataService;
import com.mawai.wiibquant.market.service.NewsCache;
import com.mawai.wiibquant.market.service.NewsFlashLocalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

/**
 * 唤醒挂的那批工具的双语契约。工具描述与提示词一样是模型逐字读的输入：混一句中文，
 * 英文 trader 就会跟着串语言。
 * <p>
 * 断言直接打在 {@link WakeSession#wakeTools} 上——那是建叶子时真正挂上去的那批，
 * 测试里另抄一份工具清单就等于漏掉后来新加的工具。
 */
class TraderToolI18nTest {

    private final PromptCatalog prompts = new PromptCatalog();
    private final BinanceRestClient binance = mock(BinanceRestClient.class);

    private final WakeSession runner = new WakeSession(
            new IndicatorToolkit(new KlineFetcher(binance, 60_000)),
            new MarketToolkit(mock(MarketDataService.class)),
            new DerivativesToolkit(mock(MarketDataService.class)),
            new WhaleToolkit(mock(WhaleQueryService.class)),
            new NewsToolkit(mock(NewsCache.class), mock(NewsFlashLocalizer.class)),
            new CrossAssetToolkit(mock(CrossAssetService.class)),
            new LocalizedToolCallbacks(prompts), prompts, new BoundedRuns());

    /** 只做反射扫描的壳：工具方法一个都不会被调起来，依赖给 null 即可 */
    private final TradeTools tradeTools = new TradeTools(
            mock(SimTradeClient.class), 1L, Set.of("BTCUSDT"), Set.of("BTCUSDT"),
            sym -> BigDecimal.ONE,
            new TraderPlanStore(mock(AiTraderPlanMapper.class), prompts, new TransactionTemplate(mock(PlatformTransactionManager.class))),
            new TraderWatchStore(mock(AiTraderWatchMapper.class), mock(ApplicationEventPublisher.class)),
            new TradeTools.WakeCtx(1L, 1, "1h", 0L, Long.MAX_VALUE, null, AgentLang.ZH, null), prompts, new MessageCatalog(),
            mock(EntryContext.class), _ -> true, new AccountGate());

    private Map<String, String> descriptions(AgentLang lang) {
        return runner.wakeTools(lang, tradeTools, null).stream().collect(Collectors.toMap(
                cb -> cb.getToolDefinition().name(), cb -> cb.getToolDefinition().description()));
    }

    /** 框架按注解自动推导出来的那批工具名（toolsFromObject 走的就是它） */
    private Set<String> annotatedNames() {
        return Arrays.stream(ToolCallbacks.from(tradeTools,
                        new IndicatorToolkit(new KlineFetcher(binance, 60_000)),
                        new MarketToolkit(mock(MarketDataService.class)),
                        new DerivativesToolkit(mock(MarketDataService.class)),
                        new WhaleToolkit(mock(WhaleQueryService.class)),
                        new CrossAssetToolkit(mock(CrossAssetService.class)),
                        new NewsToolkit(mock(NewsCache.class), mock(NewsFlashLocalizer.class))
                                .boundTo(AgentLang.EN)))
                .map(cb -> cb.getToolDefinition().name())
                .collect(Collectors.toSet());
    }

    @Test
    void 英文trader拿到的工具描述全文无中文() {
        Map<String, String> en = descriptions(AgentLang.EN);
        assertThat(en).isNotEmpty();
        en.forEach((name, description) -> {
            PromptI18nAssertions.assertNoCjk("英文工具名 " + name, name);
            PromptI18nAssertions.assertNoCjk("英文工具描述 " + name, description);
        });
    }

    /** 工具表由 LocalizedToolCallbacks 出之后，挂上去的工具一个不能多一个不能少 */
    @Test
    void 工具集与注解自动推导那份逐个对得上() {
        assertThat(descriptions(AgentLang.ZH).keySet()).isEqualTo(annotatedNames());
    }

    /** 换描述不能把自动推导的 inputSchema 弄丢：参数没了模型就调不动工具 */
    @Test
    void 参数与schema照旧由注解推导() {
        ToolCallback open = runner.wakeTools(AgentLang.EN, tradeTools, null).stream()
                .filter(cb -> "open_position".equals(cb.getToolDefinition().name()))
                .findFirst().orElseThrow();
        assertThat(open.getToolDefinition().inputSchema())
                .contains("symbol").contains("stopLossPrice").contains("invalidationCondition");
        var schema = MAPPER.readTree(open.getToolDefinition().inputSchema());
        assertThat(schema.path("properties").path("entries").path("items").path("required").toString()).isEqualTo("[\"quantity\"]");
        assertThat(schema.path("properties").has("quantity")).isFalse();
        assertThat(schema.path("properties").has("orderType")).isFalse();
        var cancel = runner.wakeTools(AgentLang.EN, tradeTools, null).stream()
                .filter(cb -> "cancel_order".equals(cb.getToolDefinition().name())).findFirst().orElseThrow();
        assertThat(MAPPER.readTree(cancel.getToolDefinition().inputSchema()).path("required").toString())
                .isEqualTo("[\"orderIds\",\"reason\"]");
        assertThat(MAPPER.readTree(cancel.getToolDefinition().inputSchema()).path("properties").path("orderIds").path("type").asString()).isEqualTo("array");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"quantity\":0.01}]", "[{\"quantity\":0.01,\"limitPrice\":98000}]",
            "[{\"quantity\":0.01},{\"quantity\":0.02,\"limitPrice\":98000}]", "[]"})
    void wakeToolCallbackBindsEntryArrays(String entries) {
        var sim = mock(SimTradeClient.class);
        when(sim.getBrackets(any())).thenReturn(Map.of("BTCUSDT", List.of(
                new SimTradeApi.Bracket(1, BigDecimal.ZERO, new BigDecimal("1000000000"), 150, BigDecimal.ZERO, BigDecimal.ZERO))));
        when(sim.openPosition(anyLong(), any())).thenAnswer(call -> {
            FuturesOpenRequest req = call.getArgument(1);
            var r = new FuturesOrderResponse(); r.setOrderId(1L);
            r.setStatus(req.getLimitPrice() == null ? "FILLED" : "PENDING"); r.setUpdatedAt(LocalDateTime.now());
            return r;
        });
        when(sim.getEquity(anyLong())).thenReturn(new BigDecimal("10000"));
        TradeTools bound = new TradeTools(sim, 1L, Set.of("BTCUSDT"), Set.of("BTCUSDT"),
                _ -> new BigDecimal("100000"), mock(TraderPlanStore.class), mock(TraderWatchStore.class),
                new TradeTools.WakeCtx(1L, 1, "1h", 0L, Long.MAX_VALUE,
                        new TraderRiskConfig(1, 20, new BigDecimal("50"), true, true), AgentLang.EN, null),
                prompts, new MessageCatalog(), mock(EntryContext.class), _ -> true, new AccountGate());
        ToolCallback cb = runner.wakeTools(AgentLang.EN, bound, null).stream()
                .filter(t -> "open_position".equals(t.getToolDefinition().name())).findFirst().orElseThrow();
        String result = cb.call("{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\",\"entries\":" + entries
                + ",\"leverage\":10,\"stopLossPrice\":95000,\"playType\":\"PULLBACK\",\"signalsUsed\":\"support\",\"invalidationCondition\":\"support breaks\"}");
        if (entries.equals("[]")) assertThat(result).contains("REJECTED");
        else assertThat(MAPPER.readTree(result).path("status").asString()).isEqualTo("ok");
    }
}
