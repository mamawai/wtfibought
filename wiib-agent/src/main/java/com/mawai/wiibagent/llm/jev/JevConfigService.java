package com.mawai.wiibagent.llm.jev;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.UserJevConfig;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibagent.llm.ApiKeyCrypto;
import com.mawai.wiibagent.llm.BaseUrlGuard;
import com.mawai.wiibagent.mapper.UserJevConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 用户 Jev 配置的读写与探测：一人一份，保存即覆盖。
 * 错误约定与端点库一致：返回 String 错误消息（跟当次请求的界面语言），成功返回 null。
 * <p>
 * 保存不发上游请求，探测是独立按钮；探测真发一题，走的就是路由那条调用路径。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JevConfigService {

    /** baseUrl/model 留空走默认；apiKey 留空=沿用已存的 key（只在已有配置时合法） */
    public record SaveReq(String baseUrl, String model, String apiKey) {
    }

    private final UserJevConfigMapper mapper;
    private final ApiKeyCrypto apiKeyCrypto;
    private final BaseUrlGuard baseUrlGuard;
    private final JevClient client;
    private final MessageCatalog messages;

    /** 未配置返回 null */
    public UserJevConfig of(long userId) {
        return mapper.selectOne(new LambdaQueryWrapper<UserJevConfig>()
                .eq(UserJevConfig::getUserId, userId).last("LIMIT 1"));
    }

    /** 首次保存必须给 key；之后留空沿用旧 key */
    public String save(long userId, SaveReq req) {
        String ssrf = baseUrlGuard.check(baseUrlOf(req));
        if (ssrf != null) {
            return ssrf;
        }
        UserJevConfig cur = of(userId);
        String keyEnc = keyEncFor(cur, req.apiKey());
        if (keyEnc == null) {
            return messages.get("agent.jev.apiKeyRequired");
        }
        UserJevConfig row = cur == null ? new UserJevConfig() : cur;
        row.setUserId(userId);
        row.setBaseUrl(baseUrlOf(req));
        row.setModel(modelOf(req));
        row.setApiKeyEnc(keyEnc);
        if (cur == null) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
        log.info("[Jev] 配置已保存 userId={} model={} baseUrl={}", userId, row.getModel(), row.getBaseUrl());
        return null;
    }

    public String delete(long userId) {
        UserJevConfig cur = of(userId);
        if (cur == null) {
            return messages.get("agent.jev.notConfigured");
        }
        mapper.deleteById(cur.getId());
        log.info("[Jev] 配置已删除 userId={}", userId);
        return null;
    }

    /** 连通性探测：真发一道是非题，拿到那道题的答案才算通。成功返 null，失败返上游原因 */
    public String test(long userId, SaveReq req) {
        String ssrf = baseUrlGuard.check(baseUrlOf(req));
        if (ssrf != null) {
            return ssrf;
        }
        String keyEnc = keyEncFor(of(userId), req.apiKey());
        if (keyEnc == null) {
            return messages.get("agent.jev.apiKeyRequired");
        }
        try {
            JevClient.Response r = client.ask(baseUrlOf(req), apiKeyCrypto.decrypt(keyEnc), modelOf(req),
                    "connectivity probe", Map.of("probe", JevClient.Question.noul("Is this a connectivity probe?")));
            if (r.answers().get("probe") == null) {
                return messages.get("agent.jev.connectFailed", Map.of("reason", "no answer for the probe question"));
            }
            return null;
        } catch (Exception e) {
            return messages.get("agent.jev.connectFailed", Map.of("reason", truncate(e)));
        }
    }

    /** 明文永远不出服务端，回显只给尾 4 位 */
    public String keyTail(UserJevConfig c) {
        try {
            String plain = apiKeyCrypto.decrypt(c.getApiKeyEnc());
            return plain.length() > 4 ? plain.substring(plain.length() - 4) : "****";
        } catch (Exception ex) {
            return "????";
        }
    }

    /** 新 key 加密；留空则沿用已有配置的密文；两头都没有 → null */
    private String keyEncFor(UserJevConfig cur, String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKeyCrypto.encrypt(apiKey.trim());
        }
        return cur == null ? null : cur.getApiKeyEnc();
    }

    private static String baseUrlOf(SaveReq req) {
        if (req.baseUrl() == null || req.baseUrl().isBlank()) {
            return JevClient.DEFAULT_BASE_URL;
        }
        String url = req.baseUrl().trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String modelOf(SaveReq req) {
        return req.model() == null || req.model().isBlank() ? JevClient.DEFAULT_MODEL : req.model().trim();
    }

    /** 上游错误可能带整个请求细节，截断防刷屏；key 不会出现在异常信息里 */
    private static String truncate(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return msg.length() > 300 ? msg.substring(0, 300) : msg;
    }
}
