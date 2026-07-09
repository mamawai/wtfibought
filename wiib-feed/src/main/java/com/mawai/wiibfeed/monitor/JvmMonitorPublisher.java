package com.mawai.wiibfeed.monitor;

import com.alibaba.fastjson2.JSON;
import com.mawai.wiibcommon.broadcast.MarketBroadcaster;
import com.mawai.wiibcommon.monitor.JvmMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * feed 进程 JVM 监控发布：定时采样本进程 JVM 发 Redis，sim 中继到 /topic/monitor/feed。
 * <p>JVM 指标是连续量、无"事件"，只能定时采样（与事件驱动的 WS 流健康本质不同）。feed 原无 @EnableScheduling，此处开启。
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class JvmMonitorPublisher {

    private final MarketBroadcaster broadcaster;

    @Scheduled(fixedRate = 5000)
    public void publish() {
        try {
            broadcaster.broadcastMonitor("feed", JSON.toJSONString(JvmMetrics.collectLite()));
        } catch (Exception e) {
            log.warn("[JVM监控] feed 发布失败: {}", e.getMessage());
        }
    }
}
