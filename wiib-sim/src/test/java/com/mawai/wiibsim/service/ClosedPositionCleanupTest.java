package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 仓位全平之后的收尾。平仓挂单那一半由 FuturesCloseOrderCancelTest 经风控服务验 */
class ClosedPositionCleanupTest {

    private final FuturesOrderMapper orderMapper = mock(FuturesOrderMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final CacheService cache = mock(CacheService.class);
    private final ClosedPositionCleanup cleanup = new ClosedPositionCleanup(mock(FuturesPositionIndexService.class),
            orderMapper, userMapper, new LimitOrderIndex(cache));

    private static FuturesOrder openShort(long id, String marginMode) {
        FuturesOrder order = new FuturesOrder();
        order.setId(id);
        order.setUserId(7L);
        order.setSymbol("BTCUSDT");
        order.setOrderSide("OPEN_SHORT");
        order.setMarginMode(marginMode);
        return order;
    }

    /** 同组开仓单：逐仓的退冻结、全仓的只改状态；限价索引等事务提交后才摘 */
    @Test
    void groupedCancellationRefundsIsolatedOrdersAndDropsIndexesAfterCommit() throws Exception {
        FuturesPosition pos = new FuturesPosition();
        pos.setUserId(7L);
        pos.setSymbol("BTCUSDT");
        pos.setSide("SHORT");
        FuturesOrder isolated = openShort(1L, "ISOLATED");
        isolated.setFrozenAmount(new BigDecimal("12"));
        FuturesOrder cross = openShort(2L, "CROSS");
        when(orderMapper.cancelGroupedOpenOrders(7L, "BTCUSDT", "OPEN_SHORT")).thenReturn(List.of(isolated, cross));
        TransactionSynchronizationManager.initSynchronization();
        try {
            cleanup.run(pos);
            verify(userMapper).atomicUnfreezeBalance(7L, new BigDecimal("12"));
            verifyNoInteractions(cache);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(cache).zRemove("futures:limit:open_short:BTCUSDT", "1");
            verify(cache).zRemove("futures:limit:open_short:BTCUSDT", "2");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        String sql = String.join(" ", FuturesOrderMapper.class.getMethod("cancelGroupedOpenOrders", Long.class, String.class, String.class)
                .getAnnotation(Select.class).value());
        assertThat(sql).contains("group_id IS NOT NULL", "status IN ('PENDING', 'TRIGGERED')", "GROUP_CLOSED");
    }
}
