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
 *   <li>v5-1 {@link #watch}：突变时不买、回路盯着突变那一边，又走 jump-extend 就买它，吐回跳幅 × jump-reject-ratio 就买另一边</li>
 *   <li>v5-2 {@link #jumpJev}：突变那一秒 Jev 判会延续就买突变那一边，判会被打回就买另一边</li>
 *   <li>v5-3 {@link #timerBuy} / {@link #timerSell}：空仓时领先方卖价够高、Jev 判最新一步逆着它就买领先方，
 *       持仓时 Jev 判手里这边会赢太低就卖</li>
 * </ul>
 * reason 一律"代码 + 那一边 + 细节"，页面按首个词出中文提示：
 * EXTEND 延续、买了突变那一边 / REJECT 回落、买了另一边（这两个第二个词是买的那一边，v5-1 的数是价差，v5-2 的数是概率）/
 * NO_CALL Jev 两道都不到线或一样高（数是延续、打回）/ BUY v5-3 下单（数是最新一步逆着）/ PRICE_BAND 卖价不在区间 /
 * NO_PULLBACK 最新一步没逆着 / HOLD 买过了或持仓没到卖出条件（第二个词是持有的那一边）/
 * SELL 卖出（数是会赢）/ NO_BID 该卖但没人接盘 / NO_QUOTE 要买的那边没人卖 / NO_BALANCE 没钱（不带那一边）。
 * 回路另记 WATCH 开始盯、NO_TRIGGER 盯满没触发（数是盯结束时的价差），MISSED 等成交时价差过了容差没抢到
 * （买入 "MISSED UP ask 看到的→实际的"，卖出 "MISSED SELL bid 看到的→实际的"）；
 * 按别的价成交了，ask、bid 后面接 "→实际的"。概率、价差三位小数，价格原样。
 */
public final class PredictionRules {

    /** sim 单笔最小本金 */
    static final BigDecimal MIN_STAKE = BigDecimal.ONE;
    /** 付不起一注的 reason */
    static final String NO_BALANCE = "NO_BALANCE";
    /** 突变后延续：买突变那一边 */
    static final String EXTEND = "EXTEND";
    /** 突变后回落：买另一边 */
    static final String REJECT = "REJECT";
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
     * 规则结论：action 成交了记 BUY_UP / BUY_DOWN / SELL，不成交是 STAY_OUT / HOLD；side 是要成交的那一边，不成交是看的那一边；
     * stake 只买入有
     */
    record Decision(String action, String side, BigDecimal stake, String reason) {

        /** 要成交：买或卖 */
        boolean trades() {
            return ACTION_BUY_UP.equals(action) || ACTION_BUY_DOWN.equals(action) || ACTION_SELL.equals(action);
        }
    }

    private PredictionRules() {
    }

    /**
     * v5-1 盯：突变那一边现在的价 p 比盯的起点 p0 又走 jump-extend 回 EXTEND，
     * 吐回 jumpSize × jump-reject-ratio 回 REJECT，都没到回 null
     */
    static String watch(BigDecimal p0, BigDecimal p, BigDecimal jumpSize, JevPredictionConfig cfg) {
        if (p.subtract(p0).compareTo(cfg.getJumpExtend()) >= 0) {
            return EXTEND;
        }
        if (p0.subtract(p).compareTo(jumpSize.multiply(cfg.getJumpRejectRatio())) >= 0) {
            return REJECT;
        }
        return null;
    }

    /**
     * v5-2：Jev 答"会延续"到 jump-extend-min 且比"会被打回"高就 EXTEND，"会被打回"到 jump-reject-min 且比"会延续"高就 REJECT，
     * 其余（都不到线、都过线又一样高）记 NO_CALL。买入见 {@link #jumpEntry}
     */
    static Decision jumpJev(Judgment j, Book b, BigDecimal gameBalance, JevPredictionConfig cfg) {
        String side = j.side();
        if (j.extend() >= cfg.getJumpExtendMin() && j.extend() > j.reject()) {
            return jumpEntry(EXTEND, side, j.extend(), b, gameBalance, cfg);
        }
        if (j.reject() >= cfg.getJumpRejectMin() && j.reject() > j.extend()) {
            return jumpEntry(REJECT, side, j.reject(), b, gameBalance, cfg);
        }
        return stay(side, "NO_CALL " + side + " " + fmt(j.extend()) + " " + fmt(j.reject()));
    }

    /**
     * 突变后的买入，v5-1、v5-2 共用：EXTEND 买突变那一边 side，REJECT 买另一边。NO_QUOTE → NO_BALANCE → BUY，
     * reason "代码 买的那一边 数 ask 卖价"
     */
    static Decision jumpEntry(String call, String side, double p, Book b, BigDecimal gameBalance, JevPredictionConfig cfg) {
        String target = EXTEND.equals(call) ? side : "UP".equals(side) ? "DOWN" : "UP";
        BigDecimal ask = b.ask(target);
        if (ask == null) {
            return stay(side, "NO_QUOTE " + target);
        }
        return buy(target, ask, gameBalance, cfg, call + " " + target + " " + fmt(p) + " ask " + ask.toPlainString());
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

    /** 概率、价差写成三位小数 */
    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
