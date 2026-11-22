package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibcommon.entity.AiTraderDecision;

import java.math.BigDecimal;

/**
 * 权益回落哨兵的触发信息：账户权益从参照点回落到了主人设的比例。不归某一个币，叫醒的是一轮整盘的。
 *
 * @param peak         参照点：上次报警之后权益到过的最高点
 * @param peakAt       权益到参照点的时刻 ms
 * @param equity       报警那一刻的权益
 * @param dropPct      从参照点回落了百分之几
 * @param thresholdPct 主人设的比例
 */
public record EquityDropTrigger(BigDecimal peak, long peakAt, BigDecimal equity, BigDecimal dropPct,
                                BigDecimal thresholdPct, long triggeredAt) implements WakeTrigger {

    @Override
    public String kind() {
        return AiTraderDecision.KIND_DRAWDOWN;
    }

    @Override
    public String symbol() {
        return null;
    }
}
