package com.mawai.wiibquant.agent.analysis;

import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.entity.QuantDeepAnalysis;
import com.mawai.wiibquant.agent.toolkit.MarketDataService;
import com.mawai.wiibquant.agent.toolkit.NewsCache;
import com.mawai.wiibquant.agent.toolkit.QuantLlm;
import com.mawai.wiibquant.mapper.QuantDeepAnalysisMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeepAnalysisServiceTest {

    private final QuantLlm quantLlm = mock(QuantLlm.class);
    private final MarketDataService marketDataService = mock(MarketDataService.class);
    private final NewsCache newsCache = mock(NewsCache.class);
    private final QuantDeepAnalysisMapper mapper = mock(QuantDeepAnalysisMapper.class);

    private final DeepAnalysisService service =
            new DeepAnalysisService(quantLlm, marketDataService, newsCache, mapper);

    private String judgeJson(int bull, int range, int bear, boolean noDirection) {
        JSONObject o = new JSONObject();
        o.put("narrative", "多头拥挤+清算邻近，若funding维持高位，未来12h下行脆弱");
        o.put("bullPct", bull);
        o.put("rangePct", range);
        o.put("bearPct", bear);
        o.put("noDirection", noDirection);
        o.put("invalidation", "若funding回正且OI回落则本研判作废");
        o.put("judgeReasoning", "Bear证据更具体");
        return o.toJSONString();
    }

    @Test
    void judgeParsesAndNormalizesScenarios() {
        when(marketDataService.assemble("BTCUSDT"))
                .thenReturn(com.mawai.wiibquant.agent.toolkit.MarketAssembly.unavailable("BTCUSDT", java.util.Map.of()));
        // 故意给和=97 的分布，验证归一化到 100
        when(quantLlm.call(anyString())).thenReturn(judgeJson(30, 30, 37, false));

        QuantDeepAnalysis analysis = service.judge("BTCUSDT", 123L, 9L, "cron_1h",
                "无新闻上下文", "bull论据", "bear论据");

        assertThat(analysis).isNotNull();
        assertThat(analysis.getSnapshotId()).isEqualTo(9L);
        assertThat(analysis.getTriggerSource()).isEqualTo("cron_1h");
        JSONObject scenarios = JSONObject.parseObject(analysis.getScenariosJson());
        int sum = scenarios.getIntValue("bullPct") + scenarios.getIntValue("rangePct") + scenarios.getIntValue("bearPct");
        assertThat(sum).isEqualTo(100);
        assertThat(analysis.getInvalidation()).contains("作废");
        assertThat(analysis.getNoDirection()).isFalse();
    }

    @Test
    void judgeReturnsNullOnLlmFailure() {
        when(marketDataService.assemble("BTCUSDT"))
                .thenReturn(com.mawai.wiibquant.agent.toolkit.MarketAssembly.unavailable("BTCUSDT", java.util.Map.of()));
        when(quantLlm.call(anyString())).thenThrow(new RuntimeException("LLM down"));

        QuantDeepAnalysis analysis = service.judge("BTCUSDT", 123L, null, "cron_1h",
                "无新闻上下文", "bull", "bear");

        assertThat(analysis).isNull(); // 研判缺席，不抛异常（快照时序不受影响）
    }

    @Test
    void bullArgueDegradesToPlaceholderOnFailure() {
        when(marketDataService.assemble("BTCUSDT"))
                .thenReturn(com.mawai.wiibquant.agent.toolkit.MarketAssembly.unavailable("BTCUSDT", java.util.Map.of()));
        when(quantLlm.call(anyString())).thenThrow(new RuntimeException("timeout"));

        assertThat(service.bullArgue("BTCUSDT", "无新闻上下文")).contains("未能提供论据");
    }

    @Test
    void newsContextDegradesWhenCacheEmpty() {
        when(newsCache.getFlashes()).thenReturn(java.util.List.of());

        assertThat(service.buildNewsContext()).isEqualTo("无新闻上下文");
    }

    @Test
    void noDirectionScenarioPersistsTrue() {
        when(marketDataService.assemble("BTCUSDT"))
                .thenReturn(com.mawai.wiibquant.agent.toolkit.MarketAssembly.unavailable("BTCUSDT", java.util.Map.of()));
        when(quantLlm.call(contains("Judge"))).thenReturn(judgeJson(33, 34, 33, true));

        QuantDeepAnalysis analysis = service.judge("BTCUSDT", 1L, null, "sentinel",
                "无新闻上下文", "bull", "bear");

        assertThat(analysis).isNotNull();
        assertThat(analysis.getNoDirection()).isTrue(); // 无方向态是一等状态
    }
}
