package com.mawai.wiibquant.agent.chat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由过滤器测试：段边界由框架的 *_FINISHED 聚合帧驱动（endSegment），
 * 复现线上问题场景——supervisor 路由数组泄漏、并行派发交错流。
 */
class RoutingFilterTest {

    private final List<String> emitted = new ArrayList<>();
    private final List<String> emittedKeys = new ArrayList<>();
    private final ChatWorkbenchController.RoutingFilter filter =
            new ChatWorkbenchController.RoutingFilter((key, text) -> {
                emittedKeys.add(key);
                emitted.add(text);
            });

    @Test
    void 路由数组整段丢弃() {
        filter.onChunk("m|sup", "[\"news");
        filter.onChunk("m|sup", "_agent\"]");
        filter.endSegment("m|sup");

        assertThat(emitted).isEmpty();
    }

    @Test
    void 连续两次路由派发各自丢弃() {
        // 线上泄漏场景：两轮派发若无分段边界会拼成 ["news_agent"]["news_agent"] 解析失败而泄漏
        filter.onChunk("m|sup", "[\"news_agent\"]");
        filter.endSegment("m|sup");
        filter.onChunk("m|sup", "[\"market_agent\",\"quant_agent\"]");
        filter.endSegment("m|sup");

        assertThat(emitted).isEmpty();
    }

    @Test
    void 普通文本增量直通() {
        filter.onChunk("m|news", "比特币");
        filter.onChunk("m|news", "突破65000");
        filter.endSegment("m|news");

        assertThat(String.join("", emitted)).isEqualTo("比特币突破65000");
        assertThat(emitted).hasSize(2); // 逐 chunk 直通，不是憋到段尾一次性吐
    }

    @Test
    void 以方括号开头的正常回答段尾补发() {
        filter.onChunk("m|sup", "[看多] 观点：");
        filter.onChunk("m|sup", "短期偏強");
        filter.endSegment("m|sup");

        assertThat(String.join("", emitted)).isEqualTo("[看多] 观点：短期偏強");
    }

    @Test
    void 首块空白不影响路由判定() {
        filter.onChunk("m|sup", "  ");
        filter.onChunk("m|sup", "[\"market_agent\"]");
        filter.endSegment("m|sup");

        assertThat(emitted).isEmpty();
    }

    @Test
    void 并行派发交错流互不污染() {
        // supervisor 输出 ["a","b"] 后 a/b 并行跑，chunk 交错到达：各 key 独立分段
        filter.onChunk("m|market", "资金费率");
        filter.onChunk("m|sup", "[\"news_agent\"]");
        filter.onChunk("m|market", "为正");
        filter.endSegment("m|sup");
        filter.endSegment("m|market");

        assertThat(String.join("", emitted)).isEqualTo("资金费率为正");
    }

    @Test
    void 外发文本带回所属key() {
        // 答案流/过程流分离靠 key 里的 agent 名：交错流各 chunk 必须带回自己的 key
        filter.onChunk("m|news_agent", "快讯");
        filter.onChunk("m|workbench_supervisor", "汇总");
        filter.endSegment("m|news_agent");
        filter.endSegment("m|workbench_supervisor");

        assertThat(emittedKeys).containsExactly("m|news_agent", "m|workbench_supervisor");
        assertThat(emitted).containsExactly("快讯", "汇总");
    }

    @Test
    void 流结束兜底把所有在途段收尾() {
        filter.onChunk("m|sup", "[\"news_agent\"]"); // 被扣住的路由段
        filter.onChunk("m|news", "快讯摘要");
        filter.endAll();

        assertThat(String.join("", emitted)).isEqualTo("快讯摘要");
    }
}
