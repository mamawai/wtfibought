package com.mawai.wiibsim.service;

import java.math.BigDecimal;
import java.util.Collection;

import com.mawai.wiibcommon.dto.FuturesStopLossRequest;
import com.mawai.wiibcommon.dto.FuturesTakeProfitRequest;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;

public interface FuturesRiskService {

    void setStopLoss(Long userId, FuturesStopLossRequest request);

    void setTakeProfit(Long userId, FuturesTakeProfitRequest request);

    /** 逐仓强平：price 是判强平的标记价，priceAt 是这个价出现的时刻（旧价不扫现在的盘口） */
    void forceClose(Long positionId, BigDecimal price, long priceAt);

    /** 止损触发平仓：price 是触发它的成交价，priceAt 同 forceClose */
    void batchTriggerStopLoss(Long positionId, Collection<String> slIds, BigDecimal price, long priceAt);

    void batchTriggerTakeProfit(Long positionId, Collection<String> tpIds, BigDecimal price, long priceAt);

    void checkAndLiquidate(Long positionId, BigDecimal currentPrice);

    /**
     * 逐仓仓位按标记价已经够强平了，就在调用方的事务里直接强平（调用方已拿仓位锁）。
     * 返回强平单；全仓仓位、没到强平、或这个币没配档位判不了，返回 null
     */
    FuturesOrder liquidateIfReached(FuturesPosition position, BigDecimal markPrice);
}
