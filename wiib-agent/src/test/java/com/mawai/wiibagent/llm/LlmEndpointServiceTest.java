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

import java.util.List;

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
        return new LlmEndpointService.SaveReq("主力", protocol, "https://8.8.8.8/", " deepseek-chat ", effort, key, webSearch);
    }

    /** 搜索开关只对 responses 协议有意义：openai 协议勾了也归一 false，不把兑现不了的承诺存进库 */
    @Test
    void webSearch只在responses协议下入库() {
        when(endpointMapper.selectList(any())).thenReturn(List.of());
        when(crypto.encrypt(any())).thenReturn("enc");

        assertThat(service.create(1L, req("responses", "", "sk-x", true))).isNull();
        assertThat(service.create(1L, req("openai", "", "sk-x", true))).isNull();
        assertThat(service.create(1L, req("responses", "", "sk-x", null))).isNull();

        ArgumentCaptor<UserLlmEndpoint> cap = ArgumentCaptor.forClass(UserLlmEndpoint.class);
        verify(endpointMapper, times(3)).insert(cap.capture());
        assertThat(cap.getAllValues().get(0).getWebSearch()).isTrue();
        assertThat(cap.getAllValues().get(1).getWebSearch()).isFalse();
        assertThat(cap.getAllValues().get(2).getWebSearch()).isFalse();
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
        assertThat(service.create(1L, new LlmEndpointService.SaveReq("x", "openai", "http://127.0.0.1:8080", "m", "", "sk", null)))
                .contains("内网");
        assertThat(service.create(1L, req("openai", "seventeen-chars-x", "sk"))).contains("思考档位");   // 17 字符，超列宽
        assertThat(service.create(1L, req("openai", "", ""))).isEqualTo("apiKey不能为空");
        assertThat(service.create(1L, new LlmEndpointService.SaveReq(" ", "openai", "https://8.8.8.8", "m", "", "sk", null)))
                .contains("名称");
        verify(endpointMapper, never()).insert(any(UserLlmEndpoint.class));
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
}
