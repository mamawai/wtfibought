package com.mawai.wiibagent.behavior;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 失败负缓存：LLM 分析失败不进成功缓存，用户每问一次重试就全额烧一遍分析
 * （7 个 sim 端点 + 一次大 prompt 的 LLM 调用），且永远烧不出缓存。失败结果短存后，
 * TTL 内重试快速返回，真跑频率被钝化成"每个负缓存周期最多一次"。
 * 信号量拒绝（人数已满）是瞬时负载，不进负缓存。
 * <p>
 * 现在这层比以前更要命：入口是对话轨的工具，重试的不是用户的手指而是模型——
 * 它一轮里连调三次是完全可能的。
 */
class BehaviorNegativeCacheTest {

    private BehaviorAnalysisWorkflow workflow;
    private BehaviorAnalysisService service;
    private final ChatModel model = mock(ChatModel.class);

    @BeforeEach
    void setUp() {
        workflow = mock(BehaviorAnalysisWorkflow.class);
        // 一进 workflow 就失败：最短路径触发 doAnalyze 失败
        when(workflow.run(any(), anyLong(), any(), any()))
                .thenThrow(new RuntimeException("模型不可用"));
        service = new BehaviorAnalysisService(workflow, new PromptCatalog());
    }

    @Test
    void 失败负缓存_TTL内重试不再烧分析() {
        var first = service.analyze(7L, AgentLang.ZH, model, null);
        assertThat(first.getCode()).isNotZero();

        var second = service.analyze(7L, AgentLang.ZH, model, null);
        assertThat(second.getCode()).isNotZero();
        assertThat(second.getMsg()).isNotBlank();

        // 第二次没有再跑分析——这是负缓存的全部意义
        verify(workflow, times(1)).run(any(), anyLong(), any(), any());
    }

    @Test
    void 负缓存按用户隔离_不互相污染() {
        service.analyze(7L, AgentLang.ZH, model, null);
        service.analyze(8L, AgentLang.ZH, model, null);
        // 各自烧各自的一次，7 的失败不挡 8
        verify(workflow, times(2)).run(any(), anyLong(), any(), any());
    }

    /** 缓存按语言分格：报告正文是模型用某门语言写的，中文那份不能拿去答英文用户 */
    @Test
    void 负缓存按语言分格() {
        service.analyze(7L, AgentLang.ZH, model, null);
        service.analyze(7L, AgentLang.EN, model, null);
        verify(workflow, times(2)).run(any(), anyLong(), any(), any());
    }

    /**
     * 失败回执跟着语言走：它会被工具原样交给模型，模型据此向用户解释。
     * <p>只钉外壳那句：{@code {{msg}}} 位置插的是上游异常原文（mock 里故意给中文），
     * 上游说什么语言不归我们翻——同 LlmErrorMessages 的口径。
     */
    @Test
    void 失败回执按语言取词() {
        assertThat(service.analyze(7L, AgentLang.ZH, model, null).getMsg())
                .startsWith("分析执行失败：");
        assertThat(service.analyze(8L, AgentLang.EN, model, null).getMsg())
                .startsWith("Analysis failed to run: ");
    }
}
