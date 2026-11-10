package com.mawai.wiibquant.market.collect;

import com.mawai.wiibcommon.enums.KlineInterval;
import com.mawai.wiibquant.market.domain.FeatureSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * BuildFeaturesBuilder 纯解析函数的行为锚定：这些函数直接吃 Binance/alternative.me 原始 JSON，
 * 上游字段变更/脏数据时靠这里第一时间红。
 */
class BuildFeaturesBuilderTest {

    // ---- buildFeatures: 空输入的输出形状 ----

    @Test
    void emptyRawDataKeepsOutputShape() {
        BuildFeaturesBuilder builder = new BuildFeaturesBuilder(null, KlineInterval.M1);

        Map<String, Object> result = builder.buildFeatures("BTCUSDT", Map.of());

        assertThat(result).containsKeys("feature_snapshot", "indicator_map", "price_change_map", "readings");
        FeatureSnapshot snapshot = (FeatureSnapshot) result.get("feature_snapshot");
        assertThat(snapshot.symbol()).isEqualTo("BTCUSDT");
        assertThat(snapshot.qualityFlags())
                .contains("NO_INDICATORS", "NO_PRICE", "PARTIAL_KLINE_DATA", "NO_AGG_TRADE");
    }

    // ---- calcBasisBps: 期现基差 ----

    @Test
    void basisBpsPositiveWhenFuturesAboveSpot() {
        assertThat(BuildFeaturesBuilder.calcBasisBps(new BigDecimal("10100"), new BigDecimal("10000")))
                .isEqualTo(100.0);
        assertThat(BuildFeaturesBuilder.calcBasisBps(null, new BigDecimal("10000"))).isZero();
        assertThat(BuildFeaturesBuilder.calcBasisBps(new BigDecimal("10100"), BigDecimal.ZERO)).isZero();
    }

    // ---- calcFearGreed / mapFearGreedLabel ----

    @Test
    void fearGreedParsesAlternativeMePayload() {
        assertThat(BuildFeaturesBuilder.calcFearGreed("{\"data\":[{\"value\":73}]}")).isEqualTo(73);
        assertThat(BuildFeaturesBuilder.calcFearGreed("{}")).isEqualTo(-1);
        assertThat(BuildFeaturesBuilder.calcFearGreed(null)).isEqualTo(-1);
    }

    @Test
    void fearGreedLabelBoundaries() {
        assertThat(BuildFeaturesBuilder.mapFearGreedLabel(-1)).isEqualTo("UNKNOWN");
        assertThat(BuildFeaturesBuilder.mapFearGreedLabel(24)).isEqualTo("EXTREME_FEAR");
        assertThat(BuildFeaturesBuilder.mapFearGreedLabel(25)).isEqualTo("FEAR");
        assertThat(BuildFeaturesBuilder.mapFearGreedLabel(44)).isEqualTo("FEAR");
        assertThat(BuildFeaturesBuilder.mapFearGreedLabel(45)).isEqualTo("NEUTRAL");
        assertThat(BuildFeaturesBuilder.mapFearGreedLabel(55)).isEqualTo("NEUTRAL");
        assertThat(BuildFeaturesBuilder.mapFearGreedLabel(56)).isEqualTo("GREED");
        assertThat(BuildFeaturesBuilder.mapFearGreedLabel(74)).isEqualTo("GREED");
        assertThat(BuildFeaturesBuilder.mapFearGreedLabel(75)).isEqualTo("EXTREME_GREED");
    }

    // ---- parseOptionIv: 距交割不足一天的到期日不参与 ----

    /** 一个到期日四档：三档 call + 一档平值 put，平值 call 的 IV 取 atmIv */
    private static String expiryRows(String expiry, double atmIv) {
        return String.join(",",
                "{\"instrument_name\":\"BTC-" + expiry + "-80000-C\",\"mark_iv\":60,\"underlying_price\":85700}",
                "{\"instrument_name\":\"BTC-" + expiry + "-85500-C\",\"mark_iv\":" + atmIv + ",\"underlying_price\":85700}",
                "{\"instrument_name\":\"BTC-" + expiry + "-90000-C\",\"mark_iv\":45,\"underlying_price\":85700}",
                "{\"instrument_name\":\"BTC-" + expiry + "-85500-P\",\"mark_iv\":" + atmIv + ",\"underlying_price\":85700}");
    }

    @Test
    void optionIvSkipsExpiriesSettlingWithinADay() {
        String book = "{\"result\":[" + String.join(",",
                expiryRows("22SEP26", 32), expiryRows("23SEP26", 35), expiryRows("24SEP26", 37)) + "]}";

        // 09-22 01:30 UTC：22日 08:00 交割只剩 6.5 小时，跳过；近月=23日，次近月=24日
        double[] iv = BuildFeaturesBuilder.parseOptionIv(book, new BigDecimal("85700"),
                Instant.parse("2026-09-22T01:30:00Z"));

        assertThat(iv[0]).isEqualTo(35);
        assertThat(iv[2]).isEqualTo(2, offset(1e-9));
    }

    @Test
    void optionIvEmptyWhenEveryExpirySettlesWithinADay() {
        String book = "{\"result\":[" + expiryRows("22SEP26", 32) + "]}";

        assertThat(BuildFeaturesBuilder.parseOptionIv(book, new BigDecimal("85700"),
                Instant.parse("2026-09-22T01:30:00Z"))).containsExactly(0, 0, 0);
    }
}
