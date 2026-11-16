package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.PredictionBet;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.PredictionBetMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 资产估值统一口径回归，锁死三种坏口径：
 * 缺价整仓蒸发(快照)、缺价按0计天文亏损(排行榜)、永远不算浮盈亏(破产判定)。
 */
class AssetValuationServiceTest {

    private static FuturesPosition position(String side, String entry, String qty, String margin) {
        FuturesPosition fp = new FuturesPosition();
        fp.setSide(side);
        fp.setEntryPrice(new BigDecimal(entry));
        fp.setQuantity(new BigDecimal(qty));
        fp.setMargin(new BigDecimal(margin));
        return fp;
    }

    @Test
    void missingPriceKeepsMarginAndZeroPnl() {
        FuturesPosition fp = position("LONG", "100000", "0.5", "5000");
        assertEquals(new BigDecimal("5000"), AssetValuationService.futuresPositionValue(fp, null));
        assertEquals(BigDecimal.ZERO, AssetValuationService.futuresUnrealizedPnl(fp, null));
    }

    @Test
    void longAndShortPnlDirections() {
        FuturesPosition long1 = position("LONG", "100000", "0.5", "5000");
        FuturesPosition short1 = position("SHORT", "100000", "0.5", "5000");
        BigDecimal mark = new BigDecimal("102000");
        // LONG 涨2000/张×0.5 = +1000；SHORT 同价 = -1000
        assertEquals(0, new BigDecimal("1000").compareTo(AssetValuationService.futuresUnrealizedPnl(long1, mark)));
        assertEquals(0, new BigDecimal("-1000").compareTo(AssetValuationService.futuresUnrealizedPnl(short1, mark)));
        assertEquals(0, new BigDecimal("6000").compareTo(AssetValuationService.futuresPositionValue(long1, mark)));
        assertEquals(0, new BigDecimal("4000").compareTo(AssetValuationService.futuresPositionValue(short1, mark)));
    }

    /**
     * 合约账户权益 = 余额 + 冻结 + 各持仓估值。全仓只计浮盈亏：保证金从没离开余额，再加就是同一笔钱算两遍
     * （竞技场曾因此把亏损显示成 +1281）；逐仓的保证金已经划走，要加回来。
     */
    @Test
    void futuresEquity全仓只计浮盈亏_逐仓加回保证金() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FuturesPosition.class);
        CacheService cache = mock(CacheService.class);
        FuturesPositionMapper positions = mock(FuturesPositionMapper.class);
        AssetValuationService service = new AssetValuationService(cache, mock(PredictionBetMapper.class), positions);
        FuturesPosition cross = position("LONG", "100000", "0.5", "5000");
        cross.setSymbol("BTCUSDT");
        cross.setMarginMode(FuturesPosition.CROSS);
        FuturesPosition isolated = position("SHORT", "2000", "1", "300");
        isolated.setSymbol("ETHUSDT");
        isolated.setMarginMode(FuturesPosition.ISOLATED);
        when(positions.selectList(any())).thenReturn(List.of(cross, isolated));
        when(cache.getMarkPrice("BTCUSDT")).thenReturn(new BigDecimal("102000"));
        when(cache.getMarkPrice("ETHUSDT")).thenReturn(new BigDecimal("1900"));
        User user = new User();
        user.setId(9L);
        user.setBalance(new BigDecimal("10000"));
        user.setFrozenBalance(new BigDecimal("50"));

        // 10000 + 50 + 1000（全仓只计浮盈）+ 300 + 100（逐仓保证金加浮盈）
        assertEquals(0, new BigDecimal("11450").compareTo(service.futuresEquity(user)));
    }

    @Test
    void predictionBetValue本回合按买一_没买一计0_已收盘按成本() {
        long ws = 1_790_016_000L;
        PredictionBet bet = new PredictionBet();
        bet.setWindowStart(ws);
        bet.setContracts(new BigDecimal("10"));
        bet.setCost(new BigDecimal("5"));
        assertEquals(0, new BigDecimal("6.5").compareTo(
                AssetValuationService.predictionBetValue(bet, new BigDecimal("0.65"), ws)));
        assertEquals(BigDecimal.ZERO, AssetValuationService.predictionBetValue(bet, null, ws));
        // 换回合后等结算：买一已是新回合的，不看它
        assertEquals(new BigDecimal("5"), AssetValuationService.predictionBetValue(bet, new BigDecimal("0.10"), ws + 300));
    }
}
