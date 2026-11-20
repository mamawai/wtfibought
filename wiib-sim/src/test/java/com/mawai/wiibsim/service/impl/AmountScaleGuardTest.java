package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.dto.FuturesAddMarginRequest;
import com.mawai.wiibcommon.dto.FuturesReduceMarginRequest;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.ledger.LedgerTx;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserLedgerMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.AssetValuationService;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.CryptoPositionService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesPrices;
import com.mawai.wiibsim.util.FairLockRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 余额、仓位保证金都只有两位小数：划转、追加保证金、减少保证金收到 0.005 这种金额一律拒，钱不动 */
class AmountScaleGuardTest {

    private static final Long UID = 7L;
    private static final BigDecimal HALF_CENT = new BigDecimal("0.005");

    private final UserMapper userMapper = mock(UserMapper.class);
    private final FuturesPositionMapper positionMapper = mock(FuturesPositionMapper.class);

    /** 1 号逐仓仓位，开着 */
    private FuturesMarginServiceImpl marginService() {
        FuturesPosition isolated = new FuturesPosition();
        isolated.setId(1L);
        isolated.setUserId(UID);
        isolated.setSymbol("BTCUSDT");
        isolated.setMarginMode(FuturesPosition.ISOLATED);
        isolated.setStatus("OPEN");
        when(positionMapper.selectById(1L)).thenReturn(isolated);
        return new FuturesMarginServiceImpl(userMapper, positionMapper, new TradingConfig(), mock(FairLockRegistry.class),
                mock(FuturesPositionIndexService.class), mock(FuturesLeverageBracketRegistry.class),
                mock(CrossMarginService.class), new LedgerTx(new TransactionTemplate(mock(PlatformTransactionManager.class))),
                new FuturesPrices(mock(CacheService.class)));
    }

    @Test
    void 划转超过两位小数被拒() {
        UserServiceImpl users = new UserServiceImpl(mock(CryptoPositionService.class), positionMapper,
                mock(AssetValuationService.class), mock(UserLedgerMapper.class), mock(MessageCatalog.class));
        // ServiceImpl 的 baseMapper 由容器注入，单测手动塞
        ReflectionTestUtils.setField(users, "baseMapper", userMapper);

        assertThatThrownBy(() -> users.transferToGame(UID, HALF_CENT))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.WALLET_TRANSFER_INVALID.getCode());
        verify(userMapper, never()).atomicTransferToGame(anyLong(), any(), any());
    }

    @Test
    void 追加保证金超过两位小数被拒() {
        FuturesMarginServiceImpl margin = marginService();
        FuturesAddMarginRequest req = new FuturesAddMarginRequest();
        req.setPositionId(1L);
        req.setAmount(HALF_CENT);

        assertThatThrownBy(() -> margin.doAddMargin(UID, req))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        verify(userMapper, never()).atomicUpdateBalance(anyLong(), any());
    }

    @Test
    void 减少保证金超过两位小数被拒() {
        FuturesMarginServiceImpl margin = marginService();
        FuturesReduceMarginRequest req = new FuturesReduceMarginRequest();
        req.setPositionId(1L);
        req.setAmount(HALF_CENT);

        assertThatThrownBy(() -> margin.doReduceMargin(UID, req))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        verify(positionMapper, never()).atomicReduceMargin(anyLong(), any());
    }
}
