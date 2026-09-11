package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.llm.ChatEndpoints;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 对话测试共用：拼一份最小可用的端点/端点对（指纹要读的字段都填上） */
public final class ChatTestEndpoints {

    /** 生产词表，全量装载一次给所有用例共用（只读，无状态） */
    public static final PromptCatalog PROMPTS = new PromptCatalog();
    public static final LocalizedToolCallbacks TOOLS = new LocalizedToolCallbacks(PROMPTS);

    private ChatTestEndpoints() {
    }

    /** 恒中文的语言解析器：默认路径都按中文断言，验切语言的用例自己另造 */
    public static UserLangResolver zhLang() {
        UserLangResolver resolver = mock(UserLangResolver.class);
        when(resolver.of(anyLong())).thenReturn(AgentLang.ZH);
        return resolver;
    }

    public static UserLlmEndpoint endpoint(String model) {
        return endpoint(model, "enc-key");
    }

    public static UserLlmEndpoint endpoint(String model, String apiKeyEnc) {
        UserLlmEndpoint e = new UserLlmEndpoint();
        e.setId(1L);
        e.setUserId(1L);
        e.setName(model);
        e.setApiProtocol("openai");
        e.setBaseUrl("https://api.example.com");
        e.setModel(model);
        e.setApiKeyEnc(apiKeyEnc);
        return e;
    }

    /** 只有主模型（轻模型复用主模型） */
    public static ChatEndpoints eps(long userId, String model) {
        return new ChatEndpoints(userId, endpoint(model), null);
    }
}
