package com.mawai.wiibagent.llm;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;
import java.util.Map;

/**
 * 异常 → 用户看得懂、也能据此动手解决的一句话。
 * <p>
 * BYOK 之后最高频的故障就是 key 相关，而 SDK 原始异常常是几百字符、带 URL 和请求片段的东西，
 * 所以认得出的那四类（401/429/模型不存在/连不上）各给一句能照着做的话。
 * <p>
 * <b>认不出来的就不替用户判病因。</b> 调用方的 catch 罩的范围都比 LLM 大得多——
 * 落历史、写记忆、上下文落库全在里面，数据库挂了也走这条路。
 * 兜底要是说"请检查端点与模型配置"，用户会去乱改一把本来没问题的 key。
 * <p>
 * 硬约束：<b>任何分支都不许把 API key 带出去</b>。这段文本不只给用户看——
 * 专家失败时它会被拼进 AssistantMessage 喂回模型，并随会话上下文落库。
 * <p>
 * 文案在 {@link PromptCatalog} 的 {@code llm.error.*}，按当前用户的 {@link AgentLang} 取；
 * 归类判据（401/429/404 model/超时那几组关键字）只认上游英文原文，与语言无关。
 */
@Slf4j
public final class LlmErrorMessages {

    /** 因果链最多往下扒这么多层，防环 */
    private static final int MAX_CAUSE_DEPTH = 5;

    private LlmErrorMessages() {
    }

    /**
     * 401/403 形态：{@link #classify} 的第一分支单独暴露，唤醒回路据它"key 失效立即暂停"——
     * 按异常本身判，不按成文后的话判（各协议的 401 原文各不同：openai 路
     * {@code UnauthorizedException: 401: Invalid API key}，responses 路 {@code Responses API HTTP 401}）。
     */
    public static boolean unauthorized(Throwable t) {
        String lower = chain(t).toLowerCase(Locale.ROOT);
        return lower.contains("401") || lower.contains("403")
                || lower.contains("unauthorized") || lower.contains("invalid api key");
    }

    public static String classify(Throwable t, PromptCatalog prompts, AgentLang lang) {
        if (t == null) {
            return prompts.get(lang, "llm.error.fallback");
        }
        String lower = chain(t).toLowerCase(Locale.ROOT);
        if (unauthorized(t)) {
            return prompts.get(lang, "llm.error.unauthorized");
        }
        if (lower.contains("429") || lower.contains("quota") || lower.contains("rate limit")) {
            return prompts.get(lang, "llm.error.quota");
        }
        if (lower.contains("404") && lower.contains("model")) {
            return prompts.get(lang, "llm.error.modelNotFound");
        }
        if (hasCause(t, java.net.SocketTimeoutException.class)
                || hasCause(t, java.net.ConnectException.class)
                || hasCause(t, java.net.UnknownHostException.class)
                || lower.contains("timeout") || lower.contains("timed out")
                || lower.contains("connection refused") || lower.contains("unknownhost")) {
            return prompts.get(lang, "llm.error.unreachable");
        }
        // 类名取最深层 cause：最外层几乎总是 CompletionException 之类的包装（实测），拿它查不到病因
        Throwable root = root(t);
        // 归类不到的形态要能被发现，否则它永远只以兜底文案示人；但不重复打栈——
        // 调用方的 catch 都已经把整条异常记下来了。
        // 不写 [LLM]：这里也会收到 DB 之类的服务端故障，贴错标签会把真跑验收的观察点搅浑
        log.warn("[ErrorClassify] 归类不到 {}", root.getClass().getName());
        // 兜底不回显上游原文：中转网关的异常里经常带完整请求 URL（?api_key=…）、自定义 header，
        // key 的形态正则永远追不全。上面四个分支返的是固定文案，天然不含 key
        return prompts.get(lang, "llm.error.unknown",
                Map.of("type", root.getClass().getSimpleName()));
    }

    private static Throwable root(Throwable t) {
        Throwable cur = t;
        for (int i = 0; i < MAX_CAUSE_DEPTH && cur.getCause() != null; i++) {
            cur = cur.getCause();
        }
        return cur;
    }

    /**
     * 归类要看<b>整条因果链</b>：框架的包装异常（CompletionException、ExecutionException 之类）
     * message 都非空但泛泛，真正的 401/429 埋在 cause 里。
     * 只在"外层 message 为 null"时才往下扒是抓不到的——那种情况几乎不发生。
     */
    private static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        for (int i = 0; i < MAX_CAUSE_DEPTH && cur != null; i++, cur = cur.getCause()) {
            sb.append(cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage())
              .append(' ');
        }
        return sb.toString();
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        Throwable cur = t;
        for (int i = 0; i < MAX_CAUSE_DEPTH && cur != null; i++, cur = cur.getCause()) {
            if (type.isInstance(cur)) {
                return true;
            }
        }
        return false;
    }
}
