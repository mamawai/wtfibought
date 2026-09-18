package com.mawai.wiibcommon.market;

import com.mawai.wiibcommon.config.BinanceProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Binance K 线原始数组的几种解析：数字是字符串、时间是整数；以及补漏用的区间取值 */
class BinanceKlineJsonTest {

    /** 官方文档示例的两根：12 列，价/量是字符串 */
    private static final String RAW = "["
            + "[1499040000000,\"0.01634790\",\"0.80000000\",\"0.01575800\",\"0.01577100\",\"148976.11427815\",1499644799999,\"2434.19055334\",308,\"1756.87402397\",\"28.46694368\",\"0\"],"
            + "[1499644800000,\"0.01577100\",\"0.90000000\",\"0.01500000\",\"0.01600000\",\"100.00000000\",1500249599999,\"1.50000000\",12,\"50.00000000\",\"0.75000000\",\"0\"]"
            + "]";

    private static BinanceRestClient clientReturning(String body) {
        return clientReturning(body, new AtomicReference<>());
    }

    /** sentUri 接最后一次发出去的 uri */
    private static BinanceRestClient clientReturning(String body, AtomicReference<String> sentUri) {
        BinanceProperties p = new BinanceProperties();
        p.setRestBaseUrl("http://localhost:1");
        p.setFuturesRestBaseUrl("http://localhost:1");
        return new BinanceRestClient(p) {
            @Override
            protected String get(String uri) {
                sentUri.set(uri);
                return body;
            }
        };
    }

    @Test
    void 精简K线只留前8列且逐字不变() {
        String slim = clientReturning(RAW).getKlinesLight("BTCUSDT", "1h", 2, null);
        assertThat(slim).isEqualTo("["
                + "[1499040000000,\"0.01634790\",\"0.80000000\",\"0.01575800\",\"0.01577100\",\"148976.11427815\",1499644799999,\"2434.19055334\"],"
                + "[1499644800000,\"0.01577100\",\"0.90000000\",\"0.01500000\",\"0.01600000\",\"100.00000000\",1500249599999,\"1.50000000\"]"
                + "]");
    }

    @Test
    void 回包不是数组时精简失败退回原文() {
        String err = "{\"code\":-1121,\"msg\":\"Invalid symbol.\"}";
        assertThat(clientReturning(err).getKlinesLight("XXX", "1h", 2, null)).isEqualTo(err);
    }

    @Test
    void 补漏K线请求带startTime与endTime且startTime按分钟取整() {
        AtomicReference<String> sent = new AtomicReference<>();
        long to = 1700000123456L;
        long from = to - 3 * 60_000L - 7_777L;   // 故意不落在整分上

        clientReturning(RAW, sent).futuresBars1m("BTCUSDT", from, to);

        assertThat(sent.get()).contains("/fapi/v1/klines")
                .contains("interval=1m")
                .contains("startTime=" + from / 60_000L * 60_000L)
                .contains("endTime=" + to)
                .contains("limit=");
    }

    /** 第一根宽（10~100）、第二根窄（40~50），能看出到底算了哪几根 */
    private static final List<KlineBar> TWO_BARS = List.of(
            bar(0L, 59_999L, "100", "10"),
            bar(60_000L, 119_999L, "50", "40"));

    private static KlineBar bar(long openTime, long closeTime, String high, String low) {
        return new KlineBar(openTime, closeTime, new BigDecimal(low), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(high), BigDecimal.ONE);
    }

    /** since=0 取全区间 */
    @Test
    void 区间高低取各根最高最低() {
        assertThat(KlineBar.lowHighAfter(TWO_BARS, 0))
                .containsExactly(new BigDecimal("10"), new BigDecimal("100"));
    }

    /** since 落在两根之间：只算后一根，前一根的极值不许掺进来 */
    @Test
    void 区间高低只算创建时间之后的K线() {
        assertThat(KlineBar.lowHighAfter(TWO_BARS, 59_999L))
                .containsExactly(new BigDecimal("40"), new BigDecimal("50"));
    }

    /** since 晚于全部 K 线 → null，调用方据此跳过（挂单晚于整段行情） */
    @Test
    void 区间高低无可用K线返回null() {
        assertThat(KlineBar.lowHighAfter(TWO_BARS, 119_999L)).isNull();
    }

    @Test
    void 落库解析时间与价量() {
        List<KlineBar> bars = KlineHistoryStore.parseRawFuturesKlines(RAW);
        assertThat(bars).hasSize(2);
        assertThat(bars.getFirst()).isEqualTo(new KlineBar(1499040000000L, 1499644799999L,
                new BigDecimal("0.01634790"), new BigDecimal("0.80000000"), new BigDecimal("0.01575800"),
                new BigDecimal("0.01577100"), new BigDecimal("148976.11427815")));
    }
}
