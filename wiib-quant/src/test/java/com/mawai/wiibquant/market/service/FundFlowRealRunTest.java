package com.mawai.wiibquant.market.service;

import com.mawai.wiibquant.external.defillama.DefiLlamaClient;
import com.mawai.wiibquant.external.tftc.TftcClient;
import com.mawai.wiibquant.market.service.FundFlowService.EtfFlows;
import com.mawai.wiibquant.market.service.FundFlowService.Stablecoins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真打 TFTC 和 DefiLlama 一次：ETF 要有最近 10 天，稳定币要有 USDT 和 USDC。
 * <pre>
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-quant -am -DskipTests=false -Dtest=FundFlowRealRunTest \
 *   -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class FundFlowRealRunTest {

    private static final Logger log = LoggerFactory.getLogger(FundFlowRealRunTest.class);

    @Test
    void 两段都拿得到() {
        FundFlowService service = new FundFlowService(new TftcClient(), new DefiLlamaClient());

        EtfFlows etf = service.etfFlows();
        Stablecoins stables = service.stablecoins();

        log.info("ETF through {} assets={}M cumulative={}M latest={} perEtf={}", etf.updatedThrough(),
                etf.totalNetAssetsMusd(), etf.cumulativeNetFlowMusd(), etf.daily().getLast(), etf.latestPerEtfMusd());
        log.info("stablecoins total={} usdt={} usdc={}", stables.total(), stables.usdt(), stables.usdc());
        assertThat(etf.daily()).hasSize(FundFlowService.ETF_DAYS);
        assertThat(etf.totalNetAssetsMusd()).isPositive();
        assertThat(stables.usdt().mcapMusd()).isPositive();
        assertThat(stables.usdc().mcapMusd()).isPositive();
    }
}
