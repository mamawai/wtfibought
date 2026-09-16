package com.mawai.wiibagent.analysis;

import com.mawai.wiibcommon.entity.QuantDeepAnalysis;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.util.JsonUtils;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibquant.market.domain.FeatureSnapshot;
import com.mawai.wiibquant.market.service.MarketAssembly;
import com.mawai.wiibquant.market.service.MarketDataService;
import com.mawai.wiibquant.market.service.NewsCache;
import com.mawai.wiibquant.market.service.NewsFlashLocalizer;
import com.mawai.wiibquant.market.service.NewsFlashLocalizer.LocalizedFlash;
import com.mawai.wiibagent.mapper.QuantDeepAnalysisMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 深研判服务（P2b）：新闻拼接 → Bull∥Bear 对抗辩论 → Judge 裁决 → 落库。
 * 产物是研判叙事（方向倾向/情景分布/失效条件，证据实在均衡才标无方向态），与交易解耦；
 * LLM 任一步失败只缺席本次研判。
 * 数据上下文只用实时测量（快照/vol预测/脆弱度已随预测管线下线，2026-08）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeepAnalysisService {

    private static final BeanOutputConverter<DeepAnalysisResponse> JUDGE_CONVERTER =
            new BeanOutputConverter<>(DeepAnalysisResponse.class);

    private final MarketDataService marketDataService;
    private final NewsCache newsCache;
    private final NewsFlashLocalizer localizer;
    private final QuantDeepAnalysisMapper mapper;
    private final PromptCatalog prompts;

    /**
     * 新闻上下文：缓存的重要快讯拼成文本喂辩论（不再 LLM 浓缩）；无则给一句"没有新闻上下文"。
     * <p>源是 BlockBeats 中文快讯，按语言取一份：英文取打标时同批产出的译文，缺译文回落中文原文。
     */
    public String buildNewsContext(AgentLang lang) {
        List<LocalizedFlash> flashes = localizer.localize(newsCache.getFlashes(), lang);
        if (flashes.isEmpty()) {
            return prompts.get(lang, "chat.deepAnalysis.noNews");
        }
        StringBuilder sb = new StringBuilder();
        for (LocalizedFlash f : flashes) {
            sb.append("· ").append(f.title());
            String body = f.plain();
            if (!body.isBlank()) {
                // 标题与正文之间的分隔符也跟语言走：英文行里插一个全角冒号就是英文提示词里混中文
                sb.append(lang == AgentLang.ZH ? "：" : ": ").append(body);
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 模型由调用方传入而不是自己去拿：BYOK 后每个用户的模型不同，
     * 服务自己去 runtimeManager 取就不知道"当前是谁在用"了。
     */
    private String call(ChatModel model, String prompt) {
        return ChatClient.builder(model).build().prompt().user(prompt).call().content();
    }

    /** Bull 辩手：严格做多立场；失败给占位论据不阻断。 */
    public String bullArgue(ChatModel model, String symbol, String newsContext, AgentLang lang) {
        return argue(model, symbol, newsContext, true, lang);
    }

    /** Bear 辩手：严格做空/观望立场；失败给占位论据不阻断。 */
    public String bearArgue(ChatModel model, String symbol, String newsContext, AgentLang lang) {
        return argue(model, symbol, newsContext, false, lang);
    }

    private String argue(ChatModel model, String symbol, String newsContext, boolean bull, AgentLang lang) {
        String side = bull ? "Bull" : "Bear";
        try {
            String stance = prompts.get(lang,
                    bull ? "chat.deepAnalysis.bullStance" : "chat.deepAnalysis.bearStance");
            String prompt = prompts.get(lang, "chat.deepAnalysis.arguePrompt", Map.of(
                    "stance", stance, "data", buildDataContext(symbol, newsContext, lang)));
            String argument = call(model, prompt);
            return argument != null && !argument.isBlank() ? argument : argueFailed(lang, side);
        } catch (Exception e) {
            log.warn("[Deep] {}辩手失败 symbol={} msg={}", side, symbol, e.getMessage());
            return argueFailed(lang, side);
        }
    }

    private String argueFailed(AgentLang lang, String side) {
        return prompts.get(lang, "chat.deepAnalysis.argueFailed", Map.of("side", side));
    }

    /** Judge 裁决：综合数据+双方论据产研判；失败返回 null（本次研判缺席）。 */
    public QuantDeepAnalysis judge(ChatModel model, String symbol, long closeTime, String triggerSource,
                                   String newsContext, String bullArgument, String bearArgument,
                                   AgentLang lang) {
        try {
            String prompt = prompts.get(lang, "chat.deepAnalysis.judgePrompt", Map.of(
                    "data", buildDataContext(symbol, newsContext, lang),
                    "bull", bullArgument,
                    "bear", bearArgument,
                    "format", JUDGE_CONVERTER.getFormat()));
            String response = call(model, prompt);
            if (response == null || response.isBlank()) {
                log.warn("[Deep] Judge 空响应 symbol={}", symbol);
                return null;
            }
            DeepAnalysisResponse parsed = JUDGE_CONVERTER.convert(JsonUtils.extractJson(response));
            return toEntity(symbol, closeTime, triggerSource, newsContext, bullArgument, bearArgument, parsed);
        } catch (Exception e) {
            log.warn("[Deep] Judge 失败 symbol={} msg={}", symbol, e.getMessage());
            return null;
        }
    }

    public Long persist(QuantDeepAnalysis analysis) {
        mapper.insert(analysis);
        return analysis.getId();
    }

    /** 数据上下文：实时微结构 + 期权IV + 新闻；重对象来自共享组装（60s 缓存）。 */
    private String buildDataContext(String symbol, String newsContext, AgentLang lang) {
        MarketAssembly a = marketDataService.assemble(symbol);
        if (!a.available()) {
            return prompts.get(lang, "chat.deepAnalysis.dataUnavailable", Map.of("news", newsContext));
        }
        FeatureSnapshot s = a.snapshot();
        String micro = ("futuresBidAsk=%.3f tradeDelta=%.3f largeBias=%.3f oiChange=%.3f fundingDev=%.3f "
                + "lsrExtreme=%.3f liquidationPressure=%.3f(vol=%.0fUSDT) topTraderBias=%.3f takerPressure=%.3f "
                + "fearGreed=%d(%s)").formatted(
                s.bidAskImbalance(), s.tradeDelta(), s.largeTradeBias(), s.oiChangeRate(), s.fundingDeviation(),
                s.lsrExtreme(), s.liquidationPressure(), s.liquidationVolumeUsdt(),
                s.topTraderBias(), s.takerBuySellPressure(), s.fearGreedIndex(), s.fearGreedLabel());
        String iv = s.toIvSummary(prompts.get(lang, "chat.deepAnalysis.noIv"));
        return prompts.get(lang, "chat.deepAnalysis.dataContext", Map.of(
                "symbol", s.symbol(), "price", s.lastPrice(),
                "micro", micro, "iv", iv, "news", newsContext));
    }

    private QuantDeepAnalysis toEntity(String symbol, long closeTime, String triggerSource,
                                       String newsContext, String bull, String bear, DeepAnalysisResponse r) {
        // 情景分布归一化到 100（LLM 偶尔差 1-3）
        ObjectNode scenarios = getScenarios(r);

        QuantDeepAnalysis entity = new QuantDeepAnalysis();
        entity.setSymbol(symbol);
        entity.setCloseTime(closeTime);
        entity.setTriggerSource(triggerSource);
        entity.setNarrative(r.narrative());
        entity.setScenariosJson(MAPPER.writeValueAsString(scenarios));
        entity.setNoDirection(Boolean.TRUE.equals(r.noDirection()));
        entity.setInvalidation(r.invalidation());
        entity.setBullArgument(bull);
        entity.setBearArgument(bear);
        entity.setJudgeReasoning(r.judgeReasoning());
        entity.setNewsContext(newsContext);
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    private static @NonNull ObjectNode getScenarios(DeepAnalysisResponse r) {
        int bullPct = r.bullPct() != null ? Math.max(0, r.bullPct()) : 33;
        int rangePct = r.rangePct() != null ? Math.max(0, r.rangePct()) : 34;
        int bearPct = r.bearPct() != null ? Math.max(0, r.bearPct()) : 33;
        int sum = bullPct + rangePct + bearPct;
        if (sum > 0 && sum != 100) {
            rangePct += 100 - sum;
        }
        ObjectNode scenarios = MAPPER.createObjectNode();
        scenarios.put("bullPct", bullPct);
        scenarios.put("rangePct", rangePct);
        scenarios.put("bearPct", bearPct);
        return scenarios;
    }

}
