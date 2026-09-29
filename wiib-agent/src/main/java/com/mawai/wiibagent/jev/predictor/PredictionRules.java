package com.mawai.wiibagent.jev.predictor;

import com.mawai.wiibcommon.dto.PredictionBetResponse;
import com.mawai.wiibcommon.market.PredictionFee;
import com.mawai.wiibagent.jev.predictor.PredictionJudge.Judgment;

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
 * 预测员三组的买卖规则，每注 base-stake，记第一条没过的：
 * <ul>
 *   <li>v5-1 {@link #jumpCodeEntry}：突变就买突变那一边，不看 Jev</li>
 *   <li>v5-2 {@link #jumpJevEntry}：Jev 判"在变弱"不高才买突变那一边</li>
 *   <li>v5-3 {@link #timerBuy} / {@link #timerSell}：空仓时领先方卖价够高、Jev 判最新一步逆着它就买领先方，
 *       持仓时 Jev 判手里这边会赢太低就卖</li>
 * </ul>
 * reason 一律"代码 + 看的那一边 + 细节"，页面按首个词出中文提示：
 * BUY 下单（v5-1 没有数，v5-2 的数是在变弱，v5-3 的数是最新一步逆着）/ FADING 在变弱太像 / PRICE_BAND 卖价不在区间 /
 * NO_PULLBACK 最新一步没逆着 / HOLD 买过了或持仓没到卖出条件（第二个词是持有的那一边）/
 * SELL 卖出（数是会赢）/ NO_BID 该卖但没人接盘 / NO_QUOTE 那边没人卖 / NO_BALANCE 没钱（不带那一边）。
 * 回路另记 MISSED 等成交时价差过了容差没抢到（买入 "MISSED UP ask 看到的→实际的"，卖出 "MISSED SELL bid 看到的→实际的"）；
 * 按别的价成交了，BUY 的 ask、SELL 的 bid 后面接 "→实际的"。概率三位小数，价格原样。
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

    /**
     * 规则结论：action 成交了记 BUY_UP / BUY_DOWN / SELL，不成交是 STAY_OUT / HOLD；side 是看的那一边；stake 只买入有
     */
    record Decision(String action, String side, BigDecimal stake, String reason) {

        /** 要成交：买或卖 */
        boolean trades() {
            return ACTION_BUY_UP.equals(action) || ACTION_BUY_DOWN.equals(action) || ACTION_SELL.equals(action);
        }
    }

    private PredictionRules() {
    }

    /** v5-1：突变就买突变那一边。NO_QUOTE → NO_BALANCE → BUY */
    static Decision jumpCodeEntry(String side, Book b, BigDecimal gameBalance, JevPredictionConfig cfg) {
        BigDecimal ask = b.ask(side);
        if (ask == null) {
            return stay(side, "NO_QUOTE " + side);
        }
        return buy(side, ask, gameBalance, cfg, "BUY " + side + " ask " + ask.toPlainString());
    }

    /** v5-2：Jev 判"在变弱"不超过 jump-fading-max 才买突变那一边。NO_QUOTE → FADING → NO_BALANCE → BUY */
    static Decision jumpJevEntry(Judgment j, Book b, BigDecimal gameBalance, JevPredictionConfig cfg) {
        String side = j.side();
        BigDecimal ask = b.ask(side);
        if (ask == null) {
            return stay(side, "NO_QUOTE " + side);
        }
        if (j.pushFading() > cfg.getJumpFadingMax()) {
            return stay(side, "FADING " + side + " " + fmt(j.pushFading()));
        }
        return buy(side, ask, gameBalance, cfg, "BUY " + side + " " + fmt(j.pushFading()) + " ask " + ask.toPlainString());
    }

    /**
     * v5-3 空仓：领先方卖价在区间里、Jev 判"最新一步逆着领先方"到 timer-against-min，就买领先方（Jev 看的就是领先方）。
     * NO_QUOTE → PRICE_BAND → NO_PULLBACK → NO_BALANCE → BUY
     */
    static Decision timerBuy(Judgment j, Book b, BigDecimal gameBalance, JevPredictionConfig cfg) {
        String side = j.side();
        BigDecimal ask = b.ask(side);
        if (ask == null) {
            return stay(side, "NO_QUOTE " + side);
        }
        if (ask.compareTo(cfg.getTimerAskMin()) < 0 || ask.compareTo(cfg.getTimerAskMax()) > 0) {
            return stay(side, "PRICE_BAND " + side + " ask " + ask.toPlainString());
        }
        if (j.latestAgainst() < cfg.getTimerAgainstMin()) {
            return stay(side, "NO_PULLBACK " + side + " " + fmt(j.latestAgainst()));
        }
        return buy(side, ask, gameBalance, cfg, "BUY " + side + " " + fmt(j.latestAgainst()) + " ask " + ask.toPlainString());
    }

    /**
     * v5-3 持仓：Jev 判手里这一边（held，Jev 看的就是它）"会赢"不超过 timer-sell-win-max，就按买一价全部卖出。
     * 没到卖出条件 HOLD → NO_BID → SELL；不卖的 action 是 HOLD
     */
    static Decision timerSell(Judgment j, String held, Book b, JevPredictionConfig cfg) {
        if (j.win() > cfg.getTimerSellWinMax()) {
            return new Decision(ACTION_HOLD, held, null, "HOLD " + held);
        }
        BigDecimal bid = b.bid(held);
        if (bid == null) {
            return new Decision(ACTION_HOLD, held, null, "NO_BID " + held);
        }
        return new Decision(ACTION_SELL, held, null, "SELL " + held + " " + fmt(j.win()) + " bid " + bid.toPlainString());
    }

    /** 买入的最后两步：付不起记 NO_BALANCE，付得起就是 BUY */
    private static Decision buy(String side, BigDecimal ask, BigDecimal gameBalance, JevPredictionConfig cfg, String reason) {
        BigDecimal stake = stake(cfg.getBaseStake(), gameBalance, ask);
        if (stake == null) {
            return stay(side, NO_BALANCE);
        }
        return new Decision("UP".equals(side) ? ACTION_BUY_UP : ACTION_BUY_DOWN, side, stake, reason);
    }

    private static Decision stay(String side, String reason) {
        return new Decision(ACTION_STAY_OUT, side, null, reason);
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
