package com.mawai.wiibagent.llm.jev;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.entity.UserJevConfig;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibagent.llm.ApiKeyCrypto;
import com.mawai.wiibagent.llm.BaseUrlGuard;
import com.mawai.wiibagent.mapper.UserJevConfigMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 一人一份覆盖式保存：首次必须给 key、之后留空沿用；URL/模型留空走默认；探测真发一题 */
class JevConfigServiceTest {

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserJevConfig.class);
    }

    private final UserJevConfigMapper mapper = mock(UserJevConfigMapper.class);
    private final ApiKeyCrypto crypto = mock(ApiKeyCrypto.class);
    private final BaseUrlGuard guard = mock(BaseUrlGuard.class);
    private final JevClient client = mock(JevClient.class);
    private final JevConfigService service = new JevConfigService(mapper, crypto, guard, client, new MessageCatalog());

    private static UserJevConfig existing() {
        UserJevConfig c = new UserJevConfig();
        c.setId(7L);
        c.setUserId(1L);
        c.setBaseUrl("https://8.8.8.8");
        c.setModel("jev-1.13.0");
        c.setApiKeyEnc("enc-old");
        return c;
    }

    @Test
    void 首次保存缺key拒绝() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertThat(service.save(1L, new JevConfigService.SaveReq("https://8.8.8.8", "jev-latest", " ")))
                .isNotNull();
        verify(mapper, never()).insert(any(UserJevConfig.class));
    }

    @Test
    void 首次保存_URL与模型留空走默认_insert() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(crypto.encrypt("sk-jev")).thenReturn("enc-new");

        assertThat(service.save(1L, new JevConfigService.SaveReq("", null, " sk-jev "))).isNull();

        verify(guard).check(JevClient.DEFAULT_BASE_URL);
        ArgumentCaptor<UserJevConfig> cap = ArgumentCaptor.forClass(UserJevConfig.class);
        verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().getUserId()).isEqualTo(1L);
        assertThat(cap.getValue().getBaseUrl()).isEqualTo(JevClient.DEFAULT_BASE_URL);
        assertThat(cap.getValue().getModel()).isEqualTo(JevClient.DEFAULT_MODEL);
        assertThat(cap.getValue().getApiKeyEnc()).isEqualTo("enc-new");
    }

    @Test
    void 再次保存key留空沿用旧密文_update() {
        when(mapper.selectOne(any())).thenReturn(existing());

        assertThat(service.save(1L, new JevConfigService.SaveReq("https://8.8.8.8/", "jev-latest", ""))).isNull();

        ArgumentCaptor<UserJevConfig> cap = ArgumentCaptor.forClass(UserJevConfig.class);
        verify(mapper).updateById(cap.capture());
        verify(mapper, never()).insert(any(UserJevConfig.class));
        assertThat(cap.getValue().getId()).isEqualTo(7L);
        assertThat(cap.getValue().getApiKeyEnc()).isEqualTo("enc-old");
        assertThat(cap.getValue().getBaseUrl()).isEqualTo("https://8.8.8.8");   // 尾斜杠抹掉
        assertThat(cap.getValue().getModel()).isEqualTo("jev-latest");
    }

    @Test
    void SSRF拒因原样返回() {
        when(guard.check(anyString())).thenReturn("blocked");
        assertThat(service.save(1L, new JevConfigService.SaveReq("http://10.0.0.1", null, "sk"))).isEqualTo("blocked");
        verify(mapper, never()).insert(any(UserJevConfig.class));
    }

    @Test
    void 删除_未配置报notConfigured_已配置按id删() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertThat(service.delete(1L)).isNotNull();

        when(mapper.selectOne(any())).thenReturn(existing());
        assertThat(service.delete(1L)).isNull();
        verify(mapper).deleteById(7L);
    }

    @Test
    void 探测_用已存key真发一题_拿到答案算通() {
        when(mapper.selectOne(any())).thenReturn(existing());
        when(crypto.decrypt("enc-old")).thenReturn("sk-plain");
        when(client.ask(eq("https://8.8.8.8"), eq("sk-plain"), eq("jev-1.13.0"), any(), any()))
                .thenReturn(new JevClient.Response("jev-1.13.0",
                        Map.of("probe", new JevClient.Answer("noul", 0.9, null, null, null, null)), 12));

        assertThat(service.test(1L, new JevConfigService.SaveReq("https://8.8.8.8", "jev-1.13.0", ""))).isNull();
    }

    @Test
    void 探测_上游抛错返回connectFailed() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(crypto.encrypt("sk")).thenReturn("enc");
        when(crypto.decrypt("enc")).thenReturn("sk");
        when(client.ask(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("401 Unauthorized"));

        String err = service.test(1L, new JevConfigService.SaveReq("https://8.8.8.8", "", "sk"));
        assertThat(err).contains("401");
    }
}
