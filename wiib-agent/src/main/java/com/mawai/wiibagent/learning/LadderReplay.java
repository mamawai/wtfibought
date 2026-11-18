package com.mawai.wiibagent.learning;

import com.mawai.wiibagent.trader.trade.PlanEntry;
import com.mawai.wiibagent.trader.trade.TargetRung;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.market.FuturesFeeRates;
import com.mawai.wiibcommon.market.KlineBar;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.mawai.wiibagent.learning.CaseFileBuilder.BAR_MS;
import static com.mawai.wiibagent.learning.CaseFileBuilder.DIRECTION_MS;
import static com.mawai.wiibagent.learning.CaseFileBuilder.Outcome;
import static com.mawai.wiibagent.learning.CaseFileBuilder.REPLAY_MS;
import static com.mawai.wiibagent.learning.CaseFileBuilder.Replay;
import static com.mawai.wiibagent.learning.CaseFileBuilder.STOP_WIDTHS;
import static com.mawai.wiibagent.learning.CaseFileBuilder.WidthOutcome;

/**
 * 按首批入场档重放「下单后什么都不改」：各档按原价位等成交，止损和止盈档按下单时的原样，几档共用一条 5m 时间线。
 * 1R＝首批各档全部成交、再全部按原止损了结的价差亏损（首批合计数量 × 加权均价到止损的距离），实际和重放都以它为单位。
 * 扣交易手续费，不算资金费；出场按止损价、目标价，不含滑点
 */
final class LadderReplay {

    private LadderReplay() {
    }

    /**
     * @param initial  首批档（add=false）
     * @param bars     从首批最早挂出那根起的 5m K 线
     * @param withAdds 这笔实际里有没有加过仓，只是带出去做标记
     * @return 首批里有下单结果未知的档、或没有 K 线时为 null
     */
    static Replay replay(FuturesPositionDTO pos, List<PlanEntry> initial, List<KlineBar> bars, boolean withAdds) {
        if (initial.stream().anyMatch(e -> "UNKNOWN".equals(e.getStatus())) || bars.isEmpty()) {
            return null;
        }
        // 加权均价：限价档按限价，市价档按实际成交价
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal notional = BigDecimal.ZERO;
        for (PlanEntry e : initial) {
            BigDecimal price = e.getLimitPrice() == null ? e.getFilledPrice() : e.getLimitPrice();
            quantity = quantity.add(e.getQuantity());
            notional = notional.add(price.multiply(e.getQuantity()));
        }
        BigDecimal entry = notional.divide(quantity, 16, RoundingMode.HALF_UP);
        // 止损距离（价格）和计划风险金额
        BigDecimal distance = entry.subtract(initial.getFirst().getStop()).abs();
        BigDecimal budget = quantity.multiply(distance);
        boolean isLong = "LONG".equals(pos.getSide());
        List<Walk> walks = STOP_WIDTHS.stream()
                .map(width -> walk(initial, bars, pos.getSymbol(), isLong, entry, distance, budget, width)).toList();
        // 首档成交后 24 小时内的顺向、逆向和拿满的位置，以加权均价为参照
        Long firstFill = walks.getFirst().firstFill();
        BigDecimal fav = null;
        BigDecimal adv = null;
        BigDecimal hold = null;
        if (firstFill != null) {
            BigDecimal favourable = BigDecimal.ZERO;
            BigDecimal adverse = BigDecimal.ZERO;
            for (KlineBar bar : bars) {
                if (bar.openTime() < firstFill || bar.openTime() >= firstFill + DIRECTION_MS) {
                    continue;
                }
                favourable = favourable.max(isLong ? bar.high().subtract(entry) : entry.subtract(bar.low()));
                adverse = adverse.max(isLong ? entry.subtract(bar.low()) : bar.high().subtract(entry));
                // K 线覆盖到 24 小时才有拿满的位置
                if (bar.openTime() + BAR_MS >= firstFill + DIRECTION_MS) {
                    hold = ratio(isLong ? bar.close().subtract(entry) : entry.subtract(bar.close()), distance);
                }
            }
            fav = ratio(favourable, distance);
            adv = ratio(adverse, distance).negate();
        }
        // 实际：净盈亏把资金费加回去，除以计划风险金额
        BigDecimal actual = null;
        if (pos.getClosedPnl() != null) {
            BigDecimal funding = pos.getFundingFeeTotal() == null ? BigDecimal.ZERO : pos.getFundingFeeTotal();
            actual = ratio(pos.getClosedPnl().add(funding), budget);
        }
        return new Replay(actual, fav, adv, hold, walks.stream().map(Walk::outcome).toList(), withAdds);
    }

    /** 一档止盈：价位和这一档入场分到的数量 */
    private record Target(BigDecimal price, BigDecimal quantity) {
    }

    /** 重放里的一档入场：成交价、还没了结的数量、在哪根成交、碰到第几档止盈了 */
    private static final class Entry {
        final PlanEntry original;
        /** 由近到远；数量按真实下单的折法（步长取整、最后一档拿余数） */
        final List<Target> targets;
        /** 成交价，没成交为 null */
        BigDecimal price;
        BigDecimal left = BigDecimal.ZERO;
        long filledBar;
        int nextTarget;

        Entry(PlanEntry original, String symbol, boolean isLong) {
            this.original = original;
            List<TargetRung> rungs = original.getTargets() == null ? List.of() : original.getTargets();
            List<BigDecimal> quantities = TargetRung.quantities(rungs, original.getQuantity(), symbol);
            List<Target> all = new ArrayList<>();
            for (int i = 0; i < rungs.size(); i++) {
                all.add(new Target(rungs.get(i).price(), quantities.get(i)));
            }
            Comparator<Target> byPrice = Comparator.comparing(Target::price);
            this.targets = all.stream().sorted(isLong ? byPrice : byPrice.reversed()).toList();
        }
    }

    /** 一档止损宽度走完的结局，和重放里首档成交的那根 */
    private record Walk(WidthOutcome outcome, Long firstFill) {
    }

    /**
     * 逐根走。每根依次：入场、止损、止盈、看是不是全部了结。
     * 止损放宽 width 倍：止损价放到加权均价往止损方向 width 倍距离处，结果再除以 width（按同样的风险金额缩小仓位）
     */
    private static Walk walk(List<PlanEntry> initial, List<KlineBar> bars, String symbol, boolean isLong,
                             BigDecimal reference, BigDecimal distance, BigDecimal budget, BigDecimal width) {
        List<Entry> entries = initial.stream().map(e -> new Entry(e, symbol, isLong)).toList();
        BigDecimal stop = isLong ? reference.subtract(distance.multiply(width)) : reference.add(distance.multiply(width));
        BigDecimal risk = budget.multiply(width);
        BigDecimal gain = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;
        Long firstFill = null;
        boolean touchedTarget = false;
        for (KlineBar bar : bars) {
            // 从首档成交那根起最远走 72 小时
            if (firstFill != null && bar.openTime() >= firstFill + REPLAY_MS) {
                break;
            }
            // 入场：实际市价档从成交那根的下一根起，实际限价档从成交那根起，用实际成交价和手续费；
            // 其余限价档从挂出那根的下一根起，穿过限价就按限价成交、手续费按 maker 估
            for (Entry entry : entries) {
                if (entry.price != null) {
                    continue;
                }
                PlanEntry original = entry.original;
                boolean market = original.getLimitPrice() == null;
                boolean actual = "FILLED".equals(original.getStatus()) && (market
                        ? bar.openTime() > floorBar(original.getClosedAt())
                        : bar.openTime() + BAR_MS > original.getClosedAt());
                if (!actual && !crossesLimit(original, bar, isLong)) {
                    continue;
                }
                entry.price = actual ? original.getFilledPrice() : original.getLimitPrice();
                entry.left = original.getQuantity();
                entry.filledBar = market ? floorBar(original.getClosedAt()) : bar.openTime();
                fees = fees.add(actual ? original.getFee()
                        : entry.price.multiply(entry.left).multiply(FuturesFeeRates.MAKER));
                if (firstFill == null) {
                    firstFill = bar.openTime();
                }
            }
            if (firstFill == null) {
                continue;
            }
            // 止损：同一根里止损和止盈都碰到按止损算，所以先看止损
            boolean stopped = isLong ? bar.low().compareTo(stop) <= 0 : bar.high().compareTo(stop) >= 0;
            if (stopped) {
                for (Entry entry : entries) {
                    if (entry.left.signum() == 0) {
                        continue;
                    }
                    gain = gain.add(pnl(entry.price, stop, entry.left, isLong));
                    fees = fees.add(stop.multiply(entry.left).multiply(FuturesFeeRates.TAKER));
                }
                Outcome outcome = touchedTarget ? Outcome.TARGET : Outcome.STOP;
                return new Walk(new WidthOutcome(width, outcome, ratio(gain.subtract(fees), risk)), firstFill);
            }
            // 止盈：这一根刚成交的档不参与
            for (Entry entry : entries) {
                if (entry.left.signum() == 0 || entry.filledBar == bar.openTime()) {
                    continue;
                }
                while (entry.nextTarget < entry.targets.size()) {
                    Target target = entry.targets.get(entry.nextTarget);
                    boolean reached = isLong ? bar.high().compareTo(target.price()) >= 0 : bar.low().compareTo(target.price()) <= 0;
                    if (!reached) {
                        break;
                    }
                    entry.nextTarget++;
                    touchedTarget = true;
                    gain = gain.add(pnl(entry.price, target.price(), target.quantity(), isLong));
                    fees = fees.add(target.price().multiply(target.quantity()).multiply(FuturesFeeRates.TAKER));
                    entry.left = entry.left.subtract(target.quantity());
                }
            }
            // 成交过的都了结了：计划了结，还没成交的档作废
            if (entries.stream().allMatch(e -> e.left.signum() == 0)) {
                return new Walk(new WidthOutcome(width, Outcome.TARGET, ratio(gain.subtract(fees), risk)), firstFill);
            }
        }
        return new Walk(new WidthOutcome(width, Outcome.OPEN, null), firstFill);
    }

    /** 这根有没有穿过这档的限价；市价档、挂出时刻所在的那根不算 */
    private static boolean crossesLimit(PlanEntry entry, KlineBar bar, boolean isLong) {
        if (entry.getLimitPrice() == null || bar.openTime() <= floorBar(entry.getPlacedAt())) {
            return false;
        }
        return crosses(bar, entry.getLimitPrice(), isLong);
    }

    /** 限价单成交要穿过限价：多单要有价低于限价，空单要有价高于，正好等于不算，和模拟盘的限价单一样 */
    static boolean crosses(KlineBar bar, BigDecimal limit, boolean isLong) {
        return isLong ? bar.low().compareTo(limit) < 0 : bar.high().compareTo(limit) > 0;
    }

    /** 这个时刻所在那根 5m 的开盘时刻 */
    static long floorBar(long time) {
        return time - Math.floorMod(time, BAR_MS);
    }

    private static BigDecimal pnl(BigDecimal entry, BigDecimal exit, BigDecimal quantity, boolean isLong) {
        return (isLong ? exit.subtract(entry) : entry.subtract(exit)).multiply(quantity);
    }

    private static BigDecimal ratio(BigDecimal value, BigDecimal divisor) {
        return value.divide(divisor, 2, RoundingMode.HALF_UP);
    }
}
