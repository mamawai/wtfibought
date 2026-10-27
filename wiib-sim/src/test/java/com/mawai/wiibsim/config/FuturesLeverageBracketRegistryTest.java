package com.mawai.wiibsim.config;

import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 档位：表里的币照表；表外的 USDT 永续一档默认（最高 10x、MMR 2.5%、速算数 0）；其余抛未配置。
 */
class FuturesLeverageBracketRegistryTest {

    private final FuturesLeverageBracketRegistry registry = new FuturesLeverageBracketRegistry();

    @Test
    void 表里的币照表_前端档位表不含走默认档的币() {
        assertThat(registry.getEffectiveMaxLeverage("BTCUSDT", new BigDecimal("1000"))).isEqualTo(150);
        assertThat(registry.getAllBrackets()).hasSize(16).doesNotContainKey("PEPEUSDT");
    }

    @Test
    void 表外的USDT永续_一档到底_最高10倍_维持保证金按2点5算() {
        assertThat(registry.getBrackets("PEPEUSDT")).hasSize(1);
        assertThat(registry.getEffectiveMaxLeverage("PEPEUSDT", new BigDecimal("100"))).isEqualTo(10);
        assertThat(registry.getEffectiveMaxLeverage("PEPEUSDT", new BigDecimal("50000000000"))).isEqualTo(10);
        // 10000 × 2.5% − 0
        assertThat(registry.calcMaintenanceMargin("PEPEUSDT", new BigDecimal("10000"))).isEqualByComparingTo("250");
    }

    @Test
    void 非USDT永续_照旧抛未配置() {
        for (String symbol : new String[]{"BTCUSDC", "BTCUSDT_261225"}) {
            assertThat(registry.getBrackets(symbol)).isNull();
            assertThatThrownBy(() -> registry.getEffectiveMaxLeverage(symbol, BigDecimal.TEN))
                    .isInstanceOf(BizException.class)
                    .extracting("code").isEqualTo(ErrorCode.FUTURES_SYMBOL_NOT_CONFIGURED.getCode());
            assertThatThrownBy(() -> registry.calcMaintenanceMargin(symbol, BigDecimal.TEN))
                    .isInstanceOf(BizException.class)
                    .extracting("code").isEqualTo(ErrorCode.FUTURES_SYMBOL_NOT_CONFIGURED.getCode());
        }
    }
}
