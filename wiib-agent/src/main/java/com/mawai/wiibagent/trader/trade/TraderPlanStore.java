package com.mawai.wiibagent.trader.trade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.enums.AgentLang;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 交易计划存取。键 trader+round+symbol+side，LIVE 至多一条（DB 部分唯一索引只约束 LIVE）。
 * <p>
 * 计划跟仓位怎么对上，靠两条事实推，不靠猜：sim 保证同币同向任意时刻至多一个 OPEN 仓位；
 * 模型开仓只有 open_position 一个入口，必立或覆盖计划。所以"这个键上、这份计划立案之后出现的仓位"
 * 就是它的仓位。一行计划对应一个仓位生命期：仓位了结这行就归档，同键还有新仓或挂单的续立一行接着跟，
 * 一个间隙里连开连平了几笔就各补一行归档。
 * <p>
 * 归档不删：论点配结局是复盘/竞技场/战绩的原料（{@link TradePairing#pairAll} 按 positionId 精确配）。
 * 单 trader 唤醒串行（调度层互斥），select-then-write 无并发问题。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraderPlanStore {

    private final AiTraderPlanMapper mapper;
    /** 覆盖/续立的修订留痕按 trader 主人语言写（trader.revise.*），与 TradeTools 的其余修订同源 */
    private final PromptCatalog prompts;

    public static String key(String symbol, String side) {
        return symbol + "|" + side;
    }

    /** 修订追加：计划的任何修改一律留痕带理由（回注给下轮无记忆的模型看）；不动计划本体的原始快照字段。 */
    public static void appendRevision(AiTraderPlan plan, long time, String type, String change, String reason) {
        ArrayNode arr = plan.getRevisionsJson() == null || plan.getRevisionsJson().isBlank()
                ? MAPPER.createArrayNode() : MAPPER.readValue(plan.getRevisionsJson(), ArrayNode.class);
        arr.add(MAPPER.createObjectNode().put("time", time).put("type", type)
                .put("change", change).put("reason", reason));
        plan.setRevisionsJson(MAPPER.writeValueAsString(arr));
    }

    // ==================== 写路径：唤醒中模型下单 ====================

    /** 该键的存活计划，无则 null。回注/详情/工具校验都只看 LIVE，归档行只喂复盘 */
    public AiTraderPlan liveFor(long traderId, int roundNo, String symbol, String side) {
        return mapper.selectOne(new LambdaQueryWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getTraderId, traderId)
                .eq(AiTraderPlan::getRoundNo, roundNo)
                .eq(AiTraderPlan::getSymbol, symbol)
                .eq(AiTraderPlan::getSide, side)
                .eq(AiTraderPlan::getStatus, AiTraderPlan.STATUS_LIVE));
    }

    /**
     * 新立一行 LIVE 计划。典型是同键无敞口时首开；有持仓却没计划的旧仓再下单、write_plan 补立也走这里。
     * 市价单响应带仓位 id 直接绑上；限价挂单 id 为空，成交后对账补。
     * 同键还有 LIVE 旧计划＝唤醒中平掉后重开（了结要到下轮开头对账才发现）：旧计划先归档，
     * 新计划仓龄从头算、修订史不继承。
     */
    public void open(AiTraderPlan plan) {
        plan.setStatus(AiTraderPlan.STATUS_LIVE);
        AiTraderPlan old = liveFor(plan.getTraderId(), plan.getRoundNo(), plan.getSymbol(), plan.getSide());
        if (old != null) {
            archive(old, plan.getOpenedWakeTime() == null ? 0 : plan.getOpenedWakeTime());
            log.info("[TraderPlan] 同轮重开归档旧计划 traderId={} {} {}",
                    plan.getTraderId(), plan.getSymbol(), plan.getSide());
        }
        mapper.insert(plan);
    }

    /**
     * 加仓覆盖：同键已有持仓或开仓挂单时再下单。新论点上位，旧论点进修订史，仓龄按最初开仓算，
     * 仓位绑定保留（sim 同向并仓 id 不变；限价加仓响应无 id）。模型下单前已在提示词里看过旧计划，知情覆盖。
     * 有持仓却没计划（旧仓）就退化成新立。
     */
    public void cover(AiTraderPlan plan, AgentLang lang) {
        AiTraderPlan old = liveFor(plan.getTraderId(), plan.getRoundNo(), plan.getSymbol(), plan.getSide());
        if (old == null) {
            open(plan);
            return;
        }
        long revisedAt = plan.getOpenedWakeTime() == null ? 0 : plan.getOpenedWakeTime();
        plan.setStatus(AiTraderPlan.STATUS_LIVE);
        plan.setId(old.getId());
        plan.setOpenedWakeTime(old.getOpenedWakeTime());
        plan.setRevisionsJson(old.getRevisionsJson());
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

    /** 止损/止盈移动、平仓的修订落库：只追加历史，原始快照字段不动（当前生效单在 sim 仓位上）。 */
    public void revise(AiTraderPlan plan, long time, String type, String change, String reason) {
        appendRevision(plan, time, type, change, reason);
        mapper.updateById(plan);
    }

    // ==================== 对账：唤醒开头 ====================

    /** 对账发现的一件事，进开场白的"自上次唤醒以来"块 */
    public sealed interface Event permits Closed, Filled, Cancelled {
        AiTraderPlan plan();
    }

    /** 计划的一笔仓位已了结（止损/止盈/强平/主动平），position 是 sim 的已平仓位 */
    public record Closed(AiTraderPlan plan, FuturesPositionDTO position) implements Event {
    }

    /** 限价单成交，计划绑上仓位 */
    public record Filled(AiTraderPlan plan) implements Event {
    }

    /** 挂单没了也从没成交过，计划归档 */
    public record Cancelled(AiTraderPlan plan) implements Event {
    }

    /**
     * 一趟对账的结果：对完还活着的计划（随持仓注入账户状态）+ 事件。
     * 同一份计划的事件按发生顺序（先结局后成交），不同计划之间按库里顺序。
     */
    public record Reconcile(List<AiTraderPlan> live, List<Event> events) {
    }

    /**
     * 每次唤醒开头拿 sim 的持仓、开仓挂单对一遍 LIVE 计划。逐份计划：
     * <ol>
     * <li>绑着的仓还在持仓里：没事，已平仓位也不查。</li>
     * <li>先找结局（{@link #outcomes}）。有结局：每笔报一条 Closed，本行归档绑第一笔，其余各补一行 CLOSED；
     *     键上还有新仓或挂单，续立一行接着跟，有新仓再报 Filled。</li>
     * <li>没有结局：键上有仓＝限价成交，绑上报 Filled；还挂着单，原样活着；无仓无挂单＝挂单没了，报 Cancelled 归档。</li>
     * </ol>
     * 已平仓位懒取一次：持仓都活着的轮次一次都不查。
     */
    public Reconcile reconcile(long traderId, int roundNo, List<FuturesPositionDTO> positions,
                               List<FuturesOrderResponse> pendingOrders,
                               Supplier<List<FuturesPositionDTO>> closedPositions,
                               long boundaryTime, AgentLang lang) {
        Map<String, FuturesPositionDTO> openByKey = new HashMap<>();
        positions.forEach(p -> openByKey.put(key(p.getSymbol(), p.getSide()), p));
        Set<String> pendingKeys = new HashSet<>();
        for (FuturesOrderResponse o : pendingOrders) {
            // orderSide 形如 OPEN_LONG / CLOSE_LONG，只有开仓挂单算键上有单
            if (o.getOrderSide() != null && o.getOrderSide().startsWith("OPEN_")) {
                pendingKeys.add(key(o.getSymbol(), o.getOrderSide().substring("OPEN_".length())));
            }
        }
        List<AiTraderPlan> all = listAll(traderId, roundNo);
        // 已被某行计划认领的仓位：结局已入册，扫的时候跳过
        Set<Long> claimed = new HashSet<>();
        all.forEach(p -> {
            if (p.getPositionId() != null) {
                claimed.add(p.getPositionId());
            }
        });
        ClosedLookup closed = new ClosedLookup(closedPositions);
        List<AiTraderPlan> live = new ArrayList<>();
        List<Event> events = new ArrayList<>();
        for (AiTraderPlan p : all) {
            if (!AiTraderPlan.STATUS_LIVE.equals(p.getStatus())) {
                continue;
            }
            String k = key(p.getSymbol(), p.getSide());
            FuturesPositionDTO open = openByKey.get(k);
            Long bound = p.getPositionId();
            if (bound != null && open != null && bound.equals(open.getId())) {
                live.add(p);
                continue;
            }
            // 绑着的仓不在持仓里就是了结了，哪怕已平列表里找不到它的详情（窗口外）也照样归档
            List<FuturesPositionDTO> outcomes = outcomes(p, k, closed, claimed);
            if (bound == null && outcomes.isEmpty()) {
                if (open != null) {
                    p.setPositionId(open.getId());
                    mapper.updateById(p);
                    events.add(new Filled(p));
                    live.add(p);
                    log.info("[TraderPlan] 限价单成交，计划绑上仓位 traderId={} {} {} positionId={}",
                            traderId, p.getSymbol(), p.getSide(), open.getId());
                } else if (pendingKeys.contains(k)) {
                    live.add(p);
                } else {
                    events.add(new Cancelled(p));
                    archive(p, boundaryTime);
                    log.info("[TraderPlan] 挂单已不在，归档计划 traderId={} {} {}", traderId, p.getSymbol(), p.getSide());
                }
                continue;
            }
            for (FuturesPositionDTO pos : outcomes) {
                events.add(new Closed(p, pos));
            }
            if (bound == null) {
                p.setPositionId(outcomes.getFirst().getId());
            }
            // 本行归档绑第一笔（绑过仓的就是原来那笔），其余各补一行
            archive(p, boundaryTime);
            for (FuturesPositionDTO pos : outcomes) {
                if (!pos.getId().equals(p.getPositionId())) {
                    record(p, pos, boundaryTime, lang);
                }
            }
            boolean carried = open != null || pendingKeys.contains(k);
            if (carried) {
                AiTraderPlan next = carryOver(p, open, boundaryTime, lang);
                if (open != null) {
                    events.add(new Filled(next));
                }
                live.add(next);
            }
            log.info("[TraderPlan] 仓位了结归档计划 traderId={} {} {} 结局数={} 续立={}",
                    traderId, p.getSymbol(), p.getSide(), outcomes.size(), carried);
        }
        return new Reconcile(live, events);
    }

    /**
     * 这份计划的结局：绑着的仓不在持仓里了按 id 找；立案之后该键上创建、还没被任何计划行认领的已平仓位
     * 也都是它的（几张限价单在一个间隙里先后成交又止损）。立案时刻取行的 createdAt 而不是 openedWakeTime：
     * 一轮里可能先平后开，边界时刻分不出前后。按仓位创建时间升序。
     */
    private List<FuturesPositionDTO> outcomes(AiTraderPlan p, String k, ClosedLookup closed, Set<Long> claimed) {
        Map<Long, FuturesPositionDTO> out = new LinkedHashMap<>();
        if (p.getPositionId() != null) {
            FuturesPositionDTO pos = closed.byId(p.getPositionId());
            if (pos != null) {
                out.put(pos.getId(), pos);
            } else {
                log.warn("[TraderPlan] 已了结仓位不在最近已平列表里，结局不报 traderId={} {} {} positionId={}",
                        p.getTraderId(), p.getSymbol(), p.getSide(), p.getPositionId());
            }
        }
        for (FuturesPositionDTO c : closed.onKeyAfter(k, p.getCreatedAt())) {
            if (!claimed.contains(c.getId())) {
                out.putIfAbsent(c.getId(), c);
            }
        }
        return out.values().stream()
                .sorted(Comparator.comparingLong(c -> TradePairing.msOf(c.getCreatedAt())))
                .toList();
    }

    /** 立案后又开又平的一笔：单独补一行 CLOSED 归档行绑它。生命期就是这笔仓位自己的，开在两次唤醒之间，没有哪一轮"开"了它 */
    private void record(AiTraderPlan from, FuturesPositionDTO pos, long boundaryTime, AgentLang lang) {
        AiTraderPlan row = lineage(from, boundaryTime, lang);
        row.setStatus(AiTraderPlan.STATUS_CLOSED);
        row.setPositionId(pos.getId());
        row.setOpenedWakeTime(TradePairing.msOf(pos.getCreatedAt()));
        row.setClosedWakeTime(boundaryTime);
        mapper.insert(row);
    }

    /**
     * 续立：旧仓了结后同键还有新仓或挂单，论点随之接着跟。
     * 生命期从本轮起算，不跟归档的旧行重叠：stale 段落归属按生命期判，重叠了主人标忽略就剔不掉。
     */
    private AiTraderPlan carryOver(AiTraderPlan from, FuturesPositionDTO open, long boundaryTime, AgentLang lang) {
        AiTraderPlan next = lineage(from, boundaryTime, lang);
        next.setStatus(AiTraderPlan.STATUS_LIVE);
        next.setPositionId(open == null ? null : open.getId());
        next.setOpenedWakeTime(boundaryTime);
        mapper.insert(next);
        return next;
    }

    /**
     * 同一论点下的下一行：论点、价格快照、修订史原样继承，加一条续立留痕。
     * createdAt 预设为本轮边界而不是落库那一刻：它是下轮扫结局的起点，得早于本轮取持仓的时刻，
     * 取持仓到落库之间成交又平掉的仓才不会漏（自动填充是 strict 的，只填空值）。
     */
    private AiTraderPlan lineage(AiTraderPlan from, long boundaryTime, AgentLang lang) {
        AiTraderPlan row = new AiTraderPlan();
        row.setTraderId(from.getTraderId());
        row.setRoundNo(from.getRoundNo());
        row.setSymbol(from.getSymbol());
        row.setSide(from.getSide());
        row.setPlayType(from.getPlayType());
        row.setSignalsUsed(from.getSignalsUsed());
        row.setInvalidationCondition(from.getInvalidationCondition());
        row.setEntryPrice(from.getEntryPrice());
        row.setStopLossPrice(from.getStopLossPrice());
        row.setTakeProfitPrice(from.getTakeProfitPrice());
        row.setRevisionsJson(from.getRevisionsJson());
        row.setCreatedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(boundaryTime), ZoneId.systemDefault()));
        appendRevision(row, boundaryTime, prompts.get(lang, "trader.revise.carry"),
                prompts.get(lang, "trader.revise.carryChange", Map.of("positionId", String.valueOf(from.getPositionId()))),
                prompts.get(lang, "trader.revise.carryReason"));
        return row;
    }

    /** 已平仓位懒取一次，两种查法共用 */
    private static final class ClosedLookup {
        private final Supplier<List<FuturesPositionDTO>> source;
        private List<FuturesPositionDTO> rows;

        ClosedLookup(Supplier<List<FuturesPositionDTO>> source) {
            this.source = source;
        }

        private List<FuturesPositionDTO> rows() {
            if (rows == null) {
                rows = source.get();
            }
            return rows;
        }

        FuturesPositionDTO byId(long id) {
            return rows().stream().filter(c -> Objects.equals(c.getId(), id)).findFirst().orElse(null);
        }

        /** 该键上、计划立案之后创建的 */
        List<FuturesPositionDTO> onKeyAfter(String k, LocalDateTime planCreatedAt) {
            long since = TradePairing.msOf(planCreatedAt);
            return rows().stream()
                    .filter(c -> k.equals(key(c.getSymbol(), c.getSide())))
                    .filter(c -> TradePairing.msOf(c.getCreatedAt()) >= since)
                    .toList();
        }
    }

    // ==================== 查询 ====================

    /** 本局存活计划。 */
    public List<AiTraderPlan> list(long traderId, int roundNo) {
        return mapper.selectList(new LambdaQueryWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getTraderId, traderId)
                .eq(AiTraderPlan::getRoundNo, roundNo)
                .eq(AiTraderPlan::getStatus, AiTraderPlan.STATUS_LIVE));
    }

    /** 本局全部计划（含归档）：对账要看谁认领了哪个仓位，stale 教材过滤要按生命期识别，都得看全量。 */
    public List<AiTraderPlan> listAll(long traderId, int roundNo) {
        return mapper.selectList(new LambdaQueryWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getTraderId, traderId)
                .eq(AiTraderPlan::getRoundNo, roundNo));
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

    /** 按 id 取计划（stale 开关的归属校验用），无则 null。 */
    public AiTraderPlan byId(long planId) {
        return mapper.selectById(planId);
    }

    // ==================== 治理 ====================

    /** 主人标记忽略/取消：只动 stale 列，计划本体不碰。 */
    public void setStale(long planId, boolean stale) {
        // wrapper 更新不走 INSERT_UPDATE 自动填充，updated_at 手动带上——本表所有写路径同一口径
        mapper.update(null, new LambdaUpdateWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getId, planId)
                .set(AiTraderPlan::getStale, stale)
                .set(AiTraderPlan::getUpdatedAt, LocalDateTime.now()));
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
