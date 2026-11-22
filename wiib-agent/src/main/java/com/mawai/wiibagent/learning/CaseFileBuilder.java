package com.mawai.wiibagent.learning;

import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibagent.trader.trade.TargetRung;
import com.mawai.wiibagent.trader.trade.PlanEntry;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.market.FuturesFeeRates;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 复盘的事实表：一笔已了结交易在 5m K 线上走出的路径（最大浮盈、最大逆向各几 R、到 +0.5R / +1R 用时、
 * 第一次收回入场价、出场后 4h/24h 最有利价）、开仓后不动的重放，加计划与入场上下文；统一复盘里按单子类型分组。
 * 纯代码，数字全来自本地 kline_history。
 */
@Component
@RequiredArgsConstructor
public class CaseFileBuilder {

    static final long BAR_MS = KlineHistoryStore.DEFAULT_BAR_MILLIS;
    static final long AFTER_SHORT_MS = 4 * 3_600_000L;
    /** 出场后看多久；一笔单子的 K 线和重放也都到这里为止 */
    static final long AFTER_LONG_MS = 24 * 3_600_000L;
    /** 方向指标看开仓后多久 */
    static final long DIRECTION_MS = 24 * 3_600_000L;
    /** 重放的几档止损宽度：原样、1.5 倍、2 倍、3 倍 */
    static final List<BigDecimal> STOP_WIDTHS = List.of(
            BigDecimal.ONE, new BigDecimal("1.5"), new BigDecimal("2"), new BigDecimal("3"));

    private final KlineHistoryStore historyStore;

    /** 一笔已了结交易：sim 仓位 + 配对的计划 + 入场上下文 + 路径 + 重放（没计划止损或开仓后没 K 线时为 null） */
    public record TradeCase(FuturesPositionDTO pos, AiTraderPlan plan, JsonNode entryCtx, Path path, Replay replay) {
        public String playType() {
            return plan == null ? null : plan.getPlayType();
        }

        public BigDecimal net() {
            return pos.getClosedPnl();
        }

        public boolean win() {
            return net() != null && net().signum() > 0;
        }
    }

    /**
     * 路径指标。浮盈/逆向按方向取 K 线高低；mfeR / maeR 是它们相当于几 R（R = 入场价到计划原始止损的距离），没计划止损就没有。
     * minToHalfR / minTo1R 是开仓后多少分钟第一次走到 +0.5R / +1R；firstBackAt 是入场根之后第一根收盘穿回入场价的时刻。
     * 出场后数据覆盖不到 4h 时 bestAfter4h 为 null，覆盖不到 24h 时 bestAfter24h 为 null。
     * targetsAfter24h 是出场后 24 小时内到过计划里的几档止盈，没设止盈或覆盖不到 24h 为 null。
     * 窗口内没有 K 线时全部为 null、hourly 为空。
     */
    public record Path(BigDecimal mfePrice, Long mfeAt, BigDecimal mfeR,
                       BigDecimal maePrice, BigDecimal maeR,
                       Long minToHalfR, Long minTo1R, Long firstBackAt,
                       List<KlineBar> hourly,
                       BigDecimal bestAfter4h, BigDecimal bestAfter24h, Integer targetsAfter24h) {
        static final Path EMPTY = new Path(null, null, null, null, null, null, null, null,
                List.of(), null, null, null);
    }

    /** 重放的结局：到过止盈档并且整仓了结、没到过止盈档就被止损、走完仓位还没了结 */
    public enum Outcome { TARGET, STOP, OPEN }

    /**
     * 一档止损宽度的重放结局。r 按同样的风险金额折算，扣估算的交易手续费；未决为 null。
     */
    public record WidthOutcome(BigDecimal width, Outcome outcome, BigDecimal r) {
    }

    /**
     * 首批计划不动的重放，R 是首批数量 × 加权入场到止损的距离；无首批档记录的沿用单档算法。
     * fav24R / adv24R / hold24R 是以入场参照价计算的价格位置；withAdds 标记实际里有加仓。
     */
    public record Replay(BigDecimal actualR, BigDecimal fav24R, BigDecimal adv24R, BigDecimal hold24R,
                         List<WidthOutcome> widths, boolean withAdds) {
        /** 止损原样那一档，就是开仓后什么都不动 */
        public WidthOutcome untouched() {
            return widths.getFirst();
        }
    }

    /** 一档止损宽度在一批交易上的合计：到目标、止损、未决各几笔，r 是已决那几笔的折算盈亏合计 */
    public record WidthTally(BigDecimal width, int target, int stop, int open, BigDecimal r) {
    }

    /**
     * 一批重放的汇总。widths 按 {@link #STOP_WIDTHS} 的顺序；
     * managed 是"实际"和"开仓后不动"两边都有数的笔数，actualR / untouchedR 只合计这几笔。
     */
    public record ReplaySummary(int trades, List<WidthTally> widths, int managed,
                                BigDecimal actualR, BigDecimal untouchedR, long withAdds) {
    }

    /**
     * 撤掉的限价档之后的结局：价格没穿过限价位；穿过了并且止损被打掉；穿过了并且止盈档把仓位平完；
     * 穿过了、走到这段 K 线末尾两头都没了结
     */
    public enum CancelledEnd { NOT_CROSSED, STOPPED, TARGETS_DONE, UNDECIDED }

    /**
     * 没成交就撤掉的限价档，撤单后 24 小时内（没有逐档记录的旧计划，从最后一次见它还挂着的那轮起）怎么走。
     * crossedAt 是价格穿过限价位的那根 5m（正好碰到不算），没穿过为 null。
     * 穿过的：从那一根起按原止损、原止盈档往后走，reachedTargets 是了结之前（没了结就是到这段 K 线末尾）到过几档；
     * 没穿过的：reachedTargets 是这 24 小时里价格到过几档
     */
    public record CancelledLimit(AiTraderPlan plan, PlanEntry entry, BigDecimal limit, BigDecimal stop,
                                 List<TargetRung> targets, long cancelledAt, Long crossedAt, CancelledEnd end, int reachedTargets) {
    }

    /** 同一类单子的一组，赢亏同台 */
    public record SetupGroup(String playType, List<TradeCase> trades, int wins, BigDecimal net) {
    }

    /** 一个币在窗口内的 OHLC 与极值时刻；from / to 是实际有 K 线的首根开盘与末根收盘时刻 */
    public record MarketWindow(String symbol, long from, long to, BigDecimal open, BigDecimal high, long highAt,
                               BigDecimal low, long lowAt, BigDecimal close) {
        public BigDecimal lowToHighPct() {
            return high.subtract(low).multiply(BigDecimal.valueOf(100)).divide(low, 2, RoundingMode.HALF_UP);
        }

        /** 首根开盘到末根收盘的涨跌 % */
        public BigDecimal changePct() {
            return close.subtract(open).multiply(BigDecimal.valueOf(100)).divide(open, 2, RoundingMode.HALF_UP);
        }
    }

    public TradeCase build(FuturesPositionDTO pos, AiTraderPlan plan) {
        long openMs = TradePairing.msOf(pos.getCreatedAt());
        long closeMs = TradePairing.msOf(pos.getUpdatedAt());
        long openBar = openMs - Math.floorMod(openMs, BAR_MS);
        long closeBar = closeMs - Math.floorMod(closeMs, BAR_MS);
        List<KlineBar> bars = bars(pos, plan, openBar);
        return new TradeCase(pos, plan, entryCtx(plan),
                path(pos, plan, bars.stream().filter(b -> b.openTime() >= openBar).toList(), openMs, openBar, closeBar), replay(pos, plan, bars, openBar));
    }

    /** 只要重放：论点战绩每轮都算 */
    public Replay replay(FuturesPositionDTO pos, AiTraderPlan plan) {
        long openMs = TradePairing.msOf(pos.getCreatedAt());
        long openBar = openMs - Math.floorMod(openMs, BAR_MS);
        return replay(pos, plan, bars(pos, plan, openBar), openBar);
    }

    /**
     * 一笔单子的 5m K 线：从首批最早挂出那根（没有逐档记录的是开仓那根）到平仓后 24 小时。
     * 路径和重放都只看这一段，平仓满 24 小时后这一段不再变
     */
    private List<KlineBar> bars(FuturesPositionDTO pos, AiTraderPlan plan, long openBar) {
        long closeMs = TradePairing.msOf(pos.getUpdatedAt());
        return historyStore.load(pos.getSymbol(), KlineHistoryStore.DEFAULT_INTERVAL,
                replayStart(plan, openBar), closeMs - Math.floorMod(closeMs, BAR_MS) + AFTER_LONG_MS);
    }

    private static long replayStart(AiTraderPlan plan, long openBar) {
        return PlanEntry.read(plan).stream().filter(e -> !e.isAdd()).mapToLong(e -> LadderReplay.floorBar(e.getPlacedAt())).min().orElse(openBar);
    }

    public static boolean hasFilledAdds(AiTraderPlan plan) {
        return PlanEntry.read(plan).stream().anyMatch(e -> e.isAdd() && "FILLED".equals(e.getStatus()));
    }

    /**
     * 没成交就撤掉的限价单之后怎么走；那段没 K 线返回 null。
     *
     * @param fromMs 从哪一刻看起：最后一次见它还挂着的那轮。计划是下一轮对账时才归档的，从归档时刻看会漏掉撤单后的头一段
     */
    public CancelledLimit cancelledLimit(AiTraderPlan plan, long fromMs) {
        return cancelledLimit(plan, null, fromMs, plan.getEntryPrice(), plan.getStopLossPrice(), TargetRung.of(plan));
    }

    public CancelledLimit cancelledLimit(AiTraderPlan plan, PlanEntry entry) {
        return cancelledLimit(plan, entry, entry.getClosedAt(), entry.getLimitPrice(),
                entry.getStop(), entry.getTargets() == null ? List.of() : entry.getTargets());
    }

    private CancelledLimit cancelledLimit(AiTraderPlan plan, PlanEntry entry, long fromMs,
                                           BigDecimal limit, BigDecimal stop, List<TargetRung> targets) {
        List<KlineBar> bars = historyStore.load(plan.getSymbol(), KlineHistoryStore.DEFAULT_INTERVAL,
                fromMs, fromMs + AFTER_LONG_MS);
        if (bars.isEmpty()) {
            return null;
        }
        boolean isLong = "LONG".equals(plan.getSide());
        long cancelledAt = entry == null ? plan.getClosedWakeTime() : entry.getClosedAt();
        int cross = 0;
        while (cross < bars.size() && !LadderReplay.crosses(bars.get(cross), limit, isLong)) {
            cross++;
        }
        if (cross == bars.size()) {
            BigDecimal best = bars.stream().map(isLong ? KlineBar::high : KlineBar::low)
                    .reduce((a, b) -> better(a, b, isLong)).orElseThrow();
            return new CancelledLimit(plan, entry, limit, stop, targets, cancelledAt, null,
                    CancelledEnd.NOT_CROSSED, reached(targets, best, isLong));
        }
        // 止盈档由近到远，一档一档碰；left 是还没被止盈档平掉的仓位百分比
        List<TargetRung> rungs = targets.stream()
                .sorted(Comparator.comparing(t -> t.price().subtract(limit).abs())).toList();
        int hit = 0;
        BigDecimal left = BigDecimal.valueOf(100);
        boolean stopped = false;
        for (int i = cross; i < bars.size() && left.signum() > 0 && !stopped; i++) {
            KlineBar b = bars.get(i);
            // 每根先看止损；穿过限价位的那一根不数止盈档，那根里先到的是哪头分不清
            stopped = reaches(b, stop, isLong);
            while (!stopped && i > cross && hit < rungs.size() && reaches(b, rungs.get(hit).price(), !isLong)) {
                left = left.subtract(rungs.get(hit).percent());
                hit++;
            }
        }
        CancelledEnd end = stopped ? CancelledEnd.STOPPED
                : left.signum() <= 0 ? CancelledEnd.TARGETS_DONE : CancelledEnd.UNDECIDED;
        return new CancelledLimit(plan, entry, limit, stop, targets, cancelledAt, bars.get(cross).openTime(), end, hit);
    }

    /** 这根的最低价有没有到 price 或更低（down），或者最高价有没有到 price 或更高 */
    private static boolean reaches(KlineBar b, BigDecimal price, boolean down) {
        return down ? b.low().compareTo(price) <= 0 : b.high().compareTo(price) >= 0;
    }

    /** 最有利价到过几档止盈 */
    private static int reached(List<TargetRung> rungs, BigDecimal best, boolean isLong) {
        return (int) rungs.stream()
                .filter(r -> isLong ? best.compareTo(r.price()) >= 0 : best.compareTo(r.price()) <= 0).count();
    }

    /** 一批重放合计成每档止损宽度一行，加"实际"对"开仓后不动" */
    public static ReplaySummary summarize(List<Replay> replays) {
        List<WidthTally> widths = new ArrayList<>();
        for (int i = 0; i < STOP_WIDTHS.size(); i++) {
            int target = 0;
            int stop = 0;
            int open = 0;
            BigDecimal r = BigDecimal.ZERO;
            for (Replay replay : replays) {
                WidthOutcome o = replay.widths().get(i);
                switch (o.outcome()) {
                    case TARGET -> target++;
                    case STOP -> stop++;
                    case OPEN -> open++;
                }
                if (o.r() != null) {
                    r = r.add(o.r());
                }
            }
            widths.add(new WidthTally(STOP_WIDTHS.get(i), target, stop, open, r));
        }
        List<Replay> managed = replays.stream()
                .filter(r -> r.actualR() != null && r.untouched().r() != null).toList();
        return new ReplaySummary(replays.size(), widths, managed.size(),
                managed.stream().map(Replay::actualR).reduce(BigDecimal.ZERO, BigDecimal::add),
                managed.stream().map(r -> r.untouched().r()).reduce(BigDecimal.ZERO, BigDecimal::add),
                managed.stream().filter(Replay::withAdds).count());
    }

    /** 计划上的入场上下文 JSON 解析成树；没计划或没存给 null */
    static JsonNode entryCtx(AiTraderPlan plan) {
        return plan == null || plan.getEntryCtxJson() == null ? null : MAPPER.readTree(plan.getEntryCtxJson());
    }

    /** 按单子类型分组；排序：两笔以上的在前，再按净额绝对值 */
    public static List<SetupGroup> groups(List<TradeCase> cases) {
        Map<String, List<TradeCase>> byPlay = new LinkedHashMap<>();
        for (TradeCase c : cases) {
            byPlay.computeIfAbsent(Objects.toString(c.playType(), ""), k -> new ArrayList<>()).add(c);
        }
        List<SetupGroup> out = new ArrayList<>();
        for (Map.Entry<String, List<TradeCase>> e : byPlay.entrySet()) {
            int wins = (int) e.getValue().stream().filter(TradeCase::win).count();
            BigDecimal net = e.getValue().stream().map(TradeCase::net).filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            out.add(new SetupGroup(e.getKey().isEmpty() ? null : e.getKey(), e.getValue(), wins, net));
        }
        out.sort(Comparator.comparing((SetupGroup g) -> g.trades().size() >= 2 ? 0 : 1)
                .thenComparing(g -> g.net().abs(), Comparator.reverseOrder()));
        return out;
    }

    /** 窗口内该币的 OHLC；没 K 线返回 null */
    public MarketWindow market(String symbol, long fromMs, long toMs) {
        List<KlineBar> bars = historyStore.load(symbol, KlineHistoryStore.DEFAULT_INTERVAL, fromMs, toMs);
        if (bars.isEmpty()) {
            return null;
        }
        KlineBar hi = bars.stream().max(Comparator.comparing(KlineBar::high)).orElseThrow();
        KlineBar lo = bars.stream().min(Comparator.comparing(KlineBar::low)).orElseThrow();
        return new MarketWindow(symbol, bars.getFirst().openTime(), bars.getLast().openTime() + BAR_MS,
                bars.getFirst().open(), hi.high(), hi.openTime(), lo.low(), lo.openTime(), bars.getLast().close());
    }

    /**
     * 截至 toMs 最近一根收盘的 5m，相对若干时长之前那一刻的涨跌 %，按 lookbacksMs 的顺序；
     * 哪一头的 K 线本地没有，那一格为 null
     */
    public List<BigDecimal> changes(String symbol, long toMs, long... lookbacksMs) {
        long toBar = toMs - Math.floorMod(toMs, BAR_MS);
        long longest = Arrays.stream(lookbacksMs).max().orElse(0);
        Map<Long, BigDecimal> closes = new HashMap<>();
        for (KlineBar b : historyStore.load(symbol, KlineHistoryStore.DEFAULT_INTERVAL, toBar - longest - BAR_MS, toBar)) {
            closes.put(b.openTime(), b.close());
        }
        BigDecimal last = closes.get(toBar - BAR_MS);
        List<BigDecimal> out = new ArrayList<>();
        for (long lookback : lookbacksMs) {
            BigDecimal ref = closes.get(toBar - lookback - BAR_MS);
            out.add(last == null || ref == null ? null
                    : last.subtract(ref).multiply(BigDecimal.valueOf(100)).divide(ref, 2, RoundingMode.HALF_UP));
        }
        return out;
    }

    private static Replay replay(FuturesPositionDTO pos, AiTraderPlan plan, List<KlineBar> bars, long openBar) {
        List<PlanEntry> initial = PlanEntry.read(plan).stream().filter(e -> !e.isAdd()).toList();
        if (!initial.isEmpty()) return LadderReplay.replay(pos, initial, bars, hasFilledAdds(plan));
        BigDecimal entry = pos.getEntryPrice();
        BigDecimal r = dist(entry, plan == null ? null : plan.getStopLossPrice());
        List<KlineBar> after = bars.stream().filter(b -> b.openTime() > openBar).toList();
        if (r == null || after.isEmpty()) {
            return null;
        }
        boolean isLong = "LONG".equals(pos.getSide());
        BigDecimal fav = BigDecimal.ZERO;
        BigDecimal adv = BigDecimal.ZERO;
        BigDecimal hold = null;
        for (KlineBar b : after) {
            if (b.openTime() >= openBar + DIRECTION_MS) {
                break;
            }
            fav = fav.max(isLong ? b.high().subtract(entry) : entry.subtract(b.low()));
            adv = adv.max(isLong ? entry.subtract(b.low()) : b.high().subtract(entry));
            hold = isLong ? b.close().subtract(entry) : entry.subtract(b.close());
        }
        boolean covers24h = after.getLast().openTime() + BAR_MS >= openBar + DIRECTION_MS;
        // 止盈档由近到远排好，重放时一档一档碰
        List<TargetRung> rungs = TargetRung.of(plan).stream()
                .sorted(Comparator.comparing(t -> t.price().subtract(entry).abs())).toList();
        JsonNode ctx = entryCtx(plan);
        BigDecimal openRate = ctx != null && "LIMIT".equals(ctx.path("orderType").asString(null))
                ? FuturesFeeRates.MAKER : FuturesFeeRates.TAKER;
        List<WidthOutcome> widths = STOP_WIDTHS.stream()
                .map(w -> walk(after, isLong, entry, r.multiply(w), rungs, w, openRate)).toList();
        BigDecimal actualR = pos.getClosedPnl() == null || pos.getOpenedQuantity() == null ? null
                : pos.getClosedPnl().add(pos.getFundingFeeTotal() == null ? BigDecimal.ZERO : pos.getFundingFeeTotal())
                .divide(pos.getOpenedQuantity().multiply(r), 2, RoundingMode.HALF_UP);
        return new Replay(actualR, frac(fav, r), frac(adv, r).negate(), covers24h ? frac(hold, r) : null, widths, hasFilledAdds(plan));
    }

    /**
     * 逐根走：先看止损再看止盈档，同一根里都碰到算止损。碰到一档就按它的占比了结那部分，剩下的仓位接着走；
     * 整个仓位了结才算已决。
     */
    private static WidthOutcome walk(List<KlineBar> bars, boolean isLong, BigDecimal entry,
                                     BigDecimal stopDist, List<TargetRung> rungs, BigDecimal width, BigDecimal openRate) {
        // left 是还没了结的仓位比例，gain 是已了结各档的占比 × 目标距离
        BigDecimal left = BigDecimal.ONE;
        BigDecimal gain = BigDecimal.ZERO;
        BigDecimal fee = entry.multiply(openRate);
        BigDecimal stopPrice = isLong ? entry.subtract(stopDist) : entry.add(stopDist);
        int next = 0;
        for (KlineBar b : bars) {
            BigDecimal advExc = isLong ? entry.subtract(b.low()) : b.high().subtract(entry);
            if (advExc.compareTo(stopDist) >= 0) {
                fee = fee.add(left.multiply(stopPrice).multiply(FuturesFeeRates.TAKER));
                return new WidthOutcome(width, next == 0 ? Outcome.STOP : Outcome.TARGET,
                        gain.subtract(left.multiply(stopDist)).subtract(fee)
                                .divide(stopDist, 2, RoundingMode.HALF_UP));
            }
            BigDecimal favExc = isLong ? b.high().subtract(entry) : entry.subtract(b.low());
            while (next < rungs.size() && favExc.compareTo(rungs.get(next).price().subtract(entry).abs()) >= 0) {
                BigDecimal share = rungs.get(next).percent().movePointLeft(2);
                gain = gain.add(share.multiply(rungs.get(next).price().subtract(entry).abs()));
                fee = fee.add(share.multiply(rungs.get(next).price()).multiply(FuturesFeeRates.TAKER));
                left = left.subtract(share);
                next++;
            }
            if (left.signum() <= 0) {
                return new WidthOutcome(width, Outcome.TARGET, gain.subtract(fee).divide(stopDist, 2, RoundingMode.HALF_UP));
            }
        }
        return new WidthOutcome(width, Outcome.OPEN, null);
    }

    private static Path path(FuturesPositionDTO pos, AiTraderPlan plan, List<KlineBar> bars,
                             long openMs, long openBar, long closeBar) {
        if (bars.isEmpty()) {
            return Path.EMPTY;
        }
        boolean isLong = "LONG".equals(pos.getSide());
        BigDecimal entry = pos.getEntryPrice();
        BigDecimal stopDist = dist(entry, plan == null ? null : plan.getStopLossPrice());

        List<KlineBar> inTrade = new ArrayList<>();
        // 有利 / 逆向都记成离入场价的差值，价位最后按方向反推
        BigDecimal mfeExc = null;
        Long mfeAt = null;
        BigDecimal maeExc = null;
        Long minToHalfR = null;
        Long minTo1R = null;
        Long firstBackAt = null;
        BigDecimal best4h = null;
        BigDecimal best24h = null;
        for (KlineBar b : bars) {
            BigDecimal favExc = isLong ? b.high().subtract(entry) : entry.subtract(b.low());
            if (b.openTime() <= closeBar) {
                inTrade.add(b);
                BigDecimal advExc = isLong ? entry.subtract(b.low()) : b.high().subtract(entry);
                if (mfeExc == null || favExc.compareTo(mfeExc) > 0) {
                    mfeExc = favExc;
                    mfeAt = b.openTime();
                }
                if (maeExc == null || advExc.compareTo(maeExc) > 0) {
                    maeExc = advExc;
                }
                if (stopDist != null) {
                    long minutes = Math.max(0, (b.openTime() - openMs) / 60_000);
                    if (minToHalfR == null && favExc.compareTo(stopDist.multiply(new BigDecimal("0.5"))) >= 0) {
                        minToHalfR = minutes;
                    }
                    if (minTo1R == null && favExc.compareTo(stopDist) >= 0) {
                        minTo1R = minutes;
                    }
                }
                if (firstBackAt == null && b.openTime() > openBar
                        && (isLong ? b.close().compareTo(entry) < 0 : b.close().compareTo(entry) > 0)) {
                    firstBackAt = b.openTime();
                }
            } else if (b.openTime() <= closeBar + AFTER_LONG_MS) {
                BigDecimal fav = isLong ? b.high() : b.low();
                if (b.openTime() <= closeBar + AFTER_SHORT_MS) {
                    best4h = better(best4h, fav, isLong);
                }
                best24h = better(best24h, fav, isLong);
            }
        }
        // 出场后数据覆盖到哪：最后一根 5m 的收盘时刻
        long coveredTo = bars.getLast().openTime() + BAR_MS;
        boolean has4h = coveredTo >= closeBar + AFTER_SHORT_MS;
        boolean has24h = coveredTo >= closeBar + AFTER_LONG_MS;
        List<TargetRung> rungs = TargetRung.of(plan);
        Integer targetsAfter = !has24h || rungs.isEmpty() ? null : reached(rungs, best24h, isLong);
        BigDecimal mfe = mfeExc == null ? null : isLong ? entry.add(mfeExc) : entry.subtract(mfeExc);
        BigDecimal mae = maeExc == null ? null : isLong ? entry.subtract(maeExc) : entry.add(maeExc);
        return new Path(mfe, mfeAt, frac(mfeExc == null ? null : mfeExc.abs(), stopDist),
                mae, frac(maeExc == null ? null : maeExc.abs(), stopDist),
                minToHalfR, minTo1R, firstBackAt, hourlyBars(inTrade),
                has4h ? best4h : null, has24h ? best24h : null, targetsAfter);
    }

    private static BigDecimal better(BigDecimal cur, BigDecimal candidate, boolean isLong) {
        if (cur == null) {
            return candidate;
        }
        return isLong ? cur.max(candidate) : cur.min(candidate);
    }

    private static BigDecimal dist(BigDecimal entry, BigDecimal price) {
        if (price == null) {
            return null;
        }
        BigDecimal d = price.subtract(entry).abs();
        return d.signum() == 0 ? null : d;
    }

    private static BigDecimal frac(BigDecimal exc, BigDecimal dist) {
        return exc == null || dist == null ? null : exc.divide(dist, 2, RoundingMode.HALF_UP);
    }

    /** 5m 聚合成 1h：按整点分组，开=组内首根开、高/低=组内极值、收=组内末根收；不足 12 根的组照算 */
    static List<KlineBar> hourlyBars(List<KlineBar> bars) {
        List<KlineBar> out = new ArrayList<>();
        long curHour = -1;
        BigDecimal open = null;
        BigDecimal high = null;
        BigDecimal low = null;
        BigDecimal close = null;
        long closeTime = 0;
        for (KlineBar b : bars) {
            long hour = b.openTime() - Math.floorMod(b.openTime(), 3_600_000L);
            if (hour != curHour) {
                if (curHour >= 0) {
                    out.add(new KlineBar(curHour, closeTime, open, high, low, close, BigDecimal.ZERO));
                }
                curHour = hour;
                open = b.open();
                high = b.high();
                low = b.low();
            } else {
                high = high.max(b.high());
                low = low.min(b.low());
            }
            close = b.close();
            closeTime = b.closeTime();
        }
        if (curHour >= 0) {
            out.add(new KlineBar(curHour, closeTime, open, high, low, close, BigDecimal.ZERO));
        }
        return out;
    }
}
