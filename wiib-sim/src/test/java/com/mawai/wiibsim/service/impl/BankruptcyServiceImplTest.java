package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibsim.campaign.service.CampaignCarryoverService;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.CryptoOrderMapper;
import com.mawai.wiibsim.mapper.CryptoPositionMapper;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.PredictionBetMapper;
import com.mawai.wiibsim.mapper.UserLedgerMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.CryptoPositionService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.ResetQuotaService;
import com.mawai.wiibsim.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 爆仓判定：批量检查锁外粗筛、锁行后按最新数据重判；全仓穿仓的 bankruptNow 不判直接清。
 * 现货/预测估值都桩成 0，净资产 = 余额 − 借款本金。
 */
class BankruptcyServiceImplTest {

    private static final Long UID = 7L;

    private UserService userService;
    private UserMapper userMapper;
    private CryptoPositionService cryptoPositionService;
    private BankruptcyServiceImpl service;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        userMapper = mock(UserMapper.class);
        cryptoPositionService = mock(CryptoPositionService.class);
        AssetValuationService assetValuationService = mock(AssetValuationService.class);
        when(cryptoPositionService.calculateCryptoMarketValue(UID)).thenReturn(BigDecimal.ZERO);
        when(assetValuationService.predictionMarketValue(UID)).thenReturn(BigDecimal.ZERO);

        service = new BankruptcyServiceImpl(
                userService, userMapper, cryptoPositionService, new TradingConfig(),
                mock(CryptoOrderMapper.class), mock(CryptoPositionMapper.class),
                mock(FuturesOrderMapper.class), mock(FuturesPositionMapper.class),
                mock(CacheService.class), mock(FuturesPositionIndexService.class),
                mock(PredictionBetMapper.class), assetValuationService,
                mock(UserLedgerMapper.class), mock(ResetQuotaService.class), mock(CampaignCarryoverService.class),
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
    }

    private static User user(String balance, String principal) {
        User u = new User();
        u.setId(UID);
        u.setIsBankrupt(false);
        u.setBalance(new BigDecimal(balance));
        u.setMarginLoanPrincipal(new BigDecimal(principal));
        return u;
    }

    /** 批量检查扫出来的用户，锁外粗筛读到的也是这一份 */
    private void givenListed(User user) {
        when(userService.list(ArgumentMatchers.<Wrapper<User>>any())).thenReturn(List.of(user));
        when(userService.getById(UID)).thenReturn(user);
    }

    /** 列表里资不抵债，拿到行锁时已回款转正：不清算 */
    @Test
    void 锁外判可破产_锁内重判已转正_不清算() {
        givenListed(user("100", "1000"));
        when(userMapper.selectByIdForUpdate(UID)).thenReturn(user("2000", "1000"));

        service.checkAndLiquidateAll();

        InOrder inOrder = inOrder(userMapper, cryptoPositionService);
        inOrder.verify(userMapper).selectByIdForUpdate(UID);
        inOrder.verify(cryptoPositionService).calculateCryptoMarketValue(UID);
        verify(userMapper, never()).markBankrupt(anyLong(), any());
    }

    @Test
    void 锁内重判仍资不抵债_清算_只锁一次() {
        givenListed(user("100", "1000"));
        when(userMapper.selectByIdForUpdate(UID)).thenReturn(user("100", "1000"));

        service.checkAndLiquidateAll();

        InOrder inOrder = inOrder(userMapper, cryptoPositionService);
        inOrder.verify(userMapper).selectByIdForUpdate(UID);
        inOrder.verify(cryptoPositionService).calculateCryptoMarketValue(UID);
        inOrder.verify(userMapper).markBankrupt(eq(UID), any());
        verify(userMapper).selectByIdForUpdate(UID);
    }

    /** 全仓穿仓的强制破产：锁行后直接清，不看净资产 */
    @Test
    void bankruptNow_不重判直接清算() {
        when(userMapper.selectByIdForUpdate(UID)).thenReturn(user("2000", "0"));

        service.bankruptNow(UID);

        verify(userMapper).markBankrupt(eq(UID), any());
        verifyNoInteractions(cryptoPositionService);
    }
}
