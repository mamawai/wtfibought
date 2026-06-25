package com.mawai.wiibquant.agent.strategy.core;

import com.mawai.wiibcommon.market.KlineBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowedMarketViewTest {

    private static final long M5 = 5 * 60_000L;
    private static final long H1 = 60 * 60_000L;

    private static KlineBar bar5m(int i, double close) {
        long open = i * M5;
        return new KlineBar(open, open + M5 - 1,
                BigDecimal.valueOf(close), BigDecimal.valueOf(close + 1),
                BigDecimal.valueOf(close - 1), BigDecimal.valueOf(close), BigDecimal.ONE);
    }

    @Test
    void aggregatesToH1AndDropsIncompleteLastBucket() {
        WindowedMarketView view = new WindowedMarketView(1000);
        // 喂 12 根（一个完整 1h）+ 3 根（不完整桶）
        for (int i = 0; i < 15; i++) view.append(bar5m(i, 100 + i));
        List<KlineBar> h1 = view.closedBars(H1, 10);
        assertEquals(1, h1.size(), "不完整的第二个1h桶必须被丢弃");
        assertEquals(0, h1.getFirst().openTime());
        assertEquals(H1 - 1, h1.getFirst().closeTime());
        assertEquals(0, h1.getFirst().close().compareTo(BigDecimal.valueOf(111)), "1h close=桶内末根close");
    }

    @Test
    void dedupesByOpenTimeAndTrimsWindow() {
        WindowedMarketView view = new WindowedMarketView(10);
        for (int i = 0; i < 15; i++) view.append(bar5m(i, 100 + i));
        view.append(bar5m(14, 999)); // 重复openTime，忽略
        List<KlineBar> base = view.closedBars(StrategyMarketView.BASE_INTERVAL_MILLIS, 100);
        assertEquals(10, base.size(), "窗口裁剪到maxBars");
        assertEquals(0, base.getLast().close().compareTo(BigDecimal.valueOf(114)), "重复bar被忽略");
    }

    @Test
    void detectsMissingBase5mBarAndRecoversAfterTrim() {
        WindowedMarketView view = new WindowedMarketView(2);
        view.append(bar5m(0, 100));
        view.append(bar5m(2, 102));

        assertTrue(view.hasBaseGap(), "跳过一根5m后当前窗口必须标记断档");
        assertTrue(view.baseGapDescription().orElse("").contains("5m K线缺口"));

        view.append(bar5m(3, 103));

        assertFalse(view.hasBaseGap(), "断档裁出滚动窗口后可以恢复");
        assertTrue(view.baseGapDescription().isEmpty());
    }

    @Test
    void detectsMalformedBase5mBarCloseTime() {
        KlineBar malformed = new KlineBar(0, M5,
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE);

        assertTrue(WindowedMarketView.firstBaseGapDescription(List.of(malformed))
                .orElse("")
                .contains("5m K线时间不合法"));
    }
}
