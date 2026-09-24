package com.mawai.wiibagent.prediction;

import com.mawai.wiibcommon.dto.PredictionBetResponse;
import com.mawai.wiibcommon.market.PredictionFee;
import com.mawai.wiibagent.prediction.PredictionJudge.Judgment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;

import static com.mawai.wiibcommon.entity.JevPredictionDecision.ACTION_BUY_DOWN;
import static com.mawai.wiibcommon.entity.JevPredictionDecision.ACTION_BUY_UP;
import static com.mawai.wiibcommon.entity.JevPredictionDecision.ACTION_HOLD;
import static com.mawai.wiibcommon.entity.JevPredictionDecision.ACTION_SELL;
import static com.mawai.wiibcommon.entity.JevPredictionDecision.ACTION_STAY_OUT;

/**
 * 预测员的规则：Jev 拍板，代码只管执行。Jev 选的那一项概率到 act-threshold 才照做（卖还要比拿着的概率高）；
 * 代码只拦机械问题：那边没人卖、没人接盘、钱包付不起一注。每注固定 base-stake。
 * <p>
 * reason 一律"代码 + 细节"，页面按首个词出中文提示，BUY / UNSURE / NO_QUOTE / MISSED 第二个词是那一边（卖出的是 SELL）：
 * BUY 下单 / PASS Jev 选不买 / UNSURE Jev 选了但把握不够 / NO_QUOTE 那边没人卖 / NO_BALANCE 没钱 /
 * HOLD Jev 选拿着 / SELL 卖出 / NO_BID 没人接盘 / MISSED 等成交时价差过了容差没抢到。
 * 按别的价成交或没抢到时，价写成 "看到的→实际的"（没价是 none），页面照这个写预计和实际。
 */
public final class PredictionRules {

    /** sim 单笔最小本金 */
    static final BigDecimal MIN_STAKE = BigDecimal.ONE;
    /** 付不起一注的 reason */
    static final String NO_BALANCE = "NO_BALANCE";
    /** 注单终态，盈亏可以算了 */
    static final Set<String> TERMINAL = Set.of("WON", "LOST", "SOLD", "DRAW");

    /** 当时盘口，缺一边就是 null */
    record Book(BigDecimal upAsk, BigDecimal upBid, BigDecimal downAsk, BigDecimal downBid) {

        BigDecimal ask(String side) {
            return "UP".equals(side) ? upAsk : downAsk;
        }

        BigDecimal bid(String side) {
            return "UP".equals(side) ? upBid : downBid;
        }
    }

    /** 入场结论：action 是 BUY_UP / BUY_DOWN / STAY_OUT；side 是 Jev 想买的那边，选不买为 null */
    record Entry(String action, String side, BigDecimal stake, String reason) {
    }

    /** 离场结论：HOLD / SELL */
    record Review(String action, String reason) {
    }

    private PredictionRules() {
    }

    /** 空仓：Jev 选买且把握够、那边有人卖、付得起就买；选不买或把握不够就不动 */
    static Entry entry(Judgment j, Book b, BigDecimal gameBalance, JevPredictionConfig cfg) {
        String choice = j.decision().choice();
        double p = j.choiceP();
        if (PredictionQuestions.PASS.equals(choice)) {
            return new Entry(ACTION_STAY_OUT, null, null, "PASS " + fmt(p));
        }
        boolean up = PredictionQuestions.BUY_UP.equals(choice);
        String side = up ? "UP" : "DOWN";
        if (p < cfg.getActThreshold()) {
            return new Entry(ACTION_STAY_OUT, side, null, "UNSURE " + side + " " + fmt(p));
        }
        BigDecimal ask = b.ask(side);
        if (ask == null) {
            return new Entry(ACTION_STAY_OUT, side, null, "NO_QUOTE " + side);
        }
        BigDecimal stake = stake(cfg.getBaseStake(), gameBalance, ask);
        if (stake == null) {
            return new Entry(ACTION_STAY_OUT, side, null, NO_BALANCE);
        }
        return new Entry(up ? ACTION_BUY_UP : ACTION_BUY_DOWN, side, stake, "BUY " + side + " " + fmt(p) + " ask " + ask.toPlainString());
    }

    /** 持仓：Jev 选卖、卖的概率比拿着高且到 act-threshold 才卖，否则拿着。没人接盘的回路里先拦了，不问 Jev */
    static Review exit(Judgment j, String side, Book b, JevPredictionConfig cfg) {
        double p = j.choiceP();
        if (!PredictionQuestions.SELL.equals(j.decision().choice())) {
            return new Review(ACTION_HOLD, "HOLD " + fmt(p));
        }
        double hold = j.decision().probabilities().getOrDefault(PredictionQuestions.HOLD, 0.0);
        if (p <= hold || p < cfg.getActThreshold()) {
            return new Review(ACTION_HOLD, "UNSURE SELL " + fmt(p));
        }
        return new Review(ACTION_SELL, "SELL " + fmt(p) + " bid " + b.bid(side).toPlainString());
    }

    /** 买入每份优势：概率 − 卖价 − 吃单费，空仓行的 edge 列用它；state 的 estimate 是同一个算式，只是估计胜率取了整 */
    static double edge(double pSide, BigDecimal ask) {
        return pSide - ask.doubleValue() - PredictionFee.perShare(ask).doubleValue();
    }

    /** 卖出每份扣掉吃单费后比概率多拿多少，持仓行的 edge 列用它；state 的 position 是同一个算式，只是估计胜率取了整 */
    static double sellOver(double pSide, BigDecimal bid) {
        return bid.doubleValue() - PredictionFee.perShare(bid).doubleValue() - pSide;
    }

    /** 想下多少和付得起多少取小；余额要留足手续费，不足 sim 最小本金就不下 */
    static BigDecimal stake(BigDecimal want, BigDecimal gameBalance, BigDecimal ask) {
        // 买入扣 cost + fee，fee/cost = 0.07 × (1 − ask)
        BigDecimal feePerCost = PredictionFee.RATE.multiply(BigDecimal.ONE.subtract(ask));
        BigDecimal affordable = gameBalance.divide(BigDecimal.ONE.add(feePerCost), 2, RoundingMode.DOWN);
        BigDecimal stake = want.min(affordable);
        return stake.compareTo(MIN_STAKE) < 0 ? null : stake;
    }

    /** 市场隐含上涨概率：双边 mid 归一；一边没价回 null */
    static BigDecimal impliedUp(Book b) {
        BigDecimal upMid = mid(b.upAsk(), b.upBid());
        BigDecimal downMid = mid(b.downAsk(), b.downBid());
        if (upMid == null || downMid == null) {
            return null;
        }
        return upMid.divide(upMid.add(downMid), 4, RoundingMode.HALF_UP);
    }

    /** 缺一边就拿另一边当中间价 */
    private static BigDecimal mid(BigDecimal ask, BigDecimal bid) {
        if (ask == null && bid == null) return null;
        if (ask == null) return bid;
        if (bid == null) return ask;
        return ask.add(bid).divide(BigDecimal.TWO, 6, RoundingMode.HALF_UP);
    }

    /** 注单终态的盈亏 = payout − cost − 买入手续费；没到终态或不在最近注单里回 null。回填和页面注单列表共用 */
    public static BigDecimal pnl(PredictionBetResponse bet) {
        if (bet == null || !TERMINAL.contains(bet.getStatus())) return null;
        BigDecimal fee = PredictionFee.commission(bet.getContracts(), bet.getAvgPrice());
        return bet.getPayout().subtract(bet.getCost()).subtract(fee).setScale(4, RoundingMode.HALF_UP);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
