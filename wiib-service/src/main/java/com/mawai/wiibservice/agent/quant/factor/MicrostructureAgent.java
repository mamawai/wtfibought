package com.mawai.wiibservice.agent.quant.factor;

import com.mawai.wiibservice.agent.quant.domain.*;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class MicrostructureAgent implements FactorAgent {

    @Override
    public String name() { return "microstructure"; }

    @Override
    public List<AgentVote> evaluate(FeatureSnapshot s) {
        double bia = s.bidAskImbalance();
        double td = s.tradeDelta();
        double oi = s.oiChangeRate();
        double liqPressure = s.liquidationPressure();
        double liqVol = s.liquidationVolumeUsdt();
        double topTrader = s.topTraderBias();
        double takerPressure = s.takerBuySellPressure();
        BigDecimal lastPrice = s.lastPrice();
        Map<String, BigDecimal> pc = s.priceChanges();
        double price5mBps = pc != null && pc.containsKey("5m")
                ? pc.get("5m").doubleValue() * 100 : 0;

        // === 各因子独立评分 ===
        double bidAskScore = clamp(bia * 2.5);
        double deltaScore = clamp(td);

        // OI-价格共振
        double oiScore = 0;
        if (Math.abs(oi) > 0.01) {
            boolean oiUp = oi > 0;
            boolean priceUp = price5mBps > 0;
            if (oiUp && priceUp) oiScore = 0.6;
            else if (oiUp) oiScore = -0.6;
            else if (priceUp) oiScore = -0.3;
            else oiScore = 0.3;
        }

        double liqScore = clamp(-liqPressure);
        double topTraderScore = clamp(topTrader);
        double takerScore = clamp(takerPressure);

        // === 0-10min: 盘口+taker主导(即时信号) ===
        List<String> flags0 = buildFlags(bia, td, oi, price5mBps, liqPressure, liqVol, topTrader, takerPressure, "0_10");
        double raw0 = 0.25 * bidAskScore + 0.20 * deltaScore + 0.15 * oiScore
                     + 0.15 * liqScore + 0.10 * topTraderScore + 0.15 * takerScore;

        // === 10-20min: OI+大户升权(中期信号) ===
        List<String> flags1 = buildFlags(bia, td, oi, price5mBps, liqPressure, liqVol, topTrader, takerPressure, "10_20");
        double raw1 = 0.10 * bidAskScore + 0.10 * deltaScore + 0.20 * oiScore
                     + 0.20 * liqScore + 0.20 * topTraderScore + 0.20 * takerScore;

        // confidence: 覆盖所有参与评分的因子
        double signalStrength = (Math.abs(bia) + Math.abs(td) + Math.abs(takerPressure)
                + Math.min(1, Math.abs(oi) * 10) + Math.abs(liqPressure) + Math.abs(topTrader)) / 6.0;
        double conf = Math.min(1.0, signalStrength * 1.5);

        int volBps = s.atr1m() != null && lastPrice != null && lastPrice.signum() > 0
                ? s.atr1m().multiply(BigDecimal.valueOf(10000))
                    .divide(lastPrice, 0, java.math.RoundingMode.HALF_UP).intValue()
                : 20;

        // === 20-30min: OI/爆仓/大户/taker仍有短延续性，但盘口信号衰减 ===
        List<String> flags2 = buildFlags(bia, td, oi, price5mBps, liqPressure, liqVol, topTrader, takerPressure, "20_30");
        double raw2 = 0.05 * bidAskScore + 0.08 * deltaScore + 0.22 * oiScore
                     + 0.25 * liqScore + 0.20 * topTraderScore + 0.20 * takerScore;
        double conf2 = conf * 0.35;

        List<AgentVote> votes = new ArrayList<>(3);
        votes.add(buildVote("0_10", raw0, conf, volBps, flags0));
        votes.add(buildVote("10_20", raw1, conf * 0.6, volBps, flags1));
        votes.add(conf2 < 0.12
                ? AgentVote.noTrade(name(), "20_30", "MICRO_TOO_WEAK")
                : buildVote("20_30", raw2, conf2, volBps, flags2));

        log.info("[Q3.micro] bia={} td={} oi={} liq={} topTrader={} taker={} → scores[{},{},{}] conf={} flags0={} flags1={} flags2={}",
                fmt(bia), fmt(td), fmt(oi), fmt(liqPressure), fmt(topTrader), fmt(takerPressure),
                fmt(raw0), fmt(raw1), fmt(raw2), String.format("%.2f", conf), flags0, flags1, flags2);
        return votes;
    }

    private List<String> buildFlags(double bia, double td, double oi, double price5mBps,
                                     double liqPressure, double liqVol, double topTrader, double takerPressure,
                                     String horizon) {
        List<String> flags = new ArrayList<>();
        if (Math.abs(bia) > 0.3) flags.add(bia > 0 ? "BID_DOMINANT" : "ASK_DOMINANT");
        if (Math.abs(td) > 0.3) flags.add(td > 0 ? "AGGRESSIVE_BUY" : "AGGRESSIVE_SELL");
        if (Math.abs(oi) > 0.01) {
            boolean oiUp = oi > 0, priceUp = price5mBps > 0;
            if (oiUp && priceUp) flags.add("OI_UP_PRICE_UP");
            else if (oiUp) flags.add("OI_UP_PRICE_DOWN");
            else if (priceUp) flags.add("OI_DOWN_PRICE_UP");
            else flags.add("OI_DOWN_PRICE_DOWN");
        }
        if (liqVol > 500_000) flags.add(liqPressure > 0 ? "HEAVY_LONG_LIQ" : "HEAVY_SHORT_LIQ");
        // 10-20min: 大户和taker信号更相关
        if ("10_20".equals(horizon)) {
            if (Math.abs(topTrader) > 0.3) flags.add(topTrader > 0 ? "TOP_ADDING_LONG" : "TOP_ADDING_SHORT");
            if (Math.abs(takerPressure) > 0.3) flags.add(takerPressure > 0 ? "TAKER_BUY_SURGE" : "TAKER_SELL_SURGE");
        } else {
            if (Math.abs(takerPressure) > 0.3) flags.add(takerPressure > 0 ? "TAKER_BUY_SURGE" : "TAKER_SELL_SURGE");
        }
        return flags;
    }

    private AgentVote buildVote(String horizon, double score, double conf, int volBps, List<String> reasons) {
        double s = clamp(score);
        Direction dir = Math.abs(s) < 0.05 ? Direction.NO_TRADE : (s > 0 ? Direction.LONG : Direction.SHORT);
        int moveBps = (int) (Math.abs(s) * volBps * 0.6);
        return new AgentVote(name(), horizon, dir, s, Math.clamp(conf, 0, 1),
                moveBps, volBps, List.copyOf(reasons), List.of());
    }

    private static double clamp(double v) { return Math.clamp(v, -1, 1); }
    private static String fmt(double v) { return String.format("%.3f", v); }
}
