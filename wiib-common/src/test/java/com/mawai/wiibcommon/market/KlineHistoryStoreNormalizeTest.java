package com.mawai.wiibcommon.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 空 symbol/interval 必须抛错而不是静默兜底 BTCUSDT/5m——兜底会把坏数据无声写进 BTC 历史。 */
class KlineHistoryStoreNormalizeTest {

    @Test
    void blankSymbolThrowsInsteadOfDefaultingToBtc() {
        assertThrows(IllegalArgumentException.class, () -> KlineHistoryStore.normalizeSymbol(null));
        assertThrows(IllegalArgumentException.class, () -> KlineHistoryStore.normalizeSymbol("  "));
    }

    @Test
    void blankIntervalThrowsInsteadOfDefaultingTo5m() {
        assertThrows(IllegalArgumentException.class, () -> KlineHistoryStore.normalizeInterval(null));
        assertThrows(IllegalArgumentException.class, () -> KlineHistoryStore.normalizeInterval(""));
    }

    @Test
    void normalizesCaseAndWhitespace() {
        assertEquals("BTCUSDT", KlineHistoryStore.normalizeSymbol(" btcusdt "));
        assertEquals("5m", KlineHistoryStore.normalizeInterval(" 5M "));
    }
}
