package com.mawai.wiibagent.trader.wakeup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.learning.CaseFileBuilder;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.trader.DecisionText;
import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
import com.mawai.wiibagent.trader.trade.PlanEntry;
import com.mawai.wiibagent.trader.trade.StopOrders;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import com.mawai.wiibquant.market.service.CrossAssetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 开场白里的观察包。{@link #assemble}：上次醒来后的事件 + 账户状态（持仓带自上次完成决策以来的波动）
 * + 上一轮结论（每个币最近一次写下的那段，没变化的币沿用旧段）+ 承诺块（持仓与挂单的失效条件、布的每条盯盘条件离现价多远 / 还有多久、
 * 各币最近一段里的等待行，只列事实）。单币轮只出那个币的。
 * 整盘轮另有 {@link #history}（最近轨迹 + 论点战绩）、{@link #symbolsSummary}（各币自上次唤醒以来的开高低收）
 * 和 {@link #snapshot}（各币标记价与涨跌、美股参考）；单币轮另有 {@link #coinFacts}（这个币的走势和现价）
 * 和 {@link #accountOverview}（账户概况，别的币只给数字）。
 * 只读不写，取数和成文都在这里。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WakeObservation {

    /** 最近唤醒轨迹列几轮 */
    private static final int RECENT_DECISIONS = 5;
    /** 最近的唤醒行取这么多：轨迹和上一轮的总评从里面找 */
    private static final int RECENT_LOOKBACK = 30;
    /** 找一个币最近一次写的结论段：正文里带它段头的最近几行，取最先切得出那段的 */
    private static final int SEGMENT_CANDIDATES = 5;

    private final AiTraderDecisionMapper decisionMapper;
    /** stale 教材过滤的共用入口（与复盘时间线/chat 同一套识别逻辑） */
    private final DecisionText decisionText;
    /** 论点战绩统计块，进第二段；null=本局无可统计或取数失败，整块缺席 */
    private final PlayStatsAssembler playStats;
    private final PromptCatalog prompts;
    /** 持仓与各币自上次完成决策以来的波动 */
    private final PositionPath positionPath;
    /** 美股期货与 VIX，进行情快照 */
    private final CrossAssetService crossAssets;
    /** 各币的标记价和资金费率 */
    private final BinanceRestClient binanceRestClient;

    /** 大盘参考：不在可交易币里也给一行 */
    static final String BENCHMARK = "BTCUSDT";
    /** 进开场白的美股参考：Yahoo 代码 → 词表里的名字 */
    private static final Map<String, String> EQUITY_NAMES = Map.of("NQ=F", "nq", "ES=F", "es", "^VIX", "vix");

    /**
     * 回注给下一轮看的一轮唤醒：决策行里只留提示词用得到的几列。
     * 被主人标记忽略的交易，内容在这里已经剔掉（正文剔那个币的段、动作名剔那笔的），
     * 行头的时刻/状态/权益原样——那是唤醒事实，不是教材。
     */
    record RecentWake(long wakeTime, String status, BigDecimal equity, String error,
                      String reasoning, List<String> tools) {
    }

    /**
     * 第一段的观察包成文 + 两个事实：有没有要处置的承诺（事件、收盘命中、持仓、挂单、盯盘条件、等待行，
     * 有就分两段）、各币上次被看到的时刻（{@link #lastSeen}，各币摘要按它开窗）
     */
    record Pack(String text, boolean busy, Map<String, Long> lastSeen) {
    }

    /** 一个币最近一次写下的结论段：哪一轮写的、用哪门语言写的（等待行的标记按它认）、段身 */
    record Segment(long wakeTime, AgentLang lang, String body) {
    }

    /**
     * 最近 {@value #RECENT_LOOKBACK} 条交易行（例行/警报/手动/持仓事件/盯盘命中），最新在前。
     * 轨迹只列前 {@value #RECENT_DECISIONS} 条，其余是找上一轮总评用的。
     * 复盘行不进来：它的产出已经走 memory 注入，再进就是重复占字数。
     * 忽略过滤要按计划生命期判段落归属（同币另一方向没被忽略的段得留），plans 是本局全部计划。
     */
    List<RecentWake> recentWakes(AiTrader trader, long wakeTime, List<AiTraderPlan> plans) {
        List<AiTraderDecision> rows = decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, trader.getId())
                .eq(AiTraderDecision::getRoundNo, trader.getRoundNo())
                .in(AiTraderDecision::getKind, AiTraderDecision.WAKE_KINDS)
                .lt(AiTraderDecision::getWakeTime, wakeTime)
                .orderByDesc(AiTraderDecision::getWakeTime)
                .last("LIMIT " + RECENT_LOOKBACK));
        List<RecentWake> out = new ArrayList<>(rows.size());
        for (AiTraderDecision d : rows) {
            String reasoning = decisionText.staleFiltered(d, plans);
            out.add(new RecentWake(d.getWakeTime(), d.getStatus(), d.getEquity(), d.getError(),
                    reasoning == null ? "" : reasoning, decisionText.staleFilteredToolNames(d, plans)));
        }
        return out;
    }

    /**
     * 观察包：事件 + 账户 + 上一轮结论 + 承诺。排在头部事实之后、第一段的问题之前。
     * 单币轮只列那个币的持仓、挂单、计划、盯盘条件和它上一次写的结论；权益和合计风险照给。
     *
     * @param scope        这一轮管哪些币
     * @param lossesToday  账户块下面"今天净亏损了结"那一行（{@link #lossesToday}），没有就是空串
     * @param live         对完账还活着的计划
     * @param planEvents   这一轮该报的事件行（{@link UnreportedEvents#due}）拼起来，进"自上次唤醒以来"
     * @param segments     这一轮管的币各自最近一次写下的结论段（{@link #latestSegments}）
     * @param firedQuietly 命中了还没哪轮告诉过它的盯盘条件，进"自上次唤醒以来"补报
     * @param armed        这一轮管的币还在盯的盯盘条件，进承诺块
     * @param marks        各币标记价，承诺块算"离价位多远"用；没有的币不带距离
     */
    Pack assemble(AiTrader trader, WakeScope scope, BigDecimal equity, List<FuturesPositionDTO> positions,
                  List<FuturesOrderResponse> pendingOrders, String lossesToday,
                  List<AiTraderPlan> live, String planEvents,
                  List<RecentWake> recent, Map<String, Segment> segments, long wakeTime, AgentLang lang,
                  List<AiTraderWatch> firedQuietly, List<AiTraderWatch> armed,
                  Map<String, BigDecimal> marks) {
        // 整盘轮全列（不在可交易币里的旧仓也列），单币轮只列那个币的
        Predicate<String> shown = scope.single() ? scope.symbol()::equals : s -> true;
        List<FuturesPositionDTO> shownPositions = positions.stream().filter(p -> shown.test(p.getSymbol())).toList();
        List<FuturesOrderResponse> shownOrders = pendingOrders.stream().filter(o -> shown.test(o.getSymbol())).toList();
        StringBuilder sb = new StringBuilder();
        sb.append(events(planEvents, firedQuietly, lang));
        // 这一轮管的币（整盘轮连大盘参考）和有持仓的币，各自上次被看到的时刻
        Set<String> watched = new LinkedHashSet<>(scope.single() ? List.of(scope.symbol()) : board(scope.tradable()));
        shownPositions.forEach(p -> watched.add(p.getSymbol()));
        Map<String, Long> lastSeen = lastSeen(trader, wakeTime, watched);
        Map<Long, CaseFileBuilder.MarketWindow> paths = new HashMap<>();
        for (FuturesPositionDTO p : shownPositions) {
            CaseFileBuilder.MarketWindow w = positionPath.build(p, lastSeen.get(p.getSymbol()), wakeTime);
            if (w != null) {
                paths.put(p.getId(), w);
            }
        }
        sb.append('\n').append(prompts.get(lang, "trader.wake.accountHeader")).append('\n')
                .append(WakeAccountState.accountStateJson(prompts, lang, shown, equity, positions, pendingOrders, live,
                        wakeTime, paths)).append('\n')
                .append(lossesToday);
        sb.append('\n').append(scope.single()
                ? coinConclusion(scope.symbol(), segments.get(scope.symbol()), lang)
                : lastConclusion(latestConcluded(recent), segments, scope.symbols(), lang));
        String commitments = commitments(trader, shownPositions, shownOrders, live,
                armed, segments, marks, wakeTime, lang);
        sb.append(commitments);
        boolean busy = !firedQuietly.isEmpty() || !planEvents.isEmpty() || !commitments.isEmpty();
        return new Pack(sb.toString(), busy, lastSeen);
    }

    /**
     * 账户块下面那一行：今天（新加坡时间 0 点起）净亏损了结的仓位，按币和方向数，其中止损单触发的另数。
     * 净亏损按 sim 给的净盈亏（已扣手续费和资金费）判；主人标了不算数的那几笔不数。
     * 一笔都没有、或者已平仓位这次没取到，返回空串。单币轮只数那个币的
     *
     * @param closed sim 最近的已平仓位，这里第一次用到才去取
     * @param plans  本局全部计划，认主人标了不算数的用
     */
    String lossesToday(AiTrader trader, WakeScope scope, Supplier<List<FuturesPositionDTO>> closed, List<AiTraderPlan> plans,
                       long wakeTime, AgentLang lang) {
        List<FuturesPositionDTO> positions;
        try {
            positions = closed.get();
        } catch (Exception e) {
            log.warn("[WakeObservation] 已平仓位没取到，本轮不带今天净亏损了结那一行 traderId={} msg={}", trader.getId(), e.toString());
            return "";
        }
        Map<FuturesPositionDTO, AiTraderPlan> planByPos = TradePairing.pairAll(positions, plans);
        long dayStart = BeijingTime.dayStart(wakeTime);
        // 值是 [亏损了结几次, 其中止损单触发几次]
        Map<String, int[]> counts = new TreeMap<>();
        for (FuturesPositionDTO p : positions) {
            AiTraderPlan plan = planByPos.get(p);
            if (p.getClosedPnl() == null || p.getClosedPnl().signum() >= 0 || TradePairing.msOf(p.getUpdatedAt()) < dayStart
                    || (plan != null && Boolean.TRUE.equals(plan.getStale()))
                    || (scope.single() && !scope.symbol().equals(p.getSymbol()))) {
                continue;
            }
            int[] c = counts.computeIfAbsent(p.getSymbol() + " " + p.getSide(), k -> new int[2]);
            c[0]++;
            if (TradePairing.MANNER_STOP_LOSS.equals(TradePairing.closeMannerKey(p))) {
                c[1]++;
            }
        }
        if (counts.isEmpty()) {
            return "";
        }
        String rows = counts.entrySet().stream()
                .map(e -> prompts.get(lang, "trader.wake.lossesTodayRow", Map.of(
                        "position", e.getKey(), "n", e.getValue()[0], "stops", e.getValue()[1])))
                .collect(Collectors.joining(prompts.get(lang, "trader.wake.lossesTodaySep")));
        return prompts.get(lang, "trader.wake.lossesToday", Map.of("rows", rows)) + "\n";
    }

    /** 第二段的历史块：最近轨迹 + 论点战绩；都没有返回空串 */
    String history(AiTrader trader, List<RecentWake> recent, AgentLang lang) {
        StringBuilder sb = new StringBuilder();
        String trajectory = trajectory(recent, lang);
        if (!trajectory.isEmpty()) {
            sb.append('\n').append(trajectory);
        }
        String stats = playStats.assemble(trader, lang);
        if (stats != null) {
            sb.append('\n').append(stats);
        }
        return sb.toString();
    }

    /** 可交易币按币码排好；大盘参考不在里面的排在最前 */
    static List<String> board(Collection<String> tradable) {
        List<String> out = new ArrayList<>();
        if (!tradable.contains(BENCHMARK)) {
            out.add(BENCHMARK);
        }
        out.addAll(tradable.stream().sorted().toList());
        return out;
    }

    /** 各币的 premiumIndex 回包（标记价 + 资金费率），一币拉一次；单币拉取失败就没有它，不挡唤醒 */
    Map<String, JsonNode> quotes(Collection<String> symbols) {
        Map<String, JsonNode> out = new HashMap<>();
        for (String sym : symbols) {
            try {
                JsonNode p = MAPPER.readTree(binanceRestClient.getPremiumIndex(sym));
                if (p.path("markPrice").asDecimal(null) != null) {
                    out.put(sym, p);
                }
            } catch (Exception e) {
                log.debug("[Trader] 行情快照拉取失败 {} msg={}", sym, e.getMessage());
            }
        }
        return out;
    }

    /** 不可交易的那一行后面的标注；可交易的币不带 */
    private String referenceTag(String symbol, Collection<String> tradable, AgentLang lang) {
        return tradable.contains(symbol) ? "" : prompts.get(lang, "trader.wake.referenceTag");
    }

    /**
     * 行情快照：各币标记价、1h / 4h / 24h 涨跌、资金费率，大盘参考不可交易也带一行；
     * 后面跟美股参考（纳指期货、标普期货、VIX 的现价与相对昨收的涨跌，取不到整块不带）。
     *
     * @param quotes 各币的 premiumIndex 回包，没拉到的币没有它那行
     */
    String snapshot(Map<String, JsonNode> quotes, Collection<String> tradable, long now, AgentLang lang) {
        StringBuilder sb = new StringBuilder();
        for (String symbol : board(tradable)) {
            JsonNode q = quotes.get(symbol);
            if (q == null) {
                continue;
            }
            sb.append(prompts.get(lang, "trader.wake.snapshotRow", Map.of(
                    "symbol", symbol,
                    "price", WatchText.num(q.path("markPrice").asDecimal(null)),
                    "changes", changesText(symbol, now, lang),
                    "funding", q.path("lastFundingRate").asString(null),
                    "tag", referenceTag(symbol, tradable, lang)))).append('\n');
        }
        List<CrossAssetService.Quote> equities = crossAssets.snapshot().stream()
                .filter(e -> EQUITY_NAMES.containsKey(e.symbol())).toList();
        if (!equities.isEmpty()) {
            sb.append(prompts.get(lang, "trader.wake.equitiesHeader")).append('\n');
            for (CrossAssetService.Quote e : equities) {
                sb.append(prompts.get(lang, "trader.wake.equityRow", Map.of(
                        "name", prompts.get(lang, "trader.wake.equity." + EQUITY_NAMES.get(e.symbol())),
                        "price", WatchText.num(e.price()),
                        "change", signed(e.changePct1d()),
                        "time", BeijingTime.format(e.timeMs())))).append('\n');
            }
        }
        return sb.toString();
    }

    private static String signed(BigDecimal v) {
        return v.signum() >= 0 ? "+" + v.toPlainString() : v.toPlainString();
    }

    /**
     * 各币摘要：这几个币各自从上次被看到起的开高低收（代码按已收盘 5m 算）；一个币都算不出返回空串
     *
     * @param rows     列哪几个币，按这个顺序
     * @param tradable 可交易币，不在里面的那一行标成大盘参考
     * @param lastSeen 各币上次被看到的时刻（{@link #lastSeen}），每个币的窗口从它自己的那个时刻起
     */
    String symbolsSummary(AiTrader trader, List<String> rows, Collection<String> tradable, Map<String, Long> lastSeen,
                          long wakeTime, AgentLang lang) {
        long intervalMs = WakeIntervals.MS.get(trader.getIntervalCode());
        StringBuilder sb = new StringBuilder();
        for (String symbol : rows) {
            CaseFileBuilder.MarketWindow w = positionPath.symbol(symbol, lastSeen.get(symbol), wakeTime, intervalMs);
            if (w == null) {
                continue;
            }
            sb.append(prompts.get(lang, "trader.wake.symbolsRow", Map.ofEntries(
                    Map.entry("tag", referenceTag(symbol, tradable, lang)),
                    Map.entry("symbol", symbol),
                    Map.entry("from", BeijingTime.shortFormat(w.from())),
                    Map.entry("to", BeijingTime.shortFormat(w.to())),
                    Map.entry("open", WatchText.num(w.open())),
                    Map.entry("high", WatchText.num(w.high())),
                    Map.entry("highAt", BeijingTime.shortFormat(w.highAt())),
                    Map.entry("low", WatchText.num(w.low())),
                    Map.entry("lowAt", BeijingTime.shortFormat(w.lowAt())),
                    Map.entry("close", WatchText.num(w.close())),
                    Map.entry("changePct", w.changePct().toPlainString())))).append('\n');
        }
        return sb.isEmpty() ? "" : "\n" + prompts.get(lang, "trader.wake.symbolsHeader") + "\n" + sb;
    }

    /** 单币轮：这个币从上次被看到起的开高低收，和它现在的标记价、涨跌、资金费率；都没有返回空串 */
    String coinFacts(AiTrader trader, String symbol, Map<String, Long> lastSeen, long wakeTime, Map<String, JsonNode> quotes,
                     long now, AgentLang lang) {
        StringBuilder sb = new StringBuilder(symbolsSummary(trader, List.of(symbol), Set.of(symbol), lastSeen, wakeTime, lang));
        JsonNode q = quotes.get(symbol);
        if (q != null) {
            sb.append(sb.isEmpty() ? "\n" : "").append(prompts.get(lang, "trader.wake.single.quote", Map.of(
                    "symbol", symbol,
                    "price", WatchText.num(q.path("markPrice").asDecimal(null)),
                    "changes", changesText(symbol, now, lang),
                    "funding", q.path("lastFundingRate").asString("")))).append('\n');
        }
        return sb.toString();
    }

    /**
     * 单币轮的账户概况：权益、可用保证金、合计止损风险，别的币的持仓和开仓挂单各一行（只给数字），
     * 最后一行大盘参考——这个币本身就是大盘参考时不给
     */
    String accountOverview(String symbol, BigDecimal equity, BigDecimal available, List<FuturesPositionDTO> positions,
                           List<FuturesOrderResponse> pendingOrders, Map<String, JsonNode> quotes, long now, AgentLang lang) {
        StringBuilder sb = new StringBuilder("\n").append(prompts.get(lang, "trader.wake.single.accountHeader")).append('\n');
        sb.append(prompts.get(lang, "trader.wake.single.accountLine", Map.of(
                "equity", equity.setScale(2, RoundingMode.HALF_UP),
                "available", available == null ? "?" : available.setScale(2, RoundingMode.HALF_UP),
                "lossAtStops", StopOrders.pctOfEquity(positions.stream().map(StopOrders::lossAtStops)
                        .reduce(BigDecimal.ZERO, BigDecimal::add), equity)))).append('\n');
        boolean others = false;
        for (FuturesPositionDTO p : positions) {
            if (symbol.equals(p.getSymbol())) {
                continue;
            }
            others = true;
            sb.append(prompts.get(lang, "trader.wake.single.otherPosition", Map.of(
                    "symbol", p.getSymbol(), "side", p.getSide(),
                    "quantity", WatchText.num(p.getQuantity()), "entry", WatchText.num(p.getEntryPrice()),
                    "leverage", p.getLeverage(), "pnl", money(p.getUnrealizedPnl()),
                    "lossAtStop", StopOrders.pctOfEquity(StopOrders.lossAtStops(p), equity)))).append('\n');
        }
        for (FuturesOrderResponse o : pendingOrders) {
            if (symbol.equals(o.getSymbol()) || o.getOrderSide() == null || !o.getOrderSide().startsWith("OPEN_")) {
                continue;
            }
            others = true;
            sb.append(prompts.get(lang, "trader.wake.single.otherOrder", Map.of(
                    "symbol", o.getSymbol(), "side", o.getOrderSide().substring("OPEN_".length()),
                    "limit", WatchText.num(o.getLimitPrice()), "quantity", WatchText.num(o.getQuantity()),
                    "lossAtStop", StopOrders.pctOfEquity(StopOrders.lossAtStops(o), equity)))).append('\n');
        }
        if (!others) {
            sb.append(prompts.get(lang, "trader.wake.single.otherNone")).append('\n');
        }
        JsonNode benchmark = quotes.get(BENCHMARK);
        if (!BENCHMARK.equals(symbol) && benchmark != null) {
            sb.append(prompts.get(lang, "trader.wake.single.benchmark", Map.of(
                    "price", WatchText.num(benchmark.path("markPrice").asDecimal(null)),
                    "changes", changesText(BENCHMARK, now, lang)))).append('\n');
        }
        return sb.toString();
    }

    /** 整盘轮开场白里的一句：此刻另有单币轮在处理哪几个币。单币轮、或者没有别的轮在跑，返回空串 */
    String busyElsewhere(WakeScope scope, Set<String> symbols, AgentLang lang) {
        return scope.single() || symbols.isEmpty() ? ""
                : "\n" + prompts.get(lang, "trader.wake.busyElsewhere",
                        Map.of("symbols", String.join(", ", symbols.stream().sorted().toList()))) + "\n";
    }

    /** 一个币最近 1h / 4h / 24h 的涨跌写成一格一格，缺 K 线的那格写占位 */
    private String changesText(String symbol, long now, AgentLang lang) {
        return positionPath.changes(symbol, now).stream()
                .map(c -> c == null ? prompts.get(lang, "trader.wake.noData") : signed(c) + "%")
                .collect(Collectors.joining(" / "));
    }

    /**
     * 这几个币各自上次被看到的时刻：本局最近一条成功的、管着它的唤醒行（复盘行不算）——看整盘的一轮，或只处理它的那一轮。
     * 只处理别的币的那几轮不算：那几轮没看它。一条都没有的币不在结果里
     */
    Map<String, Long> lastSeen(AiTrader trader, long wakeTime, Collection<String> symbols) {
        Map<String, Long> out = new HashMap<>();
        for (String symbol : symbols) {
            List<AiTraderDecision> rows = decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                    .eq(AiTraderDecision::getTraderId, trader.getId())
                    .eq(AiTraderDecision::getRoundNo, trader.getRoundNo())
                    .in(AiTraderDecision::getKind, AiTraderDecision.WAKE_KINDS)
                    .eq(AiTraderDecision::getStatus, AiTraderDecision.STATUS_OK)
                    .and(w -> w.isNull(AiTraderDecision::getSymbol).or().eq(AiTraderDecision::getSymbol, symbol))
                    .lt(AiTraderDecision::getWakeTime, wakeTime)
                    .orderByDesc(AiTraderDecision::getWakeTime)
                    .last("LIMIT 1"));
            if (!rows.isEmpty()) {
                out.put(symbol, rows.getFirst().getWakeTime());
            }
        }
        return out;
    }

    /** 自上次唤醒以来：命中了但没叫醒它的盯盘条件，再是对账事件行。都没有返回空串 */
    private String events(String planEvents, List<AiTraderWatch> firedQuietly, AgentLang lang) {
        if (planEvents.isEmpty() && firedQuietly.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n").append(prompts.get(lang, "trader.wake.eventsHeader")).append('\n');
        for (AiTraderWatch w : firedQuietly) {
            sb.append(prompts.get(lang, "trader.wake.eventWatchFired", Map.of(
                    "time", BeijingTime.format(w.getFiredAt()),
                    "detail", WatchText.detail(w, w.getFiredPrice(), prompts, lang),
                    "note", WatchText.note(w, prompts, lang)))).append('\n');
        }
        return sb.append(planEvents).toString();
    }

    /**
     * 对账事件逐条成文，一条一行（了结说结局、成交说成交、挂单没了说没了），同一计划内先结局后成交；每行带着它归哪个币
     */
    List<UnreportedEvents.Line> planEvents(List<TraderPlanStore.Event> events, AgentLang lang) {
        List<UnreportedEvents.Line> out = new ArrayList<>();
        for (TraderPlanStore.Event e : events) {
            AiTraderPlan p = e.plan();
            out.add(new UnreportedEvents.Line(p.getSymbol(), switch (e) {
                case TraderPlanStore.Closed c -> closedEvent(p, c.position(), lang);
                case TraderPlanStore.Filled f -> prompts.get(lang, "trader.wake.eventFilled",
                        Map.of("symbol", p.getSymbol(), "side", p.getSide()));
                case TraderPlanStore.EntryFilled f -> entryEvent(p, f.entry(), f.counts(), true, lang);
                case TraderPlanStore.EntryCancelled c -> entryEvent(p, c.entry(), c.counts(), false, lang);
                case TraderPlanStore.Cancelled c -> prompts.get(lang, "trader.wake.eventCancelled",
                        Map.of("symbol", p.getSymbol(), "side", p.getSide()));
            } + '\n'));
        }
        return out;
    }

    /** 分档计划里一档成交或被撤的一句：哪一档、成交价或撤单原因，这份计划共几档、成交几档、还挂几档 */
    private String entryEvent(AiTraderPlan plan, PlanEntry entry,
                              TraderPlanStore.EntryCounts counts, boolean filled, AgentLang lang) {
        BigDecimal price = entry.getLimitPrice() == null ? entry.getFilledPrice() : entry.getLimitPrice();
        return prompts.get(lang, filled ? "trader.wake.eventEntryFilled" : "trader.wake.eventEntryCancelled",
                Map.of("symbol", plan.getSymbol(), "side", plan.getSide(),
                        "price", WatchText.num(price),
                        "qty", WatchText.num(entry.getQuantity()),
                        "detail", filled ? WatchText.num(entry.getFilledPrice()) : String.valueOf(entry.getReason()),
                        "total", counts.total(), "filled", counts.filled(), "pending", counts.pending()));
    }

    /** 一条平仓事件：了结方式/成交价/盈亏 + 当时的论点与失效条件 */
    private String closedEvent(AiTraderPlan p, FuturesPositionDTO pos, AgentLang lang) {
        return prompts.get(lang, "trader.wake.eventClosed", Map.of(
                "symbol", p.getSymbol(), "side", p.getSide(),
                "manner", TradePairing.closeManner(prompts, pos, lang),
                "price", String.valueOf(pos.getClosedPrice()),
                "pnl", money(pos.getClosedPnl()),
                "playType", String.valueOf(p.getPlayType()),
                "invalidation", p.getInvalidationCondition()));
    }

    /** sim 的 closedPnl 可以为空（PlayStatsAssembler 同样按空处理），Map.of 不收 null */
    private static String money(BigDecimal v) {
        return v == null ? "?" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 最近一条写出了结论块的 OK 轮；没有给 null */
    private RecentWake latestConcluded(List<RecentWake> recent) {
        for (RecentWake w : recent) {
            if (AiTraderDecision.STATUS_OK.equals(w.status()) && decisionText.conclusionBlock(w.reasoning()) != null) {
                return w;
            }
        }
        return null;
    }

    /**
     * 这几个币各自最近一次写下的结论段，按币码排好。按币到库里找：正文里带它段头的、最近写完的几行，
     * 取最先切得出这个币那段的。本局没写过的币不在结果里
     *
     * @param plans 本局全部计划，剔主人标了不算数的段用
     */
    Map<String, Segment> latestSegments(AiTrader trader, long wakeTime, Collection<String> symbols, List<AiTraderPlan> plans) {
        Map<String, Segment> out = new LinkedHashMap<>();
        for (String symbol : symbols.stream().sorted().toList()) {
            List<AiTraderDecision> rows = decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                    .eq(AiTraderDecision::getTraderId, trader.getId())
                    .eq(AiTraderDecision::getRoundNo, trader.getRoundNo())
                    .in(AiTraderDecision::getKind, AiTraderDecision.WAKE_KINDS)
                    .eq(AiTraderDecision::getStatus, AiTraderDecision.STATUS_OK)
                    .lt(AiTraderDecision::getWakeTime, wakeTime)
                    .like(AiTraderDecision::getReasoning, "[" + symbol + "]")
                    // 决策行在一轮结束时落库：两轮都写了这个币的，后写完的那段算数
                    .orderByDesc(AiTraderDecision::getId)
                    .last("LIMIT " + SEGMENT_CANDIDATES));
            for (AiTraderDecision d : rows) {
                Segment segment = segmentOf(d, symbol, plans);
                if (segment != null) {
                    out.put(symbol, segment);
                    break;
                }
            }
        }
        return out;
    }

    /** 这一行决策的结论块里这个币的那一段；没写结论块、没有这个币的段、或者那段被主人标了不算数，给 null */
    private Segment segmentOf(AiTraderDecision d, String symbol, List<AiTraderPlan> plans) {
        String reasoning = decisionText.staleFiltered(d, plans);
        if (reasoning == null || reasoning.isBlank()) {
            return null;
        }
        DecisionText.Conclusion c = decisionText.locateConclusion(reasoning, AgentLang.ZH);
        if (c == null) {
            return null;
        }
        for (DecisionText.ConclusionSegment s : DecisionText.splitSegments(c.body(reasoning))) {
            if (symbol.equals(s.symbol())) {
                return new Segment(d.getWakeTime(), c.lang(), s.body().strip());
            }
        }
        return null;
    }

    /** 单币轮的上一轮结论：只有这个币最近一次写下的那一段；本局没写过就点名 */
    private String coinConclusion(String symbol, Segment segment, AgentLang lang) {
        StringBuilder sb = new StringBuilder(prompts.get(lang, "trader.wake.single.lastConclusionHeader",
                Map.of("symbol", symbol))).append('\n');
        if (segment == null) {
            return sb.append(prompts.get(lang, "trader.wake.lastSegmentNone", Map.of("symbol", symbol))).append('\n').toString();
        }
        return sb.append(prompts.get(lang, "trader.wake.lastSegment", Map.of(
                        "symbol", symbol, "time", BeijingTime.shortFormat(segment.wakeTime()))))
                .append('\n').append(segment.body()).append('\n').toString();
    }

    /**
     * 上一轮结论：最近一轮的引子（总评、主人留言的对账行）+ 每个币最近一次写下的那段，段头带写下它的时刻。
     * 本轮没写的币沿用旧段，所以各段的时刻可以不同；翻不到段的币点名；一轮结论都没有就说没有
     */
    private String lastConclusion(RecentWake concluded, Map<String, Segment> segments, Collection<String> symbols,
                                  AgentLang lang) {
        if (concluded == null) {
            return prompts.get(lang, "trader.wake.lastConclusionNone") + "\n";
        }
        StringBuilder sb = new StringBuilder(prompts.get(lang, "trader.wake.lastConclusionHeader", Map.of(
                "time", BeijingTime.shortFormat(concluded.wakeTime()),
                "equity", concluded.equity().setScale(0, RoundingMode.HALF_UP)))).append('\n');
        DecisionText.Conclusion c = decisionText.locateConclusion(concluded.reasoning(), lang);
        String intro = DecisionText.intro(c.body(concluded.reasoning()));
        if (!intro.isEmpty()) {
            sb.append(intro).append('\n');
        }
        segments.forEach((symbol, s) -> sb.append(prompts.get(lang, "trader.wake.lastSegment", Map.of(
                "symbol", symbol, "time", BeijingTime.shortFormat(s.wakeTime()))))
                .append('\n').append(s.body()).append('\n'));
        symbols.stream().sorted().filter(s -> !segments.containsKey(s)).forEach(s ->
                sb.append(prompts.get(lang, "trader.wake.lastSegmentNone", Map.of("symbol", s))).append('\n'));
        return sb.toString();
    }

    // ==================== 承诺块 ====================

    /**
     * 承诺块：持仓与挂单各一行（失效条件 + 刚收盘那根心跳周期的开高低收）、在盯的每条条件一行（离现价多远 / 还有多久，带预案的跟上命中后平台会做什么）、
     * 每个币最近一段结论里的等待行一行（那个币一条条件都没布就说一句）。只列事实，判断留给模型。一条都没有返回空串
     */
    private String commitments(AiTrader trader, List<FuturesPositionDTO> positions,
                               List<FuturesOrderResponse> pendingOrders, List<AiTraderPlan> live, List<AiTraderWatch> armed,
                               Map<String, Segment> segments, Map<String, BigDecimal> marks, long wakeTime, AgentLang lang) {
        long intervalMs = WakeIntervals.MS.get(trader.getIntervalCode());
        Map<String, AiTraderPlan> planByKey = new HashMap<>();
        live.forEach(p -> planByKey.put(TraderPlanStore.key(p.getSymbol(), p.getSide()), p));
        Map<String, String> lastBars = new HashMap<>();
        List<String> lines = new ArrayList<>();
        for (FuturesPositionDTO p : positions) {
            AiTraderPlan plan = planByKey.get(TraderPlanStore.key(p.getSymbol(), p.getSide()));
            lines.add(prompts.get(lang, "trader.wake.commitPosition", Map.of(
                    "symbol", p.getSymbol(), "side", p.getSide(),
                    "invalidation", invalidation(plan),
                    "bar", lastBars.computeIfAbsent(p.getSymbol(), s -> lastBar(s, trader.getIntervalCode(), intervalMs, wakeTime, lang)))));
        }
        for (FuturesOrderResponse o : pendingOrders) {
            String side = o.getOrderSide();
            // 开仓挂单才是承诺（带计划的入场条件）；平仓挂单只在账户块里
            if (side == null || !side.startsWith("OPEN_")) {
                continue;
            }
            AiTraderPlan plan = planByKey.get(TraderPlanStore.key(o.getSymbol(), side.substring("OPEN_".length())));
            lines.add(prompts.get(lang, "trader.wake.commitOrder", Map.of(
                    "symbol", o.getSymbol(), "side", side,
                    "limit", WatchText.num(o.getLimitPrice()),
                    "pendingFor", WakeAccountState.humanizeHeld(prompts, lang, wakeTime - TradePairing.msOf(o.getCreatedAt())),
                    "invalidation", invalidation(plan),
                    "distance", distance(o.getLimitPrice(), marks.get(o.getSymbol()), lang))));
        }
        for (AiTraderWatch w : armed) {
            if (AiTraderWatch.AT_TIME.equals(w.getKind())) {
                lines.add(prompts.get(lang, "trader.wake.commitWatchTime", Map.of(
                        "symbol", w.getSymbol(), "at", BeijingTime.format(w.getAtTime()), "note", w.getNote(),
                        "forTag", WatchText.forTag(w, prompts, lang),
                        "minutes", Math.max(0, (w.getAtTime() - wakeTime + 59_999) / 60_000)))
                        + WatchText.action(w, prompts, lang));
            } else {
                lines.add(prompts.get(lang, "trader.wake.commitWatchPrice", Map.of(
                        "symbol", w.getSymbol(), "condition", WatchText.armed(w, prompts, lang), "note", w.getNote(),
                        "forTag", WatchText.forTag(w, prompts, lang),
                        "distance", distance(w.getLevel(), marks.get(w.getSymbol()), lang)))
                        + WatchText.action(w, prompts, lang));
            }
        }
        // 一个币有没有布条件，看的是为它布的
        Set<String> armedSymbols = armed.stream().map(AiTraderWatch::getForSymbol).collect(Collectors.toSet());
        waitLines(segments).forEach((symbol, wait) ->
                lines.add(prompts.get(lang, "trader.wake.commitWait", Map.of("symbol", symbol, "wait", wait))
                        + (armedSymbols.contains(symbol) ? "" : prompts.get(lang, "trader.wake.commitWaitUnarmed"))));
        if (lines.isEmpty()) {
            return "";
        }
        return "\n" + prompts.get(lang, "trader.wake.commitHeader") + "\n" + String.join("\n", lines) + "\n";
    }

    private static String invalidation(AiTraderPlan plan) {
        return plan == null || plan.getInvalidationCondition() == null ? "?" : plan.getInvalidationCondition();
    }

    /** 刚收盘那根心跳周期的开高低收，接在持仓行后面；本地没有那段 5m 就不带 */
    private String lastBar(String symbol, String interval, long intervalMs, long wakeTime, AgentLang lang) {
        CaseFileBuilder.MarketWindow w = positionPath.lastClosedBar(symbol, wakeTime, intervalMs);
        return w == null ? "" : prompts.get(lang, "trader.wake.commitBar", Map.of(
                "interval", interval,
                "open", WatchText.num(w.open()), "high", WatchText.num(w.high()),
                "low", WatchText.num(w.low()), "close", WatchText.num(w.close())));
    }

    /** "现价 X，在价位上方 / 下方 Y%"，接在挂单行、价格类条件行后面；没有现价就不带 */
    private String distance(BigDecimal level, BigDecimal mark, AgentLang lang) {
        if (level == null || mark == null || level.signum() <= 0) {
            return "";
        }
        BigDecimal pct = mark.subtract(level).multiply(BigDecimal.valueOf(100)).divide(level, 2, RoundingMode.HALF_UP);
        return prompts.get(lang, pct.signum() < 0 ? "trader.wake.commitBelow" : "trader.wake.commitAbove", Map.of(
                "mark", WatchText.num(mark), "level", WatchText.num(level), "pct", pct.abs().toPlainString()));
    }

    /**
     * 每个币最近一段结论里的"等待"行（写了"无"的不算）：按那段自己那门语言的标记认。
     * 一行等待都没写返回空表；顺序照币码
     */
    private Map<String, String> waitLines(Map<String, Segment> segments) {
        Map<String, String> out = new LinkedHashMap<>();
        segments.forEach((symbol, s) -> {
            // 长的排前面：等待条件 / Waiting for 要先于 等待 / Waiting 认
            Matcher m = Pattern.compile("(?m)^\\s*(?:" + Pattern.quote(prompts.get(s.lang(), "trader.mark.waitLong"))
                    + "|" + Pattern.quote(prompts.get(s.lang(), "trader.mark.wait")) + ")\\s*[:：]\\s*(.+?)\\s*$").matcher(s.body());
            if (m.find() && !m.group(1).replaceAll("[。.\\s]+$", "")
                    .equalsIgnoreCase(prompts.get(s.lang(), "trader.mark.waitNone"))) {
                out.put(symbol, m.group(1));
            }
        });
        return out;
    }

    /** 一行一轮：时刻 [状态] 权益，OK 行列工具名，非 OK 行列原因；SKIPPED 行没权益、OK 行多半没 error */
    private String trajectory(List<RecentWake> recent, AgentLang lang) {
        if (recent.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(prompts.get(lang, "trader.wake.trajectoryHeader")).append('\n');
        for (RecentWake w : recent.subList(0, Math.min(RECENT_DECISIONS, recent.size()))) {
            sb.append("- ").append(BeijingTime.shortFormat(w.wakeTime()))
                    .append(" [").append(w.status()).append(']');
            if (w.equity() != null) {
                sb.append(' ').append(prompts.get(lang, "trader.label.equity",
                        Map.of("value", w.equity().setScale(0, RoundingMode.HALF_UP))));
            }
            if (w.error() != null && !w.error().isBlank()) {
                sb.append(' ').append(w.error());
            }
            if (!w.tools().isEmpty()) {
                sb.append(' ').append(prompts.get(lang, "trader.wake.trajectoryTools", Map.of("tools", countNames(w.tools()))));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** klines, kline_structure×2 这种写法 */
    private static String countNames(List<String> names) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        names.forEach(n -> counts.merge(n, 1, Integer::sum));
        return counts.entrySet().stream()
                .map(e -> e.getValue() == 1 ? e.getKey() : e.getKey() + "×" + e.getValue())
                .collect(Collectors.joining(", "));
    }
}
