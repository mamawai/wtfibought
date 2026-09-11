package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibagent.llm.ApiKeyCrypto;
import com.mawai.wiibagent.llm.ByokModelBuilder;
import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibagent.llm.ResponsesChatModel;
import com.mawai.wiibagent.llm.SseChatModel;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BYOK 建模：构造期不发网络请求；同端点对必须复用，改端点必须换新的 */
class ChatModelFactoryTest {

    /** spy 而不是裸实例：建一次模解一次密，用调用次数数出"到底真建了几次"，见 同配置命中缓存 */
    private final ApiKeyCrypto crypto =
            spy(new ApiKeyCrypto(Base64.getEncoder().encodeToString(new byte[32])));

    /**
     * 密文只加密一次全程复用。ApiKeyCrypto 是 AES-GCM 随机 IV，每次现加密的话两份配置的指纹
     * 本来就不同——下面"改配置拿到新实例""指纹覆盖全部要素"会退化成恒真：
     * 把 getModel() 从 fingerprint() 里整个删掉它们照样绿。
     */
    private final String encOnce = crypto.encrypt("sk-test");

    private ChatModelFactory factory() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ObservationRegistry> op = mock(ObjectProvider.class);
        when(op.getIfUnique(any())).thenReturn(ObservationRegistry.NOOP);
        return new ChatModelFactory(new ByokModelBuilder(crypto, mock(ToolCallingManager.class), op));
    }

    private UserLlmEndpoint endpoint(String model, String effort) {
        UserLlmEndpoint e = ChatTestEndpoints.endpoint(model, encOnce);
        e.setReasoningEffort(effort);
        return e;
    }

    /** 主 + 轻两条端点（同 key），lightModel 为 null 表示不单独绑轻模型 */
    private ChatEndpoints config(String model, String lightModel) {
        return new ChatEndpoints(1L, endpoint(model, null), lightModel == null ? null : endpoint(lightModel, null));
    }

    /** 同一份配置反复取必须命中缓存：既要是同一对实例，也不许背地里重建一遍再丢掉 */
    @Test
    void 同配置命中缓存() {
        ChatModelFactory f = factory();
        ChatEndpoints c = config("gpt-5", "gpt-5-mini");

        ChatModelFactory.Models first = f.modelsFor(c);

        assertThat(f.modelsFor(c).deep()).isSameAs(first.deep());
        assertThat(f.modelsFor(c).light()).isSameAs(first.light());
        // 光断实例相同抓不住漏建：把"先查缓存"那步删掉，后两次照样重新建一遍，
        // 再被 putIfAbsent 换回旧实例——断言全绿，而每轮对话都在白建 SDK 客户端。
        // 建一次模解一次密（主/轻各一次），用解密次数数真实建了几次
        verify(crypto, times(2)).decrypt(anyString());
    }

    /** 改了模型名必须拿到新实例——指纹变了旧的就不该再用 */
    @Test
    void 改配置后拿到新实例() {
        ChatModelFactory f = factory();

        ChatModelFactory.Models first = f.modelsFor(config("gpt-5", "gpt-5-mini"));
        ChatModelFactory.Models second = f.modelsFor(config("gpt-5.1", "gpt-5-mini"));

        assertThat(second.deep()).isNotSameAs(first.deep());
    }

    /**
     * responses 协议要走自研的 {@link ResponsesChatModel}（/v1/responses），不能悄悄按
     * openai 发 /v1/chat/completions；档位也要一起进去。
     * 生产上 grok/xAI/CPA 用户走的正是这条分支，而它和 openai 那条各有各的注入点，分开钉。
     */
    @Test
    void responses协议建出自研模型并带上思考档位() {
        UserLlmEndpoint e = endpoint("grok-4.5", "high");
        e.setApiProtocol("responses");

        ChatModel deep = factory().modelsFor(new ChatEndpoints(1L, e, null)).deep();

        assertThat(deep).isInstanceOf(ResponsesChatModel.class);
        assertThat(effortOf(deep)).isEqualTo("high");
    }

    /** 档位是每条端点自己的属性：主模型 high、轻模型没配 → 各走各的，不互相串 */
    @Test
    void 思考档位按端点各自生效() {
        ChatEndpoints c = new ChatEndpoints(1L, endpoint("gpt-5", "high"), endpoint("gpt-5-mini", null));

        ChatModelFactory.Models models = factory().modelsFor(c);

        assertThat(effortOf(models.deep())).isEqualTo("high");
        assertThat(effortOf(models.light())).isNull();
    }

    /**
     * 读出模型上真实生效的档位。openai 那侧走 options；自研协议那侧只能反射——
     * {@code SseChatModel.getOptions()} 故意不带这个字段（它是请求体里的思考档位，
     * 不是 ChatOptions 的东西），而 agent/llm 是只读地盘，不能为了测试给它加 getter。
     * 字段改名的话这里当场 NoSuchFieldException，不会静默变绿。
     */
    private static String effortOf(ChatModel model) {
        if (model instanceof OpenAiChatModel openAi) {
            return openAi.getOptions().getReasoningEffort();
        }
        try {
            Field field = SseChatModel.class.getDeclaredField("reasoningEffort");
            field.setAccessible(true);
            return (String) field.get(model);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("读不到 SseChatModel.reasoningEffort", e);
        }
    }

    /** 轻模型不绑时直接复用深模型实例，不该白建第二个 */
    @Test
    void 轻模型不绑时复用主模型实例() {
        ChatModelFactory.Models models = factory().modelsFor(config("gpt-5", null));

        assertThat(models.light()).isSameAs(models.deep());
    }

    /**
     * 指纹漏掉任何一个建模要素，都会出现"改了配置还用旧模型"。
     * 第一条正向断言（同输入同输出）不能省——没有它，"密文只算一次"这个前提本身没被验证，
     * 后面两条不等断言可能只是因为密文每次都不一样才成立。
     */
    @Test
    void 指纹覆盖全部建模要素() {
        String base = ChatModelFactory.fingerprint(config("gpt-5", "gpt-5-mini"));

        assertThat(ChatModelFactory.fingerprint(config("gpt-5", "gpt-5-mini"))).isEqualTo(base);
        assertThat(ChatModelFactory.fingerprint(config("gpt-5.1", "gpt-5-mini"))).isNotEqualTo(base);
        assertThat(ChatModelFactory.fingerprint(config("gpt-5", "other"))).isNotEqualTo(base);
        // 轻模型"没绑"与"绑了同一条"必须是不同指纹之外的事——没绑给固定占位，不会与真端点撞
        assertThat(ChatModelFactory.fingerprint(config("gpt-5", null))).isNotEqualTo(base);
        // 档位也是建模要素：漏算它的话用户从 low 调到 high，拿到的还是那个 low 的旧模型
        ChatEndpoints highEffort = new ChatEndpoints(1L, endpoint("gpt-5", "high"), endpoint("gpt-5-mini", null));
        assertThat(ChatModelFactory.fingerprint(highEffort)).isNotEqualTo(base);
        // 搜索开关同理：勾了 web_search 不换指纹的话，模型与叶子（summarizer 提示词按它拼）都还是旧的
        ChatEndpoints searchOn = config("gpt-5", "gpt-5-mini");
        searchOn.deep().setWebSearch(true);
        assertThat(ChatModelFactory.fingerprint(searchOn)).isNotEqualTo(base);
    }

    /**
     * userId 必须进指纹。叶子（{@link ChatAgentFactory}）与模型共用这个指纹当缓存键，而叶子里有
     * 按用户烤死的工具——两个人的配置若算出同一个指纹，就会共用一份叶子，
     * 一个人的持仓/决策会端到另一个人眼前。
     * <p>
     * 光靠"api_key_enc 是随机 IV 的密文、两人不可能撞"是不够的：那是加密实现的性质，
     * 不是隔离的依据；这里刻意让两份配置<b>连密文都一样</b>，只有 userId 不同。
     */
    @Test
    void 指纹区分用户() {
        ChatEndpoints mine = config("gpt-5", "gpt-5-mini");
        ChatEndpoints others = new ChatEndpoints(2L, mine.deep(), mine.light());

        assertThat(ChatModelFactory.fingerprint(others))
                .isNotEqualTo(ChatModelFactory.fingerprint(mine));
    }

    /**
     * 分隔符不能省，而且不能挑用户打得出来的字符：两份配置拼成同一个串就会共用一个 ChatModel。
     * 第二组用带空格的模型名——model 是前端自由输入的，拿空格当分隔符照样撞。
     */
    @Test
    void 相邻字段拼接不会串味() {
        assertThat(ChatModelFactory.fingerprint(config("ab", "c")))
                .isNotEqualTo(ChatModelFactory.fingerprint(config("a", "bc")));
        assertThat(ChatModelFactory.fingerprint(config("gpt-5 x", "y")))
                .isNotEqualTo(ChatModelFactory.fingerprint(config("gpt-5", "x y")));
    }

    /**
     * 上限真的会淘汰。不是在测 JDK：putIfAbsent 走的是 HashMap.putVal，
     * 它触不触发 removeEldestEntry 得实证——不触发的话这个上限就是死的、缓存无界增长。
     */
    @Test
    void 超过上限后最久未用的被淘汰() {
        ChatModelFactory f = factory();
        ChatEndpoints oldest = config("m-0", null);
        ChatModelFactory.Models first = f.modelsFor(oldest);

        for (int i = 1; i <= ChatModelFactory.MAX_ENTRIES; i++) {
            f.modelsFor(config("m-" + i, null));
        }

        assertThat(f.modelsFor(oldest).deep()).isNotSameAs(first.deep());
    }
}
