package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.monitor.JvmMetrics;
import com.mawai.wiibcommon.util.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 进程监控（sim）。JVM 采集下沉到共享 {@link JvmMetrics}，三进程共用。
 * <p>sim 是 WS 网关，自身 JVM 直推 /topic/monitor/sim；feed/quant 无网关，发 Redis 由 WsBroadcastRelay
 * 中继到 /topic/monitor/{进程}。前端轮播统一订 /topic/monitor/{sim,feed,quant}。
 */
@RestController
@RequestMapping("/monitor")
@EnableScheduling
@Tag(name = "监控")
public class MonitorController {

    private final SimpMessagingTemplate ws;

    public MonitorController(SimpMessagingTemplate ws) {
        this.ws = ws;
    }

    @Scheduled(fixedRate = 5000)
    public void pushMonitor() {
        ws.convertAndSend("/topic/monitor/sim", JvmMetrics.collectLite());
    }

    @GetMapping("/detail")
    @Operation(summary = "详细监控（含classLoading和内存池）")
    public Result<Map<String, Object>> detail() {
        return Result.ok(JvmMetrics.collect());
    }
}
