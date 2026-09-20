package com.mawai.wiibagent.trader.wakeup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.trader.DecisionText;
import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 开场白里的观察包：上次醒来后的事件 + 账户状态 + 上一轮结论 + 最近轨迹 + 论点战绩。
 * 例行/警报两种开场白共用这一份。只读不写，取数和成文都在这里。
 */
@Component
@RequiredArgsConstructor
public class WakeObservation {

    private static final int RECENT_DECISIONS = 5;
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final AiTraderDecisionMapper decisionMapper;
    /** stale 教材过滤的共用入口（与复盘时间线/chat 同一套识别逻辑） */
    private final DecisionText decisionText;
    /** 论点战绩统计块，进观察包；null=本局无可统计或取数失败，整块缺席 */
    private final PlayStatsAssembler playStats;
    private final PromptCatalog prompts;

    /**
     * 回注给下一轮看的一轮唤醒：决策行里只留提示词用得到的几列。
     * 被主人标记忽略的交易，内容在这里已经剔掉（正文剔那个币的段、动作名剔那笔的），
     * 行头的时刻/状态/权益原样——那是唤醒事实，不是教材。
     */
    record RecentWake(long wakeTime, String status, BigDecimal equity, String error,
                      String reasoning, List<String> tools) {
    }

    /**
     * 最近 {@value #RECENT_DECISIONS} 条交易行（例行/警报/手动），最新在前。
     * 复盘/学习行不进来：它们的产出已经走 memory/learning_notes 注入，再进就是重复占字数。
     * 忽略过滤要按计划生命期判段落归属（同币另一方向没被忽略的段得留），plans 是本局全部计划。
     */
    List<RecentWake> recentWakes(AiTrader trader, long boundaryTime, List<AiTraderPlan> plans) {
        List<AiTraderDecision> rows = decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, trader.getId())
                .eq(AiTraderDecision::getRoundNo, trader.getRoundNo())
                .in(AiTraderDecision::getKind, AiTraderDecision.KIND_TRADE, AiTraderDecision.KIND_ALERT, AiTraderDecision.KIND_MANUAL)
                .lt(AiTraderDecision::getWakeTime, boundaryTime)
                .orderByDesc(AiTraderDecision::getWakeTime)
                .last("LIMIT " + RECENT_DECISIONS));
        List<RecentWake> out = new ArrayList<>(rows.size());
        for (AiTraderDecision d : rows) {
            String reasoning = decisionText.staleFiltered(d, plans);
            out.add(new RecentWake(d.getWakeTime(), d.getStatus(), d.getEquity(), d.getError(),
                    reasoning == null ? "" : reasoning, decisionText.staleFilteredToolNames(d, plans)));
        }
        return out;
    }

    /** 观察包：事件 + 账户 + 上一轮结论 + 最近轨迹 + 论点战绩。例行/警报开场白共用，排在头部事实之后、问题之前 */
    String assemble(AiTrader trader, BigDecimal equity, List<FuturesPositionDTO> positions,
                    List<FuturesOrderResponse> pendingOrders, TraderPlanStore.Reconcile reconcile,
                    List<RecentWake> recent, long boundaryTime, AgentLang lang) {
        StringBuilder sb = new StringBuilder();
        sb.append(events(reconcile.events(), lang));
        sb.append('\n').append(prompts.get(lang, "trader.wake.accountHeader")).append('\n')
                .append(WakeAccountState.accountStateJson(prompts, lang, equity, positions, pendingOrders, reconcile.live(), boundaryTime)).append('\n');
        sb.append('\n').append(lastConclusion(recent, lang));
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

    /** 自上次唤醒以来：对账事件逐条成文（了结说结局、成交说成交、挂单没了说没了），同一计划内先结局后成交；没事件返回空串 */
    private String events(List<TraderPlanStore.Event> events, AgentLang lang) {
        if (events.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n").append(prompts.get(lang, "trader.wake.eventsHeader")).append('\n');
        for (TraderPlanStore.Event e : events) {
            AiTraderPlan p = e.plan();
            sb.append(switch (e) {
                case TraderPlanStore.Closed c -> closedEvent(p, c.position(), lang);
                case TraderPlanStore.Filled f -> prompts.get(lang, "trader.wake.eventFilled",
                        Map.of("symbol", p.getSymbol(), "side", p.getSide()));
                case TraderPlanStore.Cancelled c -> prompts.get(lang, "trader.wake.eventCancelled",
                        Map.of("symbol", p.getSymbol(), "side", p.getSide()));
            }).append('\n');
        }
        return sb.toString();
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

    /** 最近一条写出了结论块的 OK 轮，结论块原样全文；一条都没有就说没有 */
    private String lastConclusion(List<RecentWake> recent, AgentLang lang) {
        for (RecentWake w : recent) {
            if (!AiTraderDecision.STATUS_OK.equals(w.status())) {
                continue;
            }
            String block = decisionText.conclusionBlock(w.reasoning());
            if (block == null) {
                continue;
            }
            return prompts.get(lang, "trader.wake.lastConclusionHeader", Map.of(
                    "time", TIME_FMT.format(Instant.ofEpochMilli(w.wakeTime())),
                    "equity", w.equity().setScale(0, RoundingMode.HALF_UP))) + "\n" + block + "\n";
        }
        return prompts.get(lang, "trader.wake.lastConclusionNone") + "\n";
    }

    /** 一行一轮：时刻 [状态] 权益，OK 行列工具名，非 OK 行列原因；SKIPPED 行没权益、OK 行多半没 error */
    private String trajectory(List<RecentWake> recent, AgentLang lang) {
        if (recent.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(prompts.get(lang, "trader.wake.trajectoryHeader")).append('\n');
        for (RecentWake w : recent) {
            sb.append("- ").append(TIME_FMT.format(Instant.ofEpochMilli(w.wakeTime())))
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
