package com.mawai.wiibagent.llm;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.PromptI18nAssertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BYOK 之后最高频的用户故障就是 key 相关，错误文案的质量直接决定用户能不能自己解决。
 * 而 SDK 原始异常可能是几百字符、带 URL 和请求片段的东西。
 * <p>
 * 文案按语言取（{@code llm.error.*}）；归类判据只认上游英文原文，与语言无关。
 */
class LlmErrorMessagesTest {

    private static final PromptCatalog PROMPTS = new PromptCatalog();

    /** 中文侧断言原样保留：这批只是把文案外置，成文一个字没变 */
    private static String zh(Throwable t) {
        return LlmErrorMessages.classify(t, PROMPTS, AgentLang.ZH);
    }

    @Test
    void 认证失败给出可操作的提示() {
        assertThat(zh(new RuntimeException("HTTP 401 Unauthorized: invalid api key")))
                .contains("API key");
    }

    @Test
    void 限流与额度用尽单独归类() {
        assertThat(zh(new RuntimeException("429 Too Many Requests")))
                .contains("额度");
    }

    /** 断"重新选择"而不是断"模型"：兜底文案里也有"模型"二字，删掉这条分支照样绿 */
    @Test
    void 模型不存在提示重选() {
        assertThat(zh(new RuntimeException("404 model 'gpt-9' not found")))
                .contains("重新选择");
    }

    /**
     * message 里不能带 timeout 字样，否则同时命中 instanceof 和 contains 两条判断，
     * 删掉 instanceof 那半边测试照样绿，这条分支就没被隔离到
     */
    @Test
    void 连不上时提示检查端点() {
        assertThat(zh(new java.net.ConnectException("")))
                .contains("Base URL");
    }

    /** 真正的原因常被 SDK/框架包在 cause 里，外层 message 非空但泛泛，不能只看最外层 */
    @Test
    void 从因果链里认出真正的原因() {
        Throwable wrapped = new java.util.concurrent.ExecutionException(
                "唤醒会话执行失败", new RuntimeException("HTTP 401 Unauthorized"));

        assertThat(zh(wrapped)).contains("API key");
    }

    /** 兜底文案里的类名取最深层 cause：最外层几乎总是包装异常，用户拿它查不到病因 */
    @Test
    void 兜底类名取最深层cause() {
        Throwable wrapped = new java.util.concurrent.CompletionException("wrapper",
                new IllegalStateException("db down"));

        assertThat(zh(wrapped))
                .contains("IllegalStateException").doesNotContain("CompletionException");
    }

    /**
     * 兜底分支要短且固定，避免几百字符的 SDK 异常灌进 SSE 和对话历史；
     * 而且<b>不许替用户判病因</b>——调用方的 catch 也罩着落历史、写记忆、上下文落库，
     * 数据库挂了同样走这条路，兜底若说"请检查端点与模型配置"，用户会去乱改一把没问题的 key
     */
    @Test
    void 未知错误既不回显原文也不替用户判病因() {
        String longMsg = "x".repeat(500);

        String msg = zh(new IllegalStateException(longMsg));

        assertThat(msg).doesNotContain("xxxx").doesNotContain("配置");
    }

    /**
     * 任何分支都不许把 key 透出去。正则永远追不全 key 的形态（URL 里的 ?api_key=、
     * 自定义 header、非 sk- 前缀的自建 key……），所以兜底根本不回显原文——
     * 这段文本不只给用户看，专家失败时还会拼进 AssistantMessage 喂回模型并随会话上下文持久化
     */
    @Test
    void 任何情况下不回显key() {
        assertThat(zh(new RuntimeException(
                "request failed, Authorization: Bearer sk-secret-abcdef123456")))
                .doesNotContain("sk-secret-abcdef123456");
        // 正则追不到的形态也必须安全
        assertThat(zh(new RuntimeException(
                "GET https://gw.example.com/v1/chat?api_key=Zm9vYmFyMTIzNDU2 failed")))
                .doesNotContain("Zm9vYmFyMTIzNDU2");
    }

    /** 英文用户不许收到一个中文字：五条归类各扫一遍 */
    @Test
    void 英文侧全部文案无中文() {
        for (Throwable t : new Throwable[]{
                new RuntimeException("HTTP 401 Unauthorized"),
                new RuntimeException("429 Too Many Requests"),
                new RuntimeException("404 model 'gpt-9' not found"),
                new java.net.ConnectException(""),
                new IllegalStateException("db down")}) {
            PromptI18nAssertions.assertNoCjk("英文 llm 错误文案",
                    LlmErrorMessages.classify(t, PROMPTS, AgentLang.EN));
        }
        PromptI18nAssertions.assertNoCjk("英文 llm 兜底文案",
                LlmErrorMessages.classify(null, PROMPTS, AgentLang.EN));
    }
}
