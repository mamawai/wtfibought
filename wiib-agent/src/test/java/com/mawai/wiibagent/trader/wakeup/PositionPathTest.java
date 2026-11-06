package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibagent.learning.CaseFileBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 窗口怎么定：起点按最近 OK 轮和开仓里晚的那个对齐到 5m 根，边界所在那根不算；极值本身在 CaseFileBuilderTest 里测。
 */
class PositionPathTest {

    /** 整点对齐的起点 */
    private static final long T0 = 1791540000000L;
    private static final long M5 = 300_000L;

    private final CaseFileBuilder caseFiles = mock(CaseFileBuilder.class);
    private final PositionPath positionPath = new PositionPath(caseFiles);

    /** 例行唤醒：上次 OK 在 T0，这次 T0+15m，窗口正好是中间收盘的三根；结果原样透传 */
    @Test
    void routineWindowSinceLastOk() {
        CaseFileBuilder.MarketWindow w = new CaseFileBuilder.MarketWindow("BTCUSDT", T0, T0 + 3 * M5,
                new BigDecimal("100190"), new BigDecimal("100420"), T0 + M5, new BigDecimal("99610"), T0 + 2 * M5,
                new BigDecimal("99880"));
        when(caseFiles.market("BTCUSDT", T0, T0 + 3 * M5)).thenReturn(w);

        assertThat(positionPath.build(position(T0 - 3600_000L), T0, T0 + 3 * M5)).isSameAs(w);
    }

    /** 仓位是上次 OK 之后才开的：从开仓所在那根算 */
    @Test
    void openedAfterLastOkStartsFromEntryBar() {
        positionPath.build(position(T0 + M5 + 60_000L), T0, T0 + 3 * M5);

        verify(caseFiles).market("BTCUSDT", T0 + M5, T0 + 3 * M5);
    }

    /** 本局还没有 OK 轮次：从开仓所在那根算 */
    @Test
    void noOkRoundStartsFromEntryBar() {
        positionPath.build(position(T0 + 2 * M5 + 30_000L), null, T0 + 3 * M5);

        verify(caseFiles).market("BTCUSDT", T0 + 2 * M5, T0 + 3 * M5);
    }

    /** 手动唤醒落在根中间：边界向下取整，那根不算 */
    @Test
    void manualWakeInsideBarFloorsBoundary() {
        assertThat(positionPath.build(position(T0 - 3600_000L), T0 + 3 * M5, T0 + 3 * M5 + 120_000L)).isNull();

        verify(caseFiles).market("BTCUSDT", T0 + 3 * M5, T0 + 3 * M5);
    }

    /** 各币摘要的窗口：有 OK 轮从它所在那根起；没有就是边界前一个心跳周期 */
    @Test
    void symbolWindowSinceLastOkOrOneInterval() {
        long hour = 3_600_000L;
        positionPath.symbol("BTCUSDT", T0 + 2 * M5 + 30_000L, T0 + 3 * M5, hour);
        verify(caseFiles).market("BTCUSDT", T0 + 2 * M5, T0 + 3 * M5);

        positionPath.symbol("ETHUSDT", null, T0 + 3 * M5 + 120_000L, hour);
        verify(caseFiles).market("ETHUSDT", T0 + 3 * M5 - hour, T0 + 3 * M5);
    }

    /** 刚收盘那根心跳周期：边界对齐到周期，取它前面一整个周期 */
    @Test
    void lastClosedBarIsThePreviousInterval() {
        long hour = 3_600_000L;
        positionPath.lastClosedBar("BTCUSDT", T0 + hour + 7 * 60_000L, hour);

        verify(caseFiles).market("BTCUSDT", T0, T0 + hour);
    }

    private static FuturesPositionDTO position(long openedMs) {
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setId(349L);
        p.setSymbol("BTCUSDT");
        p.setSide("LONG");
        p.setCreatedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(openedMs), ZoneId.systemDefault()));
        return p;
    }
}
