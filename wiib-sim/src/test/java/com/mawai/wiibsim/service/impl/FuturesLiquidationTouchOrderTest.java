package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.service.CrossLiquidationService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesRiskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 仓位级触发：同一秒止损和强平同时命中，价格先触及的先执行。
 * 逐仓比的是强平索引分数（静态强平价）和止损索引分数；全仓止损转给账户级处理。
 * 例：逐仓多 1 BTC@100000、保证金 1000，强平价约 99397；止损 A 99500、B 99200，这一秒 mark 到 99100。
 */
class FuturesLiquidationTouchOrderTest {

    private static final String SYM = "BTCUSDT";
    private static final String LIQ_LONG = "futures:liq:long:" + SYM;
    private static final String LIQ_SHORT = "futures:liq:short:" + SYM;
    private static final String SL_LONG = "futures:sl:long:" + SYM;
    private static final String SL_SHORT = "futures:sl:short:" + SYM;
    private static final String TP_LONG = "futures:tp:long:" + SYM;
    private static final BigDecimal TICK = new BigDecimal("99100");

    private final CacheService cacheService = mock(CacheService.class);
    private final FuturesRiskService riskService = mock(FuturesRiskService.class);
    private final FuturesPositionMapper positionMapper = mock(FuturesPositionMapper.class);
    private final FuturesPositionIndexService indexService = mock(FuturesPositionIndexService.class);
    private final CrossLiquidationService crossLiquidation = mock(CrossLiquidationService.class);
    private final FuturesLiquidationServiceImpl service = new FuturesLiquidationServiceImpl(riskService, cacheService,
            mock(BinanceProperties.class), positionMapper, indexService, crossLiquidation);

    @BeforeEach
    void setUp() {
        when(cacheService.zRangeByScoreAndRemove(anyString(), anyDouble(), anyDouble())).thenReturn(Map.of());
    }

    /** 仓位 9；库里现存的止损档只有 slIds 这些（执行掉的已经不在了） */
    private static FuturesPosition position(String side, String marginMode, String status, String... slIds) {
        FuturesPosition p = new FuturesPosition();
        p.setId(9L);
        p.setUserId(7L);
        p.setSymbol(SYM);
        p.setSide(side);
        p.setMarginMode(marginMode);
        p.setStatus(status);
        List<FuturesStopLoss> sls = new ArrayList<>();
        for (String id : slIds) sls.add(new FuturesStopLoss(id, BigDecimal.ONE, BigDecimal.ONE));
        p.setStopLosses(sls);
        return p;
    }

    /** tick 99100 摘出：强平项 99397 + 给定的多头止损项 */
    private void longHits(Map<String, Double> sls) {
        when(cacheService.zRangeByScoreAndRemove(LIQ_LONG, 99100d, Double.MAX_VALUE)).thenReturn(Map.of("9", 99397d));
        when(cacheService.zRangeByScoreAndRemove(SL_LONG, 99100d, Double.MAX_VALUE)).thenReturn(sls);
    }

    // ==================== 逐仓：先后顺序 ====================

    @Test
    void 多头_价高的先_止损A_强平_止损B() {
        longHits(Map.of("9:slA", 99500d, "9:slB", 99200d));
        when(positionMapper.selectById(9L)).thenReturn(position("LONG", FuturesPosition.ISOLATED, "OPEN"));

        service.checkOnPriceUpdate(SYM, TICK, TICK);

        // B 还做不做由 batchTriggerStopLoss 锁内按库里现状定：强平平掉了就是空操作
        verify(riskService, timeout(2000)).batchTriggerStopLoss(9L, List.of("slB"), TICK);
        InOrder inOrder = inOrder(riskService);
        inOrder.verify(riskService).batchTriggerStopLoss(9L, List.of("slA"), TICK);
        inOrder.verify(riskService).forceClose(9L, TICK);
        inOrder.verify(riskService).batchTriggerStopLoss(9L, List.of("slB"), TICK);
    }

    @Test
    void 空头_价低的先_止损A_强平_止损B() {
        // 空头强平价 100600，止损 A 100500、B 100800，这一秒 mark 涨到 100900
        BigDecimal tick = new BigDecimal("100900");
        when(cacheService.zRangeByScoreAndRemove(LIQ_SHORT, 0d, 100900d)).thenReturn(Map.of("9", 100600d));
        when(cacheService.zRangeByScoreAndRemove(SL_SHORT, 0d, 100900d))
                .thenReturn(Map.of("9:slA", 100500d, "9:slB", 100800d));
        when(positionMapper.selectById(9L)).thenReturn(position("SHORT", FuturesPosition.ISOLATED, "OPEN"));

        service.checkOnPriceUpdate(SYM, tick, tick);

        verify(riskService, timeout(2000)).batchTriggerStopLoss(9L, List.of("slB"), tick);
        InOrder inOrder = inOrder(riskService);
        inOrder.verify(riskService).batchTriggerStopLoss(9L, List.of("slA"), tick);
        inOrder.verify(riskService).forceClose(9L, tick);
        inOrder.verify(riskService).batchTriggerStopLoss(9L, List.of("slB"), tick);
    }

    @Test
    void 止损价等于强平价_止损先() {
        longHits(Map.of("9:slA", 99397d));
        when(positionMapper.selectById(9L)).thenReturn(position("LONG", FuturesPosition.ISOLATED, "OPEN"));

        service.checkOnPriceUpdate(SYM, TICK, TICK);

        verify(riskService, timeout(2000)).forceClose(9L, TICK);
        InOrder inOrder = inOrder(riskService);
        inOrder.verify(riskService).batchTriggerStopLoss(9L, List.of("slA"), TICK);
        inOrder.verify(riskService).forceClose(9L, TICK);
        verify(riskService, times(1)).batchTriggerStopLoss(anyLong(), any(), any());
    }

    @Test
    void 逐仓只命中止损_一批直接执行_不走账户级() {
        when(cacheService.zRangeByScoreAndRemove(SL_LONG, 99100d, Double.MAX_VALUE))
                .thenReturn(Map.of("9:slA", 99500d, "9:slB", 99200d));
        when(positionMapper.selectById(9L)).thenReturn(position("LONG", FuturesPosition.ISOLATED, "OPEN"));

        service.checkOnPriceUpdate(SYM, TICK, TICK);

        verify(riskService, timeout(2000)).batchTriggerStopLoss(eq(9L),
                argThat(ids -> ids.size() == 2 && ids.containsAll(List.of("slA", "slB"))), eq(TICK));
        verify(riskService, never()).forceClose(anyLong(), any());
        verifyNoInteractions(crossLiquidation);
    }

    // ==================== 逐仓：处理完放回索引 ====================

    @Test
    void 强平等锁超时_已执行的止损A不放回_强平和没执行的止损B放回() {
        longHits(Map.of("9:slA", 99500d, "9:slB", 99200d));
        doThrow(new BizException(ErrorCode.ORDER_PROCESSING)).when(riskService).forceClose(anyLong(), any());
        // A 执行掉了，库里只剩 B
        FuturesPosition afterA = position("LONG", FuturesPosition.ISOLATED, "OPEN", "slB");
        when(positionMapper.selectById(9L)).thenReturn(afterA);

        service.checkOnPriceUpdate(SYM, TICK, TICK);

        verify(indexService, timeout(2000)).registerLiquidation(afterA);
        verify(cacheService, timeout(2000)).zAdd(SL_LONG, "9:slB", 99200d);
        verify(riskService).batchTriggerStopLoss(9L, List.of("slA"), TICK);
        verify(riskService, never()).batchTriggerStopLoss(9L, List.of("slB"), TICK);
        verify(cacheService, never()).zAdd(eq(SL_LONG), eq("9:slA"), anyDouble());
    }

    @Test
    void 强平平掉了仓位_后面的止损作废_什么都不放回() {
        longHits(Map.of("9:slA", 99500d, "9:slB", 99200d));
        when(positionMapper.selectById(9L)).thenReturn(position("LONG", FuturesPosition.ISOLATED, "LIQUIDATED", "slB"));

        service.checkOnPriceUpdate(SYM, TICK, TICK);

        verify(riskService, timeout(2000)).forceClose(9L, TICK);
        verify(indexService, after(500).never()).registerLiquidation(any());
        verify(cacheService, never()).zAdd(anyString(), anyString(), anyDouble());
    }

    @Test
    void 同批还命中止盈_先处理止损强平这一侧_止盈放回等下一秒() {
        // mark 和最新成交价两边都穿（补漏区间、巡检取价都可能这样）
        longHits(Map.of("9:slA", 99500d));
        when(cacheService.zRangeByScoreAndRemove(TP_LONG, 0d, 104500d)).thenReturn(Map.of("9:tp1", 104000d));
        FuturesPosition after = position("LONG", FuturesPosition.ISOLATED, "OPEN");
        after.setTakeProfits(List.of(new FuturesTakeProfit("tp1", new BigDecimal("104000"), BigDecimal.ONE)));
        when(positionMapper.selectById(9L)).thenReturn(after);

        service.checkOnPriceUpdate(SYM, TICK, new BigDecimal("104500"));

        verify(cacheService, timeout(2000)).zAdd(TP_LONG, "9:tp1", 104000d);
        verify(riskService).batchTriggerStopLoss(9L, List.of("slA"), TICK);
        verify(riskService).forceClose(9L, TICK);
        verify(riskService, never()).batchTriggerTakeProfit(anyLong(), any(), any());
    }

    // ==================== 全仓止损转账户级 ====================

    @Test
    void 全仓止损_转给账户级处理_不直接执行() {
        when(cacheService.zRangeByScoreAndRemove(SL_LONG, 99100d, Double.MAX_VALUE)).thenReturn(Map.of("9:sl1", 99500d));
        FuturesPosition cross = position("LONG", FuturesPosition.CROSS, "OPEN", "sl1");
        when(positionMapper.selectById(9L)).thenReturn(cross);

        service.checkOnPriceUpdate(SYM, TICK, TICK);

        verify(crossLiquidation, timeout(2000)).triggerStopLoss(cross, List.of("sl1"), TICK);
        verify(riskService, never()).batchTriggerStopLoss(anyLong(), any(), any());
    }
}
