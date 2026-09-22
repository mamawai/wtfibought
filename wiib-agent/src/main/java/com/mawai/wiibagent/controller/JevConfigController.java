package com.mawai.wiibagent.controller;

import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.entity.UserJevConfig;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibagent.llm.jev.JevConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户 Jev 决策模型配置（AI 页「模型配置」里的 Jev 卡片）：一人一份，读 / 保存 / 删除 / 探测。
 * key 明文任何情况下不出服务端。
 */
@Tag(name = "用户 Jev 配置")
@RestController
@RequestMapping("/api/ai/jev")
@RequiredArgsConstructor
public class JevConfigController {

    private final JevConfigService service;

    /** key 只回尾 4 位 */
    public record JevView(String baseUrl, String model, String apiKeyTail) {
    }

    /** baseUrl/model 留空走默认；apiKey 已有配置时留空=不换 */
    public record SaveRequest(String baseUrl, String model, String apiKey) {
        JevConfigService.SaveReq toReq() {
            return new JevConfigService.SaveReq(baseUrl, model, apiKey);
        }
    }

    @GetMapping
    @Operation(summary = "我的 Jev 配置（未配置返回 null）")
    public Result<JevView> get(@CurrentUserId long userId) {
        UserJevConfig c = service.of(userId);
        return Result.ok(c == null ? null : new JevView(c.getBaseUrl(), c.getModel(), service.keyTail(c)));
    }

    @PutMapping
    @Operation(summary = "保存 Jev 配置（一人一份，覆盖；apiKey 传空=不换）")
    public Result<Void> save(@CurrentUserId long userId, @RequestBody SaveRequest req) {
        String err = service.save(userId, req.toReq());
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    @DeleteMapping
    @Operation(summary = "删除 Jev 配置（路由回落轻模型）")
    public Result<Void> delete(@CurrentUserId long userId) {
        String err = service.delete(userId);
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    @PostMapping("/test")
    @Operation(summary = "测试 Jev 连通性（apiKey 传空=用已存 key）")
    public Result<Void> test(@CurrentUserId long userId, @RequestBody SaveRequest req) {
        String err = service.test(userId, req.toReq());
        return err == null ? Result.ok(null) : Result.fail(err);
    }
}
