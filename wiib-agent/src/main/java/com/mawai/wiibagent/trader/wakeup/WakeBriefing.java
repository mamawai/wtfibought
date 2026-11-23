package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibagent.trader.prompt.EconCalendarAssembler;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
import com.mawai.wiibagent.trader.prompt.TraderPromptAssembler;
import com.mawai.wiibagent.trader.trade.AccountGate;
import com.mawai.wiibagent.trader.trade.AccountGates;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 一轮开头的备料，分两步：{@link #reconcile} 取账户、对计划；{@link #prepare} 取这一轮该报的事件、最近几轮、盯盘条件、报价、
 * 观察包，拼成走法要用的 {@link WakeFlows.Brief}。整盘轮备整盘的，单币轮只备那个币的，另加一段账户概况。
 */
@Component
@RequiredArgsConstructor
class WakeBriefing {

    private final SimTradeClient simTradeClient;
    private final TraderPlanStore planStore;
    private final TraderWatchStore watchStore;
    /** 取账户和对账在这个 trader 的账户闸里做 */
    private final AccountGates gates;
    private final UnreportedEvents unreported;
    private final WakeObservation wakeObservation;
    /** 主人留言段从这里取，取一次就消费一轮 */
    private final TraderPromptAssembler promptAssembler;
    /** 财经日历注入块；null=无相关事件或取数失败，整块缺席 */
    private final EconCalendarAssembler econCalendar;

    /** 墙钟注入点：日历和账户概况的涨跌按它算 */
    LongSupplier nowMs = System::currentTimeMillis;

    /**
     * 对完账的账户：现取的持仓和挂单、对完还活着的计划。
     *
     * @param closed       已平仓位，这一轮最多拉一次，第一次用到才拉
     * @param lastActionNo 取这份账户时账户闸里记到第几笔动作：之后别的轮做的动作，这一轮没看到
     */
    record Ledger(List<FuturesPositionDTO> positions, List<FuturesOrderResponse> pendingOrders, List<AiTraderPlan> live,
                  Supplier<List<FuturesPositionDTO>> closed, long lastActionNo) {
    }

    /**
     * 备好的料。
     *
     * @param reportedEvents 开场白里报了的事件行，这一轮成功了才清
     * @param watchHits      告诉它的盯盘命中和告诉它的时刻：开场白里念的（触发源里的和补报的）是这一轮的 wake_time；
     *                       进行中插话告诉的之后加进来，是插进去那一刻。这一轮成功了才算告诉过
     * @param armed          这个 trader 全部在盯的条件，算这一轮看到过的
     */
    record Prepared(WakeFlows.Brief brief, List<UnreportedEvents.Line> reportedEvents, Map<Long, Long> watchHits,
                    List<AiTraderWatch> armed) {
    }

    /**
     * 现取持仓和挂单，拿它们对一遍计划：了结的归档并报结局、限价成交的绑上仓位。对出来的事件先存下，由管那个币的那一轮去报。
     * 取账户和对账在同一把账户闸里做：几轮同时在跑时，别的轮刚开的仓不会因为这边的账户是旧的被当成"仓位没了"
     */
    Ledger reconcile(AiTrader trader, long wakeTime, AgentLang lang) {
        // 已平仓位这一轮最多拉一次，第一次用到才拉：对账有事时用，开场白里今天净亏损了结的次数也从它数
        Supplier<List<FuturesPositionDTO>> closed = once(
                () -> simTradeClient.getClosedPositions(trader.getSimUserId(), PlayStatsAssembler.FETCH_LIMIT));
        AccountGate gate = gates.of(trader.getId());
        return gate.exclusive(() -> {
            List<FuturesPositionDTO> positions = simTradeClient.getAllPositions(trader.getSimUserId());
            List<FuturesOrderResponse> pendingOrders = simTradeClient.getPendingOrders(trader.getSimUserId(), null);
            TraderPlanStore.Reconcile reconcile = planStore.reconcile(trader.getId(), trader.getRoundNo(), positions,
                    pendingOrders, closed, group -> simTradeClient.getGroupOrders(trader.getSimUserId(), group), wakeTime, lang);
            unreported.add(trader.getId(), wakeObservation.planEvents(reconcile.events(), lang));
            return new Ledger(positions, pendingOrders, reconcile.live(), closed, gate.lastNo());
        });
    }

    /**
     * @param busyElsewhere 此刻另有单币轮在处理的币；整盘轮的开场白写明，这些币名下补报的盯盘命中留给那几轮
     */
    Prepared prepare(AiTrader trader, WakeScope scope, long wakeTime, Ledger ledger, BigDecimal equity,
                     List<WakeTrigger> triggers, Set<String> busyElsewhere, AgentLang lang) {
        List<FuturesPositionDTO> positions = ledger.positions();
        List<FuturesOrderResponse> pendingOrders = ledger.pendingOrders();
        Supplier<List<FuturesPositionDTO>> closed = ledger.closed();
        // 这一轮该报的事件：别的币的留给管它的那一轮
        List<UnreportedEvents.Line> events = unreported.due(trader.getId(), scope);
        // 本局全部计划（含归档）：最近轮的 stale 过滤要按计划生命期判段落归属
        List<AiTraderPlan> plans = planStore.listAll(trader.getId(), trader.getRoundNo());
        // 最近几轮只有整盘轮的开场白用（上次唤醒、轨迹）；单币轮说的是这个币上次被看到的时刻
        List<WakeObservation.RecentWake> recent = scope.single() ? List.of() : wakeObservation.recentWakes(trader, wakeTime, plans);

        // 盯盘条件只看这一轮管的币的。命中了还没哪轮告诉过模型的分两份：本轮触发源里的（头几句念）、其余的（事件块补报）
        Predicate<AiTraderWatch> mine = w -> scope.single() ? scope.symbol().equals(w.getForSymbol())
                : !busyElsewhere.contains(w.getForSymbol());
        Set<Long> triggerWatchIds = triggers.stream()
                .filter(WatchTrigger.class::isInstance).map(t -> ((WatchTrigger) t).watch().getId()).collect(Collectors.toSet());
        List<AiTraderWatch> quiet = watchStore.firedUnreported(trader.getId(), trader.getRoundNo()).stream()
                .filter(w -> !triggerWatchIds.contains(w.getId()) && mine.test(w)).toList();
        List<AiTraderWatch> armedAll = watchStore.armed(trader.getId(), trader.getRoundNo());
        List<AiTraderWatch> armed = scope.single()
                ? armedAll.stream().filter(w -> scope.symbol().equals(w.getForSymbol())).toList() : armedAll;

        // 开场的价格和资金费率共用一份：这一轮管的币、大盘参考和盯盘涉及的币都要
        Set<String> quoted = new LinkedHashSet<>(scope.single()
                ? List.of(WakeObservation.BENCHMARK, scope.symbol()) : WakeObservation.board(scope.tradable()));
        armed.forEach(w -> quoted.add(w.getSymbol()));
        Map<String, JsonNode> quotes = wakeObservation.quotes(quoted);
        Map<String, BigDecimal> marks = new HashMap<>();
        quotes.forEach((symbol, q) -> marks.put(symbol, q.path("markPrice").asDecimal(null)));

        WakeObservation.Pack pack = wakeObservation.assemble(trader, scope, equity, positions, pendingOrders,
                wakeObservation.lossesToday(trader, scope, closed, plans, wakeTime, lang),
                ledger.live(), events.stream().map(UnreportedEvents.Line::text).collect(Collectors.joining()),
                scope.single() ? null : wakeObservation.lastBookConclusion(trader, wakeTime, plans),
                wakeObservation.latestSegments(trader, wakeTime, scope.symbols(), plans),
                wakeTime, lang, quiet, armed, marks);

        // 留言进开场白不进 system；取一次就消费一轮。只给按点的例行轮和手动轮：被触发叫醒的轮在 K 线中间，主人留的话多半是按收盘说的
        String ownerNote = triggers.isEmpty() ? promptAssembler.ownerNoteBlock(trader, lang) : "";
        String calendar = null;
        String overview = "";
        if (scope.single()) {
            Object available = simTradeClient.getBalanceDetail(trader.getSimUserId()).get("available");
            overview = wakeObservation.accountOverview(scope.symbol(), equity,
                    available == null ? null : new BigDecimal(String.valueOf(available)),
                    positions, pendingOrders, quotes, nowMs.getAsLong(), lang);
        } else {
            // 上一边界之后公布的算"刚公布"：例行轮就是上次唤醒以来；手动和被触发的唤醒时刻不在边界上，对齐后同一个意思
            long intervalMs = WakeIntervals.MS.get(trader.getIntervalCode());
            calendar = econCalendar.assemble(nowMs.getAsLong(), WakeIntervals.align(wakeTime, intervalMs) - intervalMs, lang);
        }

        Map<Long, Long> watchHits = new HashMap<>();
        triggerWatchIds.forEach(id -> watchHits.put(id, wakeTime));
        quiet.forEach(w -> watchHits.put(w.getId(), wakeTime));
        return new Prepared(new WakeFlows.Brief(trader, scope, lang, wakeTime, triggers, recent, pack, calendar, ownerNote,
                quotes, overview, wakeObservation.busyElsewhere(scope, busyElsewhere, lang)), events, watchHits, armedAll);
    }

    /** 只取一次：第一次调才真去取，取到了之后都给同一份 */
    private static <T> Supplier<T> once(Supplier<T> source) {
        List<T> got = new ArrayList<>(1);
        return () -> {
            if (got.isEmpty()) {
                got.add(source.get());
            }
            return got.getFirst();
        };
    }
}
