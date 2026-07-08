package com.mawai.wiibquant.agent.analysis;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.entity.QuantDeepAnalysis;
import com.mawai.wiibcommon.util.JsonUtils;
import com.mawai.wiibquant.agent.quant.domain.FeatureSnapshot;
import com.mawai.wiibquant.agent.quant.domain.news.NewsFlash;
import com.mawai.wiibquant.agent.toolkit.MarketAssembly;
import com.mawai.wiibquant.agent.toolkit.MarketDataService;
import com.mawai.wiibquant.agent.toolkit.NewsCache;
import com.mawai.wiibquant.agent.toolkit.QuantLlm;
import com.mawai.wiibquant.mapper.QuantDeepAnalysisMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 深研判服务（P2b）：新闻浓缩 → Bull∥Bear 对抗辩论 → Judge 裁决 → 落库。
 * 产物是研判叙事（情景分布/失效条件/无方向态），与交易解耦；LLM 任一步失败只缺席本次研判，
 * 绝不影响数值快照时序（记分卡命脉）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeepAnalysisService {

    private static final BeanOutputConverter<DeepAnalysisResponse> JUDGE_CONVERTER =
            new BeanOutputConverter<>(DeepAnalysisResponse.class);

    private final QuantLlm quantLlm;
    private final MarketDataService marketDataService;
    private final NewsCache newsCache;
    private final QuantDeepAnalysisMapper mapper;

    /** 新闻上下文：缓存的重要快讯原样拼成文本喂辩论（不再 LLM 浓缩）；无则"无新闻上下文"。 */
    public String buildNewsContext() {
        List<NewsFlash> flashes = newsCache.getFlashes();
        if (flashes.isEmpty()) {
            return "无新闻上下文";
        }
        StringBuilder sb = new StringBuilder();
        for (NewsFlash f : flashes) {
            sb.append("· ").append(f.title());
            String body = f.plainContent();
            if (!body.isBlank()) {
                sb.append("：").append(body);
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /** Bull 辩手：严格做多立场；失败给占位论据不阻断。 */
    public String bullArgue(String symbol, String newsContext) {
        return argue(symbol, newsContext, true);
    }

    /** Bear 辩手：严格做空/观望立场；失败给占位论据不阻断。 */
    public String bearArgue(String symbol, String newsContext) {
        return argue(symbol, newsContext, false);
    }

    private String argue(String symbol, String newsContext, boolean bull) {
        String side = bull ? "Bull" : "Bear";
        try {
            String stance = bull
                    ? """
                    你是加密货币研判系统的做多辩手(Bull)。基于以下数据，严格站在"未来6-24小时偏多"立场构建最强论据。
                    - 你的唯一目标是论证偏多情景，不要替对方辩护，不要自我质疑
                    - 引用具体信号数据（如"funding偏离+0.35但清算压力显示空头爆仓"）
                    - 指出有利于多头的 vol 状态、持仓结构、微结构信号"""
                    : """
                    你是加密货币研判系统的做空辩手(Bear)。基于以下数据，严格站在"未来6-24小时偏空或震荡"立场构建最强论据。
                    - 你的唯一目标是论证偏空/震荡情景，不要替对方辩护，不要自我质疑
                    - 引用具体信号数据（如"多头拥挤 lsrExtreme=0.4 且下方清算密集"）
                    - 指出脆弱度结构、资金费率、持仓量、爆仓信号中不利于多头的部分""";
            String prompt = """
                    %s

                    %s

                    限300字，纯文字论述，不要返回JSON。""".formatted(stance, buildDataContext(symbol, newsContext));
            String argument = quantLlm.call(prompt);
            return argument != null && !argument.isBlank() ? argument : side + "辩手未能提供论据";
        } catch (Exception e) {
            log.warn("[Deep] {}辩手失败 symbol={} msg={}", side, symbol, e.getMessage());
            return side + "辩手未能提供论据";
        }
    }

    /** Judge 裁决：综合数据+双方论据产研判；失败返回 null（本次研判缺席）。 */
    public QuantDeepAnalysis judge(String symbol, long closeTime, Long snapshotId, String triggerSource,
                                   String newsContext, String bullArgument, String bearArgument) {
        try {
            String prompt = """
                    你是加密货币研判系统的裁判(Judge)。Bull 与 Bear 辩手已在完全隔离的环境中独立完成辩论。
                    请综合原始数据与双方论据，产出一份"市场研判"——注意：这是研判叙事，不是交易信号，
                    系统的方向预测已被统计验证无 edge，你的职责是把信号综合成可读、可证伪的情景研判。

                    ========== 原始数据 ==========
                    %s

                    ========== 辩论论据 ==========
                    【Bull辩手（做多方）】
                    %s

                    【Bear辩手（做空/观望方）】
                    %s

                    ========== 产出要求 ==========
                    1. narrative：一段研判叙事(150字内)——"若X兑现 → 未来Yh可能Z"的后果式人话
                    2. bullPct/rangePct/bearPct：未来6-24h三情景概率，和必须等于100
                    3. noDirection：信号矛盾、看不清时设 true（大方承认，不硬挤方向）——三情景接近均匀时应为 true
                    4. invalidation：一句话反事实失效条件，必须可证伪，"若A则本研判作废"
                    5. judgeReasoning：裁决推理(100字内)——谁的证据更具体、更有数据支撑

                    %s
                    """.formatted(buildDataContext(symbol, newsContext), bullArgument, bearArgument,
                    JUDGE_CONVERTER.getFormat());
            String response = quantLlm.call(prompt);
            if (response == null || response.isBlank()) {
                log.warn("[Deep] Judge 空响应 symbol={}", symbol);
                return null;
            }
            DeepAnalysisResponse parsed = JUDGE_CONVERTER.convert(JsonUtils.extractJson(response));
            if (parsed == null) {
                return null;
            }
            return toEntity(symbol, closeTime, snapshotId, triggerSource,
                    newsContext, bullArgument, bearArgument, parsed);
        } catch (Exception e) {
            log.warn("[Deep] Judge 失败 symbol={} msg={}", symbol, e.getMessage());
            return null;
        }
    }

    public Long persist(QuantDeepAnalysis analysis) {
        mapper.insert(analysis);
        return analysis.getId();
    }

    /** 数据上下文：vol 三腿 + 脆弱度 + 信号面板 + 微结构，全部来自共享组装（60s 缓存，与快照同源）。 */
    private String buildDataContext(String symbol, String newsContext) {
        MarketAssembly a = marketDataService.assemble(symbol);
        if (!a.available()) {
            return "【市场数据】暂不可用\n【新闻上下文】" + newsContext;
        }
        FeatureSnapshot s = a.snapshot();
        String micro = ("futuresBidAsk=%.3f tradeDelta=%.3f largeBias=%.3f oiChange=%.3f fundingDev=%.3f "
                + "lsrExtreme=%.3f liquidationPressure=%.3f(vol=%.0fUSDT) topTraderBias=%.3f takerPressure=%.3f "
                + "fearGreed=%d(%s)").formatted(
                s.bidAskImbalance(), s.tradeDelta(), s.largeTradeBias(), s.oiChangeRate(), s.fundingDeviation(),
                s.lsrExtreme(), s.liquidationPressure(), s.liquidationVolumeUsdt(),
                s.topTraderBias(), s.takerBuySellPressure(), s.fearGreedIndex(), s.fearGreedLabel());
        String iv = s.toIvSummary();
        return """
                【标的】%s 现价=%s
                【脆弱度】%d/100 (%s) 方向=%s ——%s
                【信号面板】%s
                【微结构快照】%s
                【期权IV】%s
                【新闻上下文】
                %s""".formatted(
                s.symbol(), s.lastPrice(),
                a.fragility().score(), a.fragility().level(), a.fragility().direction(), a.fragility().headline(),
                JSON.toJSONString(a.signalPanel()),
                micro, iv, newsContext);
    }

    private QuantDeepAnalysis toEntity(String symbol, long closeTime, Long snapshotId, String triggerSource,
                                       String newsContext, String bull, String bear, DeepAnalysisResponse r) {
        // 情景分布归一化到 100（LLM 偶尔差 1-3）
        int bullPct = r.bullPct() != null ? Math.max(0, r.bullPct()) : 33;
        int rangePct = r.rangePct() != null ? Math.max(0, r.rangePct()) : 34;
        int bearPct = r.bearPct() != null ? Math.max(0, r.bearPct()) : 33;
        int sum = bullPct + rangePct + bearPct;
        if (sum > 0 && sum != 100) {
            rangePct += 100 - sum;
        }
        JSONObject scenarios = new JSONObject();
        scenarios.put("bullPct", bullPct);
        scenarios.put("rangePct", rangePct);
        scenarios.put("bearPct", bearPct);

        QuantDeepAnalysis entity = new QuantDeepAnalysis();
        entity.setSymbol(symbol);
        entity.setCloseTime(closeTime);
        entity.setTriggerSource(triggerSource);
        entity.setSnapshotId(snapshotId);
        entity.setNarrative(r.narrative());
        entity.setScenariosJson(scenarios.toJSONString());
        entity.setNoDirection(Boolean.TRUE.equals(r.noDirection()));
        entity.setInvalidation(r.invalidation());
        entity.setBullArgument(bull);
        entity.setBearArgument(bear);
        entity.setJudgeReasoning(r.judgeReasoning());
        entity.setNewsContext(newsContext);
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

}
