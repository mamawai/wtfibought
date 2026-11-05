package com.mawai.wiibagent.trader.trade;

import com.mawai.wiibcommon.market.FuturesFeeRates;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 开仓回执里这笔单子的事实：往返手续费估算、止损距离（% 与心跳周期 ATR 倍数）、扣费后盈亏比、
 * 止损打掉亏权益的百分比。只报数不评价。入场参考价：市价单按标记价、限价单按限价。
 */
final class OpenFacts {

    private OpenFacts() {
    }

    /** @param atr 心跳周期的 ATR14；取不到传 null，ATR 相关的项就不出 */
    static ObjectNode of(TradeGuard.OpenReq req, BigDecimal entryRef, BigDecimal equity,
                         String atrInterval, BigDecimal atr) {
        BigDecimal notional = req.quantity().multiply(entryRef);
        // 开仓腿按下单方式（限价 maker、市价 taker），平仓腿按 taker：止损止盈触发都是 taker 成交
        BigDecimal openRate = "LIMIT".equals(req.orderType()) ? FuturesFeeRates.MAKER : FuturesFeeRates.TAKER;
        BigDecimal fee = notional.multiply(openRate.add(FuturesFeeRates.TAKER));
        BigDecimal stopDist = entryRef.subtract(req.stopLossPrice()).abs();
        BigDecimal targetDist = req.takeProfitPrice().subtract(entryRef).abs();
        BigDecimal lossAtStop = stopDist.multiply(req.quantity()).add(fee);
        BigDecimal gainAtTarget = targetDist.multiply(req.quantity()).subtract(fee);
        ObjectNode f = MAPPER.createObjectNode();
        f.put("entryRef", entryRef);
        f.put("notional", usdt(notional));
        f.put("feeRoundTrip", usdt(fee));
        f.put("stopDistancePct", pct(stopDist, entryRef));
        if (atr != null) {
            f.put("atrInterval", atrInterval);
            f.put("atr14", atr);
            f.put("stopDistanceAtr", stopDist.divide(atr, 2, RoundingMode.HALF_UP));
        }
        f.put("lossAtStop", usdt(lossAtStop));
        f.put("gainAtTarget", usdt(gainAtTarget));
        f.put("rewardRiskAfterFees", gainAtTarget.divide(lossAtStop, 2, RoundingMode.HALF_UP));
        f.put("riskPctOfEquity", pct(lossAtStop, equity));
        return f;
    }

    private static BigDecimal usdt(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 2, RoundingMode.HALF_UP);
    }
}
