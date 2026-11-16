package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.market.ForceOrderService;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FundingRateService;
import com.mawai.wiibsim.service.FuturesMarginService;
import com.mawai.wiibsim.service.FuturesQueryService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.FuturesTradingService;
import com.mawai.wiibsim.service.KlineCacheService;
import com.mawai.wiibsim.service.PositionHistoryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 用户开仓只认 feed 订阅的合约币；别的 USDT 永续只给内部接口（交易员）开。
 */
class FuturesOpenSymbolGuardTest {

    private static final Long UID = 7L;

    private final FuturesTradingService trading = mock(FuturesTradingService.class);
    private final FuturesController controller = controller();

    private FuturesController controller() {
        BinanceProperties props = new BinanceProperties();
        props.setSymbols(List.of("BTCUSDT"));
        props.setCryptoFuturesSymbols(List.of("HYPEUSDT"));
        return new FuturesController(trading, mock(FuturesMarginService.class), mock(FuturesQueryService.class),
                mock(FuturesRiskService.class), mock(ForceOrderService.class),
                mock(KlineCacheService.class), mock(FuturesLeverageBracketRegistry.class),
                mock(CrossMarginService.class), mock(TradeFilterRegistry.class),
                mock(PositionHistoryService.class), mock(FundingRateService.class), props);
    }

    private static FuturesOpenRequest req(String symbol) {
        FuturesOpenRequest r = new FuturesOpenRequest();
        r.setSymbol(symbol);
        return r;
    }

    @Test
    void feed外的币_用户开仓直接拒_不进下单() {
        assertThatThrownBy(() -> controller.open(UID, req("PEPEUSDT")))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.CRYPTO_SYMBOL_INVALID.getCode());

        verify(trading, never()).openPosition(anyLong(), any());
    }

    @Test
    void feed订阅的币_含纯合约币_照常下单() {
        controller.open(UID, req("BTCUSDT"));
        controller.open(UID, req("HYPEUSDT"));

        verify(trading).openPosition(UID, req("BTCUSDT"));
        verify(trading).openPosition(UID, req("HYPEUSDT"));
    }
}
