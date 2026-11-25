package com.mawai.wiibsim.ledger;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.entity.UserLedger;
import com.mawai.wiibcommon.enums.LedgerBizType;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserLedgerMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.BankruptcyService;
import com.mawai.wiibsim.service.CrossBandRegistry;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesPrices;
import com.mawai.wiibsim.service.impl.CrossMarginServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 全仓结算落的那条流水：类型和关联对象是调用方给的，币种是调用方那一段的；金额为 0 不动钱，也不留标注。
 * 切面真织到 mock 的 mapper 上（同 LedgerAspectTest）。
 */
class CrossSettleLedgerTest {

    private static final Long UID = 7L;

    private final UserMapper target = mock(UserMapper.class);
    private final UserLedgerMapper ledgerMapper = mock(UserLedgerMapper.class);
    private CrossMarginServiceImpl crossMargin;

    @BeforeEach
    void setUp() {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new LedgerAspect(ledgerMapper));
        crossMargin = new CrossMarginServiceImpl(factory.getProxy(), mock(FuturesPositionMapper.class),
                new FuturesPrices(mock(CacheService.class)), mock(FuturesLeverageBracketRegistry.class),
                mock(FuturesPositionIndexService.class), mock(BankruptcyService.class),
                mock(StringRedisTemplate.class), new CrossBandRegistry());

        User user = new User();
        user.setBalance(new BigDecimal("900"));
        when(target.selectById(UID)).thenReturn(user);
        // 挂着事务同步：结算后刷全仓索引那一步等提交，这里不会真跑
        TransactionSynchronizationManager.initSynchronization();
        // 别的用例可能在这条线程上留了标注
        LedgerCtx.takeMark();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
        LedgerCtx.takeMark();
    }

    @Test
    void 流水的类型和关联对象是调用方给的_币种是调用方那一段的() {
        when(target.atomicSettleBalance(UID, new BigDecimal("-100"))).thenReturn(new BigDecimal("900"));

        // 调用方的事务段：限价平仓那条路的默认类型是限价单成交，币种挂在这一段上
        LedgerCtx.push(LedgerBizType.FUTURES_LIMIT_DEDUCT);
        try {
            LedgerCtx.symbol("BTCUSDT");
            crossMargin.settle(UID, new BigDecimal("-100"), LedgerBizType.FUTURES_CLOSE_SETTLE, "POSITION", 9L);
        } finally {
            LedgerCtx.pop();
        }

        ArgumentCaptor<UserLedger> row = ArgumentCaptor.forClass(UserLedger.class);
        verify(ledgerMapper).insert(row.capture());
        assertThat(row.getValue().getBizType()).isEqualTo(LedgerBizType.FUTURES_CLOSE_SETTLE);
        assertThat(row.getValue().getRefType()).isEqualTo("POSITION");
        assertThat(row.getValue().getRefId()).isEqualTo(9L);
        assertThat(row.getValue().getSymbol()).isEqualTo("BTCUSDT");
        assertThat(row.getValue().getDelta()).isEqualByComparingTo("-100");
    }

    @Test
    void 金额为0不动钱也不留标注() {
        crossMargin.settle(UID, BigDecimal.ZERO, LedgerBizType.FUNDING_FEE_PAY, "POSITION", 9L);

        verifyNoInteractions(ledgerMapper);
        assertThat(LedgerCtx.takeMark()).isNull();
    }
}
