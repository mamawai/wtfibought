package com.mawai.wiibagent.analysis;

import com.mawai.wiibquant.market.domain.KlineClosedEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class VerificationTaskTest {

    private final NarrativeVerificationService narrativeService = mock(NarrativeVerificationService.class);
    private final VerificationTask task = new VerificationTask(narrativeService);

    {
        // @Value 字段纯单测拿不到默认值（boolean 停在 false 把门挡死），手动拨到生产默认 true
        org.springframework.test.util.ReflectionTestUtils.setField(task, "analysisEnabled", true);
    }

    @Test
    void barCloseTriggersNarrativeTrackImmediately() {
        task.onKlineClosed(new KlineClosedEvent(this, "btcusdt", "5m", 123L));

        // 时间一到马上对账：bar 收盘事件即触发叙事轨扫描（异步虚拟线程）
        verify(narrativeService, timeout(1000)).verifyDue();
    }

    @Test
    void non5mAndNonWatchSymbolsIgnored() {
        task.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "1m", 123L));
        task.onKlineClosed(new KlineClosedEvent(this, "SOLUSDT", "5m", 123L)); // 策略篮子币不进本轨

        verifyNoInteractions(narrativeService);
    }
}
