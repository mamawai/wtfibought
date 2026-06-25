package com.mawai.wiibquant.agent.research.kline;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibcommon.market.KlineBar;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KlineHistoryStoreParseTest {

    @Test
    void parsesRawBinanceKlineArrayKeepingOpen() {
        // Binance 合约 klines 原始格式：[openTime, open, high, low, close, volume, closeTime, ...]
        String json = "[[1700000000000,\"100.0\",\"110.0\",\"95.0\",\"108.0\",\"123.45\",1700000059999,"
                + "\"0\",0,\"0\",\"0\",\"0\"]]";

        List<KlineBar> bars = KlineHistoryStore.parseRawFuturesKlines(json);

        assertThat(bars).hasSize(1);
        KlineBar b = bars.get(0);
        assertThat(b.openTime()).isEqualTo(1700000000000L);
        assertThat(b.closeTime()).isEqualTo(1700000059999L);
        assertThat(b.open()).isEqualByComparingTo("100.0");   // ★ open 被保留（parseKlines 会丢）
        assertThat(b.high()).isEqualByComparingTo("110.0");
        assertThat(b.low()).isEqualByComparingTo("95.0");
        assertThat(b.close()).isEqualByComparingTo("108.0");
        assertThat(b.volume()).isEqualByComparingTo("123.45");
    }

    @Test
    void blankJsonReturnsEmpty() {
        assertThat(KlineHistoryStore.parseRawFuturesKlines("")).isEmpty();
        assertThat(KlineHistoryStore.parseRawFuturesKlines(null)).isEmpty();
    }
}
