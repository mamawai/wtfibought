package com.mawai.wiibagent.toolkit;

import com.mawai.wiibquant.market.service.CrossAssetService;
import com.mawai.wiibquant.market.service.CrossAssetService.Quote;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CrossAssetToolkitTest {

    private final CrossAssetService service = mock(CrossAssetService.class);
    private final CrossAssetToolkit toolkit = new CrossAssetToolkit(service);

    @Test
    void 每个品种一行_带名字涨跌和北京时间() {
        // 2025-09-04 15:33:20 UTC = 北京 23:33
        when(service.snapshot()).thenReturn(List.of(new Quote("^GSPC", "S&P 500",
                new BigDecimal("7720.39"), new BigDecimal("-0.30"), new BigDecimal("0.74"), 1_757_000_000_000L)));

        JsonNode out = MAPPER.readTree(toolkit.crossAssetSnapshot());

        assertThat(out.get("available").asBoolean()).isTrue();
        assertThat(out.get("now").asString()).isNotBlank();
        JsonNode q = out.get("quotes").get(0);
        assertThat(q.get("name").asString()).isEqualTo("S&P 500");
        assertThat(q.get("changePct1d").asDouble()).isEqualTo(-0.30);
        assertThat(q.get("changePct5d").asDouble()).isEqualTo(0.74);
        assertThat(q.get("time").asString()).isEqualTo("2025-09-04 23:33");
    }

    @Test
    void 一个都没拿到回不可用() {
        when(service.snapshot()).thenReturn(List.of());

        assertThat(toolkit.crossAssetSnapshot()).contains("\"available\":false");
    }
}
