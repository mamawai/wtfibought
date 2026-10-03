package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.CryptoOrderMapper;
import com.mawai.wiibsim.mapper.CryptoPositionMapper;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.MinesGameMapper;
import com.mawai.wiibsim.mapper.PredictionBetMapper;
import com.mawai.wiibsim.mapper.UserLedgerMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.mapper.VideoPokerGameMapper;
import com.mawai.wiibsim.service.CryptoPositionService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.ResetQuotaService;
import com.mawai.wiibsim.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 破产清算 / 破产恢复对进行中游戏局的处理：矿工 PLAYING、视频扑克 DEALING 的局一并作废。
 */
class BankruptcyGameForfeitTest {

    private static final Long UID = 7L;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);

    private final UserMapper userMapper = mock(UserMapper.class);
    private final MinesGameMapper minesGameMapper = mock(MinesGameMapper.class);
    private final VideoPokerGameMapper videoPokerGameMapper = mock(VideoPokerGameMapper.class);
    private BankruptcyServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BankruptcyServiceImpl(mock(UserService.class), userMapper, mock(CryptoPositionService.class),
                new TradingConfig(), mock(CryptoOrderMapper.class), mock(CryptoPositionMapper.class),
                mock(FuturesOrderMapper.class), mock(FuturesPositionMapper.class), mock(CacheService.class),
                mock(FuturesPositionIndexService.class), mock(PredictionBetMapper.class),
                minesGameMapper, videoPokerGameMapper, mock(AssetValuationService.class),
                mock(UserLedgerMapper.class), mock(ResetQuotaService.class));
        ReflectionTestUtils.setField(service, "initialBalance", new BigDecimal("10000"));

        when(userMapper.selectByIdForUpdate(UID)).thenReturn(new User());
        when(userMapper.markBankrupt(eq(UID), any())).thenReturn(1);
        when(userMapper.resetAfterBankruptcy(eq(UID), any(), any())).thenReturn(1);
    }

    @Test
    void 爆仓清算_进行中的游戏局作废() {
        service.liquidateUser(UID, TODAY);

        // 先锁 user 行、清钱包，再动游戏局（和兑现路径同一个加锁顺序）
        InOrder order = inOrder(userMapper, minesGameMapper, videoPokerGameMapper);
        order.verify(userMapper).selectByIdForUpdate(UID);
        order.verify(userMapper).markBankrupt(UID, TODAY.plusDays(1));
        order.verify(minesGameMapper).forfeitPlayingByUserId(UID);
        order.verify(videoPokerGameMapper).forfeitDealingByUserId(UID);
    }

    @Test
    void 破产恢复_破产期间开的局同样作废() {
        service.resetUser(UID, TODAY);

        verify(minesGameMapper).forfeitPlayingByUserId(UID);
        verify(videoPokerGameMapper).forfeitDealingByUserId(UID);
    }
}
