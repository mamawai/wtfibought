package com.mawai.wiibsim.service.impl;

import org.junit.jupiter.api.Test;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FuturesHelperTest {

    @Test
    void reducedPositionAllocatesNearestTargetsBeforeMerging() {
        FuturesTakeProfit near = new FuturesTakeProfit("old90", new BigDecimal("90"), new BigDecimal("5"));
        near.setCreatedAt(123L);
        var merged = FuturesHelper.mergeTpList(List.of(near,
                        new FuturesTakeProfit("old80", new BigDecimal("80"), new BigDecimal("5"))),
                List.of(new FuturesTakeProfit("new90", new BigDecimal("90.0"), BigDecimal.ONE),
                        new FuturesTakeProfit("new80", new BigDecimal("80"), BigDecimal.ONE)), new BigDecimal("4"), "SHORT");
        assertThat(merged).hasSize(2);
        assertThat(merged.get(0).getQuantity()).isEqualByComparingTo("5");
        assertThat(merged.get(0).getId()).isEqualTo("old90");
        assertThat(merged.get(0).getCreatedAt()).isEqualTo(123L);
        assertThat(merged.get(1).getId()).isEqualTo("new80");
        assertThat(merged.get(1).getQuantity()).isEqualByComparingTo("1");
        assertThat(near.getQuantity()).isEqualByComparingTo("5");
    }

    /** 这次加仓没带保护单：仓位上原有的原样不动（返回 null＝不改库），哪怕部分平仓后旧档数量已经超过持仓 */
    @Test
    void addWithoutProtectionLeavesExistingUntouched() {
        List<FuturesStopLoss> stale = List.of(new FuturesStopLoss("old", new BigDecimal("90"), new BigDecimal("5")));
        List<FuturesTakeProfit> staleTp = List.of(new FuturesTakeProfit("old", new BigDecimal("110"), new BigDecimal("5")));
        assertThat(FuturesHelper.mergeSlList(stale, null, new BigDecimal("4"), "LONG")).isNull();
        assertThat(FuturesHelper.mergeSlList(stale, List.of(), new BigDecimal("4"), "LONG")).isNull();
        assertThat(FuturesHelper.mergeTpList(staleTp, null, new BigDecimal("4"), "LONG")).isNull();
    }

    @Test
    void tightestStopsKeepTheirIdentityAndNewPricesAppend() {
        var wide = new FuturesStopLoss("wide", new BigDecimal("80"), BigDecimal.ONE);
        var tight = new FuturesStopLoss("tight", new BigDecimal("90"), BigDecimal.ONE);
        tight.setCreatedAt(456L);
        var merged = FuturesHelper.mergeSlList(List.of(wide, tight), List.of(
                new FuturesStopLoss("same", new BigDecimal("90.0"), BigDecimal.ONE),
                new FuturesStopLoss("new", new BigDecimal("95"), BigDecimal.ONE)), BigDecimal.ONE, "LONG");
        assertThat(merged).extracting(FuturesStopLoss::getId).containsExactly("tight", "new");
        assertThat(merged.getFirst().getQuantity()).isEqualByComparingTo("2");
        assertThat(merged.getFirst().getCreatedAt()).isEqualTo(456L);
    }

    @Test
    void fifthDistinctProtectionPriceIsRejected() {
        var stops = java.util.stream.IntStream.range(0, 4)
                .mapToObj(i -> new FuturesStopLoss("s" + i, BigDecimal.valueOf(80 + i), BigDecimal.ONE)).toList();
        assertThatThrownBy(() -> FuturesHelper.mergeSlList(stops,
                List.of(new FuturesStopLoss("fifth", new BigDecimal("90"), BigDecimal.ONE)), new BigDecimal("4"), "LONG"))
                .isInstanceOf(BizException.class);
        var targets = stops.stream().map(s -> new FuturesTakeProfit(s.getId(), s.getPrice(), s.getQuantity())).toList();
        assertThatThrownBy(() -> FuturesHelper.mergeTpList(targets,
                List.of(new FuturesTakeProfit("fifth", new BigDecimal("90"), BigDecimal.ONE)), new BigDecimal("4"), "SHORT"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void groupedCancellationRefundsIsolatedOrdersAndDropsIndexesAfterCommit() throws Exception {
        var mapper = mock(FuturesOrderMapper.class);
        var users = mock(UserMapper.class);
        var cache = mock(CacheService.class);
        FuturesPosition pos = new FuturesPosition();
        pos.setUserId(7L); pos.setSymbol("BTCUSDT"); pos.setSide("SHORT");
        FuturesOrder isolated = new FuturesOrder();
        isolated.setId(1L); isolated.setUserId(7L); isolated.setSymbol("BTCUSDT");
        isolated.setOrderSide("OPEN_SHORT"); isolated.setMarginMode("ISOLATED");
        isolated.setFrozenAmount(new BigDecimal("12"));
        FuturesOrder cross = new FuturesOrder();
        cross.setId(2L); cross.setSymbol("BTCUSDT"); cross.setOrderSide("OPEN_SHORT"); cross.setMarginMode("CROSS");
        when(mapper.cancelGroupedOpenOrders(7L, "BTCUSDT", "OPEN_SHORT")).thenReturn(List.of(isolated, cross));
        when(users.atomicUnfreezeBalance(7L, new BigDecimal("12"))).thenReturn(mock(UserMapper.BalanceFrozen.class));
        TransactionSynchronizationManager.initSynchronization();
        try {
            FuturesHelper.cancelGroupedOpenOrders(pos, mapper, users, cache);
            verify(users).atomicUnfreezeBalance(7L, new BigDecimal("12"));
            verifyNoInteractions(cache);
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
            verify(cache).zRemove("futures:limit:open_short:BTCUSDT", "1");
            verify(cache).zRemove("futures:limit:open_short:BTCUSDT", "2");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        String sql = String.join(" ", FuturesOrderMapper.class.getMethod("cancelGroupedOpenOrders", Long.class, String.class, String.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        assertThat(sql).contains("group_id IS NOT NULL", "status IN ('PENDING', 'TRIGGERED')", "GROUP_CLOSED");
    }

    @Test
    void fundingTransfer_正费率_多付空收() {
        BigDecimal notional = new BigDecimal("10000");
        BigDecimal rate = new BigDecimal("0.0001");
        assertThat(FuturesHelper.fundingTransfer("LONG", notional, rate)).isEqualByComparingTo("1.00");
        assertThat(FuturesHelper.fundingTransfer("SHORT", notional, rate)).isEqualByComparingTo("-1.00");
    }

    @Test
    void fundingTransfer_负费率_空付多收() {
        BigDecimal notional = new BigDecimal("10000");
        BigDecimal rate = new BigDecimal("-0.0002");
        assertThat(FuturesHelper.fundingTransfer("LONG", notional, rate)).isEqualByComparingTo("-2.00");
        assertThat(FuturesHelper.fundingTransfer("SHORT", notional, rate)).isEqualByComparingTo("2.00");
    }

    @Test
    void fundingTransfer_零费率与分精度() {
        assertThat(FuturesHelper.fundingTransfer("LONG", new BigDecimal("10000"), BigDecimal.ZERO).signum()).isZero();
        assertThat(FuturesHelper.fundingTransfer("LONG", new BigDecimal("12345"), new BigDecimal("0.0001")))
                .isEqualByComparingTo("1.23");   // 1.2345 → 分精度 HALF_UP
    }
}
