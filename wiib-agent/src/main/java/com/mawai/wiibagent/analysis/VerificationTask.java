package com.mawai.wiibagent.analysis;

import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibquant.market.domain.KlineClosedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对账调度（叙事轨）：事件驱动为主——每根 5m bar 收盘扫到期研判点，
 * "时间一到马上对账"（延迟秒级：刚收盘的 bar 正是对账所需的最后一块数据）；
 * 每小时 cron 只作 WS 断流/漏事件的兜底。服务幂等，失败下轮自愈。
 * vol 数字轨已随预测管线下线（2026-08：生产验证被 naive 基线打平/反杀）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationTask {

    private final NarrativeVerificationService narrativeVerificationService;

    /** 关掉后自动对账停跑。 */
    @Value("${agent.analysis.enabled:true}")
    private boolean analysisEnabled;

    /** 叙事扫描是全局的（不分 symbol），防抖免得多 symbol 同刻收盘时重复扫。 */
    private final AtomicBoolean narrativeInFlight = new AtomicBoolean();

    /** 主触发：5m bar 收盘即对账。 */
    @EventListener
    public void onKlineClosed(KlineClosedEvent event) {
        if (!analysisEnabled) {
            return;
        }
        if (!"5m".equalsIgnoreCase(event.interval())) {
            return;
        }
        String symbol = normalize(event.symbol());
        if (!QuantConstants.WATCH_SYMBOLS.contains(symbol)) {
            return;
        }
        Thread.startVirtualThread(this::verifyNarrative);
    }

    /** 兜底 cron：WS 断流/事件丢失时每小时扫平欠账。 */
    @Scheduled(cron = "0 10 * * * *")
    public void verifyDuePoints() {
        if (!analysisEnabled) {
            return;
        }
        verifyNarrative();
    }

    private void verifyNarrative() {
        if (!narrativeInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            narrativeVerificationService.verifyDue();
        } catch (Exception e) {
            log.warn("[Verify] 叙事对账异常 msg={}", e.getMessage());
        } finally {
            narrativeInFlight.set(false);
        }
    }

    private static String normalize(String symbol) {
        return symbol == null || symbol.isBlank() ? "" : symbol.trim().toUpperCase();
    }
}
