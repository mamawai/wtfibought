package com.mawai.wiibquant.task;

import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibquant.agent.analysis.NarrativeVerificationService;
import com.mawai.wiibquant.agent.analysis.VolVerificationService;
import com.mawai.wiibquant.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibquant.agent.research.ForecastHorizon;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对账调度（vol 数字轨 + 叙事轨）：事件驱动为主——每根 5m bar 收盘立刻扫该 symbol 的到期预测点，
 * "时间一到马上对账"（延迟秒级：刚收盘的 bar 正是对账所需的最后一块数据）；
 * 每小时 cron 只作 WS 断流/漏事件的兜底。两轨服务均幂等，失败下轮自愈。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerificationTask {

    private final VolVerificationService volVerificationService;
    private final NarrativeVerificationService narrativeVerificationService;

    /** 每 symbol 在飞防抖：上一轮没扫完不叠加（幂等，漏了有 cron 兜底）。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    /** 叙事扫描是全局的（不分 symbol），单独防抖免得多 symbol 同刻收盘时重复扫。 */
    private final AtomicBoolean narrativeInFlight = new AtomicBoolean();

    /** 主触发：5m bar 收盘即对账（快照/研判只产于 WATCH_SYMBOLS，其余 symbol 的 bar 不进本轨）。 */
    @EventListener
    public void onKlineClosed(KlineClosedEvent event) {
        if (!"5m".equalsIgnoreCase(event.interval())) {
            return;
        }
        String symbol = normalize(event.symbol());
        if (!QuantConstants.WATCH_SYMBOLS.contains(symbol)) {
            return;
        }
        if (!inFlight.add(symbol)) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                verifySymbol(symbol);
            } finally {
                inFlight.remove(symbol);
            }
        });
    }

    /** 兜底 cron：WS 断流/事件丢失时每小时扫平欠账。 */
    @Scheduled(cron = "0 10 * * * *")
    public void verifyDuePoints() {
        for (String symbol : QuantConstants.WATCH_SYMBOLS) {
            verifySymbol(normalize(symbol));
        }
    }

    private void verifySymbol(String symbol) {
        for (ForecastHorizon horizon : ForecastHorizon.values()) {
            try {
                volVerificationService.verifyDue(symbol, horizon);
            } catch (Exception e) {
                log.warn("[Verify] vol对账异常 symbol={} horizon={} msg={}", symbol, horizon, e.getMessage());
            }
        }
        if (narrativeInFlight.compareAndSet(false, true)) {
            try {
                narrativeVerificationService.verifyDue();
            } catch (Exception e) {
                log.warn("[Verify] 叙事对账异常 msg={}", e.getMessage());
            } finally {
                narrativeInFlight.set(false);
            }
        }
    }

    private static String normalize(String symbol) {
        return symbol == null || symbol.isBlank() ? "" : symbol.trim().toUpperCase();
    }
}
