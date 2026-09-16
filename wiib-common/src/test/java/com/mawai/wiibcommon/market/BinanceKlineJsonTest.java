package com.mawai.wiibcommon.market;

import com.mawai.wiibcommon.config.BinanceProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Binance K 线原始数组的几种解析：数字是字符串、时间是整数 */
class BinanceKlineJsonTest {

    /** 官方文档示例的两根：12 列，价/量是字符串 */
    private static final String RAW = "["
            + "[1499040000000,\"0.01634790\",\"0.80000000\",\"0.01575800\",\"0.01577100\",\"148976.11427815\",1499644799999,\"2434.19055334\",308,\"1756.87402397\",\"28.46694368\",\"0\"],"
            + "[1499644800000,\"0.01577100\",\"0.90000000\",\"0.01500000\",\"0.01600000\",\"100.00000000\",1500249599999,\"1.50000000\",12,\"50.00000000\",\"0.75000000\",\"0\"]"
            + "]";

    private static BinanceRestClient clientReturning(String body) {
        BinanceProperties p = new BinanceProperties();
        p.setRestBaseUrl("http://localhost:1");
        p.setFuturesRestBaseUrl("http://localhost:1");
        return new BinanceRestClient(p) {
            @Override
            protected String get(String uri) {
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
    void 区间高低取各根最高最低() {
        BigDecimal[] lowHigh = clientReturning(RAW).getRecentHighLow("BTCUSDT");
        assertThat(lowHigh).containsExactly(new BigDecimal("0.01500000"), new BigDecimal("0.90000000"));
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
