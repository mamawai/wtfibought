package com.mawai.wiibagent.behavior;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.util.JsonUtils;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibagent.i18n.PromptCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 行为分析的准入层：缓存、失败负缓存、并发闸门都在这儿，真跑在 {@link BehaviorAnalysisWorkflow}。
 * <p>
 * <b>唯一入口是对话轨的 {@code analyze_my_behavior} 工具</b>（原来那个 REST 端点与平台
 * behavior 功能位已随之退休）。模型因此由调用方传进来——用的是这个用户 BYOK 的深模型，
 * 平台不再为行为分析建模型、也不再替他付这次调用。
 * <p>
 * 工具体必须经这一层而不是直接调 workflow：模型一轮里连点三次就是连烧三次，
 * 缓存和信号量正是拦这个的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BehaviorAnalysisService {

    private final BehaviorAnalysisWorkflow workflow;
    private final PromptCatalog prompts;

    /** 缓存按语言分格：报告正文是模型用某门语言写的，换了语言那份就不能再用 */
    private record CacheKey(long userId, AgentLang lang) {
    }

    /** 全局闸门：一次分析要打 sim 十个内部端点，10 并发就是 100 条在途 HTTP，再多没必要 */
    private final Semaphore behaviorSemaphore = new Semaphore(10);
    private final Cache<CacheKey, BehaviorAnalysisReport> reportCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();
    // 失败负缓存：失败不进 reportCache 的话，用户每问一次重试就全额烧一遍分析（10 个端点 + 一次
    // 大 prompt 的 LLM 调用）且永远烧不出缓存。失败也短存，把重试风暴钝化成每 2 分钟最多一次真跑；
    // TTL 刻意远短于成功缓存——给上游（模型/网络）故障恢复留窗口
    private final Cache<CacheKey, String> failCache = Caffeine.newBuilder()
            .expireAfterWrite(2, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();

    /**
     * @param model      这个用户 BYOK 的深模型（对话轨叶子上绑的那个）
     * @param onProgress 阶段文案，推给对话的 SSE 通道；可空
     */
    public Result<BehaviorAnalysisReport> analyze(long userId, AgentLang lang, ChatModel model,
                                                  Consumer<String> onProgress) {
        CacheKey key = new CacheKey(userId, lang);

        BehaviorAnalysisReport cached = reportCache.getIfPresent(key);
        if (cached != null) {
            return Result.ok(cached);
        }
        String recentFail = failCache.getIfPresent(key);
        if (recentFail != null) {
            return Result.fail(recentFail);
        }

        if (!behaviorSemaphore.tryAcquire()) {
            // 瞬时负载不是故障：不进负缓存，下一秒就可能有空位
            return Result.fail(prompts.get(lang, "behavior.error.busy"));
        }
        try {
            Result<BehaviorAnalysisReport> result = doAnalyze(key, model, onProgress);
            if (result.getCode() == 0 && result.getData() != null) {
                reportCache.put(key, result.getData());
            } else {
                failCache.put(key, result.getMsg());
            }
            return result;
        } finally {
            behaviorSemaphore.release();
        }
    }

    private Result<BehaviorAnalysisReport> doAnalyze(CacheKey key, ChatModel model,
                                                     Consumer<String> onProgress) {
        long userId = key.userId();
        AgentLang lang = key.lang();
        log.info("用户{}请求行为分析，语言{}", userId, lang.code());

        String text;
        try {
            text = workflow.run(model, userId, lang, onProgress);
        } catch (Exception e) {
            log.error("行为分析执行失败 userId={}", userId, e);
            return Result.fail(prompts.get(lang, "behavior.error.runFailed",
                    Map.of("msg", String.valueOf(e.getMessage()))));
        }
        log.info("用户{} 行为分析模型返回, responseLength={}", userId, text.length());

        BehaviorAnalysisReport report;
        try {
            report = JsonUtils.MAPPER.readValue(JsonUtils.extractJson(text), BehaviorAnalysisReport.class);
        } catch (Exception e) {
            log.error("行为分析报告解析失败 userId={}", userId, e);
            return Result.fail(prompts.get(lang, "behavior.error.parseFailed"));
        }

        if (!report.isValid()) {
            log.error("行为分析关键字段缺失 userId={}", userId);
            return Result.fail(prompts.get(lang, "behavior.error.incomplete"));
        }

        log.info("用户{} 行为分析完成", userId);
        return Result.ok(report);
    }
}
