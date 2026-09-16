package com.mawai.wiibagent.monitor;

import com.mawai.wiibcommon.broadcast.MarketBroadcaster;
import com.mawai.wiibcommon.monitor.JvmMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * agent 进程 JVM 监控发布：定时采样本进程 JVM 发 Redis，sim 中继到 /topic/monitor/quant。
 * <p>频道名 quant 是拆进程前的旧称，前端按它订，别改。
 * <p>JVM 指标是连续量、无"事件"，只能定时采样。agent 已开 @EnableScheduling（应用类），此处不再重复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JvmMonitorPublisher {

    private final MarketBroadcaster broadcaster;

    @Scheduled(fixedRate = 5000)
    public void publish() {
        try {
            broadcaster.broadcastMonitor("quant", MAPPER.writeValueAsString(JvmMetrics.collectLite()));
        } catch (Exception e) {
            log.warn("[JVM监控] quant 发布失败: {}", e.getMessage());
        }
    }
}
