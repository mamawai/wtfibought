package com.mawai.wiibagent.trader;

import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibagent.llm.ByokModelBuilder;
import com.mawai.wiibagent.llm.LlmEndpointService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 交易员的 BYOK 模型工厂：按 trader 缓存 ChatModel。每次取模型都现查一次端点（{@link #endpointFor}：
 * trader 自己选的端点优先，否则用户默认端点），建模要素一变（换端点/改端点/换 key）就重建，无需显式刷新。
 * 建模本身在 {@link ByokModelBuilder}，与对话轨同一条路。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraderModelFactory {

    /** 一轮唤醒要用的模型和联不联网，都按同一次查到的端点算 */
    public record RoundModel(ChatModel model, boolean webSearch) {
    }

    /** 建一个模型用到的全部字段，本身就是"变没变"的比对依据；key 用密文，明文不留在内存字段里 */
    private record Spec(String protocol, String baseUrl, String model, String effort, Boolean webSearch,
                        String apiKeyEnc) {
        static Spec of(UserLlmEndpoint e) {
            return new Spec(e.getApiProtocol(), e.getBaseUrl(), e.getModel(), e.getReasoningEffort(),
                    e.getWebSearch(), e.getApiKeyEnc());
        }
    }

    private record CacheEntry(Spec spec, ChatModel model) {
    }

    private final LlmEndpointService endpointService;
    private final ByokModelBuilder modelBuilder;
    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * 取这一轮唤醒的模型和联网开关，端点只查一次。用户一条端点都没有时抛 IllegalStateException——
     * runner 都把取模型包在自己的失败处理里（连败计数、暂停），不需要在这里兜。
     */
    public RoundModel roundModel(AiTrader trader) {
        UserLlmEndpoint endpoint = endpointFor(trader);
        if (endpoint == null) {
            throw new IllegalStateException("交易员没有可用的模型端点，请先在 AI 页「模型配置」里添加");
        }
        Spec spec = Spec.of(endpoint);
        CacheEntry entry = cache.compute(trader.getId(), (id, cached) ->
                cached != null && cached.spec().equals(spec)
                        ? cached
                        : new CacheEntry(spec, modelBuilder.build(endpoint)));
        return new RoundModel(entry.model(), webSearchEnabled(trader, endpoint));
    }

    /** 只要模型、不管联网的（复盘、单笔复盘）用这个 */
    public ChatModel modelFor(AiTrader trader) {
        return roundModel(trader).model();
    }

    /**
     * trader 实际用的端点（可能为 null）：选了的那条还在、也归这个用户就用它，否则用户默认端点。
     * 模型名展示、决策日志记 model 都从这里取
     */
    public UserLlmEndpoint endpointFor(AiTrader trader) {
        UserLlmEndpoint chosen = trader.getLlmEndpointId() == null ? null
                : endpointService.get(trader.getUserId(), trader.getLlmEndpointId());
        return chosen != null ? chosen : endpointService.defaultOf(trader.getUserId());
    }

    /** 这个 trader 联不联网搜索，两道都要过：主人在「我的 Trader」开了，所用端点也开了；端点被删光了（null）算没开 */
    public static boolean webSearchEnabled(AiTrader trader, UserLlmEndpoint endpoint) {
        return Boolean.TRUE.equals(trader.getWebSearchEnabled()) && endpoint != null && LlmEndpointService.searches(endpoint);
    }

    public void evict(long traderId) {
        cache.remove(traderId);
    }

    /** 连通性测试：按这条端点真建一次模、发一条最小请求。成功返回 null，失败返回给用户看的错误摘要 */
    public String testConnection(UserLlmEndpoint endpoint) {
        try {
            modelBuilder.build(endpoint).call(new Prompt(new UserMessage("ping")));
            return null;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            // 上游错误可能带请求细节，截断防刷屏；key 本身不会出现在异常信息里
            return msg.length() > 300 ? msg.substring(0, 300) : msg;
        }
    }
}
