package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.dto.FuturesAdjustLeverageRequest;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.FairLockRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.locks.Lock;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 全仓调杠杆是占用（usedMargin）变动的唯一不经交易/结算路径的口子：
 * 20x→50x 占用 100→40，安全带的流出下界 (U−mm)/2 随之塌掉，旧带必须作废。
 * 其余占用变动全汇于 refreshUserIndex（内含 bump），这里验证调杠杆也汇进去。
 */
class CrossLeverageBandInvalidateTest {

    private static final Long UID = 7L;

    @Test
    void 全仓调杠杆改占用_必须刷新用户索引作废安全带() {
        var userMapper = mock(UserMapper.class);
        var positionMapper = mock(FuturesPositionMapper.class);
        var orderMapper = mock(FuturesOrderMapper.class);
        var cacheService = mock(CacheService.class);
        var bracketRegistry = mock(FuturesLeverageBracketRegistry.class);
        var crossMargin = mock(CrossMarginService.class);
        var lockRegistry = mock(FairLockRegistry.class);

        FuturesPosition cross = new FuturesPosition();
        cross.setId(1L);
        cross.setUserId(UID);
        cross.setSymbol("BTCUSDT");
        cross.setSide("LONG");
        cross.setMarginMode(FuturesPosition.CROSS);
        cross.setLeverage(20);
        cross.setQuantity(new BigDecimal("20"));
        cross.setEntryPrice(new BigDecimal("100"));
        cross.setMargin(new BigDecimal("100"));
        cross.setStatus("OPEN");

        when(positionMapper.selectList(any())).thenReturn(List.of(cross));
        when(cacheService.getMarkPrice("BTCUSDT")).thenReturn(new BigDecimal("100"));
        when(bracketRegistry.getEffectiveMaxLeverage(eq("BTCUSDT"), any())).thenReturn(100);
        when(positionMapper.updateLeverageAndMargin(eq(1L), eq(50), any())).thenReturn(1);
        when(lockRegistry.tryLockAsUser(anyString())).thenReturn(mock(Lock.class));

        FuturesTradingServiceImpl trading = new FuturesTradingServiceImpl(
                mock(UserService.class), userMapper, positionMapper, orderMapper,
                new TradingConfig(), lockRegistry, cacheService,
                mock(FuturesPositionIndexService.class), bracketRegistry, crossMargin,
                new TradeFilterRegistry(mock(BinanceRestClient.class)),
                new MessageCatalog());

        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(FuturesTradingServiceImpl.class)).thenReturn(trading);
        new SpringUtils().setApplicationContext(ctx);

        FuturesAdjustLeverageRequest req = new FuturesAdjustLeverageRequest();
        req.setSymbol("BTCUSDT");
        req.setLeverage(50);

        trading.adjustLeverage(UID, req);

        verify(crossMargin).refreshUserIndex(UID);
    }
}
