package com.mawai.wiibfeed;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

/** CLOB book 快照的取最优价：买盘最高、卖盘最低，与档位顺序无关；空档位回 null */
class PolymarketBookSnapshotTest {

    @Test
    void 买盘取最高卖盘取最低不依赖排序() {
        JsonNode book = MAPPER.readTree("""
                {"event_type":"book","asset_id":"up",
                 "bids":[{"price":"0.40","size":"10"},{"price":"0.55","size":"3"},{"price":"0.52","size":"8"}],
                 "asks":[{"price":"0.60","size":"5"},{"price":"0.56","size":"2"},{"price":"0.70","size":"9"}]}
                """);
        assertThat(PolymarketWsClient.bestPrice(book.path("bids"), true)).isEqualByComparingTo(new BigDecimal("0.55"));
        assertThat(PolymarketWsClient.bestPrice(book.path("asks"), false)).isEqualByComparingTo(new BigDecimal("0.56"));
    }

    @Test
    void 空档位回null() {
        JsonNode book = MAPPER.readTree("""
                {"event_type":"book","asset_id":"up","bids":[]}
                """);
        assertThat(PolymarketWsClient.bestPrice(book.path("bids"), true)).isNull();
    }
}
