package com.mawai.wiibquant.agent.chat;

import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.StoreItem;
import com.alibaba.cloud.ai.graph.store.StoreSearchRequest;
import com.mawai.wiibcommon.constant.QuantConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作台跨会话长期记忆（P5，框架 Store/DatabaseStore 落 PG）：
 * 写入规则化（不烧 LLM）——对话中出现的关注 symbol 计数 + 最近一次问题/回答摘要；
 * 召回拼成 prompt 前缀段，让 agent 记得"用户常看什么、上次聊到哪"。
 * namespace=[workbench, userId]，用户间隔离。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMemoryService {

    private static final int SUMMARY_MAX = 200;

    private final Store workbenchStore;

    /** 对话完成后规则化提取写入（异步调用方保证不阻塞 SSE 收尾）。 */
    public void remember(long userId, String question, String answer) {
        try {
            List<String> ns = namespace(userId);
            for (String symbol : QuantConstants.WATCH_SYMBOLS) {
                String coin = symbol.replace("USDT", "");
                if (question.toUpperCase().contains(coin)) {
                    Map<String, Object> value = new HashMap<>();
                    StoreItem existing = workbenchStore.getItem(ns, symbol).orElse(null);
                    long count = existing != null && existing.getValue().get("count") instanceof Number n
                            ? n.longValue() + 1 : 1;
                    value.put("count", count);
                    value.put("lastQuestion", truncate(question));
                    value.put("lastAnswerSummary", truncate(answer));
                    workbenchStore.putItem(StoreItem.of(ns, symbol, value));
                }
            }
        } catch (Exception e) {
            // 记忆是增益不是主链，失败只记日志（带堆栈——框架包装异常的 message 不含真实 SQL 错误）
            log.warn("[Memory] 写入失败 userId={}", userId, e);
        }
    }

    /** 召回：拼成注入对话的前缀段；无记忆返回空串。 */
    public String recall(long userId) {
        try {
            var result = workbenchStore.searchItems(StoreSearchRequest.builder()
                    .namespace(namespace(userId))
                    .limit(5)
                    .build());
            if (result == null || result.getItems() == null || result.getItems().isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("【用户历史偏好（跨会话记忆）】\n");
            for (StoreItem item : result.getItems()) {
                sb.append("- ").append(item.getKey())
                        .append(" 关注").append(item.getValue().getOrDefault("count", 1)).append("次");
                Object lastQ = item.getValue().get("lastQuestion");
                if (lastQ != null) {
                    sb.append("，上次问：").append(lastQ);
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[Memory] 召回失败 userId={}", userId, e);
            return "";
        }
    }

    private static List<String> namespace(long userId) {
        return List.of("workbench", String.valueOf(userId));
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > SUMMARY_MAX ? s.substring(0, SUMMARY_MAX) + "…" : s;
    }
}
