package com.mawai.wiibquant.market.service;

import com.mawai.wiibquant.external.yahoo.YahooFinanceClient;
import com.mawai.wiibquant.market.service.CrossAssetService.Quote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真打 Yahoo 一次：九个品种都要回来，价格为正、报价时刻是最近 7 天内的。
 * Yahoo 是非官方接口，它改了形状或封了 UA 这里第一时间能看出来。
 * <pre>
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-quant -am -DskipTests=false -Dtest=CrossAssetRealRunTest \
 *   -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class CrossAssetRealRunTest {

    private static final Logger log = LoggerFactory.getLogger(CrossAssetRealRunTest.class);

    @Test
    void 九个品种都拿得到() {
        List<Quote> quotes = new CrossAssetService(new YahooFinanceClient()).snapshot();

        quotes.forEach(q -> log.info("{} {} price={} 1d={}% 5d={}% at={}",
                q.symbol(), q.name(), q.price(), q.changePct1d(), q.changePct5d(), q.timeMs()));
        assertThat(quotes).hasSize(CrossAssetService.INSTRUMENTS.size());
        long weekAgo = System.currentTimeMillis() - 7 * 86_400_000L;
        assertThat(quotes).allSatisfy(q -> {
            assertThat(q.price()).isPositive();
            assertThat(q.timeMs()).isGreaterThan(weekAgo);
        });
    }
}
