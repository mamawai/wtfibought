package com.mawai.wiibquant.task;

import com.mawai.wiibquant.agent.analysis.NarrativeVerificationService;
import com.mawai.wiibquant.agent.analysis.VolVerificationService;
import com.mawai.wiibquant.agent.quant.domain.KlineClosedEvent;
import com.mawai.wiibquant.agent.research.ForecastHorizon;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class VerificationTaskTest {

    private final VolVerificationService volService = mock(VolVerificationService.class);
    private final NarrativeVerificationService narrativeService = mock(NarrativeVerificationService.class);
    private final VerificationTask task = new VerificationTask(volService, narrativeService);

    @Test
    void barCloseTriggersBothTracksImmediately() {
        task.onKlineClosed(new KlineClosedEvent(this, "btcusdt", "5m", 123L));

        // 时间一到马上对账：bar 收盘事件即触发两轨扫描（异步虚拟线程）
        for (ForecastHorizon h : ForecastHorizon.values()) {
            verify(volService, timeout(1000)).verifyDue("BTCUSDT", h);
        }
        verify(narrativeService, timeout(1000)).verifyDue();
    }

    @Test
    void non5mAndNonWatchSymbolsIgnored() {
        task.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "1m", 123L));
        task.onKlineClosed(new KlineClosedEvent(this, "SOLUSDT", "5m", 123L)); // 策略篮子币不进本轨

        verifyNoInteractions(volService, narrativeService);
    }
}
