package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.entity.UserLedger;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.mapper.UserLedgerMapper;
import com.mawai.wiibsim.service.AccountResetService;
import com.mawai.wiibsim.service.AssetValuationService;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesMarginService;
import com.mawai.wiibsim.service.FuturesQueryService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.FuturesTradingService;
import com.mawai.wiibsim.service.InternalOrderIdempotency;
import com.mawai.wiibsim.service.PositionHistoryService;
import com.mawai.wiibsim.service.TraderSymbolService;
import com.mawai.wiibsim.service.UserService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI Trader 资金账单走的内部接口：查路径里那个子账户的全部类型流水，单页条数和用户账单页一样封顶。
 */
class InternalFuturesLedgerTest {

    private final UserLedgerMapper ledgerMapper = mock(UserLedgerMapper.class);

    private final InternalFuturesTradeController controller = new InternalFuturesTradeController(
            mock(FuturesTradingService.class), mock(FuturesRiskService.class), mock(UserService.class),
            mock(AccountResetService.class), mock(InternalOrderIdempotency.class), mock(CrossMarginService.class),
            mock(TraderSymbolService.class), new FuturesLeverageBracketRegistry(), mock(AssetValuationService.class),
            mock(FuturesMarginService.class), mock(FuturesQueryService.class), ledgerMapper,
            mock(PositionHistoryService.class));

    @Test
    void 查这个子账户的全部类型_游标原样传() {
        UserLedger row = new UserLedger();
        when(ledgerMapper.selectByCursor(42L, null, 777L, 30)).thenReturn(List.of(row));

        assertThat(controller.ledger(42L, 777L, 30).getData()).containsExactly(row);
    }

    /** 封顶写死 100：改封顶得来这儿显式改一次 */
    @Test
    void 单页条数封顶100_负数兜到1() {
        controller.ledger(42L, null, Integer.MAX_VALUE);
        verify(ledgerMapper).selectByCursor(42L, null, null, 100);

        controller.ledger(42L, null, -1);
        verify(ledgerMapper).selectByCursor(42L, null, null, 1);
    }
}
