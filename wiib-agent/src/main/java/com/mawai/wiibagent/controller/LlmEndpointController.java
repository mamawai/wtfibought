package com.mawai.wiibagent.controller;

import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibagent.llm.LlmEndpointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户 BYOK 端点库（AI 页「模型配置」）：端点 CRUD、默认、用途绑定、探测。
 * 对话/交易员/复盘教练全部从这里选，平台不承担 LLM 成本。key 明文任何情况下不出服务端。
 */
@Tag(name = "用户 LLM 端点")
@RestController
@RequestMapping("/api/ai/llm-endpoints")
@RequiredArgsConstructor
public class LlmEndpointController {

    private final LlmEndpointService service;

    /** key 只回尾 4 位；contextWindow 单位 token */
    public record EndpointView(long id, String name, String apiProtocol, String baseUrl, String model,
                               String reasoningEffort, boolean webSearch, boolean antigravity, int contextWindow,
                               String apiKeyTail, boolean isDefault) {
    }

    /**
     * reasoningEffort 留空=不传给上游走模型默认；apiKey 更新时留空=不换；webSearch 只在能声明服务端搜索的协议下入库；
     * antigravity 只在 gemini 协议且勾了搜索时入库；contextWindow 单位 token，留空=默认 256K
     */
    public record SaveRequest(String name, String apiProtocol, String baseUrl, String model,
                              String reasoningEffort, String apiKey, Boolean webSearch, Boolean antigravity,
                              Integer contextWindow) {
        LlmEndpointService.SaveReq toReq() {
            return new LlmEndpointService.SaveReq(name, apiProtocol, baseUrl, model, reasoningEffort, apiKey,
                    webSearch, antigravity, contextWindow);
        }
    }

    /** endpointId 传 null = 解绑（跟随默认） */
    public record BindRequest(String purpose, Long endpointId) {
    }

    private EndpointView view(UserLlmEndpoint e) {
        return new EndpointView(e.getId(), e.getName(), e.getApiProtocol(), e.getBaseUrl(), e.getModel(),
                e.getReasoningEffort(), Boolean.TRUE.equals(e.getWebSearch()), Boolean.TRUE.equals(e.getAntigravity()),
                e.getContextWindow(), service.keyTail(e), Boolean.TRUE.equals(e.getIsDefault()));
    }

    @GetMapping
    @Operation(summary = "我的端点列表（按创建顺序，key 只回尾4位）")
    public Result<List<EndpointView>> list(@CurrentUserId long userId) {
        return Result.ok(service.list(userId).stream().map(this::view).toList());
    }

    @PostMapping
    @Operation(summary = "新增端点（首条自动成默认）")
    public Result<Void> create(@CurrentUserId long userId, @RequestBody SaveRequest req) {
        String err = service.create(userId, req.toReq());
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新端点（apiKey 传空=不换）")
    public Result<Void> update(@CurrentUserId long userId, @PathVariable long id, @RequestBody SaveRequest req) {
        String err = service.update(userId, id, req.toReq());
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除端点（引用它的用途回落默认；删默认则最早一条顶上）")
    public Result<Void> delete(@CurrentUserId long userId, @PathVariable long id) {
        String err = service.delete(userId, id);
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    @PostMapping("/{id}/default")
    @Operation(summary = "设为默认端点")
    public Result<Void> setDefault(@CurrentUserId long userId, @PathVariable long id) {
        String err = service.setDefault(userId, id);
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    @GetMapping("/bindings")
    @Operation(summary = "用途绑定（purpose → endpointId；未绑定的用途不出现=跟随默认）")
    public Result<Map<String, Long>> bindings(@CurrentUserId long userId) {
        return Result.ok(service.bindings(userId));
    }

    @PostMapping("/bindings")
    @Operation(summary = "绑定用途到端点（endpointId 传空=解绑跟随默认）")
    public Result<Void> bind(@CurrentUserId long userId, @RequestBody BindRequest req) {
        String err = service.bind(userId, req.purpose(), req.endpointId());
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    @PostMapping("/models")
    @Operation(summary = "拉取端点可用模型清单（id 非空且 apiKey 传空=用该端点已存 key）")
    public Result<List<String>> listModels(@CurrentUserId long userId,
                                           @RequestParam(required = false) Long id,
                                           @RequestBody SaveRequest req) {
        LlmEndpointService.ListModelsResult r = service.listModels(userId, id, req.toReq());
        return r.error() == null ? Result.ok(r.models()) : Result.fail(r.error());
    }

    /** 测连通，勾了联网搜索的再测搜索；保存时什么情况下探见 LlmEndpointService */
    @PostMapping("/test")
    @Operation(summary = "测试端点连通性（id 非空且 apiKey 传空=用该端点已存 key）")
    public Result<Void> test(@CurrentUserId long userId,
                             @RequestParam(required = false) Long id,
                             @RequestBody SaveRequest req) {
        String err = service.testConnection(userId, id, req.toReq());
        return err == null ? Result.ok(null) : Result.fail(err);
    }
}
