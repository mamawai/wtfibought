package com.mawai.wiibfeed.book;

import com.mawai.wiibfeed.book.OrderBook.Levels;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 盘口报文解析：价乘 1e8 取整，一侧数组解成等长的价、量 */
class DepthParseTest {

    private static long units(String s) {
        return DepthParse.units(s, 0, s.length());
    }

    @Test
    void 价乘1e8取整() {
        assertThat(units("64599.20")).isEqualTo(6_459_920_000_000L);
        assertThat(units("7")).isEqualTo(700_000_000L);
        assertThat(units("0.00001234")).isEqualTo(1234L);
        assertThat(units("0.123456789")).isEqualTo(12_345_678L);
    }

    @Test
    void 一侧数组解成价和量_空数组和没这个键都是空() {
        String raw = "{\"s\":\"BTCUSDT\",\"b\":[[\"1000.00\",\"16.628\"],[\"6463.80\",\"0.000\"]],\"a\":[]}";

        Levels b = DepthParse.levels(raw, "\"b\":[");
        assertThat(b.px()).containsExactly(100_000_000_000L, 646_380_000_000L);
        assertThat(b.qty()).containsExactly(16.628, 0.0);
        assertThat(DepthParse.levels(raw, "\"a\":[").size()).isZero();
        assertThat(DepthParse.levels("{\"s\":\"BTCUSDT\"}", "\"b\":[").size()).isZero();
    }

    @Test
    void 档数超过初始容量照样解全() {
        StringBuilder raw = new StringBuilder("{\"b\":[");
        for (int i = 1; i <= 100; i++) raw.append(i > 1 ? "," : "").append("[\"").append(i).append(".5\",\"").append(i).append("\"]");
        raw.append("]}");

        Levels b = DepthParse.levels(raw.toString(), "\"b\":[");

        assertThat(b.size()).isEqualTo(100);
        assertThat(b.px()[99]).isEqualTo(10_050_000_000L);
        assertThat(b.qty()[99]).isEqualTo(100.0);
    }
}
