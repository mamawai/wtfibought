package com.mawai.wiibagent.analysis;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.entity.QuantDeepAnalysis;
import com.mawai.wiibquant.market.service.MarketAssembly;
import com.mawai.wiibquant.market.service.MarketDataService;
import com.mawai.wiibquant.market.service.NewsCache;
import com.mawai.wiibquant.market.service.NewsFlashLocalizer;
import com.mawai.wiibquant.mapper.NewsEventMapper;
import com.mawai.wiibagent.mapper.QuantDeepAnalysisMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeepAnalysisServiceTest {

    private static final PromptCatalog PROMPTS = new PromptCatalog();

    /**
     * 模型是调用方传进来的（BYOK 后每个用户一套），服务自己不去 runtimeManager 现取——
     * 现取就不知道"当前是谁在用"了。所以这里直接 mock ChatModel。
     */
    private final ChatModel model = mock(ChatModel.class);
    private final MarketDataService marketDataService = mock(MarketDataService.class);
    private final NewsCache newsCache = mock(NewsCache.class);
    private final QuantDeepAnalysisMapper mapper = mock(QuantDeepAnalysisMapper.class);
    private final NewsEventMapper newsEventMapper = mock(NewsEventMapper.class);

    private final DeepAnalysisService service = new DeepAnalysisService(marketDataService, newsCache,
            new NewsFlashLocalizer(newsEventMapper), mapper, PROMPTS);

    {
        // 服务内部走 ChatClient，而 ChatClient 建请求时**无条件**执行 getOptions().mutate()
        //（DefaultChatClientUtils），裸 mock 返回 null 会在到达任何断言前就 NPE
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
    }

    private static ChatResponse responseOf(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private void marketUnavailable() {
        when(marketDataService.assemble("BTCUSDT"))
                .thenReturn(MarketAssembly.unavailable("BTCUSDT", Map.of()));
    }

    private String judgeJson(int bull, int range, int bear, boolean noDirection) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("narrative", "多头拥挤+清算邻近，若funding维持高位，未来12h下行脆弱");
        o.put("bullPct", bull);
        o.put("rangePct", range);
        o.put("bearPct", bear);
        o.put("noDirection", noDirection);
        o.put("invalidation", "若funding回正且OI回落则本研判作废");
        o.put("judgeReasoning", "Bear证据更具体");
        return MAPPER.writeValueAsString(o);
    }

    @Test
    void judgeParsesAndNormalizesScenarios() {
        marketUnavailable();
        // 故意给和=97 的分布，验证归一化到 100
        when(model.call(any(Prompt.class))).thenReturn(responseOf(judgeJson(30, 30, 37, false)));

        QuantDeepAnalysis analysis = service.judge(model, "BTCUSDT", 123L, "cron_1h",
                "无新闻上下文", "bull论据", "bear论据", AgentLang.ZH);

        assertThat(analysis).isNotNull();
        assertThat(analysis.getTriggerSource()).isEqualTo("cron_1h");
        JsonNode scenarios = MAPPER.readTree(analysis.getScenariosJson());
        int sum = scenarios.path("bullPct").asInt(0) + scenarios.path("rangePct").asInt(0) + scenarios.path("bearPct").asInt(0);
        assertThat(sum).isEqualTo(100);
        assertThat(analysis.getInvalidation()).contains("作废");
        assertThat(analysis.getNoDirection()).isFalse();
    }

    @Test
    void judgeReturnsNullOnLlmFailure() {
        marketUnavailable();
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException("LLM down"));

        QuantDeepAnalysis analysis = service.judge(model, "BTCUSDT", 123L, "cron_1h",
                "无新闻上下文", "bull", "bear", AgentLang.ZH);

        assertThat(analysis).isNull(); // 研判缺席，不抛异常
    }

    @Test
    void bullArgueDegradesToPlaceholderOnFailure() {
        marketUnavailable();
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException("timeout"));

        assertThat(service.bullArgue(model, "BTCUSDT", "无新闻上下文", AgentLang.ZH)).contains("未能提供论据");
    }

    @Test
    void newsContextDegradesWhenCacheEmpty() {
        when(newsCache.getFlashes()).thenReturn(List.of());

        assertThat(service.buildNewsContext(AgentLang.ZH)).isEqualTo("无新闻上下文");
    }

    @Test
    void noDirectionScenarioPersistsTrue() {
        marketUnavailable();
        when(model.call(argThat((Prompt p) -> p.getContents().contains("Judge"))))
                .thenReturn(responseOf(judgeJson(33, 34, 33, true)));

        QuantDeepAnalysis analysis = service.judge(model, "BTCUSDT", 1L, "sentinel",
                "无新闻上下文", "bull", "bear", AgentLang.ZH);

        assertThat(analysis).isNotNull();
        assertThat(analysis.getNoDirection()).isTrue(); // 无方向态是一等状态
    }

    /**
     * 三个公开方法的产出都必须来自<b>调用方传进来的那个 model</b>。
     * <p>
     * 这条钉的是本次改造的核心：模型从"服务自己去 runtimeManager 现取"改成"参数传入"。
     * 谁把方法体改回不用形参（自己现取、或塞个别的模型进去），这三条断言就拿不到自己打的桩。
     * 三次打桩给三个不同回答，是为了区分"确实各自跑了一次"和"碰巧共用同一个返回值"。
     */
    @Test
    void 三个方法的产出都来自传进来的模型() {
        marketUnavailable();
        when(model.call(any(Prompt.class)))
                .thenReturn(responseOf("看多理由"))
                .thenReturn(responseOf("看空理由"))
                .thenReturn(responseOf(judgeJson(50, 30, 20, false)));

        assertThat(service.bullArgue(model, "BTCUSDT", "无新闻上下文", AgentLang.ZH)).isEqualTo("看多理由");
        assertThat(service.bearArgue(model, "BTCUSDT", "无新闻上下文", AgentLang.ZH)).isEqualTo("看空理由");

        QuantDeepAnalysis analysis = service.judge(model, "BTCUSDT", 1L, "chat",
                "无新闻上下文", "看多理由", "看空理由", AgentLang.ZH);
        assertThat(analysis).isNotNull();
        assertThat(analysis.getNarrative()).contains("下行脆弱");
    }
}
