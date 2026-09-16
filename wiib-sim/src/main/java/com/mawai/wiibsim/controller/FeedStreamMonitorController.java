package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.annotation.RequireAdmin;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.service.FeedInternalClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * feed WS 流健康的前端转发端点（sim 作为唯一前端网关，经 FeedInternalClient 调 feed）。
 * <p>实时状态走 STOMP /topic/feed/streams（feed→Redis→WsBroadcastRelay）；本端点只管进面板拉快照 + 点重试。
 * 整块 {@code @RequireAdmin}：强制重连生产数据流是运维动作，面板仅管理员可见可操作。
 */
@Slf4j
@Tag(name = "feed流健康监控")
@RestController
@RequestMapping("/api/monitor/streams")
@RequiredArgsConstructor
@RequireAdmin
public class FeedStreamMonitorController {

    private final FeedInternalClient feedClient;
    /** 管理页的报错也跟界面语言 */
    private final MessageCatalog messages;

    @GetMapping
    @Operation(summary = "feed WS 流健康快照")
    public Result<Object> streams() {
        try {
            String body = feedClient.getStreams();
            return Result.ok(body == null ? null : MAPPER.readTree(body));
        } catch (Exception e) {
            log.warn("获取 feed 流健康失败: {}", e.getMessage());
            return Result.fail(messages.get("sim.feed.statusFailed", Map.of("reason", String.valueOf(e.getMessage()))));
        }
    }

    @PostMapping("/{name}/retry")
    @Operation(summary = "手动重试指定 WS 流")
    public Result<Object> retry(@PathVariable String name) {
        try {
            String body = feedClient.retry(name);
            return Result.ok(body == null ? null : MAPPER.readTree(body));
        } catch (Exception e) {
            log.warn("重试 feed 流 {} 失败: {}", name, e.getMessage());
            return Result.fail(messages.get("sim.feed.retryFailed", Map.of("reason", String.valueOf(e.getMessage()))));
        }
    }
}
