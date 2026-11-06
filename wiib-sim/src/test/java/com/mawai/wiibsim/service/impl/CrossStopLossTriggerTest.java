package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.BankruptcyService;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.CrossMarginService.CrossAccount;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.NoBookFillPricer;
import com.mawai.wiibsim.service.TradeNotificationService;
import com.mawai.wiibsim.util.FairLockRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 全仓止损（成交价摸到）在用户级锁里执行，跟爆仓串行。止损看成交价、爆仓看标记价，两边按实际先后：
 * 止损触发时账户按标记价已经够爆，就是爆仓先到——先爆仓、止损作废；不够爆就执行这批止损。
 * 例：余额 1000，全仓多 1 BTC@100000、100x，维持保证金 0.4%，标记价 99397 左右爆。
 */
class CrossStopLossTriggerTest {

    private static final Long UID = 7L;
    private static final String SYM = "BTCUSDT";
    private static final String USER_LOCK = "futures:cross:liq:" + UID;
    /** 触发止损的那笔成交 */
    private static final BigDecimal TRADE = new BigDecimal("99450");
    private static final long TRADE_AT = 1_700_000_000_000L;

    private CrossMarginService crossMargin;
    private FuturesPositionMapper positionMapper;
    private CacheService cacheService;
    private FairLockRegistry lockRegistry;
    private FuturesRiskService riskService;
    private Lock userLock;
    private CrossLiquidationServiceImpl service;

    /** 爆仓撤单那句 LambdaQueryWrapper 用了 .in()/.notLikeRight()，裸 mock 下要先补 FuturesOrder 的表信息 */
    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FuturesOrder.class);
    }

    @BeforeEach
    void setUp() {
        crossMargin = mock(CrossMarginService.class);
        positionMapper = mock(FuturesPositionMapper.class);
        cacheService = mock(CacheService.class);
        lockRegistry = mock(FairLockRegistry.class);
        riskService = mock(FuturesRiskService.class);
        userLock = mock(Lock.class);

        when(lockRegistry.tryLockAsSystem(anyString())).thenReturn(mock(Lock.class));
        when(lockRegistry.tryLockAsSystem(USER_LOCK)).thenReturn(userLock);
        when(positionMapper.selectById(1L)).thenReturn(btcLong());
        when(positionMapper.casClosePosition(anyLong(), anyString(), any(), any())).thenReturn(1);

        service = new CrossLiquidationServiceImpl(crossMargin, positionMapper, mock(FuturesOrderMapper.class),
                new TradingConfig(), cacheService, mock(FuturesPositionIndexService.class),
                lockRegistry, mock(TradeNotificationService.class), new CrossBandRegistry(),
                riskService, new TransactionTemplate(mock(PlatformTransactionManager.class)),
                new TradeFilterRegistry(mock(BinanceRestClient.class)), mock(UserMapper.class),
                mock(BankruptcyService.class), NoBookFillPricer.create());
    }

    private static FuturesPosition btcLong() {
        FuturesPosition p = new FuturesPosition();
        p.setId(1L);
        p.setUserId(UID);
        p.setSymbol(SYM);
        p.setSide("LONG");
        p.setMarginMode(FuturesPosition.CROSS);
        p.setLeverage(100);
        p.setQuantity(BigDecimal.ONE);
        p.setEntryPrice(new BigDecimal("100000"));
        p.setMargin(new BigDecimal("1000"));
        p.setStatus("OPEN");
        return p;
    }

    /** 按标记价 mark 估值：净值 1000 + (mark − 100000)，维持保证金 mark × 0.4% */
    private static CrossAccount markAt(String mark) {
        BigDecimal p = new BigDecimal(mark);
        return new CrossAccount(new BigDecimal("1000"), p.subtract(new BigDecimal("100000")), new BigDecimal("1000"),
                BigDecimal.ZERO, p.multiply(new BigDecimal("0.004")), List.of(btcLong()), Map.of(SYM, p));
    }

    @Test
    void 账户不够爆_用户级锁内执行这批止损_按触发那笔成交() {
        when(crossMargin.snapshot(UID)).thenReturn(markAt("99500"));

        service.triggerStopLoss(btcLong(), List.of("sl1", "sl2"), TRADE, TRADE_AT);

        InOrder inOrder = inOrder(lockRegistry, riskService, userLock);
        inOrder.verify(lockRegistry).tryLockAsSystem(USER_LOCK);
        inOrder.verify(riskService).batchTriggerStopLoss(1L, List.of("sl1", "sl2"), TRADE, TRADE_AT);
        inOrder.verify(userLock).unlock();
        verify(positionMapper, never()).casClosePosition(anyLong(), anyString(), any(), any());
    }

    @Test
    void 按标记价已经够爆_先爆仓_止损作废() {
        when(crossMargin.snapshot(UID)).thenReturn(markAt("99300"));
        when(crossMargin.snapshot(UID, null, null)).thenReturn(markAt("99300"));
        when(cacheService.getMarkPrice(SYM)).thenReturn(new BigDecimal("99300"));

        service.triggerStopLoss(btcLong(), List.of("sl1"), TRADE, TRADE_AT);

        // 爆仓按标记价判，成交没盘口按标记价
        verify(positionMapper).casClosePosition(eq(1L), eq("LIQUIDATED"), eq(new BigDecimal("99300")), any());
        verify(riskService, never()).batchTriggerStopLoss(anyLong(), any(), any(), anyLong());
    }

    @Test
    void 锁外够爆_拿齐仓位锁重判不够了_照常执行止损() {
        when(crossMargin.snapshot(UID)).thenReturn(markAt("99300"));
        when(crossMargin.snapshot(UID, null, null)).thenReturn(markAt("99500"));

        service.triggerStopLoss(btcLong(), List.of("sl1"), TRADE, TRADE_AT);

        verify(riskService).batchTriggerStopLoss(1L, List.of("sl1"), TRADE, TRADE_AT);
        verify(positionMapper, never()).casClosePosition(anyLong(), anyString(), any(), any());
    }

    @Test
    void 用户级锁等不到_抛处理中() {
        when(lockRegistry.tryLockAsSystem(USER_LOCK)).thenReturn(null);

        assertThatThrownBy(() -> service.triggerStopLoss(btcLong(), List.of("sl1"), TRADE, TRADE_AT))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.ORDER_PROCESSING.getCode());
        verifyNoInteractions(crossMargin, riskService);
    }

    @Test
    void 要爆仓但仓位锁等不到_抛处理中_止损不执行_用户锁照常释放() {
        when(crossMargin.snapshot(UID)).thenReturn(markAt("99300"));
        when(lockRegistry.tryLockAsSystem("futures:pos:1")).thenReturn(null);

        assertThatThrownBy(() -> service.triggerStopLoss(btcLong(), List.of("sl1"), TRADE, TRADE_AT))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.ORDER_PROCESSING.getCode());
        verify(riskService, never()).batchTriggerStopLoss(anyLong(), any(), any(), anyLong());
        verify(userLock).unlock();
    }

    @Test
    void 标记价精查够爆_直接爆仓_不再翻止损档() {
        BigDecimal pin = new BigDecimal("99300");
        when(crossMargin.snapshot(UID, SYM, pin)).thenReturn(markAt("99300"));

        service.checkUser(UID, SYM, pin, System.currentTimeMillis());

        verify(positionMapper).casClosePosition(eq(1L), eq("LIQUIDATED"), eq(pin), any());
        verify(positionMapper, never()).selectList(any());
        verifyNoInteractions(riskService);
    }
}
