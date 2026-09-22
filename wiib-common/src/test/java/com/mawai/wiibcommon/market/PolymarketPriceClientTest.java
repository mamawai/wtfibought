package com.mawai.wiibcommon.market;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 取价地址必须带 TWAP 参数：不带回的是边界时刻现价，结算会判反 */
class PolymarketPriceClientTest {

    @Test
    void 地址带窗口起止和TWAP参数() {
        String uri = PolymarketPriceClient.uri(1_790_016_000L).toString();
        assertThat(uri).contains("variant=fiveminute")
                .contains("eventStartTime=2026-09-21T18:40:00Z")
                .contains("endDate=2026-09-21T18:45:00Z")
                .contains("twapEnabled=true")
                .contains("twapLookbackSeconds=60");
    }
}
