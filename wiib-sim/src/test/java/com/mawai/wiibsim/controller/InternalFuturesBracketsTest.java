package com.mawai.wiibsim.controller;

import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.service.AccountResetService;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.FuturesTradingService;
import com.mawai.wiibsim.service.InternalOrderIdempotency;
import com.mawai.wiibsim.service.TraderSymbolService;
import com.mawai.wiibsim.service.UserService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 交易员按档位算统一杠杆的上限：表里的币照表，表外的 USDT 永续给默认档，不认识的不出。
 */
class InternalFuturesBracketsTest {

    private final InternalFuturesTradeController controller = new InternalFuturesTradeController(
            mock(FuturesTradingService.class), mock(FuturesRiskService.class), mock(UserService.class),
            mock(AccountResetService.class), mock(InternalOrderIdempotency.class), mock(CrossMarginService.class),
            mock(TraderSymbolService.class), new FuturesLeverageBracketRegistry());

    @Test
    void 表里的照表_表外的给默认档_不认识的不出() {
        Map<String, List<FuturesLeverageBracketRegistry.Bracket>> out =
                controller.brackets(List.of("BTCUSDT", "PEPEUSDT", "BTCUSDC")).getData();
        assertThat(out).containsOnlyKeys("BTCUSDT", "PEPEUSDT");
        assertThat(out.get("BTCUSDT").getFirst().maxLeverage()).isEqualTo(150);
        assertThat(out.get("PEPEUSDT")).hasSize(1);
        assertThat(out.get("PEPEUSDT").getFirst().maxLeverage()).isEqualTo(10);
    }
}
