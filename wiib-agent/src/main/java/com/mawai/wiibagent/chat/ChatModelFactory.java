package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibagent.llm.ByokModelBuilder;
import com.mawai.wiibagent.llm.ChatEndpoints;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对话轨的 BYOK 模型工厂：一对端点（主/轻）→ 深/浅两个 ChatModel。建模本身交给 {@link ByokModelBuilder}，
 * 这里只管缓存策略。
 * <p>
 * 缓存键是<b>配置指纹</b>，userId 是它的第一个分量：一个用户一份由键本身保证，
 * 改端点指纹就变、自然拿到新实例，不需要显式 evict。
 * <b>userId 那一格不能省</b>：叶子里有按用户烤死的工具，两人共享一份叶子就是跨用户泄露。
 * 重新输入 key（哪怕同一把）会因 IV 变化触发一次重建，旧实例被 LRU 淘汰，无实际损失。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModelFactory {

    /**
     * 缓存上限。既然一个用户一份（见类头），这个数就是<b>能同时缓存几个活跃用户</b>——
     * 超了就 LRU 抖动，被淘汰的人下次发消息要重建一整套模型（建对象和 HTTP client，不打网络）。
     * <p>
     * 对话已对全体用户开放，32 是拍的数。注意 {@link ChatConcurrencyGate} 卡的是
     * <b>同时在跑的轮数</b>（默认全局 10），卡不住这里——一轮跑完缓存还占着。
     * 所以要调，按<b>一段时间内轮流来聊的人数</b>调，不是按并发数，更不是按注册用户数。
     */
    static final int MAX_ENTRIES = 32;

    public record Models(ChatModel deep, ChatModel light) {
    }

    private final ByokModelBuilder modelBuilder;

    /**
     * LRU（accessOrder=true + removeEldest）。必须包 synchronizedMap：accessOrder 下连 get
     * 都会改链表。锁只保护单次 get/putIfAbsent，<b>建模不在锁里做</b>，见 {@link #modelsFor}。
     */
    private final Map<String, Models> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Models> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    /**
     * 先查后建，<b>不用 computeIfAbsent</b>：它会在整个 mapping 函数执行期间攥着互斥锁，
     * 而这里的 mapping 是装配 SDK 客户端和连接池。这条路后面还要放到请求线程上，
     * 写成 computeIfAbsent 的话任何一个用户首次建模期间，别人的对话请求全堵在这把锁上。
     */
    public Models modelsFor(ChatEndpoints eps) {
        String fp = fingerprint(eps);
        Models hit = cache.get(fp);
        if (hit != null) {
            return hit;
        }
        Models built = build(eps);      // 锁外建模，慢也只慢自己
        // 并发下可能有人先放好了，用先到的那份：模型无状态，多建一份只是一次 GC
        Models prev = cache.putIfAbsent(fp, built);
        return prev != null ? prev : built;
    }

    /**
     * 指纹含全部建模要素（主/轻两条端点各自的协议/URL/模型/档位/key 密文）；key 用密文参与，明文不留存内存字段。
     * <p>
     * <b>用 SHA-256 而不是 {@code Objects.hash}</b>：这是跨用户的缓存主键，32 位 int 撞一次
     * 就是 B 的请求拿到 A 的 ChatModel——<b>烧的是 A 的 key</b>。概率低（生日界约 7.7 万份配置
     * 撞到 50%），但代价是"用别人的 key"，换 SHA-256 零成本。
     *（{@code TraderModelFactory} 那个 {@code Objects.hash} 语义完全不同：它是同一个 traderId
     * 槽位内的"变了没有"校验位，撞了只影响该不该重建自己那一个模型。）
     * <p>
     * 分隔符不能省：没有它 {@code ("ab","c")} 和 {@code ("a","bc")} 拼出同一个串。
     * 而且必须挑一个<b>字段值里不可能出现</b>的字符——model 是用户在前端自由输入的，
     * 用空格的话 {@code ("gpt-5 x","y")} 和 {@code ("gpt-5","x y")} 照样撞。取 NUL：
     * Postgres 的 text 存不下这个字节，所以它绝不会出现在任何一个从库里读出来的字段值里。
     */
    public static String fingerprint(ChatEndpoints eps) {
        // userId 必须进指纹，TraderQueryToolkit 读的是"这个人的 trader"，
        String raw = String.join("\0", String.valueOf(eps.userId()), part(eps.deep()), part(eps.light()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 没有 SHA-256", e);   // impossible
        }
    }

    /** 一条端点的建模要素；null（没单独绑轻模型）给固定占位，与"绑了但恰好同字段"区分开 */
    private static String part(UserLlmEndpoint e) {
        if (e == null) {
            return "-";
        }
        // webSearch 也是建模要素（ResponsesChatModel 构造参数 + 主 agent 提示词按它拼），勾了必须换指纹
        return String.join("\0", String.valueOf(e.getApiProtocol()), String.valueOf(e.getBaseUrl()),
                String.valueOf(e.getModel()), String.valueOf(e.getReasoningEffort()),
                String.valueOf(e.getWebSearch()), String.valueOf(e.getApiKeyEnc()));
    }

    private Models build(ChatEndpoints eps) {
        ChatModel deep = modelBuilder.build(eps.deep());
        ChatModel light = eps.light() == null ? deep : modelBuilder.build(eps.light());
        log.info("[ChatModel] 建模完成 model={} light={}", eps.deep().getModel(),
                eps.light() == null ? "(同主模型)" : eps.light().getModel());
        return new Models(deep, light);
    }
}
