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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具描述按语言取词表：@Tool 上只有名字，描述是这一层按语言填的。
 * <p>三条底线：名字与 inputSchema 必须还是注解自动推导那份（schema 零手写）、
 * 词表里没有的工具一建就抛、方法照样能被调起来。
 */
class LocalizedToolCallbacksTest {

    /** 只用于反射扫描的假工具集 */
    static class DemoTools {

        @Tool(name = "echo_tool")
        public String echo(@ToolParam(description = "text to echo") String text) {
            return "echo:" + text;
        }

        @Tool(name = "boom_tool")
        public String boom() {
            throw new IllegalStateException("kline data exploded");
        }

        @Tool(name = "interrupted_tool")
        public String interrupted() {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("socket closed by interrupt");
        }
    }

    private final PromptCatalog prompts = mock(PromptCatalog.class);
    private final LocalizedToolCallbacks localized = new LocalizedToolCallbacks(prompts);

    {
        when(prompts.get(any(), anyString())).thenReturn("demo description");
        when(prompts.get(AgentLang.ZH, "tool.echo_tool")).thenReturn("回声工具（词表中文描述）");
        when(prompts.get(AgentLang.EN, "tool.echo_tool")).thenReturn("Echo tool (catalog English description)");
    }

    private Map<String, ToolCallback> callbacks(AgentLang lang) {
        return localized.of(lang, new DemoTools()).stream()
                .collect(Collectors.toMap(cb -> cb.getToolDefinition().name(), Function.identity()));
    }

    @Test
    void 描述取词表里当前语言那条() {
        assertThat(callbacks(AgentLang.EN).get("echo_tool").getToolDefinition().description())
                .isEqualTo("Echo tool (catalog English description)");
        assertThat(callbacks(AgentLang.ZH).get("echo_tool").getToolDefinition().description())
                .isEqualTo("回声工具（词表中文描述）");
    }

    /** 真词表里没有这几只假工具：不兜底，建的时候就抛，报错里带着缺的那条 key */
    @Test
    void 词表里没有这只工具_建的时候就抛() {
        LocalizedToolCallbacks production = new LocalizedToolCallbacks(new PromptCatalog());

        assertThatThrownBy(() -> production.of(AgentLang.EN, new DemoTools()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tool.");
    }

    @Test
    void 名字与inputSchema照旧由注解推导() {
        assertThat(callbacks(AgentLang.EN).keySet())
                .containsExactlyInAnyOrder("echo_tool", "boom_tool", "interrupted_tool");
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
        assertThat(all).hasSize(6);
    }

    /** 反射的方法顺序每次启动都不一样：出来的工具按名字排，请求、提示缓存、前缀指纹才稳 */
    @Test
    void 工具按名字排好() {
        assertThat(localized.of(AgentLang.ZH, new DemoTools())).extracting(cb -> cb.getToolDefinition().name())
                .containsExactly("boom_tool", "echo_tool", "interrupted_tool");
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
