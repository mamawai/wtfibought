package com.mawai.wiibfeed.health;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * feed 内部端点：WS 流健康快照 + 手动重试。
 * <p>/internal/** 由共享 {@code InternalApiFilter} 校验 X-Internal-Token，不直接暴露给浏览器——
 * 前端只经 sim 的 FeedInternalClient 转发。实时状态推送走 Redis→sim 中继，本端点只管快照拉取与重试命令。
 */
@RestController
@RequestMapping("/internal/streams")
@RequiredArgsConstructor
public class FeedStreamController {

    private final WsConnectionRegistry registry;

    /** 全量流健康快照（前端进面板时拉一次，之后靠 WS 事件增量刷新）。 */
    @GetMapping
    public List<StreamHealth> list() {
        return registry.snapshot();
    }

    /** 手动重试指定流；ok=false 表示流名未命中。 */
    @PostMapping("/{name}/retry")
    public Map<String, Object> retry(@PathVariable String name) {
        return Map.of("ok", registry.retry(name), "name", name);
    }
}
