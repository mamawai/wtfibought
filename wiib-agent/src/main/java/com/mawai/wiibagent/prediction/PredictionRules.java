package com.mawai.wiibagent.prediction;

import com.mawai.wiibcommon.dto.PredictionBetResponse;
import com.mawai.wiibcommon.market.PredictionFee;
import com.mawai.wiibagent.prediction.PredictionJudge.Judgment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;

import static com.mawai.wiibagent.prediction.PredictionQuestions.BUY_UP;
import static com.mawai.wiibagent.prediction.PredictionQuestions.WAIT;
import static com.mawai.wiibcommon.entity.JevPredictionDecision.ACTION_BUY_DOWN;
import static com.mawai.wiibcommon.entity.JevPredictionDecision.ACTION_BUY_UP;
import static com.mawai.wiibcommon.entity.JevPredictionDecision.ACTION_HOLD;
import static com.mawai.wiibcommon.entity.JevPredictionDecision.ACTION_SELL;
import static com.mawai.wiibcommon.entity.JevPredictionDecision.ACTION_STAY_OUT;

/**
 * 预测员的规则：买由 Jev 的决定题拍板，代码只做三件事——概率不够不动、不便宜的不买、定注额；
 * 卖不问 Jev，买一价扣掉吃单费比公平价高出 sell-edge 就卖（市场给多了），否则拿到结算。
 * 优势 = 公平价 − 卖价 − 每份吃单费；比例 = 优势 ÷ (1 − 卖价 − 手续费)，就是 Kelly 分数。
 * <p>
 * reason 一律"代码 + 细节"，页面按首个词出中文提示：
 * BUY 下单 / WAIT Jev 选等 / UNSURE 概率不够 / NO_QUOTE 没人卖 / ASK_RANGE 卖价出区间 /
 * NOT_CHEAP 不够便宜 / NO_BALANCE 没钱 / HOLD 拿着 / SELL 卖出 / NO_BID 没人接盘。
 */
public final class PredictionRules {

    /** sim 单笔最小本金 */
    static final BigDecimal MIN_STAKE = BigDecimal.ONE;
    /** 注单终态，盈亏可以算了 */
    static final Set<String> TERMINAL = Set.of("WON", "LOST", "SOLD", "DRAW");

    /** 当时盘口，缺一边就是 null */
    record Book(BigDecimal upAsk, BigDecimal upBid, BigDecimal downAsk, BigDecimal downBid) {
    }

    /** 入场结论：action 是 BUY_UP / BUY_DOWN / STAY_OUT；edge 取 Jev 想买那边的绝对优势，没买也记 */
    record Entry(String action, String side, BigDecimal stake, Double edge, String reason) {
    }

    /** 复核结论：HOLD / SELL */
    record Review(String action, String reason) {
    }

    private PredictionRules() {
    }

    /**
     * 空仓：Jev 选买且概率够，再过两道拦：卖价出了区间不买、那边每份优势不到 min-edge 不算便宜不买（和 state 里 slightly cheap 同一条线）。
     * 注额：基础额；Jev 很有把握且 Kelly 比例够大才翻倍。
     */
    static Entry entry(Judgment j, Book b, BigDecimal gameBalance, JevPredictionConfig cfg) {
        String top = j.decision();
        double p = j.decisionP();
        if (WAIT.equals(top)) {
            return new Entry(ACTION_STAY_OUT, null, null, null, "WAIT " + fmt(p));
        }
        boolean up = BUY_UP.equals(top);
        String side = up ? "UP" : "DOWN";
        BigDecimal ask = up ? b.upAsk() : b.downAsk();
        double pSide = up ? j.pModel() : 1 - j.pModel();
        Double edge = ask == null ? null : edge(pSide, ask);
        if (p < cfg.getActThreshold()) {
            return new Entry(ACTION_STAY_OUT, side, null, edge, "UNSURE " + top + " " + fmt(p));
        }
        if (ask == null) {
            return new Entry(ACTION_STAY_OUT, side, null, null, "NO_QUOTE");
        }
        if (ask.compareTo(cfg.getMaxAsk()) > 0 || ask.compareTo(cfg.getMinAsk()) < 0) {
            return new Entry(ACTION_STAY_OUT, side, null, edge, "ASK_RANGE " + ask.toPlainString());
        }
        if (edge < cfg.getMinEdge()) {
            return new Entry(ACTION_STAY_OUT, side, null, edge, "NOT_CHEAP edge " + fmt(edge));
        }
        double ratio = edgeRatio(pSide, ask);
        boolean big = p >= cfg.getBigThreshold() && ratio >= cfg.getBigValueRatio();
        BigDecimal stake = stake(big ? cfg.getBaseStake().multiply(BigDecimal.TWO) : cfg.getBaseStake(), gameBalance, ask);
        if (stake == null) {
            return new Entry(ACTION_STAY_OUT, side, null, edge, "NO_BALANCE");
        }
        return new Entry(up ? ACTION_BUY_UP : ACTION_BUY_DOWN, side, stake, edge,
                "BUY " + top + " " + fmt(p) + " ratio " + fmt(ratio) + " ask " + ask.toPlainString());
    }

    /** 持仓：买一价扣掉吃单费比公平价高出 sell-edge 就卖（市场给多了），否则拿到结算；没人接盘只能拿着 */
    static Review review(double pModel, String side, Book b, JevPredictionConfig cfg) {
        boolean up = "UP".equals(side);
        BigDecimal bid = up ? b.upBid() : b.downBid();
        if (bid == null) {
            return new Review(ACTION_HOLD, "NO_BID");
        }
        double over = bid.doubleValue() - PredictionFee.perShare(bid).doubleValue() - (up ? pModel : 1 - pModel);
        if (over >= cfg.getSellEdge()) {
            return new Review(ACTION_SELL, "SELL over " + fmt(over) + " bid " + bid.toPlainString());
        }
        return new Review(ACTION_HOLD, "HOLD over " + fmt(over));
    }

    /** 每份优势：概率 − 卖价 − 吃单费 */
    static double edge(double pSide, BigDecimal ask) {
        return pSide - ask.doubleValue() - PredictionFee.perShare(ask).doubleValue();
    }

    /** 优势占最大利润的比例 */
    static double edgeRatio(double pSide, BigDecimal ask) {
        return edge(pSide, ask) / (1 - ask.doubleValue() - PredictionFee.perShare(ask).doubleValue());
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
