package com.mawai.wiibagent.i18n;

import com.mawai.wiibcommon.enums.AgentLang;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具描述按语言换：@Tool 的 description 是编译期常量换不掉，这一层就是替它换。
 * <p>三条底线：名字与 inputSchema 必须还是注解自动推导那份（schema 零手写）、
 * 没搬进词表的工具保留原描述照常工作、方法照样能被调起来。
 * <p>词表三态（有/只中文/完全没有）用桩造：find 的回落语义本身归 LangBundleTest 钉。
 */
class LocalizedToolCallbacksTest {

    /** 只用于反射扫描的假工具集，覆盖"词表有/词表只有中文/词表完全没有"三种 */
    static class DemoTools {

        @Tool(name = "echo_tool", description = "Annotated echo description")
        public String echo(@ToolParam(description = "text to echo") String text) {
            return "echo:" + text;
        }

        @Tool(name = "zh_only_tool", description = "Annotated zh-only description")
        public String zhOnly() {
            return "zh-only";
        }

        @Tool(name = "untouched_tool", description = "Annotated untouched description")
        public String untouched() {
            return "untouched";
        }

        @Tool(name = "boom_tool", description = "Always throws")
        public String boom() {
            throw new IllegalStateException("kline data exploded");
        }

        @Tool(name = "interrupted_tool", description = "Throws with interrupt flag set")
        public String interrupted() {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("socket closed by interrupt");
        }
    }

    private final PromptCatalog prompts = mock(PromptCatalog.class);
    private final LocalizedToolCallbacks localized = new LocalizedToolCallbacks(prompts);

    {
        when(prompts.find(AgentLang.ZH, "tool.echo_tool")).thenReturn("回声工具（词表中文描述）");
        when(prompts.find(AgentLang.EN, "tool.echo_tool")).thenReturn("Echo tool (catalog English description)");
        // zh_only：find 对缺英文的 key 回落中文（回落行为见 LangBundleTest），这里桩出回落后的结果
        when(prompts.find(AgentLang.ZH, "tool.zh_only_tool")).thenReturn("只有中文词表有它");
        when(prompts.find(AgentLang.EN, "tool.zh_only_tool")).thenReturn("只有中文词表有它");
        // untouched：词表完全没有 → find 两门语言都 null（mock 默认），走注解回落
    }

    private Map<String, ToolCallback> callbacks(AgentLang lang) {
        return localized.of(lang, new DemoTools()).stream()
                .collect(Collectors.toMap(cb -> cb.getToolDefinition().name(), Function.identity()));
    }

    @Test
    void 词表里有就换成当前语言的描述() {
        assertThat(callbacks(AgentLang.EN).get("echo_tool").getToolDefinition().description())
                .isEqualTo("Echo tool (catalog English description)");
        assertThat(callbacks(AgentLang.ZH).get("echo_tool").getToolDefinition().description())
                .isEqualTo("回声工具（词表中文描述）");
    }

    @Test
    void 英文词表缺这条_回落中文而不是掉回注解() {
        assertThat(callbacks(AgentLang.EN).get("zh_only_tool").getToolDefinition().description())
                .isEqualTo("只有中文词表有它");
    }

    /** 唯一允许静默回落的地方：还没搬进词表的工具照常工作，后续批次逐个搬 */
    @Test
    void 词表完全没有这条_保留注解里的原描述() {
        assertThat(callbacks(AgentLang.EN).get("untouched_tool").getToolDefinition().description())
                .isEqualTo("Annotated untouched description");
    }

    @Test
    void 名字与inputSchema照旧由注解推导() {
        assertThat(callbacks(AgentLang.EN).keySet())
                .containsExactlyInAnyOrder("echo_tool", "zh_only_tool", "untouched_tool",
                        "boom_tool", "interrupted_tool");
        // 参数与其描述都还在自动推导的 schema 里——换描述不能把 schema 弄丢
        assertThat(callbacks(AgentLang.EN).get("echo_tool").getToolDefinition().inputSchema())
                .contains("text").contains("text to echo");
    }

    @Test
    void 换完描述工具照样调得起来() {
        String result = callbacks(AgentLang.EN).get("echo_tool").call("{\"text\":\"hi\"}");
        assertThat(result).contains("echo:hi");
    }

    /** 多个工具对象一把扫，建叶子时一行接上 */
    @Test
    void 一次可以扫多个工具对象() {
        List<ToolCallback> all = localized.of(AgentLang.ZH, new DemoTools(), new DemoTools());
        assertThat(all).hasSize(10);
    }

    /** ReactLoop 执行工具不接异常：抛出去整轮唤醒作废，所以失败必须变成模型看得懂的结果 */
    @Test
    void 工具抛异常_回给模型的是失败结果而不是异常() {
        String result = callbacks(AgentLang.EN).get("boom_tool").call("{}");
        assertThat(result).contains("\"available\":false").contains("boom_tool failed: kline data exploded");
    }

    /** 唤醒超时 cancel(true) 打断工具里的 HTTP 时，中断要原样往外抛，否则图会在作废的一轮里接着调模型 */
    @Test
    void 带中断标志的异常原样抛出() {
        try {
            assertThatThrownBy(() -> callbacks(AgentLang.EN).get("interrupted_tool").call("{}"))
                    .hasStackTraceContaining("socket closed by interrupt");
        } finally {
            assertThat(Thread.interrupted()).isTrue();
        }
    }
}
