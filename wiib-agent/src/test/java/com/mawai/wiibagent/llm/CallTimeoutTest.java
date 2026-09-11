package com.mawai.wiibagent.llm;

import com.mawai.wiibcommon.constant.AiProtocols;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 单次调用整体超时按协议落点：openai 协议落 {@code OpenAiChatOptions.timeout}
 * （Spring AI 2.0.1 起逐请求传给 SDK，缺省 60s 会盖掉 client 上设的 10 分钟），自研协议经 toolContext 捎。
 */
class CallTimeoutTest {

    private static final Duration SHORT = Duration.ofSeconds(20);

    @Test
    void openai协议落timeout字段且类型不变() {
        OpenAiChatOptions base = OpenAiChatOptions.builder().model("m").reasoningEffort("high").build();

        ChatOptions withTimeout = SseChatModel.withCallTimeout(base, SHORT);

        assertThat(withTimeout).isInstanceOf(OpenAiChatOptions.class);
        assertThat(((OpenAiChatOptions) withTimeout).getTimeout()).isEqualTo(SHORT);
        assertThat(((OpenAiChatOptions) withTimeout).getReasoningEffort()).isEqualTo("high");   // 其余字段原样带过去
        assertThat(base.getTimeout()).isNotEqualTo(SHORT);                                       // 入参不动
    }

    @Test
    void 自研协议经toolContext捎超时且不覆盖已有上下文() {
        ToolCallingChatOptions base = ToolCallingChatOptions.builder().toolContext(Map.of("k", "v")).build();

        ChatOptions withTimeout = SseChatModel.withCallTimeout(base, SHORT);

        assertThat(((ToolCallingChatOptions) withTimeout).getToolContext())
                .containsEntry(SseChatModel.TIMEOUT_KEY, SHORT)
                .containsEntry("k", "v");
    }

    /** 不显式设的话 OpenAiChatOptions 自带 60s，2.0.1 起会逐请求盖掉 client 上的 10 分钟，思考模型长回答必超时 */
    @Test
    @SuppressWarnings("unchecked")
    void openai协议建模的默认超时与自研协议同值() {
        ObjectProvider<ObservationRegistry> registry = mock(ObjectProvider.class);
        when(registry.getIfUnique(any())).thenReturn(ObservationRegistry.NOOP);
        ByokModelBuilder builder = new ByokModelBuilder(
                new ApiKeyCrypto(Base64.getEncoder().encodeToString(new byte[32])),
                mock(ToolCallingManager.class), registry);

        ChatModel model = builder.build(AiProtocols.OPENAI, "http://127.0.0.1:1/v1", "sk-test", "m", null, false);

        assertThat(((OpenAiChatOptions) model.getOptions()).getTimeout()).isEqualTo(SseChatModel.CALL_TIMEOUT);
    }
}
