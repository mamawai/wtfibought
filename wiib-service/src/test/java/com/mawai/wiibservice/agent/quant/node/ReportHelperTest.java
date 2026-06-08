package com.mawai.wiibservice.agent.quant.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibservice.agent.quant.CryptoAnalysisReport;
import com.mawai.wiibservice.agent.quant.domain.AgentVote;
import com.mawai.wiibservice.agent.quant.domain.Direction;
import com.mawai.wiibservice.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibservice.agent.quant.domain.ForecastResult;
import com.mawai.wiibservice.agent.quant.domain.HorizonForecast;
import com.mawai.wiibservice.agent.quant.domain.MarketRegime;
import com.mawai.wiibservice.agent.quant.factor.NewsEventAgent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReportHelperTest {

    @Test
    void hardReportBuilderBuildsStructuredReportWithoutLlm() {
        List<HorizonForecast> forecasts = List.of(
                new HorizonForecast("H6", Direction.LONG, 0.72, 0.5, 0.2,
                        BigDecimal.valueOf(100), BigDecimal.valueOf(101), BigDecimal.valueOf(99),
                        BigDecimal.valueOf(103), BigDecimal.valueOf(105), 3, 0.08),
                HorizonForecast.noTrade("H12", 0.4),
                HorizonForecast.noTrade("H24", 0.5)
        );
        List<AgentVote> votes = List.of(
                new AgentVote("momentum", "H6", Direction.LONG, 0.7, 0.8, 20, 30, List.of(), List.of()),
                new AgentVote("news_event", "H6", Direction.LONG, 0.2, 0.7, 10, 20, List.of(), List.of())
        );
        List<NewsEventAgent.FilteredNewsItem> news = List.of(
                new NewsEventAgent.FilteredNewsItem("ETF inflow", "bullish", "HIGH", "资金流入"));

        CryptoAnalysisReport report = new ReportHardReportBuilder(null).build(
                "BTCUSDT", forecasts, votes, news, "LONG", "NORMAL", snapshot(), null,
                Map.of("H6", new Object[]{55, 30, 15}));

        assertThat(report.getSummary()).contains("当前优先关注H6(6h)做多");
        assertThat(report.getAnalysisBasis()).contains("结构化裁决结果为LONG");
        assertThat(report.getDirection().getUltraShort()).isEqualTo("做多(55%) 震荡(30%) 偏空(15%)");
        assertThat(report.getDirection().getLongTerm()).isEqualTo("观望");
        assertThat(report.getKeyLevels().getSupport()).containsExactly("99.7", "99.4", "99.1");
        assertThat(report.getPositionAdvice().getFirst().getRiskReward()).isEqualTo("1:1.67");
        assertThat(report.getImportantNews().getFirst().getSentiment()).isEqualTo("偏多");
        assertThat(report.getRiskWarnings()).anyMatch(warning -> warning.contains("数据存在质量标记"));
        assertThat(report.getConfidence()).isEqualTo(72);
        assertThat(report.getMacroContext()).isEqualTo("宏观上下文预热中，暂不介入交易。");
    }

    @Test
    void promptBuilderKeepsStructuredBlocksAndFormatsContext() {
        CryptoAnalysisReport hardReport = hardReport();
        List<AgentVote> votes = List.of(new AgentVote("momentum", "H6", Direction.LONG,
                0.7, 0.8, 20, 30, List.of("ORDERFLOW"), List.of("SPREAD_WIDE")));
        List<NewsEventAgent.FilteredNewsItem> news = List.of(
                new NewsEventAgent.FilteredNewsItem("ETF inflow", "bullish", "HIGH", "资金流入"));

        String prompt = new ReportPromptBuilder().build(hardReport, votes,
                "{\"rsi\":35}", "{\"5m\":\"1%\"}", news, "NORMAL", null,
                "{\"bullArgument\":\"多头\",\"bearArgument\":\"空头\",\"judgeReasoning\":\"观望\"}",
                "历史命中率偏低");

        assertThat(prompt)
                .contains("【方向裁决（不可修改）】")
                .contains("短周期(H6/6h): 做多(60%)")
                .contains("H6(6h): 方向=LONG 具体入场/止损/止盈由交易执行层生成")
                .contains("momentum[H6]: LONG score=0.70 conf=0.80 reasons=[ORDERFLOW] flags=[SPREAD_WIDE]")
                .contains("- [bullish/HIGH] ETF inflow → 资金流入")
                .contains("Bull论据: 多头")
                .contains("历史命中率偏低");
    }

    @Test
    void responseMergerKeepsHardStructuredFieldsAndUsesLlmTextFields() {
        CryptoAnalysisReport hardReport = hardReport();

        CryptoAnalysisReport merged = new ReportResponseMerger().merge(hardReport, """
                ```json
                {
                  "reasoning": "模型推理",
                  "summary": "模型总结",
                  "analysisBasis": "",
                  "indicators": "模型指标",
                  "riskWarnings": ["", "模型风险"]
                }
                ```
                """);

        assertThat(merged.getDirection()).isSameAs(hardReport.getDirection());
        assertThat(merged.getKeyLevels()).isSameAs(hardReport.getKeyLevels());
        assertThat(merged.getPositionAdvice()).isSameAs(hardReport.getPositionAdvice());
        assertThat(merged.getImportantNews()).isSameAs(hardReport.getImportantNews());
        assertThat(merged.getConfidence()).isEqualTo(60);
        assertThat(merged.getSummary()).isEqualTo("模型总结");
        assertThat(merged.getReasoning()).isEqualTo("模型推理");
        assertThat(merged.getAnalysisBasis()).isEqualTo("硬依据");
        assertThat(merged.getIndicators()).isEqualTo("模型指标");
        assertThat(merged.getRiskWarnings()).containsExactly("模型风险");
    }

    @Test
    void snapshotSerializerAddsStateOnlyPersistenceFields() {
        FeatureSnapshot snapshot = snapshot();
        OverAllState state = new OverAllState(Map.of(
                "regime_confidence_stddev", 0.12,
                "news_confidence_stddev", 0.34,
                "news_low_confidence", true,
                "memory_weight_adjustments", Map.of("momentum", 0.2),
                "macro_context", Map.of("status", "neutral")
        ));

        String json = new ReportSnapshotSerializer().serialize(snapshot, state);

        JSONObject obj = JSON.parseObject(json);
        assertThat(obj.getString("symbol")).isEqualTo("BTCUSDT");
        assertThat(obj.getDoubleValue("regimeConfidenceStddev")).isEqualTo(0.12);
        assertThat(obj.getDoubleValue("newsConfidenceStddev")).isEqualTo(0.34);
        assertThat(obj.getBoolean("newsLowConfidence")).isTrue();
        assertThat(obj.getJSONObject("memoryWeightAdjustments").getDoubleValue("momentum")).isEqualTo(0.2);
        assertThat(obj.getJSONObject("macroContext").getString("status")).isEqualTo("neutral");
    }

    @Test
    void forecastResultJsonRoundTripKeepsSnapshotForResearchForecast() {
        List<HorizonForecast> horizons = List.of(new HorizonForecast("H6", Direction.LONG,
                0.72, 0.5, 0.2, BigDecimal.valueOf(100), BigDecimal.valueOf(101),
                BigDecimal.valueOf(99), BigDecimal.valueOf(103), BigDecimal.valueOf(105),
                3, 0.08));
        List<AgentVote> votes = List.of(new AgentVote("momentum", "H6", Direction.LONG,
                0.7, 0.8, 20, 30, List.of("ORDERFLOW"), List.of()));
        ForecastResult original = new ForecastResult("BTCUSDT", "cycle-1",
                LocalDateTime.of(2026, 1, 1, 0, 30),
                horizons, "LONG", "NORMAL", votes, snapshot(), null, hardReport(), null);

        ForecastResult parsed = JSON.parseObject(JSON.toJSONString(original), ForecastResult.class);

        assertThat(parsed.snapshot()).isNotNull();
        assertThat(parsed.snapshot().symbol()).isEqualTo("BTCUSDT");
        assertThat(parsed.snapshot().lastPrice()).isEqualByComparingTo("100");
        assertThat(parsed.snapshot().qualityFlags()).contains("PARTIAL_KLINE_DATA");
        assertThat(parsed.horizons()).hasSize(1);
        assertThat(parsed.allVotes()).hasSize(1);
        assertThat(parsed.report()).isNotNull();
    }

    private CryptoAnalysisReport hardReport() {
        CryptoAnalysisReport report = new CryptoAnalysisReport();
        report.setSummary("硬总结");
        report.setAnalysisBasis("硬依据");
        report.setReasoning("硬推理");
        report.setIndicators("硬指标");
        report.setRiskWarnings(List.of("硬风险"));
        report.setConfidence(60);
        report.setMacroContext("宏观中性");

        CryptoAnalysisReport.DirectionInfo direction = new CryptoAnalysisReport.DirectionInfo();
        direction.setUltraShort("做多(60%)");
        direction.setShortTerm("震荡(80%)");
        direction.setMid("偏空(40%)");
        direction.setLongTerm("观望");
        report.setDirection(direction);

        CryptoAnalysisReport.KeyLevels keyLevels = new CryptoAnalysisReport.KeyLevels();
        keyLevels.setSupport(List.of("99", "98", "97"));
        keyLevels.setResistance(List.of("101", "102", "103"));
        report.setKeyLevels(keyLevels);

        CryptoAnalysisReport.PositionAdvice advice = new CryptoAnalysisReport.PositionAdvice();
        advice.setPeriod("H6(6h)");
        advice.setType("LONG");
        advice.setEntry("100 - 101");
        advice.setStopLoss("99");
        advice.setTakeProfit("103 / 105");
        advice.setRiskReward("1:2");
        report.setPositionAdvice(List.of(advice));

        CryptoAnalysisReport.ImportantNews importantNews = new CryptoAnalysisReport.ImportantNews();
        importantNews.setTitle("ETF inflow");
        importantNews.setSentiment("偏多");
        importantNews.setSummary("资金流入");
        report.setImportantNews(List.of(importantNews));
        return report;
    }

    private FeatureSnapshot snapshot() {
        return new FeatureSnapshot("BTCUSDT", LocalDateTime.of(2026, 1, 1, 0, 0),
                BigDecimal.valueOf(100), null, null, null,
                Map.of(), Map.of(), 0, null, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 50, "Neutral",
                null, null, null, false,
                0, 0, 0, 0,
                MarketRegime.RANGE, List.of(), List.of("PARTIAL_KLINE_DATA"), 0.7, "NONE");
    }
}
