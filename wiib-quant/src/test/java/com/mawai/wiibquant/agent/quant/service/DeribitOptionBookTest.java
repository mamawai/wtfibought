package com.mawai.wiibquant.agent.quant.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deribit 期权簿解析回归测试。
 * 曾因 ddMMMyy(单位数日期解析失败) + 缺 Locale.ENGLISH(中文 JVM 上月份缩写解析抛异常)
 * 导致 IV 采集每小时静默失败，此处锁死这两个坑。
 */
class DeribitOptionBookTest {

    private static String row(String instrument, double markIv, double underlying) {
        return "{\"instrument_name\":\"" + instrument + "\",\"mark_iv\":" + markIv
                + ",\"underlying_price\":" + underlying + "}";
    }

    private static String book(String... rows) {
        return "{\"result\":[" + String.join(",", rows) + "]}";
    }

    @Test
    void parsesSingleDigitDayUnderChineseLocale() {
        Locale saved = Locale.getDefault();
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
        try {
            DeribitOptionBook parsed = DeribitOptionBook.parse(book(
                    row("BTC-3JAN26-90000-C", 55.2, 88000)));
            assertThat(parsed.isEmpty()).isFalse();
            assertThat(parsed.byExpiry()).containsOnlyKeys(LocalDate.of(2026, 1, 3));
            assertThat(parsed.underlyingPrice()).isEqualTo(88000);
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void ordersExpiriesByDateNotByString() {
        // 字符串序会把 "27FEB26" 排在 "3JAN26" 前面，日期序必须反过来
        DeribitOptionBook parsed = DeribitOptionBook.parse(book(
                row("BTC-27FEB26-90000-C", 60, 88000),
                row("BTC-3JAN26-90000-C", 55, 88000)));
        assertThat(parsed.byExpiry().firstKey()).isEqualTo(LocalDate.of(2026, 1, 3));
    }

    @Test
    void atmCallIvPicksNearestStrikeCallIgnoringPuts() {
        List<DeribitOptionBook.Quote> quotes = List.of(
                new DeribitOptionBook.Quote(80000, true, 50),
                new DeribitOptionBook.Quote(90000, true, 55),
                new DeribitOptionBook.Quote(90000, false, 99));
        assertThat(DeribitOptionBook.atmCallIv(quotes, 88000)).isEqualTo(55);
        assertThat(DeribitOptionBook.atmCallIv(
                List.of(new DeribitOptionBook.Quote(90000, false, 99)), 88000)).isZero();
    }

    @Test
    void skipsJunkRowsWithoutAbortingWholeBook() {
        DeribitOptionBook parsed = DeribitOptionBook.parse(book(
                row("BTC-PERPETUAL", 0, 88000),          // 非期权 + iv=0
                row("BTC-BADDATE-90000-C", 50, 88000),   // 到期日非法，只跳该行
                row("BTC-3JAN26-XX-C", 50, 88000),       // strike 非法，只跳该行
                row("BTC-3JAN26-90000-C", 55, 88000)));
        assertThat(parsed.byExpiry().get(LocalDate.of(2026, 1, 3)))
                .containsExactly(new DeribitOptionBook.Quote(90000, true, 55));
    }

    @Test
    void emptyOrBlankJsonYieldsEmptyBook() {
        assertThat(DeribitOptionBook.parse(null).isEmpty()).isTrue();
        assertThat(DeribitOptionBook.parse(" ").isEmpty()).isTrue();
        assertThat(DeribitOptionBook.parse("{\"result\":[]}").isEmpty()).isTrue();
    }
}
