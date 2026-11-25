package com.mawai.wiibagent.trader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.learning.DebriefRunner;
import com.mawai.wiibagent.learning.ReviewRunner;
import com.mawai.wiibagent.learning.RunningDebriefs;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.trader.trade.TradeActions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 竞技场「已了结交易 · 论点→结局」：sim 已平仓位 ⟵配对⟶ 交易计划 ⟵关联⟶ 开仓/平仓那一轮的决策全文。
 * 配对与了结方式推断复用 {@link TradePairing} 的同一套算法（复盘/竞技场/统计三处一致，各配一套会自相矛盾）。
 * 只看当前局：每局独立 sim 子账户，AiTrader 只存当前局 simUserId，历史局查不回来。
 */
@Service
@RequiredArgsConstructor
public class TradeRecordService {

    /** 竞技场一页最多展示的已了结笔数（sim 按 updatedAt 倒序返回，取前 N 即最近 N 笔） */
    static final int LIMIT = 50;
    /** 平仓决策候选窗口向前放宽的量：例行唤醒的 wake_time 是 K 线边界，比同一轮里才开出的仓位早，至多一个档位（最大 4h） */
    private static final long CLOSE_LOOKBACK_MS = 14_400_000L;
    /** 平仓决策从哪几种行里找：唤醒行，加预案执行 */
    private static final List<String> CLOSE_KINDS = Stream.concat(
            AiTraderDecision.WAKE_KINDS.stream(), Stream.of(AiTraderDecision.KIND_PRESET)).toList();

    private final SimTradeClient simTradeClient;
    private final AiTraderPlanMapper planMapper;
    private final AiTraderDecisionMapper decisionMapper;
    /** 每个交易员正在复盘哪一笔 */
    private final RunningDebriefs running;

    /** 时间线里那一条决策的引用（id 可对上时间线卡）；reason 只有平仓有——close_position 的一句话理由 */
    public record DecisionRef(long id, long wakeTime, String kind, String reasoning, String reason) {
    }

    /**
     * debrief 是这笔的单笔复盘（时间线上的 DEBRIEF 行，只带 id 和时刻），还没复盘成功就是 null。
     * debriefable：能点"立即复盘"（计划正好绑着这个仓位、没忽略、没复盘成功过、没在复盘、平仓那根 K 线已经收进来）；
     * debriefing：正在复盘的就是这一笔；debriefError：还没复盘成功、最近一次失败的原因，没失败过为 null。
     * review 是这笔进了的那篇统一复盘（只带 id 和时刻），还没进就是 null；
     * reviewDueAt：单笔复盘做完了、还没进统一复盘的，预计进哪一篇（那篇的时刻），别的情况为 null
     */
    public record TradeRecord(long positionId, String symbol, String side, Integer leverage,
                              BigDecimal entryPrice, BigDecimal closedPrice, BigDecimal closedPnl,
                              long openedAt, long closedAt, String closeMannerKey,
                              AiTraderPlan plan, DecisionRef openDecision, DecisionRef closeDecision,
                              DecisionRef debrief, boolean debriefable, boolean debriefing, String debriefError,
                              DecisionRef review, Long reviewDueAt) {
    }

    /** 这些仓位的单笔复盘：ok 是成功的那篇（有几篇取最新的），lastError 是没成功过的那几笔最近一次失败的原因 */
    private record Debriefs(Map<Long, DecisionRef> ok, Map<Long, String> lastError) {
    }

    public List<TradeRecord> closedTrades(AiTrader t) {
        List<FuturesPositionDTO> closed = simTradeClient.getClosedPositions(t.getSimUserId(), LIMIT);
        if (closed.isEmpty()) {
            return List.of();
        }
        // 全量拉本局计划在内存配对（与复盘同口径：一局的计划量有限），配对走 pairAll 统一入口
        List<AiTraderPlan> plans = planMapper.selectList(new LambdaQueryWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getTraderId, t.getId())
                .eq(AiTraderPlan::getRoundNo, t.getRoundNo()));
        Map<FuturesPositionDTO, AiTraderPlan> planByPos = TradePairing.pairAll(closed, plans);
        Map<Long, AiTraderDecision> openByPlan = openDecisions(t, planByPos.values());
        Map<Long, DecisionRef> closeByPos = closeDecisions(t, closed);
        // 先读正在复盘哪笔，再查复盘行（复盘收尾是先落行、再清标记）
        Long debriefingPos = running.positionOf(t.getId());
        Debriefs debriefs = debriefs(t, closed);
        Map<Long, DecisionRef> reviewById = reviews(planByPos.values());
        long now = System.currentTimeMillis();

        List<TradeRecord> out = new ArrayList<>(closed.size());
        for (FuturesPositionDTO pos : closed) {
            AiTraderPlan plan = planByPos.get(pos);
            AiTraderDecision open = plan == null ? null : openByPlan.get(plan.getId());
            DecisionRef debrief = debriefs.ok().get(pos.getId());
            boolean debriefing = pos.getId().equals(debriefingPos);
            long closeMs = TradePairing.msOf(pos.getUpdatedAt());
            // 能复盘的：计划正好绑着这个仓位（按时间就近配上的不算），主人也没标不算数
            boolean reviewable = plan != null && pos.getId().equals(plan.getPositionId())
                    && !Boolean.TRUE.equals(plan.getStale());
            boolean debriefable = debrief == null && !debriefing && reviewable && DebriefRunner.manualReady(closeMs, now);
            DecisionRef review = plan == null || plan.getReviewId() == null ? null : reviewById.get(plan.getReviewId());
            Long reviewDueAt = debrief != null && reviewable && plan.getReviewId() == null
                    ? ReviewRunner.dueBoundary(closeMs, debrief.wakeTime(), now) : null;
            // 下发语言无关的码，文案由前端查自己的词表：这张卡是给人看的界面元素，
            // 该跟界面语言走；服务端渲染成某一门语言存下来，切了语言就翻不回去了
            String mannerKey = TradePairing.closeMannerKey(pos);
            out.add(new TradeRecord(pos.getId(), pos.getSymbol(), pos.getSide(), pos.getLeverage(),
                    pos.getEntryPrice(), pos.getClosedPrice(), pos.getClosedPnl(),
                    TradePairing.msOf(pos.getCreatedAt()), closeMs,
                    mannerKey, plan,
                    open == null ? null
                            : new DecisionRef(open.getId(), open.getWakeTime(), open.getKind(), open.getReasoning(), null),
                    // 止损/止盈带走的依据就是计划里的原始止损/目标，不挂平仓决策
                    TradePairing.MANNER_MANUAL.equals(mannerKey)
                            ? closeByPos.get(pos.getId()) : null,
                    debrief, debriefable, debriefing, debriefs.lastError().get(pos.getId()),
                    review, reviewDueAt));
        }
        return out;
    }

    /** 这些计划进过的统一复盘，键是那篇的决策行 id；只带 id 和时刻 */
    private Map<Long, DecisionRef> reviews(Collection<AiTraderPlan> plans) {
        Set<Long> ids = plans.stream().map(AiTraderPlan::getReviewId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                        .select(AiTraderDecision::getId, AiTraderDecision::getWakeTime, AiTraderDecision::getKind)
                        .in(AiTraderDecision::getId, ids))
                .stream().collect(Collectors.toMap(AiTraderDecision::getId,
                        d -> new DecisionRef(d.getId(), d.getWakeTime(), d.getKind(), null, null)));
    }

    /** 这些仓位的单笔复盘行，成功和失败的都取；成功的只带 id 和时刻，正文在时间线上看 */
    private Debriefs debriefs(AiTrader t, List<FuturesPositionDTO> closed) {
        Map<Long, DecisionRef> ok = new HashMap<>();
        Map<Long, String> lastError = new HashMap<>();
        for (AiTraderDecision d : decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                .select(AiTraderDecision::getId, AiTraderDecision::getWakeTime, AiTraderDecision::getKind,
                        AiTraderDecision::getPositionId, AiTraderDecision::getStatus, AiTraderDecision::getError)
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .eq(AiTraderDecision::getKind, AiTraderDecision.KIND_DEBRIEF)
                .in(AiTraderDecision::getPositionId, closed.stream().map(FuturesPositionDTO::getId).toList())
                .orderByAsc(AiTraderDecision::getId))) {
            if (AiTraderDecision.STATUS_OK.equals(d.getStatus())) {
                ok.put(d.getPositionId(), new DecisionRef(d.getId(), d.getWakeTime(), d.getKind(), null, null));
            } else {
                lastError.put(d.getPositionId(), d.getError());
            }
        }
        lastError.keySet().removeAll(ok.keySet());
        return new Debriefs(ok, lastError);
    }

    /**
     * 开仓决策，键是计划行 id：plan.openedWakeTime 精确命中的那一轮（与 WakeCtx.wakeTime 同源），
     * 并且那一轮的动作里开过这份计划的币和方向（反手的开仓腿也算）。
     * 续立计划的 openedWakeTime 是对账那一轮，可能是只处理别的币的轮；write_plan 补立的是仓位创建时刻。这两种都对不上开仓，留空
     */
    public Map<Long, AiTraderDecision> openDecisions(AiTrader t, Collection<AiTraderPlan> plans) {
        Set<Long> times = plans.stream().map(AiTraderPlan::getOpenedWakeTime)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (times.isEmpty()) {
            return Map.of();
        }
        List<AiTraderDecision> rows = decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .in(AiTraderDecision::getKind, AiTraderDecision.WAKE_KINDS)
                .in(AiTraderDecision::getWakeTime, times)
                .orderByAsc(AiTraderDecision::getId));
        Map<Long, AiTraderDecision> out = new HashMap<>();
        for (AiTraderPlan p : plans) {
            rows.stream().filter(d -> Objects.equals(d.getWakeTime(), p.getOpenedWakeTime())
                            && opened(d, p.getSymbol(), p.getSide()))
                    .findFirst().ifPresent(d -> out.put(p.getId(), d));
        }
        return out;
    }

    /**
     * 这一轮的动作里有没有开这个币这个方向的仓：开成了，或者下单结果未知（可能已经成交，计划照样立了）；反手的开仓腿也算
     */
    private static boolean opened(AiTraderDecision d, String symbol, String side) {
        if (d.getActionsJson() == null) {
            return false;
        }
        for (JsonNode action : MAPPER.readTree(d.getActionsJson())) {
            for (JsonNode leg : TradeActions.legs(action)) {
                boolean tried = TradeActions.opened(leg) || "open_position".equals(leg.path("tool").asString(null))
                        && TradeActions.UNKNOWN.equals(leg.path("status").asString(null));
                if (tried && symbol.equals(leg.path("args").path("symbol").asString(null))
                        && side.equals(leg.path("args").path("side").asString(null))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 平仓决策：对该 positionId 做成的平仓里，做成时刻最晚的那一次（了结的那次）所在的那一行。
     * 算数的有两种：唤醒轮里的 close_position，和预案执行里类型是平仓的那一条（理由在那一行正文的备注里，这里不单给）。
     * 做成时刻取动作上记的 at；老行没记的，按那一行的 wake_time。几轮同时在跑时，开始得晚的那轮不一定平得晚。
     * 候选窗口 [最早开仓−4h, 最晚了结]，SQL 先按 like 粗筛，再在 Java 里精确对 positionId。
     * 被拒（rejected 字段）/出错（status=error）的动作不算数——窗口上界是全部仓位里最晚的了结时刻，
     * 仓位早已平掉后再对它下的一条失败 close_position 也会落进窗口，不筛掉就会顶掉真正的平仓决策。
     */
    private Map<Long, DecisionRef> closeDecisions(AiTrader t, List<FuturesPositionDTO> closed) {
        long from = closed.stream().mapToLong(p -> TradePairing.msOf(p.getCreatedAt())).min().orElse(0)
                - CLOSE_LOOKBACK_MS;
        long to = closed.stream().mapToLong(p -> TradePairing.msOf(p.getUpdatedAt())).max().orElse(0);
        List<AiTraderDecision> rows = decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .in(AiTraderDecision::getKind, CLOSE_KINDS)
                .between(AiTraderDecision::getWakeTime, from, to)
                .and(w -> w.like(AiTraderDecision::getActionsJson, "close_position")
                        .or().like(AiTraderDecision::getActionsJson, "\"type\":\"" + AiTraderWatch.ACTION_CLOSE + "\""))
                .orderByAsc(AiTraderDecision::getWakeTime));
        Map<Long, DecisionRef> byPos = new HashMap<>();
        Map<Long, Long> doneAt = new HashMap<>();
        for (AiTraderDecision d : rows) {
            ArrayNode actions;
            try {
                actions = MAPPER.readValue(d.getActionsJson(), ArrayNode.class);
            } catch (Exception e) {
                continue;
            }
            if (actions == null) {
                continue;
            }
            for (JsonNode action : actions) {
                // 反手拆成两条腿，腿上没有时刻，用外层这条的
                long at = action.path("at").asLong(d.getWakeTime());
                for (JsonNode a : TradeActions.legs(action)) {
                    Long positionId = closedPosition(a);
                    if (positionId == null || at < doneAt.getOrDefault(positionId, Long.MIN_VALUE)) {
                        continue;
                    }
                    doneAt.put(positionId, at);
                    byPos.put(positionId, new DecisionRef(d.getId(), d.getWakeTime(), d.getKind(), d.getReasoning(),
                            a.path("args").path("reason").asString(null)));
                }
            }
        }
        return byPos;
    }

    /** 这条动作做成了平仓的话，平的是哪个仓位；不是平仓、没做成都给 null。旧轨迹的 pending/无状态沿用成功语义 */
    private static Long closedPosition(JsonNode a) {
        JsonNode args = a.path("args");
        String status = a.path("status").asString("pending");
        if (a.has("rejected") || !(TradeActions.OK.equals(status) || "pending".equals(status)) || !args.hasNonNull("positionId")) {
            return null;
        }
        String tool = a.path("tool").asString("");
        boolean close = "close_position".equals(tool)
                || "preset".equals(tool) && AiTraderWatch.ACTION_CLOSE.equals(args.path("type").asString(null));
        return close ? args.get("positionId").asLong() : null;
    }
}
