package com.mawai.wiibagent.trader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 竞技场「已了结交易 · 论点→结局」：sim 已平仓位 ⟵配对⟶ 交易计划 ⟵关联⟶ 开仓/平仓那一轮的决策全文。
 * 配对与了结方式推断复用 {@link TradePairing} 的同一套算法（复盘/同侪学习/竞技场三处一致，各配一套会自相矛盾）。
 * 只看当前局：每局独立 sim 子账户，AiTrader 只存当前局 simUserId，历史局查不回来。
 */
@Service
@RequiredArgsConstructor
public class TradeRecordService {

    /** 竞技场一页最多展示的已了结笔数（sim 按 updatedAt 倒序返回，取前 N 即最近 N 笔） */
    static final int LIMIT = 50;
    private static final List<String> TRADE_KINDS =
            List.of(AiTraderDecision.KIND_TRADE, AiTraderDecision.KIND_ALERT, AiTraderDecision.KIND_MANUAL);
    /** 平仓决策候选窗口向前放宽的量：手动唤醒的 wake_time 是当前边界，可能早于仓位创建至多一个档位（最大 4h） */
    private static final long CLOSE_LOOKBACK_MS = 14_400_000L;

    private final SimTradeClient simTradeClient;
    private final AiTraderPlanMapper planMapper;
    private final AiTraderDecisionMapper decisionMapper;

    /** 时间线里那一条决策的引用（id 可对上时间线卡）；reason 只有平仓有——close_position 的一句话理由 */
    public record DecisionRef(long id, long wakeTime, String kind, String reasoning, String reason) {
    }

    public record TradeRecord(long positionId, String symbol, String side, Integer leverage,
                              BigDecimal entryPrice, BigDecimal closedPrice, BigDecimal closedPnl,
                              long openedAt, long closedAt, String closeMannerKey,
                              AiTraderPlan plan, DecisionRef openDecision, DecisionRef closeDecision) {
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
        Map<Long, AiTraderDecision> openByWake = openDecisions(t, planByPos.values());
        Map<Long, DecisionRef> closeByPos = closeDecisions(t, closed);

        List<TradeRecord> out = new ArrayList<>(closed.size());
        for (FuturesPositionDTO pos : closed) {
            AiTraderPlan plan = planByPos.get(pos);
            AiTraderDecision open = plan == null ? null : openByWake.get(plan.getOpenedWakeTime());
            // 下发语言无关的码，文案由前端查自己的词表：这张卡是给人看的界面元素，
            // 该跟界面语言走；服务端渲染成某一门语言存下来，切了语言就翻不回去了
            String mannerKey = TradePairing.closeMannerKey(pos);
            out.add(new TradeRecord(pos.getId(), pos.getSymbol(), pos.getSide(), pos.getLeverage(),
                    pos.getEntryPrice(), pos.getClosedPrice(), pos.getClosedPnl(),
                    TradePairing.msOf(pos.getCreatedAt()), TradePairing.msOf(pos.getUpdatedAt()),
                    mannerKey, plan,
                    open == null ? null
                            : new DecisionRef(open.getId(), open.getWakeTime(), open.getKind(), open.getReasoning(), null),
                    // 止损/止盈带走的依据就是计划里的原始止损/目标，不挂平仓决策
                    TradePairing.MANNER_MANUAL.equals(mannerKey)
                            ? closeByPos.get(pos.getId()) : null));
        }
        return out;
    }

    /**
     * 开仓决策：plan.openedWakeTime 精确命中那一轮（ALERT/MANUAL 轮的 wake_time 与 WakeCtx.boundaryTime 同源）。
     * 同一边界可能既有例行轮又有手动轮（手动唤醒的 wake_time 就是当前边界）：开了仓的那条才是"论点"所在。
     * write_plan 补立的计划 openedWakeTime 是仓位创建时刻而非任何边界，天然配不到，openDecision 留空。
     */
    private Map<Long, AiTraderDecision> openDecisions(AiTrader t, Collection<AiTraderPlan> plans) {
        Set<Long> times = plans.stream().map(AiTraderPlan::getOpenedWakeTime)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (times.isEmpty()) {
            return Map.of();
        }
        return decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                        .eq(AiTraderDecision::getTraderId, t.getId())
                        .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                        .in(AiTraderDecision::getKind, TRADE_KINDS)
                        .in(AiTraderDecision::getWakeTime, times)
                        .orderByAsc(AiTraderDecision::getId))
                .stream().collect(Collectors.toMap(AiTraderDecision::getWakeTime, d -> d,
                        (a, b) -> opened(a) ? a : b));
    }

    private static boolean opened(AiTraderDecision d) {
        return d.getActionsJson() != null && d.getActionsJson().contains("open_position");
    }

    /**
     * 平仓决策：actionsJson 里 close_position 命中该 positionId 的那一轮。同一仓位多次减仓取最后一轮（了结的那次）。
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
                .in(AiTraderDecision::getKind, TRADE_KINDS)
                .between(AiTraderDecision::getWakeTime, from, to)
                .like(AiTraderDecision::getActionsJson, "close_position")
                .orderByAsc(AiTraderDecision::getWakeTime));
        Map<Long, DecisionRef> byPos = new HashMap<>();
        for (AiTraderDecision d : rows) {
            JSONArray actions;
            try {
                actions = JSON.parseArray(d.getActionsJson());
            } catch (Exception e) {
                continue;
            }
            for (int i = 0; actions != null && i < actions.size(); i++) {
                JSONObject a = actions.getJSONObject(i);
                JSONObject args = a == null ? null : a.getJSONObject("args");
                if (a == null || !"close_position".equals(a.getString("tool"))
                        || args == null || args.getLong("positionId") == null
                        || a.containsKey("rejected") || "error".equals(a.getString("status"))) {
                    continue;
                }
                // 升序遍历 + 覆盖 = 留最后一轮
                byPos.put(args.getLong("positionId"),
                        new DecisionRef(d.getId(), d.getWakeTime(), d.getKind(), d.getReasoning(), args.getString("reason")));
            }
        }
        return byPos;
    }
}
