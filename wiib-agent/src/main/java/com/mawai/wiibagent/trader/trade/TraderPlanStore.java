package com.mawai.wiibagent.trader.trade;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 交易计划存取（存活键：trader+round+symbol+side，DB 部分唯一索引只约束 LIVE）。
 * 计划了结一律归档不删——"当初的论点/失效条件"与"实际结局"的配对是 reviewer
 * 每日复盘的原料（{@link TradePairing#pairAll}：position_id 精确 join，
 * 无 id 历史行按 symbol/side/时间就近兜底）。
 * 单 trader 的唤醒是串行的（调度层抢占互斥），select-then-write 无并发问题。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraderPlanStore {

    private final AiTraderPlanMapper mapper;
    /** 加仓覆盖的修订留痕按 trader 主人语言写（trader.revise.*），与 TradeTools 的其余修订同源 */
    private final PromptCatalog prompts;

    public static String key(String symbol, String side) {
        return symbol + "|" + side;
    }

    /** 修订追加：计划的任何修改一律留痕带理由（回注给下轮无记忆的模型看）；不动计划本体的原始快照字段。 */
    public static void appendRevision(AiTraderPlan plan, long time, String type, String change, String reason) {
        JSONArray arr = plan.getRevisionsJson() == null || plan.getRevisionsJson().isBlank()
                ? new JSONArray() : JSON.parseArray(plan.getRevisionsJson());
        arr.add(new JSONObject().fluentPut("time", time).fluentPut("type", type)
                .fluentPut("change", change).fluentPut("reason", reason));
        plan.setRevisionsJson(arr.toJSONString());
    }

    /** 存活计划（回注/详情展示/工具校验都只看 LIVE；归档行只喂复盘）。 */
    public AiTraderPlan find(long traderId, int roundNo, String symbol, String side) {
        return mapper.selectOne(new LambdaQueryWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getTraderId, traderId)
                .eq(AiTraderPlan::getRoundNo, roundNo)
                .eq(AiTraderPlan::getSymbol, symbol)
                .eq(AiTraderPlan::getSide, side)
                .eq(AiTraderPlan::getStatus, AiTraderPlan.STATUS_LIVE));
    }

    /**
     * 开仓/加仓成交或限价挂出即落计划。isAddOn=true（同币同向已有持仓）走加仓覆盖：新论点上位，
     * 旧论点进修订历史（模型下单前已在提示词里看过旧计划，知情覆盖），持有时长按最初开仓算。
     * isAddOn=false 但同键旧计划还在＝同轮内平掉后重开（已了结计划要等下次唤醒开头才归档）：这是独立新仓
     * 不是加仓——旧计划归档，仓龄从新仓起算，修订史不继承（仓龄诚实）。
     */
    public void upsert(AiTraderPlan plan, boolean isAddOn, AgentLang lang) {
        plan.setStatus(AiTraderPlan.STATUS_LIVE);
        AiTraderPlan old = find(plan.getTraderId(), plan.getRoundNo(), plan.getSymbol(), plan.getSide());
        if (old == null) {
            mapper.insert(plan);
            return;
        }
        if (!isAddOn) {
            archive(old, plan.getOpenedWakeTime() == null ? 0 : plan.getOpenedWakeTime());
            mapper.insert(plan);
            log.info("[TraderPlan] 同轮重开归档旧计划 traderId={} {} {}",
                    plan.getTraderId(), plan.getSymbol(), plan.getSide());
            return;
        }
        long revisedAt = plan.getOpenedWakeTime() == null ? 0 : plan.getOpenedWakeTime();
        plan.setId(old.getId());
        plan.setOpenedWakeTime(old.getOpenedWakeTime());
        plan.setRevisionsJson(old.getRevisionsJson());
        // sim 并仓 id 不变：市价加仓响应带的就是同一仓 id；限价加仓挂单响应无 id，保留旧值
        if (plan.getPositionId() == null) {
            plan.setPositionId(old.getPositionId());
        }
        appendRevision(plan, revisedAt, prompts.get(lang, "trader.revise.addOn"),
                prompts.get(lang, "trader.revise.addOnNote", Map.of(
                        "playType", String.valueOf(old.getPlayType()),
                        "invalidation", String.valueOf(old.getInvalidationCondition()))),
                plan.getSignalsUsed());
        mapper.updateById(plan);
    }

    /** 止损/止盈移动的修订落库：只追加历史，原始快照字段不动（当前生效单在 sim 仓位上）。 */
    public void revise(AiTraderPlan plan, long time, String type, String change, String reason) {
        appendRevision(plan, time, type, change, reason);
        mapper.updateById(plan);
    }

    /** 本局全部计划（含归档）：stale 教材过滤按计划生命期与仓位绑定识别，要看全量。 */
    public List<AiTraderPlan> listAll(long traderId, int roundNo) {
        return mapper.selectList(new LambdaQueryWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getTraderId, traderId)
                .eq(AiTraderPlan::getRoundNo, roundNo));
    }

    /** 本局存活计划。 */
    public List<AiTraderPlan> list(long traderId, int roundNo) {
        return mapper.selectList(new LambdaQueryWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getTraderId, traderId)
                .eq(AiTraderPlan::getRoundNo, roundNo)
                .eq(AiTraderPlan::getStatus, AiTraderPlan.STATUS_LIVE));
    }

    /** 一趟 rebind 的结果：还活着的、这趟归档的、这趟补上仓位 id 的 */
    public record Rebind(List<AiTraderPlan> live, List<AiTraderPlan> closed, List<AiTraderPlan> filled) {
    }

    /**
     * 每次唤醒开头，拿 sim 的持仓/挂单对一遍 LIVE 计划：
     * 计划的 (symbol|side) 还有持仓或开仓挂单 → 留下；
     * 其中限价单成交后计划还没有仓位 id 的，补上它当初拿不到的 sim 仓位 id；
     * 既无持仓也无挂单（止损/止盈/平仓/撤单） → 归档，带上了结时刻。
     * liveKeys 含挂单键，positionIdByKey 只有持仓键，所以分开传。
     */
    public Rebind rebind(long traderId, int roundNo, Set<String> liveKeys,
                         Map<String, Long> positionIdByKey, long boundaryTime) {
        List<AiTraderPlan> live = new ArrayList<>();
        List<AiTraderPlan> closed = new ArrayList<>();
        List<AiTraderPlan> filled = new ArrayList<>();
        for (AiTraderPlan p : list(traderId, roundNo)) {
            if (liveKeys.contains(key(p.getSymbol(), p.getSide()))) {
                Long posId = positionIdByKey.get(key(p.getSymbol(), p.getSide()));
                if (p.getPositionId() == null && posId != null) {
                    p.setPositionId(posId);
                    mapper.updateById(p);
                    filled.add(p);
                    log.info("[TraderPlan] 限价单成交，计划补上仓位id traderId={} {} {} positionId={}", traderId, p.getSymbol(), p.getSide(), posId);
                }
                live.add(p);
                continue;
            }
            archive(p, boundaryTime);
            closed.add(p);
            log.info("[TraderPlan] 归档已了结计划 traderId={} {} {}", traderId, p.getSymbol(), p.getSide());
        }
        return new Rebind(live, closed, filled);
    }

    /** 按仓位 id 找存活计划：平仓工具手里只有 positionId */
    public AiTraderPlan findLiveByPositionId(long traderId, int roundNo, long positionId) {
        return mapper.selectOne(new LambdaQueryWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getTraderId, traderId)
                .eq(AiTraderPlan::getRoundNo, roundNo)
                .eq(AiTraderPlan::getPositionId, positionId)
                .eq(AiTraderPlan::getStatus, AiTraderPlan.STATUS_LIVE));
    }

    /** 按 id 取计划（stale 开关的归属校验用），无则 null。 */
    public AiTraderPlan byId(long planId) {
        return mapper.selectById(planId);
    }

    /** 主人标记忽略/取消：只动 stale 列，计划本体不碰。 */
    public void setStale(long planId, boolean stale) {
        // wrapper 更新不走 INSERT_UPDATE 自动填充，updated_at 手动带上——本表所有写路径同一口径
        mapper.update(null, new LambdaUpdateWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getId, planId)
                .set(AiTraderPlan::getStale, stale)
                .set(AiTraderPlan::getUpdatedAt, LocalDateTime.now()));
    }

    /** 本局最近归档的计划（最新在前）：对话轨要回答"上一笔为什么平了"，只看 LIVE 是答不了的。 */
    public List<AiTraderPlan> recentClosed(long traderId, int roundNo, int limit) {
        return mapper.selectList(new LambdaQueryWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getTraderId, traderId)
                .eq(AiTraderPlan::getRoundNo, roundNo)
                .eq(AiTraderPlan::getStatus, AiTraderPlan.STATUS_CLOSED)
                .orderByDesc(AiTraderPlan::getClosedWakeTime)
                .last("LIMIT " + limit));
    }

    /** 重置开新局：本局存活计划一并归档（历史局的计划是那局决策的公开凭证，早已归档在册）。 */
    public void archiveRound(long traderId, int roundNo, long closedAt) {
        for (AiTraderPlan p : list(traderId, roundNo)) {
            archive(p, closedAt);
        }
    }

    /** 保留窗口外的过期轮次整局清除：连归档行一起删——那局的决策行都没了，凭证失去对照对象。 */
    public void purgeRounds(long traderId, int maxRoundNo) {
        mapper.delete(new LambdaQueryWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getTraderId, traderId)
                .le(AiTraderPlan::getRoundNo, maxRoundNo));
    }

    /** 删 trader：不分轮次全清。 */
    public void purgeAll(long traderId) {
        mapper.delete(new LambdaQueryWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getTraderId, traderId));
    }

    private void archive(AiTraderPlan plan, long closedAt) {
        plan.setStatus(AiTraderPlan.STATUS_CLOSED);
        plan.setClosedWakeTime(closedAt);
        mapper.updateById(plan);
    }
}
