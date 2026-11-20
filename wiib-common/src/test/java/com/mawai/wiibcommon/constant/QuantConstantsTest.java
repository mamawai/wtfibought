package com.mawai.wiibcommon.constant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link QuantConstants#normalizeSymbol} 的归一与校验。
 * 钉住短简写（BTC/ETH）不许越界：SymbolArgumentResolver 拿它解析 API 入参，{@code ?symbol=BTC} 必须能走通。
 */
class QuantConstantsTest {

    @ParameterizedTest
    @ValueSource(strings = {"BTC", "btc", " btc ", "ETH", "DOGE"})
    void 简写归一成完整交易对(String raw) {
        assertThat(QuantConstants.normalizeSymbol(raw)).isEqualTo(raw.trim().toUpperCase() + "USDT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"BTCUSDT", "btcusdt", " BTCUSDT ", "BTCUSDC"})
    void 带后缀的写法归一后仍是BTCUSDT(String raw) {
        assertThat(QuantConstants.normalizeSymbol(raw)).isEqualTo("BTCUSDT");
    }

    @Test
    void 空值回退BTCUSDT() {
        assertThat(QuantConstants.normalizeSymbol(null)).isEqualTo("BTCUSDT");
        assertThat(QuantConstants.normalizeSymbol("  ")).isEqualTo("BTCUSDT");
        // 光一个后缀，截完就空了，同样回退而不是拼成 "USDT"
        assertThat(QuantConstants.normalizeSymbol("USDT")).isEqualTo("BTCUSDT");
    }

    @Test
    void 白名单外的标的抛异常() {
        assertThatThrownBy(() -> QuantConstants.normalizeSymbol("SOL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅支持");
        assertThatThrownBy(() -> QuantConstants.normalizeSymbol("SOLUSDT"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
