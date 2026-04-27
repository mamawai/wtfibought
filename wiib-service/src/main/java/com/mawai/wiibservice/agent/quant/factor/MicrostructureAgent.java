package com.mawai.wiibservice.agent.quant.factor;

import com.mawai.wiibservice.agent.quant.domain.*;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 微观结构因子 Agent。
 * 组合盘口失衡、主动成交、OI、爆仓、资金费率、现货/合约联动等短线信号，给三个 horizon 输出交易方向。
 */
@Slf4j
public class MicrostructureAgent implements FactorAgent {

    @Override
    public String name() { return "microstructure"; }

    @Override
    public List<AgentVote> evaluate(FeatureSnapshot s) {
        double bia = s.bidAskImbalance();
        double td = s.tradeDelta();
        double tradeIntensity = s.tradeIntensity();
        double largeBias = s.largeTradeBias();
        double oi = s.oiChangeRate();
        double liqPressure = s.liquidationPressure();
        double liqVol = s.liquidationVolumeUsdt();
        double topTrader = s.topTraderBias();
        double takerPressure = s.takerBuySellPressure();
        double spotBia = s.spotBidAskImbalance();
        double spotLeadLag = s.spotLeadLagScore();
        double basisBps = s.spotPerpBasisBps();
        BigDecimal lastPrice = s.lastPrice();
        Map<String, BigDecimal> pc = s.priceChanges();
        double price5mBps = pc != null && pc.containsKey("5m")
                ? pc.get("5m").doubleValue() * 100 : 0;
        double spotPrice5mBps = s.spotPriceChange5m() != null ? s.spotPriceChange5m().doubleValue() * 100 : 0;

        double fundDev = s.fundingDeviation();
        double fundTrend = s.fundingRateTrend();
        double fundExtreme = s.fundingRateExtreme();
        double lsr = s.lsrExtreme();

        // === 各因子独立评分 ===
        double bidAskScore = clamp(bia * 1.8);
        // aggTrade 可用时用强度加权 delta，放大高活跃度下的信号
        double intensityBoost = tradeIntensity > 1.5 ? Math.min(1.3, 1.0 + (tradeIntensity - 1.5) * 0.2) : 1.0;
        double deltaScore = clamp(td * intensityBoost);
        double largeBiasScore = clamp(largeBias);

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
        double spotBookScore = clamp(spotBia * 2.0);
        double leadLagScore = clamp(spotLeadLag);
        double basisScore = clamp(-basisBps / 12.0);
        double spotConfirmScore = calcSpotConfirmScore(spotPrice5mBps, price5mBps, spotBia);

        // 资金费率复合（反向：高费率=多头拥挤→空头信号）
        double fundingRaw = -(0.5 * fundDev + 0.3 * fundExtreme + 0.2 * fundTrend);
        double fundingScore = clamp(fundingRaw);
        // 多空比（反向：极端多头→空头信号）
        double lsrScore = clamp(-lsr);

        // === 0-10min: 盘口+taker主导，衍生品情绪权重低 ===
        List<String> flags0 = buildFlags(bia, td, oi, price5mBps, liqPressure, liqVol, topTrader, takerPressure, "0_10");
        addSentimentFlags(flags0, fundDev, lsr);
        addCrossMarketFlags(flags0, spotBia, spotPrice5mBps, price5mBps, basisBps, spotLeadLag);
        double raw0 = 0.17 * bidAskScore + 0.11 * deltaScore + 0.03 * largeBiasScore + 0.10 * oiScore
                     + 0.10 * liqScore + 0.07 * topTraderScore + 0.11 * takerScore
                     + 0.08 * spotBookScore + 0.06 * spotConfirmScore + 0.04 * leadLagScore
                     + 0.03 * fundingScore + 0.05 * lsrScore + 0.05 * basisScore;

        // === 10-20min: OI+大户升权，资金费率权重提升 ===
        List<String> flags1 = buildFlags(bia, td, oi, price5mBps, liqPressure, liqVol, topTrader, takerPressure, "10_20");
        addSentimentFlags(flags1, fundDev, lsr);
        addCrossMarketFlags(flags1, spotBia, spotPrice5mBps, price5mBps, basisBps, spotLeadLag);
        double raw1 = 0.05 * bidAskScore + 0.03 * deltaScore + 0.02 * largeBiasScore + 0.13 * oiScore
                     + 0.12 * liqScore + 0.12 * topTraderScore + 0.13 * takerScore
                     + 0.06 * spotBookScore + 0.07 * spotConfirmScore + 0.05 * leadLagScore
                     + 0.08 * fundingScore + 0.04 * lsrScore + 0.10 * basisScore;

        // 大单/高频 flags 加到全部 horizon
        for (List<String> fl : List.of(flags0, flags1)) {
            if (Math.abs(largeBias) > 0.3) fl.add(largeBias > 0 ? "LARGE_BUY_DOMINANT" : "LARGE_SELL_DOMINANT");
            if (tradeIntensity > 2.0) fl.add("HIGH_TRADE_INTENSITY");
        }

        double signalStrength = (Math.abs(bia) + Math.abs(td) + Math.abs(takerPressure)
                + Math.min(1, Math.abs(oi) * 10) + Math.abs(liqPressure) + Math.abs(topTrader)
                + Math.abs(fundingRaw) + Math.abs(lsr) + Math.abs(spotBia)
                + Math.abs(spotLeadLag) + Math.min(1, Math.abs(basisBps) / 12.0)
                + Math.abs(largeBias)) / 12.0;

        // 方向一致度：13 个带符号 Score 用统一"+多-空"语义（已消化 funding/basis/lsr 反向解读）
        // coherence = |带符号和|/|绝对值和|，完全同向=1，完全对冲=0
        // 避免"12 因子都活跃但多空对冲"时 conf 虚高
        double scoreAbsSum = Math.abs(bidAskScore) + Math.abs(deltaScore) + Math.abs(largeBiasScore)
                + Math.abs(oiScore) + Math.abs(liqScore) + Math.abs(topTraderScore)
                + Math.abs(takerScore) + Math.abs(spotBookScore) + Math.abs(spotConfirmScore)
                + Math.abs(leadLagScore) + Math.abs(fundingScore) + Math.abs(lsrScore)
                + Math.abs(basisScore);
        double scoreSignedSum = bidAskScore + deltaScore + largeBiasScore
                + oiScore + liqScore + topTraderScore
                + takerScore + spotBookScore + spotConfirmScore
                + leadLagScore + fundingScore + lsrScore
                + basisScore;
        double coherence = scoreAbsSum > 1e-9 ? Math.abs(scoreSignedSum) / scoreAbsSum : 0;
        // 对冲场景留底 40%（强对冲本身也有信息量——告诉 judge 这里分歧大）
        double conf = Math.min(1.0, signalStrength * 1.5 * (0.4 + 0.6 * coherence));

        int volBps = s.atr1m() != null && lastPrice != null && lastPrice.signum() > 0
                ? s.atr1m().multiply(BigDecimal.valueOf(10000))
                    .divide(lastPrice, 0, java.math.RoundingMode.HALF_UP).intValue()
                : 20;

        // === 20-30min: 衍生品情绪+OI/大户主导，盘口信号衰减 ===
        List<String> flags2 = buildFlags(bia, td, oi, price5mBps, liqPressure, liqVol, topTrader, takerPressure, "20_30");
        addSentimentFlags(flags2, fundDev, lsr);
        addCrossMarketFlags(flags2, spotBia, spotPrice5mBps, price5mBps, basisBps, spotLeadLag);
        double raw2 = 0.03 * bidAskScore + 0.02 * deltaScore + 0.02 * largeBiasScore + 0.15 * oiScore
                     + 0.16 * liqScore + 0.12 * topTraderScore + 0.14 * takerScore
                     + 0.04 * spotBookScore + 0.05 * spotConfirmScore + 0.03 * leadLagScore
                     + 0.10 * fundingScore + 0.07 * lsrScore + 0.07 * basisScore;
        double conf2 = conf * 0.35;

        List<AgentVote> votes = new ArrayList<>(3);
        votes.add(buildVote("0_10", raw0, conf, volBps, flags0));
        votes.add(buildVote("10_20", raw1, conf * 0.6, volBps, flags1));
        votes.add(conf2 < 0.12
                ? AgentVote.noTrade(name(), "20_30", "MICRO_TOO_WEAK")
                : buildVote("20_30", raw2, conf2, volBps, flags2));

        log.info("[Q3.micro] futBia={} spotBia={} td={} largeBias={} intensity={} oi={} liq={} topTrader={} taker={} basis={} spotLead={} funding={} lsr={} → scores[{},{},{}] conf={} coherence={}",
                fmt(bia), fmt(spotBia), fmt(td), fmt(largeBias), String.format("%.1f", tradeIntensity),
                fmt(oi), fmt(liqPressure), fmt(topTrader), fmt(takerPressure),
                fmt(basisBps / 10.0), fmt(spotLeadLag), fmt(fundingRaw), fmt(lsr),
                fmt(raw0), fmt(raw1), fmt(raw2), String.format("%.2f", conf), String.format("%.2f", coherence));
        return votes;
    }

    private void addSentimentFlags(List<String> flags, double fundDev, double lsr) {
        if (Math.abs(fundDev) > 0.5) flags.add(fundDev > 0 ? "HIGH_FUNDING" : "LOW_FUNDING");
        if (Math.abs(lsr) > 0.5) flags.add(lsr > 0 ? "LSR_EXTREME_LONG" : "LSR_EXTREME_SHORT");
    }

    private void addCrossMarketFlags(List<String> flags, double spotBia, double spotPrice5mBps,
                                     double perpPrice5mBps, double basisBps, double spotLeadLag) {
        if (Math.abs(spotBia) > 0.20) flags.add(spotBia > 0 ? "SPOT_BID_DOMINANT" : "SPOT_ASK_DOMINANT");
        if (Math.abs(basisBps) >= 8) flags.add(basisBps > 0 ? "PERP_PREMIUM_RICH" : "PERP_DISCOUNT_DEEP");
        if (Math.abs(spotLeadLag) > 0.35) flags.add(spotLeadLag > 0 ? "SPOT_STRONGER_THAN_PERP" : "PERP_STRONGER_THAN_SPOT");

        if (Math.abs(spotPrice5mBps) < 2 || Math.abs(perpPrice5mBps) < 2) return;
        boolean sameDirection = Math.signum(spotPrice5mBps) == Math.signum(perpPrice5mBps);
        if (sameDirection) {
            flags.add(spotPrice5mBps > 0 ? "SPOT_PERP_CONFIRM_UP" : "SPOT_PERP_CONFIRM_DOWN");
        } else {
            flags.add("SPOT_PERP_DIVERGENCE");
        }
    }

    private double calcSpotConfirmScore(double spotPrice5mBps, double perpPrice5mBps, double spotBia) {
        double base = 0;
        if (Math.abs(spotPrice5mBps) > 1.5 && Math.abs(perpPrice5mBps) > 1.5) {
            boolean sameDirection = Math.signum(spotPrice5mBps) == Math.signum(perpPrice5mBps);
            if (sameDirection) {
                base = 0.55 * Math.signum(spotPrice5mBps);
            } else {
                base = -0.45 * Math.signum(perpPrice5mBps != 0 ? perpPrice5mBps : spotPrice5mBps);
            }
        }
        return clamp(base + spotBia * 0.30);
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
