package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.run.ChildRunRegistry;
import com.mawai.wiibagent.chat.run.WorkbenchPrefService;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibagent.toolkit.NewsToolkit;

import static org.mockito.ArgumentMatchers.any;
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

    /**
     * NewsToolkit 的 mock：boundTo 要返回真的工具视图，建主 agent 时才挂得上 news_search；
     * newsSearch 本身仍是 mock，要喂快讯的用例自己 stub
     */
    public static NewsToolkit newsToolkit() {
        NewsToolkit toolkit = mock(NewsToolkit.class);
        when(toolkit.boundTo(any())).thenAnswer(inv -> new NewsToolkit.NewsSearchTool(toolkit, inv.getArgument(0)));
        return toolkit;
    }

    /** 用户偏好的 mock：开关开、上限 limit、硬上限 5 */
    public static WorkbenchPrefService prefs(boolean enabled, int limit) {
        WorkbenchPrefService prefs = mock(WorkbenchPrefService.class);
        when(prefs.of(anyLong())).thenReturn(new WorkbenchPrefService.Prefs(enabled, limit));
        when(prefs.hardLimit()).thenReturn(5);
        return prefs;
    }

    public static WorkbenchPrefService prefs() {
        return prefs(true, 3);
    }

    /** 上下文存储的 mock：没单独桩过的会话读出来是空上下文 */
    public static ChatContextStore contextStore() {
        ChatContextStore store = mock(ChatContextStore.class);
        when(store.load(any())).thenReturn(ChatContextStore.State.EMPTY);
        return store;
    }

    /** 子运行注册表的真实现：没轮在跑时报告落进传进来的 contextStore；SSE 没通道可推；偏好按默认 */
    public static ChildRunRegistry childRuns(ChatContextStore contextStore) {
        return new ChildRunRegistry(contextStore, new WorkbenchRunRegistry(), prefs(), PROMPTS, 20, 300, 4000);
    }

    /** 整理器的真实现，线在窗口的 90%：测试里那点历史够不着，等于不整理 */
    public static ContextCompactor compactor() {
        return new ContextCompactor(PROMPTS, 0.9);
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
        e.setContextWindow(256_000);   // 与库的默认值一致
        return e;
    }

    /** 只有主模型（轻模型复用主模型） */
    public static ChatEndpoints eps(long userId, String model) {
        return new ChatEndpoints(userId, endpoint(model), null);
    }
}
