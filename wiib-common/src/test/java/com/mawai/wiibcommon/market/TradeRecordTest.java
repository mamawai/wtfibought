package com.mawai.wiibcommon.market;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 成交 Stream 一条记录 → 价、成交时间、feed 收到的时刻；老 feed 写的没有收到时刻，按成交时间算 */
class TradeRecordTest {

    @Test
    void 新记录带收到时刻() {
        TradeRecord r = TradeRecord.of(Map.of("ts", "100", "p", "37000.10", "q", "1", "bm", "0", "rt", "150"));

        assertThat(r.price()).isEqualByComparingTo("37000.10");
        assertThat(r.ts()).isEqualTo(100L);
        assertThat(r.receivedAt()).isEqualTo(150L);
    }

    @Test
    void 老feed写的没有rt_按成交时间算() {
        // 老 feed 价格用 Double.toString 写的，小价格是科学计数法
        TradeRecord r = TradeRecord.of(Map.of("ts", "100", "p", "1.234E-5", "q", "1", "bm", "0"));

        assertThat(r.price()).isEqualByComparingTo("0.00001234");
        assertThat(r.receivedAt()).isEqualTo(100L);
    }
}
