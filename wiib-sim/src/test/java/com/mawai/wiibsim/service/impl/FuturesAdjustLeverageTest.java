package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.dto.FuturesAdjustLeverageRequest;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.RedisLockUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 币种级调杠杆（对齐Binance：多空共用杠杆，一次调整作用于该币全部仓位）。
 * 锁死的规则：
 * <ol>
 *   <li>多张全仓一起调低时，占用增量按币种合并后一次过闸可用余额</li>
 *   <li>逐仓调低必须整体拒绝，且拒绝发生在任何一张仓位落库之前</li>
 *   <li>逐仓调高按张释放保证金回钱包并刷新静态强平索引</li>
 *   <li>全部仓位已是目标杠杆 → 幂等直返，不产生任何写</li>
 * </ol>
 */
class FuturesAdjustLeverageTest {

    private static final Long UID = 7L;
    private static final String SYMBOL = "BTCUSDT";

    private FuturesPositionMapper positionMapper;
    private UserMapper userMapper;
    private CacheService cacheService;
    private FuturesLeverageBracketRegistry bracketRegistry;
    private CrossMarginService crossMarginService;
    private FuturesPositionIndexService positionIndexService;
    private FuturesTradingServiceImpl service;

    @BeforeEach
    void setUp() {
        positionMapper = mock(FuturesPositionMapper.class);
        userMapper = mock(UserMapper.class);
        cacheService = mock(CacheService.class);
        bracketRegistry = mock(FuturesLeverageBracketRegistry.class);
        crossMarginService = mock(CrossMarginService.class);
        positionIndexService = mock(FuturesPositionIndexService.class);

        when(cacheService.getMarkPrice(SYMBOL)).thenReturn(new BigDecimal("100"));
        when(bracketRegistry.getEffectiveMaxLeverage(anyString(), any())).thenReturn(150);
        when(bracketRegistry.calcMaintenanceMargin(anyString(), any())).thenReturn(new BigDecimal("0.40"));
        when(positionMapper.updateLeverageAndMargin(anyLong(), anyInt(), any())).thenReturn(1);
        when(userMapper.atomicUpdateBalance(anyLong(), any())).thenReturn(1);

        service = new FuturesTradingServiceImpl(
                mock(UserService.class), userMapper, positionMapper, mock(FuturesOrderMapper.class),
                new TradingConfig(), mock(RedisLockUtil.class), cacheService,
                positionIndexService, bracketRegistry, crossMarginService,
                new TradeFilterRegistry(mock(BinanceRestClient.class)));
    }

    private static FuturesPosition pos(long id, String side, String mode, int leverage,
                                       String entryPrice, String qty, String margin) {
        FuturesPosition p = new FuturesPosition();
        p.setId(id);
        p.setUserId(UID);
        p.setSymbol(SYMBOL);
        p.setSide(side);
        p.setMarginMode(mode);
        p.setLeverage(leverage);
        p.setEntryPrice(new BigDecimal(entryPrice));
        p.setQuantity(new BigDecimal(qty));
        p.setMargin(new BigDecimal(margin));
        p.setStatus("OPEN");
        return p;
    }

    private static FuturesAdjustLeverageRequest req(int leverage) {
        FuturesAdjustLeverageRequest r = new FuturesAdjustLeverageRequest();
        r.setSymbol(SYMBOL);
        r.setLeverage(leverage);
        return r;
    }

    @Test
    void 全仓多空一起调低_占用增量合并一次过闸_两张都落库() {
        // LONG: 100×1/100x 占1.00 → 50x 目标2.00 (+1.00)；SHORT: 100×2/100x 占2.00 → 4.00 (+2.00)
        FuturesPosition lp = pos(1L, "LONG", FuturesPosition.CROSS, 100, "100", "1", "1.00");
        FuturesPosition sp = pos(2L, "SHORT", FuturesPosition.CROSS, 100, "100", "2", "2.00");
        when(positionMapper.selectList(any())).thenReturn(List.of(lp, sp));

        service.doAdjustLeverage(UID, req(50));

        verify(crossMarginService).assertCanAfford(eq(UID), argThat(d -> d.compareTo(new BigDecimal("3.00")) == 0));
        verify(positionMapper).updateLeverageAndMargin(eq(1L), eq(50), argThat(m -> m.compareTo(new BigDecimal("2.00")) == 0));
        verify(positionMapper).updateLeverageAndMargin(eq(2L), eq(50), argThat(m -> m.compareTo(new BigDecimal("4.00")) == 0));
        // 全仓不动钱包也不动静态强平索引
        verify(userMapper, never()).atomicUpdateBalance(anyLong(), any());
        verify(positionIndexService, never()).updateLiquidationPrice(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void 全仓调高释放占用_不过闸() {
        FuturesPosition lp = pos(1L, "LONG", FuturesPosition.CROSS, 50, "100", "1", "2.00");
        when(positionMapper.selectList(any())).thenReturn(List.of(lp));

        service.doAdjustLeverage(UID, req(100));

        // 占用减少无需校验可用
        verify(crossMarginService, never()).assertCanAfford(anyLong(), any());
        verify(positionMapper).updateLeverageAndMargin(eq(1L), eq(100), argThat(m -> m.compareTo(new BigDecimal("1.00")) == 0));
    }

    @Test
    void 有逐仓仓位时调低_整体拒绝_零落库() {
        // 混合场景（历史数据可能出现）：全仓能调低但逐仓不能 → 必须整体拒绝，不允许半程落库
        FuturesPosition cp = pos(1L, "LONG", FuturesPosition.CROSS, 100, "100", "1", "1.00");
        FuturesPosition ip = pos(2L, "SHORT", FuturesPosition.ISOLATED, 100, "100", "1", "1.00");
        when(positionMapper.selectList(any())).thenReturn(List.of(cp, ip));

        assertThatThrownBy(() -> service.doAdjustLeverage(UID, req(50)))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.FUTURES_LEVERAGE_ONLY_UP.getCode());

        verify(positionMapper, never()).updateLeverageAndMargin(anyLong(), anyInt(), any());
    }

    @Test
    void 逐仓调高_释放保证金回钱包_刷新强平索引() {
        // 100×1/10x 划扣10 → 20x 目标5，释放5；markIM=100/20=5，newMargin(5)+pnl(0) ≥ 5 通过
        FuturesPosition ip = pos(1L, "LONG", FuturesPosition.ISOLATED, 10, "100", "1", "10.00");
        when(positionMapper.selectList(any())).thenReturn(List.of(ip));

        service.doAdjustLeverage(UID, req(20));

        verify(positionMapper).updateLeverageAndMargin(eq(1L), eq(20), argThat(m -> m.compareTo(new BigDecimal("5.00")) == 0));
        verify(userMapper).atomicUpdateBalance(eq(UID), argThat(d -> d.compareTo(new BigDecimal("5.00")) == 0));
        verify(positionIndexService).updateLiquidationPrice(eq(1L), eq(SYMBOL), eq("LONG"), any());
    }

    @Test
    void 全部已是目标杠杆_幂等直返() {
        FuturesPosition lp = pos(1L, "LONG", FuturesPosition.CROSS, 50, "100", "1", "2.00");
        FuturesPosition sp = pos(2L, "SHORT", FuturesPosition.CROSS, 50, "100", "1", "2.00");
        when(positionMapper.selectList(any())).thenReturn(List.of(lp, sp));

        service.doAdjustLeverage(UID, req(50));

        verify(positionMapper, never()).updateLeverageAndMargin(anyLong(), anyInt(), any());
        verify(crossMarginService, never()).assertCanAfford(anyLong(), any());
    }

    @Test
    void 无持仓_报仓位不存在() {
        when(positionMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.doAdjustLeverage(UID, req(50)))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.FUTURES_POSITION_NOT_FOUND.getCode());
    }
}
