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
        List<String> flags = new ArrayList<>();

        double bia = s.bidAskImbalance();
        double td = s.tradeDelta();
        double oi = s.oiChangeRate();
        double liqPressure = s.liquidationPressure();
        double liqVol = s.liquidationVolumeUsdt();
        double topTrader = s.topTraderBias();
        double takerPressure = s.takerBuySellPressure();
        BigDecimal lastPrice = s.lastPrice();
        Map<String, BigDecimal> pc = s.priceChanges();
        double price1mBps = pc != null && pc.containsKey("5m")
                ? pc.get("5m").doubleValue() * 100 : 0;

        // 盘口偏向
        double bidAskScore = clamp(bia * 2.5);
        if (Math.abs(bia) > 0.3) flags.add(bia > 0 ? "BID_DOMINANT" : "ASK_DOMINANT");

        // 主动买卖差
        double deltaScore = clamp(td);
        if (Math.abs(td) > 0.3) flags.add(td > 0 ? "AGGRESSIVE_BUY" : "AGGRESSIVE_SELL");

        // OI-价格共振
        double oiScore = 0;
        if (Math.abs(oi) > 0.01) {
            boolean oiUp = oi > 0;
            boolean priceUp = price1mBps > 0;
            if (oiUp && priceUp) { oiScore = 0.6; flags.add("OI_UP_PRICE_UP"); }
            else if (oiUp && !priceUp) { oiScore = -0.6; flags.add("OI_UP_PRICE_DOWN"); }
            else if (!oiUp && priceUp) { oiScore = -0.3; flags.add("OI_DOWN_PRICE_UP"); }
            else { oiScore = 0.3; flags.add("OI_DOWN_PRICE_DOWN"); }
        }

        // 爆仓压力: 正=多头爆仓多(利空信号取反), 负=空头爆仓多(利多信号取反)
        double liqScore = clamp(-liqPressure);
        if (liqVol > 500_000) flags.add(liqPressure > 0 ? "HEAVY_LONG_LIQ" : "HEAVY_SHORT_LIQ");

        // 大户持仓: 正=大户加多(利多), 负=大户加空(利空)
        double topTraderScore = clamp(topTrader);
        if (Math.abs(topTrader) > 0.3) flags.add(topTrader > 0 ? "TOP_ADDING_LONG" : "TOP_ADDING_SHORT");

        // 主动买卖: 正=主动买入增强(利多), 负=主动卖出增强(利空)
        double takerScore = clamp(takerPressure);
        if (Math.abs(takerPressure) > 0.3) flags.add(takerPressure > 0 ? "TAKER_BUY_SURGE" : "TAKER_SELL_SURGE");

        // 0-10min: 盘口+taker主导(即时信号)
        double raw0 = 0.25 * bidAskScore + 0.20 * deltaScore + 0.15 * oiScore
                     + 0.15 * liqScore + 0.10 * topTraderScore + 0.15 * takerScore;
        // 10-20min: OI+大户升权(中期信号)
        double raw1 = 0.10 * bidAskScore + 0.10 * deltaScore + 0.20 * oiScore
                     + 0.20 * liqScore + 0.20 * topTraderScore + 0.20 * takerScore;
        // 20-30min: 大户+爆仓主导(趋势信号)
        double raw2 = 0.05 * bidAskScore + 0.05 * deltaScore + 0.15 * oiScore
                     + 0.25 * liqScore + 0.30 * topTraderScore + 0.20 * takerScore;

        double conf = Math.min(1.0, (Math.abs(bia) + Math.abs(td) + Math.abs(takerPressure)) / 1.5);

        int volBps = s.atr1m() != null && lastPrice != null && lastPrice.signum() > 0
                ? s.atr1m().multiply(BigDecimal.valueOf(10000))
                    .divide(lastPrice, 0, java.math.RoundingMode.HALF_UP).intValue()
                : 20;

        List<AgentVote> votes = new ArrayList<>(3);
        votes.add(buildVote("0_10", raw0, conf, volBps, flags));
        votes.add(buildVote("10_20", raw1, conf * 0.6, volBps, flags));
        votes.add(buildVote("20_30", raw2, conf * 0.3, volBps, flags));

        log.info("[Q3.micro] bia={} td={} oi={} liq={} topTrader={} taker={} → scores[{},{},{}] conf={} flags={}",
                String.format("%.3f", bia), String.format("%.3f", td), String.format("%.3f", oi),
                String.format("%.3f", liqPressure), String.format("%.3f", topTrader),
                String.format("%.3f", takerPressure),
                String.format("%.3f", raw0), String.format("%.3f", raw1), String.format("%.3f", raw2),
                String.format("%.2f", conf), flags);
        return votes;
    }

    private AgentVote buildVote(String horizon, double score, double conf, int volBps, List<String> reasons) {
        double s = clamp(score);
        Direction dir = Math.abs(s) < 0.05 ? Direction.NO_TRADE : (s > 0 ? Direction.LONG : Direction.SHORT);
        int moveBps = (int) (Math.abs(s) * volBps * 0.6);
        return new AgentVote(name(), horizon, dir, s, Math.clamp(conf, 0, 1),
                moveBps, volBps, List.copyOf(reasons), List.of());
    }

    private static double clamp(double v) { return Math.clamp(v, -1, 1); }
}
