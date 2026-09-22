package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.PredictionBet;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
