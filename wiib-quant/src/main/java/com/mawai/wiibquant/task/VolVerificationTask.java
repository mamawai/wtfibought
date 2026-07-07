package com.mawai.wiibquant.task;

import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibquant.agent.analysis.VolVerificationService;
import com.mawai.wiibquant.agent.research.ForecastHorizon;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** vol 验证定时任务（P3）：每小时扫一次到期预测点。幂等可重跑，失败下轮自愈。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VolVerificationTask {

    private final VolVerificationService verificationService;

    @Scheduled(cron = "0 10 * * * *")
    public void verifyDuePoints() {
        for (String symbol : QuantConstants.WATCH_SYMBOLS) {
            for (ForecastHorizon horizon : ForecastHorizon.values()) {
                try {
                    verificationService.verifyDue(symbol, horizon);
                } catch (Exception e) {
                    log.warn("[VolVerify] 验证异常 symbol={} horizon={} msg={}", symbol, horizon, e.getMessage());
                }
            }
        }
    }
}
