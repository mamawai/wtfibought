package com.mawai.wiibquant.external.deribit;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 币种路由与 USDC 板块筛选：SOL/XRP 的期权挂在 currency=USDC 下，拿 currency=SOL 去查只会得到空簿。
 */
class DeribitClientTest {

    @Test
    void routesCoinsToTheirOptionBook() {
        assertThat(DeribitClient.bookCurrency("BTC")).isEqualTo("BTC");
        assertThat(DeribitClient.bookCurrency("eth")).isEqualTo("ETH");
        assertThat(DeribitClient.bookCurrency("SOL")).isEqualTo("USDC");
        assertThat(DeribitClient.bookCurrency("XRP")).isEqualTo("USDC");
        assertThat(DeribitClient.bookCurrency("DOGE")).isNull();
        assertThat(DeribitClient.bookCurrency("BNB")).isNull();
        assertThat(DeribitClient.bookCurrency(null)).isNull();

        assertThat(DeribitClient.supports("SOL")).isTrue();
        assertThat(DeribitClient.supports("DOGE")).isFalse();
    }

    @Test
    void onlyInverseCoinsHaveDvol() {
        assertThat(DeribitClient.hasDvol("BTC")).isTrue();
        assertThat(DeribitClient.hasDvol("ETH")).isTrue();
        assertThat(DeribitClient.hasDvol("SOL")).isFalse();
        assertThat(DeribitClient.hasDvol("XRP")).isFalse();
    }

    @Test
    void filterKeepsOnlyTheCoinFromMixedUsdcBook() {
        String raw = """
                {"result":[
                  {"instrument_name":"SOL_USDC-25SEP26-118-C","mark_iv":74.46},
                  {"instrument_name":"XRP_USDC-25SEP26-1d52-C","mark_iv":79.89},
                  {"instrument_name":"HYPE_USDC-25SEP26-40-C","mark_iv":90.1},
                  {"instrument_name":"SOL_USDC-26SEP26-120-P","mark_iv":75.2}
                ]}""";

        List<String> names = new ArrayList<>();
        for (JsonNode item : MAPPER.readValue(DeribitClient.filterBook(raw, "SOL_USDC-"), ObjectNode.class).path("result")) {
            names.add(item.path("instrument_name").asString());
        }

        assertThat(names).containsExactly("SOL_USDC-25SEP26-118-C", "SOL_USDC-26SEP26-120-P");
    }
}
