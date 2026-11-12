package com.mawai.wiibagent.trader.trade;

import com.mawai.wiibcommon.market.FuturesFeeRates;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 开仓回执里这笔单子的事实：往返手续费估算、止损距离（% 与心跳周期 ATR 倍数）、止损打掉亏权益的百分比；
 * 设了止盈的再给各档全部成交的盈利和扣费后盈亏比，没设就没有这两项。只报数不评价。
 * 入场参考价：市价单按标记价、限价单按限价。
 */
final class OpenFacts {

    private OpenFacts() {
    }

    /** 纯算术的几项；ATR 那几项成交后另加（{@link #addAtr}） */
    static ObjectNode of(TradeGuard.OpenReq req, BigDecimal entryRef, BigDecimal equity) {
        BigDecimal notional = req.quantity().multiply(entryRef);
        // 开仓手续费：限价按 maker、市价按 taker；平仓手续费按 taker 估算。
        BigDecimal openRate = "LIMIT".equals(req.orderType()) ? FuturesFeeRates.MAKER : FuturesFeeRates.TAKER;
        BigDecimal fee = notional.multiply(openRate.add(FuturesFeeRates.TAKER));
        BigDecimal stopDist = stopDistance(req, entryRef);
        BigDecimal lossAtStop = stopDist.multiply(req.quantity()).add(fee);
        ObjectNode f = MAPPER.createObjectNode();
        f.put("entryRef", entryRef);
        f.put("notional", usdt(notional));
        f.put("feeRoundTrip", usdt(fee));
        f.put("stopDistancePct", pct(stopDist, entryRef));
        f.put("lossAtStop", usdt(lossAtStop));
        f.put("riskPctOfEquity", pct(lossAtStop, equity));
        if (!req.takeProfits().isEmpty()) {
            // 各档都成交的盈利；占比合计不到 100 的，剩下那部分仓位不算
            List<BigDecimal> quantities = TargetRung.quantities(req.takeProfits(), req.quantity(), req.symbol());
            BigDecimal gain = fee.negate();
            BigDecimal covered = BigDecimal.ZERO;
            for (int i = 0; i < quantities.size(); i++) {
                TargetRung rung = req.takeProfits().get(i);
                gain = gain.add(rung.price().subtract(entryRef).abs().multiply(quantities.get(i)));
                covered = covered.add(rung.percent());
            }
            f.put("gainAtTargets", usdt(gain));
            f.put("rewardRiskAfterFees", gain.divide(lossAtStop, 2, RoundingMode.HALF_UP));
            f.put("targetsCoverPct", covered);
        }
        return f;
    }

    /** 心跳周期的 ATR14 与止损距离是它的几倍；atr 取不到（null）就不加 */
    static void addAtr(ObjectNode facts, TradeGuard.OpenReq req, BigDecimal entryRef, String atrInterval, BigDecimal atr) {
        if (atr == null) {
            return;
        }
        facts.put("atrInterval", atrInterval);
        facts.put("atr14", atr);
        facts.put("stopDistanceAtr", stopDistance(req, entryRef).divide(atr, 2, RoundingMode.HALF_UP));
    }

    private static BigDecimal stopDistance(TradeGuard.OpenReq req, BigDecimal entryRef) {
        return entryRef.subtract(req.stopLossPrice()).abs();
    }

    private static BigDecimal usdt(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 2, RoundingMode.HALF_UP);
    }
}
