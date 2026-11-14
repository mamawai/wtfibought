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
    static ObjectNode of(TradeGuard.OpenReq req, BigDecimal mark, BigDecimal equity) {
        BigDecimal entryRef = req.entryPrice(mark);
        BigDecimal notional = req.notional(mark);
        BigDecimal fee = req.entries().stream().map(e -> e.quantity().multiply(e.price(mark))
                .multiply((e.limitPrice() == null ? FuturesFeeRates.TAKER : FuturesFeeRates.MAKER).add(FuturesFeeRates.TAKER)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal stopDist = stopDistance(req, entryRef);
        BigDecimal lossAtStop = stopDist.multiply(req.quantity()).add(fee);
        ObjectNode f = MAPPER.createObjectNode();
        f.put("quantity", req.quantity());
        f.put("entryRef", entryRef);
        f.put("notional", usdt(notional));
        f.put("feeRoundTrip", usdt(fee));
        f.put("stopDistancePct", pct(stopDist, entryRef));
        f.put("lossAtStop", usdt(lossAtStop));
        f.put("riskPctOfEquity", pct(lossAtStop, equity));
        if (!req.takeProfits().isEmpty()) {
            // 各档都成交的盈利；占比合计不到 100 的，剩下那部分仓位不算
            BigDecimal gain = fee.negate();
            for (EntryRung entry : req.entries()) {
                List<BigDecimal> quantities = TargetRung.quantities(req.takeProfits(), entry.quantity(), req.symbol());
                for (int i = 0; i < quantities.size(); i++) {
                    gain = gain.add(req.takeProfits().get(i).price().subtract(entry.price(mark)).abs().multiply(quantities.get(i)));
                }
            }
            f.put("gainAtTargets", usdt(gain));
            f.put("rewardRiskAfterFees", gain.divide(lossAtStop, 2, RoundingMode.HALF_UP));
            f.put("targetsCoverPct", req.takeProfits().stream().map(TargetRung::percent).reduce(BigDecimal.ZERO, BigDecimal::add));
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
