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
 * 预测员的规则：买看 Jev 给的胜率——每份优势 = 胜率 − 卖价 − 每份吃单费，两边挑优势大的，到 min-edge 才买、到 big-edge 下两倍；
 * 卖价低于 min-ask、付不起不买。卖不问 Jev，买一价扣掉吃单费比数学公平价高出 sell-edge 就卖，否则拿到结算。
 * <p>
 * reason 一律"代码 + 细节"，页面按首个词出中文提示，BUY / WAIT / ASK_LOW 第二个词是挑中的那边：
 * BUY 下单 / WAIT 优势不够 / NO_QUOTE 两边都没人卖 / ASK_LOW 卖价太低 /
 * NO_BALANCE 没钱 / HOLD 拿着 / SELL 卖出 / NO_BID 没人接盘。
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
    }

    /** 入场结论：action 是 BUY_UP / BUY_DOWN / STAY_OUT；edge 是按 Jev 胜率算、优势大的那边的每份优势，没买也记 */
    record Entry(String action, String side, BigDecimal stake, Double edge, String reason) {
    }

    /** 复核结论：HOLD / SELL */
    record Review(String action, String reason) {
    }

    private PredictionRules() {
    }

    /** 空仓：按 Jev 的胜率算两边每份优势，挑大的那边；到 min-edge、卖价不低于 min-ask、付得起就买，到 big-edge 下两倍 */
    static Entry entry(Judgment j, Book b, BigDecimal gameBalance, JevPredictionConfig cfg) {
        Double upEdge = b.upAsk() == null ? null : edge(j.pJev(), b.upAsk());
        Double downEdge = b.downAsk() == null ? null : edge(1 - j.pJev(), b.downAsk());
        if (upEdge == null && downEdge == null) {
            return new Entry(ACTION_STAY_OUT, null, null, null, "NO_QUOTE");
        }
        boolean up = downEdge == null || (upEdge != null && upEdge >= downEdge);
        String side = up ? "UP" : "DOWN";
        BigDecimal ask = up ? b.upAsk() : b.downAsk();
        double edge = up ? upEdge : downEdge;
        if (edge < cfg.getMinEdge()) {
            return new Entry(ACTION_STAY_OUT, side, null, edge, "WAIT " + side + " " + fmt(edge));
        }
        if (ask.compareTo(cfg.getMinAsk()) < 0) {
            return new Entry(ACTION_STAY_OUT, side, null, edge, "ASK_LOW " + side + " " + ask.toPlainString());
        }
        BigDecimal want = edge >= cfg.getBigEdge() ? cfg.getBaseStake().multiply(BigDecimal.TWO) : cfg.getBaseStake();
        BigDecimal stake = stake(want, gameBalance, ask);
        if (stake == null) {
            return new Entry(ACTION_STAY_OUT, side, null, edge, NO_BALANCE);
        }
        return new Entry(up ? ACTION_BUY_UP : ACTION_BUY_DOWN, side, stake, edge,
                "BUY " + side + " " + fmt(edge) + " ask " + ask.toPlainString());
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
