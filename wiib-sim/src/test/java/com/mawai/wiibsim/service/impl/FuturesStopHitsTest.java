package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.TradeRecord;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.service.CrossLiquidationService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.PriceTick;
import com.mawai.wiibsim.service.TradeHit;
import com.mawai.wiibsim.service.TraderSymbolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 止损止盈看成交价：一批行情里找每一档最先被摸到的那一格，同一个仓位按摸到的先后执行。
 * 例：逐仓多 1 BTC@100，T0 开仓、档位也挂在 T0；止损 99，止盈 101。
 */
class FuturesStopHitsTest {

    private static final String SYM = "BTCUSDT";
    private static final String SL_LONG = "futures:sl:long:" + SYM;
    private static final String TP_LONG = "futures:tp:long:" + SYM;
    private static final long T0 = 1_700_000_000_000L;

    private final CacheService cacheService = mock(CacheService.class);
    private final FuturesRiskService riskService = mock(FuturesRiskService.class);
    private final FuturesPositionMapper positionMapper = mock(FuturesPositionMapper.class);
    private final CrossLiquidationService crossLiquidation = mock(CrossLiquidationService.class);
    private final FuturesLiquidationServiceImpl service = new FuturesLiquidationServiceImpl(riskService, cacheService,
            mock(TraderSymbolService.class), positionMapper, mock(FuturesPositionIndexService.class), crossLiquidation);

    @BeforeEach
    void setUp() {
        when(cacheService.zRangeByScoreAndRemove(anyString(), anyDouble(), anyDouble())).thenReturn(Map.of());
    }

    private static FuturesPosition longPos(String marginMode) {
        FuturesPosition p = new FuturesPosition();
        p.setId(9L);
        p.setUserId(7L);
        p.setSymbol(SYM);
        p.setSide("LONG");
        p.setMarginMode(marginMode);
        p.setStatus("OPEN");
        p.setCreatedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(T0), ZoneId.systemDefault()));
        p.setStopLosses(new ArrayList<>());
        p.setTakeProfits(new ArrayList<>());
        return p;
    }

    private static FuturesStopLoss sl(String id, String price, long createdAt) {
        FuturesStopLoss sl = new FuturesStopLoss(id, new BigDecimal(price), BigDecimal.ONE);
        sl.setCreatedAt(createdAt);
        return sl;
    }

    private static FuturesTakeProfit tp(String id, String price) {
        FuturesTakeProfit tp = new FuturesTakeProfit(id, new BigDecimal(price), BigDecimal.ONE);
        tp.setCreatedAt(T0);
        return tp;
    }

    /** feed 在 rt 收到的一笔成交 */
    private static PriceTick trade(String price, long rt) {
        return PriceTick.of(new TradeRecord(new BigDecimal(price), rt, rt));
    }

    private static PriceTick bar(String open, String low, String high) {
        return PriceTick.of(new KlineBar(T0, T0 + 59_999L, new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(open), BigDecimal.ONE));
    }

    private void run(List<PriceTick> ticks) {
        TradeHit.runByUser(service.stopHits(SYM, ticks));
    }

    @Test
    void 同一批先跌破止损再涨过止盈_先止损后止盈() {
        FuturesPosition pos = longPos(FuturesPosition.ISOLATED);
        pos.getStopLosses().add(sl("sl1", "99", T0));
        pos.getTakeProfits().add(tp("tp1", "101"));
        when(positionMapper.selectById(9L)).thenReturn(pos);
        when(cacheService.zRangeByScoreAndRemove(SL_LONG, 98.9, Double.MAX_VALUE)).thenReturn(Map.of("9:sl1", 99d));
        when(cacheService.zRangeByScoreAndRemove(TP_LONG, 0, 101.2)).thenReturn(Map.of("9:tp1", 101d));

        run(List.of(trade("100", T0 + 10), trade("98.9", T0 + 20), trade("101.2", T0 + 30)));

        // 止盈锁内看到仓位已平就是空操作；先后由这里定
        InOrder inOrder = inOrder(riskService);
        inOrder.verify(riskService).batchTriggerStopLoss(9L, List.of("sl1"), new BigDecimal("98.9"), T0 + 20);
        inOrder.verify(riskService).batchTriggerTakeProfit(9L, List.of("tp1"), new BigDecimal("101.2"), T0 + 30);
    }

    @Test
    void 一笔同时摸到两档止损_并成一次平仓() {
        FuturesPosition pos = longPos(FuturesPosition.ISOLATED);
        pos.getStopLosses().add(sl("slA", "99.5", T0));
        pos.getStopLosses().add(sl("slB", "99.2", T0));
        when(positionMapper.selectById(9L)).thenReturn(pos);
        when(cacheService.zRangeByScoreAndRemove(SL_LONG, 99.1, Double.MAX_VALUE))
                .thenReturn(Map.of("9:slA", 99.5, "9:slB", 99.2));

        run(List.of(trade("100", T0 + 10), trade("99.1", T0 + 20)));

        verify(riskService).batchTriggerStopLoss(eq(9L), argThat(ids -> ids.size() == 2
                && ids.containsAll(List.of("slA", "slB"))), eq(new BigDecimal("99.1")), eq(T0 + 20));
    }

    @Test
    void 止损挂在那笔成交之后_不算_原样放回() {
        FuturesPosition pos = longPos(FuturesPosition.ISOLATED);
        pos.getStopLosses().add(sl("sl1", "99", T0 + 100));
        when(positionMapper.selectById(9L)).thenReturn(pos);
        when(cacheService.zRangeByScoreAndRemove(SL_LONG, 98.9, Double.MAX_VALUE)).thenReturn(Map.of("9:sl1", 99d));

        run(List.of(trade("98.9", T0 + 50)));

        verify(cacheService).zAdd(SL_LONG, "9:sl1", 99d);
        verifyNoInteractions(riskService);
    }

    @Test
    void K线里止损止盈都摸到_分不清先后按止损在前() {
        FuturesPosition pos = longPos(FuturesPosition.ISOLATED);
        pos.getStopLosses().add(sl("sl1", "99", T0));
        pos.getTakeProfits().add(tp("tp1", "101"));
        when(positionMapper.selectById(9L)).thenReturn(pos);
        when(cacheService.zRangeByScoreAndRemove(SL_LONG, 98, Double.MAX_VALUE)).thenReturn(Map.of("9:sl1", 99d));
        when(cacheService.zRangeByScoreAndRemove(TP_LONG, 0, 102)).thenReturn(Map.of("9:tp1", 101d));

        run(List.of(bar("100", "98", "102")));

        // 开盘在两档中间：各按自己的触发价，时刻是这根 K 线开盘（旧价，不扫现在的盘口）
        InOrder inOrder = inOrder(riskService);
        inOrder.verify(riskService).batchTriggerStopLoss(9L, List.of("sl1"), new BigDecimal("99"), T0);
        inOrder.verify(riskService).batchTriggerTakeProfit(9L, List.of("tp1"), new BigDecimal("101"), T0);
    }

    @Test
    void K线开盘就已经跌过止损_按开盘价成交() {
        FuturesPosition pos = longPos(FuturesPosition.ISOLATED);
        pos.getStopLosses().add(sl("sl1", "99", T0));
        when(positionMapper.selectById(9L)).thenReturn(pos);
        when(cacheService.zRangeByScoreAndRemove(SL_LONG, 96, Double.MAX_VALUE)).thenReturn(Map.of("9:sl1", 99d));

        run(List.of(bar("97", "96", "98")));

        verify(riskService).batchTriggerStopLoss(9L, List.of("sl1"), new BigDecimal("97"), T0);
    }

    @Test
    void 全仓止损转给账户级处理() {
        FuturesPosition pos = longPos(FuturesPosition.CROSS);
        pos.getStopLosses().add(sl("sl1", "99", T0));
        when(positionMapper.selectById(9L)).thenReturn(pos);
        when(cacheService.zRangeByScoreAndRemove(SL_LONG, 98.9, Double.MAX_VALUE)).thenReturn(Map.of("9:sl1", 99d));

        run(List.of(trade("98.9", T0 + 20)));

        verify(crossLiquidation).triggerStopLoss(pos, List.of("sl1"), new BigDecimal("98.9"), T0 + 20);
        verify(riskService, never()).batchTriggerStopLoss(anyLong(), any(), any(), anyLong());
    }

    @Test
    void 执行失败_库里还在的档放回索引() {
        FuturesPosition pos = longPos(FuturesPosition.ISOLATED);
        pos.getStopLosses().add(sl("sl1", "99", T0));
        when(positionMapper.selectById(9L)).thenReturn(pos);
        when(cacheService.zRangeByScoreAndRemove(SL_LONG, 98.9, Double.MAX_VALUE)).thenReturn(Map.of("9:sl1", 99d));
        doThrow(new BizException(ErrorCode.ORDER_PROCESSING))
                .when(riskService).batchTriggerStopLoss(anyLong(), any(), any(), anyLong());

        run(List.of(trade("98.9", T0 + 20)));

        verify(cacheService).zAdd(SL_LONG, "9:sl1", 99d);
    }

    /** 摘下来以后查仓位就出错：判不了，摘下来的原样放回，别的仓位照判 */
    @Test
    void 查仓位出错_摘下来的放回_不连累别的仓位() {
        FuturesPosition other = longPos(FuturesPosition.ISOLATED);
        other.setId(10L);
        other.getStopLosses().add(sl("sl2", "99", T0));
        when(positionMapper.selectById(9L)).thenThrow(new RuntimeException("数据库抖了一下"));
        when(positionMapper.selectById(10L)).thenReturn(other);
        when(cacheService.zRangeByScoreAndRemove(SL_LONG, 98.9, Double.MAX_VALUE))
                .thenReturn(Map.of("9:sl1", 99d, "10:sl2", 99d));

        run(List.of(trade("98.9", T0 + 20)));

        verify(cacheService).zAdd(SL_LONG, "9:sl1", 99d);
        verify(riskService).batchTriggerStopLoss(10L, List.of("sl2"), new BigDecimal("98.9"), T0 + 20);
    }

    @Test
    void 仓位已平_索引是过期项_不执行也不放回() {
        FuturesPosition pos = longPos(FuturesPosition.ISOLATED);
        pos.getStopLosses().add(sl("sl1", "99", T0));
        pos.setStatus("CLOSED");
        when(positionMapper.selectById(9L)).thenReturn(pos);
        when(cacheService.zRangeByScoreAndRemove(SL_LONG, 98.9, Double.MAX_VALUE)).thenReturn(Map.of("9:sl1", 99d));

        run(List.of(trade("98.9", T0 + 20)));

        verifyNoInteractions(riskService);
        verify(cacheService, never()).zAdd(anyString(), anyString(), anyDouble());
    }
}
