package com.mawai.wiibagent.llm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibcommon.constant.AiProtocols;
import com.mawai.wiibcommon.entity.UserLlmBinding;
import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibagent.mapper.UserLlmBindingMapper;
import com.mawai.wiibagent.mapper.UserLlmEndpointMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户 BYOK 端点库：端点 CRUD + 用途绑定 + 解析（谁用哪条）。全站唯一的 BYOK 配置入口。
 * <p>
 * 三条不变量：
 * <ul>
 *   <li>一人只要还有端点，就<b>恰有一条</b>默认（首条自动默认；删默认时最早的一条顶上）；</li>
 *   <li>绑定行永远指向活着的端点：删端点连带删它的绑定，受影响的用途回落默认；</li>
 *   <li>保存不发上游请求（探测是独立按钮）；测什么就是接下来真跑什么——探测走 {@link ByokModelBuilder} 的生产建模路径。</li>
 * </ul>
 * 错误约定与 TraderService 一致：返回 String 错误消息（跟当次请求的界面语言），成功返回 null。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmEndpointService {

    /** 一人上限：够挂几家平台各几个模型；再多多半是没删干净 */
    static final int MAX_PER_USER = 20;

    /** 档位列宽 VARCHAR(16)，超了留给 SQL 报错不如这里说人话 */
    private static final int MAX_EFFORT_LEN = 16;

    private static final Set<String> PURPOSES = Set.of(UserLlmBinding.CHAT_MAIN, UserLlmBinding.CHAT_LIGHT, UserLlmBinding.TRADER);

    /** apiKey 传空=沿用已存的 key（只在 update/探测已有端点时合法）；webSearch 只在能声明服务端搜索的协议下入库 */
    public record SaveReq(String name, String apiProtocol, String baseUrl, String model,
                          String reasoningEffort, String apiKey, Boolean webSearch) {
    }

    public record ListModelsResult(String error, List<String> models) {
    }

    private final UserLlmEndpointMapper endpointMapper;
    private final UserLlmBindingMapper bindingMapper;
    private final ApiKeyCrypto apiKeyCrypto;
    private final BaseUrlGuard baseUrlGuard;
    private final ByokModelBuilder modelBuilder;
    private final MessageCatalog messages;

    // ==================== 读 ====================

    /** 按创建顺序 */
    public List<UserLlmEndpoint> list(long userId) {
        return endpointMapper.selectList(new LambdaQueryWrapper<UserLlmEndpoint>()
                .eq(UserLlmEndpoint::getUserId, userId).orderByAsc(UserLlmEndpoint::getId));
    }

    /** 归属校验一起做：别人的 id 当不存在 */
    public UserLlmEndpoint get(long userId, long id) {
        UserLlmEndpoint e = endpointMapper.selectById(id);
        return e != null && e.getUserId() == userId ? e : null;
    }

    /** 默认端点；没有任何端点返回 null。默认标记意外丢了（不该发生）就拿最早那条兜住 */
    public UserLlmEndpoint defaultOf(long userId) {
        UserLlmEndpoint d = endpointMapper.selectOne(new LambdaQueryWrapper<UserLlmEndpoint>()
                .eq(UserLlmEndpoint::getUserId, userId).eq(UserLlmEndpoint::getIsDefault, true).last("LIMIT 1"));
        if (d != null) {
            return d;
        }
        List<UserLlmEndpoint> all = list(userId);
        return all.isEmpty() ? null : all.get(0);
    }

    /** purpose → endpointId（只含显式绑定的用途） */
    public Map<String, Long> bindings(long userId) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (UserLlmBinding b : bindingMapper.selectList(new LambdaQueryWrapper<UserLlmBinding>()
                .eq(UserLlmBinding::getUserId, userId))) {
            out.put(b.getPurpose(), b.getEndpointId());
        }
        return out;
    }

    /** 某用途实际用哪条：显式绑定优先，否则默认；一条端点都没有 → null */
    public UserLlmEndpoint resolve(long userId, String purpose) {
        UserLlmBinding b = binding(userId, purpose);
        if (b != null) {
            UserLlmEndpoint e = get(userId, b.getEndpointId());
            if (e != null) {
                return e;
            }
        }
        return defaultOf(userId);
    }

    /** 对话轨的一对：主模型按 CHAT_MAIN 解析，轻模型只认显式绑定（没绑就复用主模型）。没端点 → null */
    public ChatEndpoints chatEndpoints(long userId) {
        UserLlmEndpoint deep = resolve(userId, UserLlmBinding.CHAT_MAIN);
        if (deep == null) {
            return null;
        }
        UserLlmBinding lb = binding(userId, UserLlmBinding.CHAT_LIGHT);
        UserLlmEndpoint light = lb == null ? null : get(userId, lb.getEndpointId());
        // 轻模型绑的就是主模型那条：等于没单独配，走 null 让下游复用同一个实例
        if (light != null && light.getId().equals(deep.getId())) {
            light = null;
        }
        return new ChatEndpoints(userId, deep, light);
    }

    /**
     * 批量解析多个用户同一用途的端点（竞技场列表要给每个 trader 标模型名）：两条查询而不是 N×2 条。
     * 结果不含没有端点的用户。
     */
    public Map<Long, UserLlmEndpoint> resolveForUsers(Collection<Long> userIds, String purpose) {
        Map<Long, UserLlmEndpoint> out = new HashMap<>();
        if (userIds.isEmpty()) {
            return out;
        }
        Map<Long, Long> bound = new HashMap<>();
        for (UserLlmBinding b : bindingMapper.selectList(new LambdaQueryWrapper<UserLlmBinding>()
                .in(UserLlmBinding::getUserId, userIds).eq(UserLlmBinding::getPurpose, purpose))) {
            bound.put(b.getUserId(), b.getEndpointId());
        }
        Map<Long, UserLlmEndpoint> byId = new HashMap<>();
        Map<Long, UserLlmEndpoint> defaults = new HashMap<>();
        Map<Long, UserLlmEndpoint> oldest = new HashMap<>();
        for (UserLlmEndpoint e : endpointMapper.selectList(new LambdaQueryWrapper<UserLlmEndpoint>()
                .in(UserLlmEndpoint::getUserId, userIds).orderByAsc(UserLlmEndpoint::getId))) {
            byId.put(e.getId(), e);
            if (Boolean.TRUE.equals(e.getIsDefault())) {
                defaults.put(e.getUserId(), e);
            }
            oldest.putIfAbsent(e.getUserId(), e);
        }
        for (Long uid : userIds) {
            UserLlmEndpoint e = bound.containsKey(uid) ? byId.get(bound.get(uid)) : null;
            if (e == null) {
                e = defaults.getOrDefault(uid, oldest.get(uid));
            }
            if (e != null) {
                out.put(uid, e);
            }
        }
        return out;
    }

    // ==================== 写 ====================

    public String create(long userId, SaveReq req) {
        String err = validate(req, true);
        if (err != null) {
            return err;
        }
        List<UserLlmEndpoint> existing = list(userId);
        if (existing.size() >= MAX_PER_USER) {
            return messages.get("agent.endpoint.limitReached", Map.of("max", MAX_PER_USER));
        }
        UserLlmEndpoint row = toRow(req, null);
        row.setUserId(userId);
        row.setIsDefault(existing.isEmpty());   // 首条自动成默认：只配一条时它就是全局配置
        endpointMapper.insert(row);
        log.info("[LlmEndpoint] 新增 userId={} id={} name={} model={}", userId, row.getId(), row.getName(), row.getModel());
        return null;
    }

    /** apiKey 传空=沿用旧 key。改了任何建模要素，对话/交易员两边的缓存都按字段指纹自动重建，不用显式失效 */
    public String update(long userId, long id, SaveReq req) {
        UserLlmEndpoint cur = get(userId, id);
        if (cur == null) {
            return messages.get("agent.endpoint.notFound");
        }
        boolean keyChanged = req.apiKey() != null && !req.apiKey().isBlank();
        String err = validate(req, false);
        if (err != null) {
            return err;
        }
        UserLlmEndpoint row = toRow(req, keyChanged ? null : cur.getApiKeyEnc());
        row.setId(id);
        endpointMapper.updateById(row);
        log.info("[LlmEndpoint] 更新 userId={} id={} name={} model={}", userId, id, row.getName(), row.getModel());
        return null;
    }

    /** 删端点：连带删它的绑定（回落默认）；删的是默认就把最早的一条顶上 */
    public String delete(long userId, long id) {
        UserLlmEndpoint cur = get(userId, id);
        if (cur == null) {
            return messages.get("agent.endpoint.notFound");
        }
        endpointMapper.deleteById(id);
        bindingMapper.delete(new LambdaQueryWrapper<UserLlmBinding>()
                .eq(UserLlmBinding::getUserId, userId).eq(UserLlmBinding::getEndpointId, id));
        if (Boolean.TRUE.equals(cur.getIsDefault())) {
            List<UserLlmEndpoint> rest = list(userId);
            if (!rest.isEmpty()) {
                markDefault(userId, rest.get(0).getId());
            }
        }
        log.info("[LlmEndpoint] 删除 userId={} id={} name={}", userId, id, cur.getName());
        return null;
    }

    public String setDefault(long userId, long id) {
        if (get(userId, id) == null) {
            return messages.get("agent.endpoint.notFound");
        }
        markDefault(userId, id);
        return null;
    }

    /** 绑定用途；endpointId 传 null = 解绑（跟随默认） */
    public String bind(long userId, String purpose, Long endpointId) {
        if (purpose == null || !PURPOSES.contains(purpose)) {
            return messages.get("agent.endpoint.unknownPurpose");
        }
        if (endpointId == null) {
            bindingMapper.delete(new LambdaQueryWrapper<UserLlmBinding>()
                    .eq(UserLlmBinding::getUserId, userId).eq(UserLlmBinding::getPurpose, purpose));
            return null;
        }
        if (get(userId, endpointId) == null) {
            return messages.get("agent.endpoint.notFound");
        }
        UserLlmBinding b = binding(userId, purpose);
        if (b == null) {
            b = new UserLlmBinding();
            b.setUserId(userId);
            b.setPurpose(purpose);
            b.setEndpointId(endpointId);
            bindingMapper.insert(b);
        } else {
            b.setEndpointId(endpointId);
            bindingMapper.updateById(b);
        }
        return null;
    }

    // ==================== 探测 ====================

    /** 拉模型清单。id 非空且 apiKey 传空=用该端点已存的 key（编辑已有端点时不用重填） */
    public ListModelsResult listModels(long userId, Long id, SaveReq req) {
        if (req.baseUrl() == null || req.baseUrl().isBlank()) {
            return new ListModelsResult(messages.get("agent.endpoint.baseUrlRequired"), List.of());
        }
        String ssrf = baseUrlGuard.check(req.baseUrl());
        if (ssrf != null) {
            return new ListModelsResult(ssrf, List.of());
        }
        String keyEnc = keyEncFor(userId, id, req.apiKey());
        if (keyEnc == null) {
            return new ListModelsResult(messages.get("agent.endpoint.apiKeyRequired"), List.of());
        }
        try {
            return new ListModelsResult(null, modelBuilder.listModels(req.apiProtocol(),
                    stripTrailingSlash(req.baseUrl().trim()), keyEnc));
        } catch (Exception e) {
            return new ListModelsResult(truncate(e), List.of());
        }
    }

    /**
     * 连通性探测（前端"测试连通性"按钮）。成功返 null，失败返上游原因。
     * 走的就是生产建模那条路（{@link ByokModelBuilder#build}）——测什么就得是接下来真跑什么。
     */
    public String testConnection(long userId, Long id, SaveReq req) {
        String err = validate(req, false);
        if (err != null) {
            return err;
        }
        String keyEnc = keyEncFor(userId, id, req.apiKey());
        if (keyEnc == null) {
            return messages.get("agent.endpoint.apiKeyRequired");
        }
        try {
            modelBuilder.build(toRow(req, keyEnc)).call(new Prompt(new UserMessage("ping")));
            return null;
        } catch (Exception e) {
            return messages.get("agent.endpoint.connectFailed", Map.of("reason", truncate(e)));
        }
    }

    /** 明文永远不出服务端，回显只给尾 4 位够用户认出是哪把 key */
    public String keyTail(UserLlmEndpoint e) {
        try {
            String plain = apiKeyCrypto.decrypt(e.getApiKeyEnc());
            return plain.length() > 4 ? plain.substring(plain.length() - 4) : "****";
        } catch (Exception ex) {
            return "????";
        }
    }

    // ==================== 内部 ====================

    private UserLlmBinding binding(long userId, String purpose) {
        return bindingMapper.selectOne(new LambdaQueryWrapper<UserLlmBinding>()
                .eq(UserLlmBinding::getUserId, userId).eq(UserLlmBinding::getPurpose, purpose).last("LIMIT 1"));
    }

    /** 先全清再置一条：两步写，中间瞬间无默认——读侧 defaultOf 有"最早一条兜住"，不会读到空 */
    private void markDefault(long userId, long id) {
        endpointMapper.update(null, new LambdaUpdateWrapper<UserLlmEndpoint>()
                .eq(UserLlmEndpoint::getUserId, userId).set(UserLlmEndpoint::getIsDefault, false));
        endpointMapper.update(null, new LambdaUpdateWrapper<UserLlmEndpoint>()
                .eq(UserLlmEndpoint::getId, id).set(UserLlmEndpoint::getIsDefault, true));
    }

    /** 新 key 加密；传空则回落到 id 指向端点已存的密文；两头都没有 → null */
    private String keyEncFor(long userId, Long id, String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKeyCrypto.encrypt(apiKey.trim());
        }
        UserLlmEndpoint cur = id == null ? null : get(userId, id);
        return cur == null ? null : cur.getApiKeyEnc();
    }

    /**
     * SaveReq → 行对象（不含 userId/id/isDefault）。create/update/testConnection 必须用同一份组装，
     * 否则"测通了但存进去的不是它"。keepKeyEnc 非空=沿用旧密文，否则用 req 里的新 key 加密。
     */
    private UserLlmEndpoint toRow(SaveReq req, String keepKeyEnc) {
        UserLlmEndpoint row = new UserLlmEndpoint();
        row.setName(req.name().trim());
        row.setApiProtocol(normalizeProtocol(req.apiProtocol()));
        row.setBaseUrl(stripTrailingSlash(req.baseUrl().trim()));
        row.setModel(req.model().trim());
        row.setReasoningEffort(normalizeEffort(req.reasoningEffort()));
        // 归一而非报错：协议声明不了服务端搜索的（chat-completions），勾了也不把兑现不了的承诺存进库
        row.setWebSearch(Boolean.TRUE.equals(req.webSearch())
                && AiProtocols.supportsServerSearch(row.getApiProtocol()));
        row.setApiKeyEnc(keepKeyEnc != null ? keepKeyEnc : apiKeyCrypto.encrypt(req.apiKey().trim()));
        return row;
    }

    private String validate(SaveReq req, boolean requireKey) {
        if (req.name() == null || req.name().isBlank() || req.name().trim().length() > 32) {
            return messages.get("agent.endpoint.nameInvalid");
        }
        if (req.baseUrl() == null || req.baseUrl().isBlank()) {
            return messages.get("agent.endpoint.baseUrlRequired");
        }
        String ssrf = baseUrlGuard.check(req.baseUrl());
        if (ssrf != null) {
            return ssrf;
        }
        if (req.model() == null || req.model().isBlank()) {
            return messages.get("agent.endpoint.modelRequired");
        }
        // 前端是 select，传空说明请求本身不对，响亮拒绝比默默兜底好查
        if (req.apiProtocol() == null || req.apiProtocol().isBlank()) {
            return messages.get("agent.endpoint.protocolRequired");
        }
        if (!AiProtocols.isValid(normalizeProtocol(req.apiProtocol()))) {
            return messages.get("agent.endpoint.protocolInvalid");
        }
        // 不限白名单：各家档位名字自己定（xhigh/minimal…），认不认只有上游知道。只挡列宽（VARCHAR(16)）免得存的时候炸 SQL
        String effort = normalizeEffort(req.reasoningEffort());
        if (effort != null && effort.length() > MAX_EFFORT_LEN) {
            return messages.get("agent.endpoint.effortTooLong", Map.of("max", MAX_EFFORT_LEN));
        }
        if (requireKey && (req.apiKey() == null || req.apiKey().isBlank())) {
            return messages.get("agent.endpoint.apiKeyRequired");
        }
        return null;
    }

    /** 校验和落库都用抹平后的值，保证"验的就是存的"；null 留给 validate 报 protocolRequired */
    private static String normalizeProtocol(String protocol) {
        return protocol == null ? null : AiProtocols.normalize(protocol);
    }

    /** 同 normalizeProtocol 的道理：档位是原样进请求体的，脏值等到上游才报错就太晚了。留空一律 null=不传 */
    private static String normalizeEffort(String effort) {
        return effort == null || effort.isBlank() ? null : effort.trim().toLowerCase();
    }

    private static String stripTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** 上游错误可能带整个请求细节，截断防刷屏；key 本身不会出现在异常信息里 */
    private static String truncate(Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return msg.length() > 300 ? msg.substring(0, 300) : msg;
    }
}
