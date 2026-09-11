package com.mawai.wiibagent.trader;

import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.UserLlmBinding;
import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibagent.llm.ApiKeyCrypto;
import com.mawai.wiibagent.llm.ByokModelBuilder;
import com.mawai.wiibagent.llm.LlmEndpointService;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** BYOK 建模：构造期不许抛（纯本地构造，不发网络请求）；端点换了要换模型，没端点要响亮失败。 */
class TraderModelFactoryTest {

    private final ApiKeyCrypto crypto = new ApiKeyCrypto(Base64.getEncoder().encodeToString(new byte[32]));
    private final LlmEndpointService endpointService = mock(LlmEndpointService.class);

    private TraderModelFactory factory() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ObservationRegistry> op = mock(ObjectProvider.class);
        when(op.getIfUnique(any())).thenReturn(ObservationRegistry.NOOP);
        return new TraderModelFactory(endpointService, new ByokModelBuilder(crypto, mock(ToolCallingManager.class), op));
    }

    private static AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setId(1L);
        t.setUserId(9L);
        return t;
    }

    private UserLlmEndpoint endpoint(long id, String protocol, String model) {
        UserLlmEndpoint e = new UserLlmEndpoint();
        e.setId(id);
        e.setUserId(9L);
        e.setApiProtocol(protocol);
        e.setBaseUrl("https://api.deepseek.com");
        e.setModel(model);
        e.setApiKeyEnc(crypto.encrypt("sk-fake-key"));
        return e;
    }

    /**
     * openai 协议建模：OpenAiChatModel.builder() 只给 sync client 时会拿 options 自建 async client，
     * 而 options 里没有 key → SDK 抛 "At least one credential source must be specified"。
     */
    @Test
    void openAiProtocolModelBuildsWithoutCredentialError() {
        when(endpointService.resolve(eq(9L), eq(UserLlmBinding.TRADER))).thenReturn(endpoint(1, "openai", "deepseek-chat"));

        ChatModel model = factory().modelFor(trader());

        assertThat(model).isNotNull();
    }

    @Test
    void responsesProtocolModelBuilds() {
        when(endpointService.resolve(eq(9L), eq(UserLlmBinding.TRADER))).thenReturn(endpoint(1, "responses", "grok-4"));

        assertThat(factory().modelFor(trader())).isNotNull();
    }

    /** 同一端点复用缓存；用户换绑到另一条端点（指纹变）下次唤醒拿到新模型 */
    @Test
    void endpointChangeRebuildsModel() {
        TraderModelFactory f = factory();
        UserLlmEndpoint a = endpoint(1, "openai", "deepseek-chat");
        when(endpointService.resolve(eq(9L), eq(UserLlmBinding.TRADER))).thenReturn(a);
        ChatModel first = f.modelFor(trader());
        assertThat(f.modelFor(trader())).isSameAs(first);

        when(endpointService.resolve(eq(9L), eq(UserLlmBinding.TRADER))).thenReturn(endpoint(2, "openai", "deepseek-reasoner"));

        assertThat(f.modelFor(trader())).isNotSameAs(first);
    }

    /** 用户把端点删光：抛异常让 runner 的失败处理接管，不能悄悄返回 null 让下游 NPE */
    @Test
    void noEndpointThrows() {
        when(endpointService.resolve(eq(9L), eq(UserLlmBinding.TRADER))).thenReturn(null);

        assertThatThrownBy(() -> factory().modelFor(trader()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模型端点");
    }
}
