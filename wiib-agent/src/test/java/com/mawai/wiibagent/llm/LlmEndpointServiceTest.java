package com.mawai.wiibagent.llm;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.entity.UserLlmBinding;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibagent.mapper.UserLlmBindingMapper;
import com.mawai.wiibagent.mapper.UserLlmEndpointMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.retry.NonTransientAiException;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 端点库的三条不变量：首条自动默认；删默认时最早的一条顶上、绑定连带删；
 * 对话轻模型绑到主模型同一条等于没绑。校验口径：协议/档位脏值抹平，档位不限白名单只挡列宽。
 * 勾了联网搜索的保存与测试连通性都要探一次搜索。
 */
class LlmEndpointServiceTest {

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserLlmEndpoint.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserLlmBinding.class);
    }

    private final UserLlmEndpointMapper endpointMapper = mock(UserLlmEndpointMapper.class);
    private final UserLlmBindingMapper bindingMapper = mock(UserLlmBindingMapper.class);
    private final ApiKeyCrypto crypto = mock(ApiKeyCrypto.class);
    private final ByokModelBuilder builder = mock(ByokModelBuilder.class);
    private final LlmEndpointService service =
            new LlmEndpointService(endpointMapper, bindingMapper, crypto, new BaseUrlGuard("", new MessageCatalog()), builder, new MessageCatalog());

    private static UserLlmEndpoint ep(long id, boolean dft) {
        UserLlmEndpoint e = new UserLlmEndpoint();
        e.setId(id);
        e.setUserId(1L);
        e.setName("e" + id);
        e.setApiProtocol("openai");
        e.setBaseUrl("https://8.8.8.8");
        e.setModel("m" + id);
        e.setApiKeyEnc("enc" + id);
        e.setIsDefault(dft);
        return e;
    }

    private static LlmEndpointService.SaveReq req(String protocol, String effort, String key) {
        return req(protocol, effort, key, null);
    }

    private static LlmEndpointService.SaveReq req(String protocol, String effort, String key, Boolean webSearch) {
        return req(protocol, effort, key, webSearch, null);
    }

    private static LlmEndpointService.SaveReq req(String protocol, String effort, String key, Boolean webSearch,
                                                  Integer contextWindow) {
        return new LlmEndpointService.SaveReq("主力", protocol, "https://8.8.8.8/", " deepseek-chat ", effort, key,
                webSearch, contextWindow);
    }

    private static final ChatResponse PONG = new ChatResponse(List.of(new Generation(new AssistantMessage("pong"))));
    private static final RuntimeException SEARCH_REJECTED =
            new NonTransientAiException("400: web_search tool is not supported by this model");

    /** 假模型：按顺序给出每次调用的结果（ChatResponse 照答、异常照抛），用完了重复最后一个 */
    private static ChatModel modelAnswering(Object... outcomes) {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        AtomicInteger calls = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            Object outcome = outcomes[Math.min(calls.getAndIncrement(), outcomes.length - 1)];
            if (outcome instanceof RuntimeException e) {
                throw e;
            }
            return outcome;
        });
        return model;
    }

    /** 搜索开关只对能声明服务端搜索的协议有意义：openai 协议勾了也归一 false；没勾的保存不发上游请求 */
    @Test
    void webSearch只在responses协议下入库() {
        when(endpointMapper.selectList(any())).thenReturn(List.of());
        when(crypto.encrypt(any())).thenReturn("enc");
        ChatModel model = modelAnswering(PONG);
        when(builder.build(any())).thenReturn(model);

        assertThat(service.create(1L, req("responses", "", "sk-x", true))).isNull();
        assertThat(service.create(1L, req("openai", "", "sk-x", true))).isNull();
        assertThat(service.create(1L, req("responses", "", "sk-x", null))).isNull();

        ArgumentCaptor<UserLlmEndpoint> cap = ArgumentCaptor.forClass(UserLlmEndpoint.class);
        verify(endpointMapper, times(3)).insert(cap.capture());
        assertThat(cap.getAllValues().get(0).getWebSearch()).isTrue();
        assertThat(cap.getAllValues().get(1).getWebSearch()).isFalse();
        assertThat(cap.getAllValues().get(2).getWebSearch()).isFalse();
        verify(builder, times(1)).build(any());   // 只有勾了搜索的那条探测过
    }

    /** 勾了联网搜索的保存先探测：上游不支持、端点连不上都不入库，各给各的说法 */
    @Test
    void 勾了联网搜索的保存要探测通过才入库() {
        when(endpointMapper.selectList(any())).thenReturn(List.of());
        when(crypto.encrypt(any())).thenReturn("enc");
        ChatModel noSearch = modelAnswering(PONG, SEARCH_REJECTED);
        ChatModel unreachable = modelAnswering(new RuntimeException("Connection refused"));
        when(builder.build(any())).thenReturn(noSearch, unreachable);

        assertThat(service.create(1L, req("responses", "", "sk-x", true))).contains("不支持联网搜索");
        assertThat(service.create(1L, req("responses", "", "sk-x", true))).contains("连不上");
        verify(endpointMapper, never()).insert(any(UserLlmEndpoint.class));
    }

    /** 测试连通性：没勾搜索只 ping 一次；勾了的 ping 通再探搜索 */
    @Test
    void 测试连通性先测连通再测搜索() {
        when(crypto.encrypt(any())).thenReturn("enc");
        ChatModel plain = modelAnswering(PONG);
        ChatModel noSearch = modelAnswering(PONG, SEARCH_REJECTED);
        ChatModel unreachable = modelAnswering(new RuntimeException("Connection refused"));
        when(builder.build(any())).thenReturn(plain, noSearch, unreachable);

        assertThat(service.testConnection(1L, null, req("responses", "", "sk-x", false))).isNull();
        verify(plain, times(1)).call(any(Prompt.class));
        assertThat(service.testConnection(1L, null, req("responses", "", "sk-x", true))).contains("不支持联网搜索");
        assertThat(service.testConnection(1L, null, req("responses", "", "sk-x", true))).contains("连通性测试失败");
    }

    @Test
    void 首条端点自动成默认且脏值抹平() {
        when(endpointMapper.selectList(any())).thenReturn(List.of());
        when(crypto.encrypt("sk-x")).thenReturn("enc");

        assertThat(service.create(1L, req("Responses ", " HIGH", "sk-x"))).isNull();

        ArgumentCaptor<UserLlmEndpoint> cap = ArgumentCaptor.forClass(UserLlmEndpoint.class);
        verify(endpointMapper).insert(cap.capture());
        UserLlmEndpoint row = cap.getValue();
        assertThat(row.getIsDefault()).isTrue();
        assertThat(row.getApiProtocol()).isEqualTo("responses");
        assertThat(row.getReasoningEffort()).isEqualTo("high");
        assertThat(row.getBaseUrl()).isEqualTo("https://8.8.8.8");
        assertThat(row.getModel()).isEqualTo("deepseek-chat");
        assertThat(row.getApiKeyEnc()).isEqualTo("enc");
    }

    @Test
    void 自定义档位原样存下不被白名单拦() {
        when(endpointMapper.selectList(any())).thenReturn(List.of());
        when(crypto.encrypt(any())).thenReturn("enc");

        assertThat(service.create(1L, req("openai", "XHigh", "sk-x"))).isNull();

        ArgumentCaptor<UserLlmEndpoint> cap = ArgumentCaptor.forClass(UserLlmEndpoint.class);
        verify(endpointMapper).insert(cap.capture());
        assertThat(cap.getValue().getReasoningEffort()).isEqualTo("xhigh");
    }

    @Test
    void 已有端点时新增不抢默认() {
        when(endpointMapper.selectList(any())).thenReturn(List.of(ep(1, true)));
        when(crypto.encrypt(any())).thenReturn("enc");

        assertThat(service.create(1L, req("openai", "", "sk-x"))).isNull();

        ArgumentCaptor<UserLlmEndpoint> cap = ArgumentCaptor.forClass(UserLlmEndpoint.class);
        verify(endpointMapper).insert(cap.capture());
        assertThat(cap.getValue().getIsDefault()).isFalse();
    }

    @Test
    void 校验挡住内网地址与超长档位与缺key() {
        when(endpointMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.create(1L, new LlmEndpointService.SaveReq("x", "openai", "http://127.0.0.1:8080", "m", "", "sk", null, null)))
                .contains("内网");
        assertThat(service.create(1L, req("openai", "seventeen-chars-x", "sk"))).contains("思考档位");   // 17 字符，超列宽
        assertThat(service.create(1L, req("openai", "", ""))).isEqualTo("apiKey不能为空");
        assertThat(service.create(1L, new LlmEndpointService.SaveReq(" ", "openai", "https://8.8.8.8", "m", "", "sk", null, null)))
                .contains("名称");
        verify(endpointMapper, never()).insert(any(UserLlmEndpoint.class));
    }

    /** 上下文窗口：不填用默认 256K，128K~1M 之外的挡住 */
    @Test
    void 上下文窗口不填用默认且挡住范围外的值() {
        when(endpointMapper.selectList(any())).thenReturn(List.of());
        when(crypto.encrypt(any())).thenReturn("enc");

        assertThat(service.create(1L, req("openai", "", "sk-x", null, 127_999))).contains("128K");
        assertThat(service.create(1L, req("openai", "", "sk-x", null, 1_000_001))).contains("1000K");
        assertThat(service.create(1L, req("openai", "", "sk-x", null, null))).isNull();
        assertThat(service.create(1L, req("openai", "", "sk-x", null, 1_000_000))).isNull();

        ArgumentCaptor<UserLlmEndpoint> cap = ArgumentCaptor.forClass(UserLlmEndpoint.class);
        verify(endpointMapper, times(2)).insert(cap.capture());
        assertThat(cap.getAllValues().get(0).getContextWindow()).isEqualTo(256_000);
        assertThat(cap.getAllValues().get(1).getContextWindow()).isEqualTo(1_000_000);
    }

    @Test
    void 删默认端点时最早的一条顶上并连带删绑定() {
        UserLlmEndpoint dft = ep(1, true);
        when(endpointMapper.selectById(1L)).thenReturn(dft);
        when(endpointMapper.selectList(any())).thenReturn(List.of(ep(2, false), ep(3, false)));   // 删完剩下的

        assertThat(service.delete(1L, 1L)).isNull();

        verify(endpointMapper).deleteById(1L);
        verify(bindingMapper).delete(any());                       // 指向它的绑定回落默认
        verify(endpointMapper, times(2)).update(isNull(), any());  // 先全清再置 id=2 为默认
    }

    @Test
    void 别人的端点当不存在() {
        UserLlmEndpoint other = ep(9, true);
        other.setUserId(2L);
        when(endpointMapper.selectById(9L)).thenReturn(other);

        assertThat(service.get(1L, 9L)).isNull();
        assertThat(service.delete(1L, 9L)).isEqualTo("端点不存在");
        assertThat(service.bind(1L, UserLlmBinding.TRADER, 9L)).isEqualTo("端点不存在");
        verify(endpointMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void 轻模型绑到主模型同一条等于没绑() {
        UserLlmEndpoint main = ep(1, true);
        when(endpointMapper.selectById(1L)).thenReturn(main);
        // CHAT_MAIN 无绑定（走默认）；CHAT_LIGHT 绑到 1
        UserLlmBinding light = new UserLlmBinding();
        light.setUserId(1L);
        light.setPurpose(UserLlmBinding.CHAT_LIGHT);
        light.setEndpointId(1L);
        when(bindingMapper.selectOne(any())).thenReturn(null, light);
        when(endpointMapper.selectOne(any())).thenReturn(main);

        ChatEndpoints eps = service.chatEndpoints(1L);

        assertThat(eps.deep().getId()).isEqualTo(1L);
        assertThat(eps.light()).isNull();
        assertThat(eps.lightOrDeep()).isSameAs(eps.deep());
    }

    @Test
    void 一条端点都没有时对话拿到null() {
        when(bindingMapper.selectOne(any())).thenReturn(null);
        when(endpointMapper.selectOne(any())).thenReturn(null);
        when(endpointMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.chatEndpoints(1L)).isNull();
        assertThat(service.resolve(1L, UserLlmBinding.TRADER)).isNull();
    }

    @Test
    void 拉模型清单沿用已存key时传给上游的是解密后的明文() {
        // 解密归调用方：漏了就会把密文当 key 发出去
        when(endpointMapper.selectById(1L)).thenReturn(ep(1, true));
        when(crypto.decrypt("enc1")).thenReturn("sk-plain");
        when(builder.listModels(any(), any(), any())).thenReturn(List.of("m1"));

        LlmEndpointService.ListModelsResult r = service.listModels(1L, 1L, req("openai", "", ""));

        assertThat(r.models()).containsExactly("m1");
        verify(builder).listModels("openai", "https://8.8.8.8", "sk-plain");
    }
}
