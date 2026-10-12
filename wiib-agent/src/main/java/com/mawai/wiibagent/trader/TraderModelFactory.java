package com.mawai.wiibagent.trader;

import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.UserLlmBinding;
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
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 交易员的 BYOK 模型工厂：按 trader 缓存 ChatModel。端点每次唤醒现解析（{@link LlmEndpointService#resolve}：
 * TRADER 绑定优先，否则用户默认端点），指纹一变（换绑定/改端点/换 key）下次唤醒自动重建，无需显式刷新。
 * 建模本身在 {@link ByokModelBuilder}，与对话轨同一条路。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraderModelFactory {

    private record CacheEntry(String fingerprint, ChatModel model) {
    }

    private final LlmEndpointService endpointService;
    private final ByokModelBuilder modelBuilder;
    private final Map<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * 取这个 trader 当前该用的模型。用户一条端点都没有时抛 IllegalStateException——
     * 唤醒/复盘两个 runner 都把 modelFor 包在自己的失败处理里（连败计数、暂停），不需要在这里兜。
     */
    public ChatModel modelFor(AiTrader trader) {
        UserLlmEndpoint endpoint = endpointFor(trader);
        if (endpoint == null) {
            throw new IllegalStateException("交易员没有可用的模型端点，请先在 AI 页「模型配置」里添加");
        }
        String fp = fingerprint(endpoint);
        CacheEntry entry = cache.compute(trader.getId(), (id, cached) ->
                cached != null && cached.fingerprint().equals(fp)
                        ? cached
                        : new CacheEntry(fp, modelBuilder.build(endpoint)));
        return entry.model();
    }

    /** trader 实际用的端点（可能为 null）；模型名展示、决策日志记 model 都从这里取，不再有 ai_trader.model 列 */
    public UserLlmEndpoint endpointFor(AiTrader trader) {
        return endpointService.resolve(trader.getUserId(), UserLlmBinding.TRADER);
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

    /**
     * 指纹只含建模要素；key 用密文参与（明文不留存内存字段）。
     * 这里的 Objects.hash 是同一个 traderId 槽位内的"变了没有"校验位，撞了只影响该不该重建自己那一个模型，
     * 与 ChatModelFactory 那个跨用户缓存主键的 SHA-256 语义不同。
     */
    private static String fingerprint(UserLlmEndpoint e) {
        return Objects.hash(e.getApiProtocol(), e.getBaseUrl(), e.getModel(), e.getReasoningEffort(),
                e.getWebSearch(), e.getApiKeyEnc()) + "";
    }
}
