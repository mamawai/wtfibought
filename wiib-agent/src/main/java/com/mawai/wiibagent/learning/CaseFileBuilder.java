package com.mawai.wiibagent.learning;

import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 复盘的事实表：一笔已了结交易在 5m K 线上走出的路径（最大浮盈、最大逆向、到目标 25%/50% 用时、
 * 第一次收回入场价、出场后 4h/24h 最有利价）加计划与入场上下文，按单子类型分组给模型做案例。
 * 纯代码，数字全来自本地 kline_history。
 */
@Component
@RequiredArgsConstructor
public class CaseFileBuilder {

    static final long BAR_MS = KlineHistoryStore.DEFAULT_BAR_MILLIS;
    static final long AFTER_SHORT_MS = 4 * 3_600_000L;
    static final long AFTER_LONG_MS = 24 * 3_600_000L;
    /** 每期最多几个案例 */
    public static final int MAX_CASES = 2;

    private final KlineHistoryStore historyStore;

    /** 一笔已了结交易：sim 仓位 + 配对的计划 + 入场上下文 + 路径 */
    public record TradeCase(FuturesPositionDTO pos, AiTraderPlan plan, JsonNode entryCtx, Path path) {
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
     * 路径指标。浮盈/逆向按方向取 K 线高低；占比以计划的原始目标/止损距离为分母，没计划就没占比。
     * minTo25/minTo50 是开仓后多少分钟第一次走到目标距离的 25%/50%；firstBackAt 是入场根之后第一根收盘穿回入场价的时刻。
     * 出场后数据覆盖不到 4h 时 bestAfter4h 为 null，覆盖不到 24h 时 bestAfter24h 与 targetAfter24h 为 null。
     * 窗口内没有 K 线时全部为 null、hourly 为空。
     */
    public record Path(BigDecimal mfePrice, Long mfeAt, BigDecimal mfeFrac,
                       BigDecimal maePrice, BigDecimal maeFrac,
                       Long minTo25, Long minTo50, Long firstBackAt,
                       List<KlineBar> hourly,
                       BigDecimal bestAfter4h, BigDecimal bestAfter24h, Boolean targetAfter24h) {
        static final Path EMPTY = new Path(null, null, null, null, null, null, null, null,
                List.of(), null, null, null);
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
        return new TradeCase(pos, plan, entryCtx(plan), path(pos, plan));
    }

    /** 计划上的入场上下文 JSON 解析成树；没计划或没存给 null */
    static JsonNode entryCtx(AiTraderPlan plan) {
        return plan == null || plan.getEntryCtxJson() == null ? null : MAPPER.readTree(plan.getEntryCtxJson());
    }

    /** 按单子类型分组；排序：本期两笔以上的在前，再按净额绝对值 */
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

    /** 本期案例：排序后取前 {@link #MAX_CASES} 组 */
    public static List<SetupGroup> select(List<SetupGroup> groups) {
        return groups.size() <= MAX_CASES ? groups : groups.subList(0, MAX_CASES);
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

    private Path path(FuturesPositionDTO pos, AiTraderPlan plan) {
        long openMs = TradePairing.msOf(pos.getCreatedAt());
        long closeMs = TradePairing.msOf(pos.getUpdatedAt());
        long openBar = openMs - Math.floorMod(openMs, BAR_MS);
        long closeBar = closeMs - Math.floorMod(closeMs, BAR_MS);
        List<KlineBar> bars = historyStore.load(pos.getSymbol(), KlineHistoryStore.DEFAULT_INTERVAL,
                openBar, closeBar + AFTER_LONG_MS);
        if (bars.isEmpty()) {
            return Path.EMPTY;
        }
        boolean isLong = "LONG".equals(pos.getSide());
        BigDecimal entry = pos.getEntryPrice();
        BigDecimal targetDist = dist(entry, plan == null ? null : plan.getTakeProfitPrice());
        BigDecimal stopDist = dist(entry, plan == null ? null : plan.getStopLossPrice());

        List<KlineBar> inTrade = new ArrayList<>();
        // 有利 / 逆向都记成离入场价的差值，价位最后按方向反推
        BigDecimal mfeExc = null;
        Long mfeAt = null;
        BigDecimal maeExc = null;
        Long minTo25 = null;
        Long minTo50 = null;
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
                if (targetDist != null) {
                    long minutes = Math.max(0, (b.openTime() - openMs) / 60_000);
                    if (minTo25 == null && favExc.compareTo(targetDist.multiply(new BigDecimal("0.25"))) >= 0) {
                        minTo25 = minutes;
                    }
                    if (minTo50 == null && favExc.compareTo(targetDist.multiply(new BigDecimal("0.5"))) >= 0) {
                        minTo50 = minutes;
                    }
                }
                if (firstBackAt == null && b.openTime() > openBar
                        && (isLong ? b.close().compareTo(entry) < 0 : b.close().compareTo(entry) > 0)) {
                    firstBackAt = b.openTime();
                }
            } else {
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
        BigDecimal tp = plan == null ? null : plan.getTakeProfitPrice();
        Boolean targetAfter = !has24h ? null
                : tp != null && (isLong ? best24h.compareTo(tp) >= 0 : best24h.compareTo(tp) <= 0);
        BigDecimal mfe = mfeExc == null ? null : isLong ? entry.add(mfeExc) : entry.subtract(mfeExc);
        BigDecimal mae = maeExc == null ? null : isLong ? entry.subtract(maeExc) : entry.add(maeExc);
        return new Path(mfe, mfeAt, frac(mfeExc == null ? null : mfeExc.abs(), targetDist),
                mae, frac(maeExc == null ? null : maeExc.abs(), stopDist),
                minTo25, minTo50, firstBackAt, hourlyBars(inTrade),
                has4h ? best4h : null, has24h ? best24h : null, targetAfter);
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
