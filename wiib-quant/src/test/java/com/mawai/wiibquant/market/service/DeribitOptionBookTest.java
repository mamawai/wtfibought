package com.mawai.wiibquant.market.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
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
    void parsesLinearUsdcOptionsWithDecimalStrike() {
        // USDC 线性期权：标的名带 _USDC，小数行权价用 d 代小数点（XRP 1d52 = 1.52）
        DeribitOptionBook xrp = DeribitOptionBook.parse(book(
                row("XRP_USDC-25SEP26-1d52-C", 79.89, 1.5167),
                row("XRP_USDC-25SEP26-1d5-P", 79.24, 1.5167)));
        assertThat(xrp.byExpiry().get(LocalDate.of(2026, 9, 25))).containsExactly(
                new DeribitOptionBook.Quote(1.52, true, 79.89),
                new DeribitOptionBook.Quote(1.5, false, 79.24));

        DeribitOptionBook sol = DeribitOptionBook.parse(book(
                row("SOL_USDC-25SEP26-118-C", 74.46, 117.83)));
        assertThat(sol.byExpiry().get(LocalDate.of(2026, 9, 25)))
                .containsExactly(new DeribitOptionBook.Quote(118, true, 74.46));
        assertThat(sol.underlyingPrice()).isEqualTo(117.83);
    }

    @Test
    void expiringAfterComparesAgainstEightUtcSettlement() {
        DeribitOptionBook parsed = DeribitOptionBook.parse(book(
                row("BTC-22SEP26-85500-C", 32.46, 85693),
                row("BTC-23SEP26-85500-C", 35.31, 85693),
                row("BTC-24SEP26-85500-C", 37.25, 85693)));

        // 22日 08:00 早于截止，剔；23日 08:00 晚于截止 01:30，留
        assertThat(parsed.expiringAfter(Instant.parse("2026-09-23T01:30:00Z")))
                .containsOnlyKeys(LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 24));
        // 截止 08:01：23日 08:00 差一分钟，剔
        assertThat(parsed.expiringAfter(Instant.parse("2026-09-23T08:01:00Z")))
                .containsOnlyKeys(LocalDate.of(2026, 9, 24));
        // 交割时刻正好等于截止不算晚于，剔
        assertThat(parsed.expiringAfter(Instant.parse("2026-09-23T08:00:00Z")))
                .containsOnlyKeys(LocalDate.of(2026, 9, 24));
    }

    @Test
    void emptyOrBlankJsonYieldsEmptyBook() {
        assertThat(DeribitOptionBook.parse(null).isEmpty()).isTrue();
        assertThat(DeribitOptionBook.parse(" ").isEmpty()).isTrue();
        assertThat(DeribitOptionBook.parse("{\"result\":[]}").isEmpty()).isTrue();
    }
}
