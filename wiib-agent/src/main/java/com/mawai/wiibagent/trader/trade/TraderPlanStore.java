package com.mawai.wiibagent.trader.trade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.enums.AgentLang;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ArrayNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 交易计划存取。键 trader+round+symbol+side，LIVE 至多一条（DB 部分唯一索引只约束 LIVE）。
 * <p>
 * 计划跟仓位怎么对上，靠两条事实推，不靠猜：sim 保证同币同向任意时刻至多一个 OPEN 仓位；
 * 模型通过 open_position 开仓或 reverse_position 反向开仓，必立或覆盖计划。所以"这个键上、这份计划立案之后出现的仓位"
 * 就是它的仓位。一行计划对应一个仓位生命期：仓位了结这行就归档，同键还有新仓或挂单的续立一行接着跟，
 * 一个间隙里连开连平了几笔就各补一行归档。
 * <p>
 * 归档不删：论点配结局是复盘/竞技场/战绩的原料（{@link TradePairing#pairAll} 按 positionId 精确配）。
 * 计划写入与预案线程共用行锁。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraderPlanStore {

    private final AiTraderPlanMapper mapper;
    /** 覆盖/续立的修订留痕按 trader 主人语言写（trader.revise.*），与 TradeTools 的其余修订同源 */
    private final PromptCatalog prompts;
    private final TransactionTemplate transactionTemplate;

    /**
     * 改已有计划行的唯一入口：短事务里锁住这一行、重读、把本次变更合并进去再写回，写完把最新的行抄回调用方手里那份。
     * 预案线程和唤醒线程不串行，各自读了再整行写回会互相盖掉
     */
    private void mutate(AiTraderPlan plan, Consumer<AiTraderPlan> change) {
        transactionTemplate.executeWithoutResult(_ -> {
            AiTraderPlan row = mapper.selectByIdForUpdate(plan.getId());
            if (row == null) {
                return;
            }
            change.accept(row);
            mapper.updateById(row);
            BeanUtils.copyProperties(row, plan);
        });
    }

    /** 只知道 id 时给 mutate 用的空壳 */
    private static AiTraderPlan reference(long id) {
        AiTraderPlan p = new AiTraderPlan();
        p.setId(id);
        return p;
    }

    public static String key(String symbol, String side) {
        return symbol + "|" + side;
    }

    /** 追加带理由的修订；不改价格快照。 */
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
    public void open(AiTraderPlan plan, Function<String, List<FuturesOrderResponse>> groupOrders, AgentLang lang) {
        plan.setStatus(AiTraderPlan.STATUS_LIVE);
        AiTraderPlan old = liveFor(plan.getTraderId(), plan.getRoundNo(), plan.getSymbol(), plan.getSide());
        if (old != null) {
            // 旧计划的各档先按订单事实对一遍再归档，归档行里不留还标着挂着的档
            syncEntries(old, groupOrders, new ArrayList<>(), lang);
            archive(old, plan.getOpenedWakeTime() == null ? 0 : plan.getOpenedWakeTime());
            log.info("[TraderPlan] 同轮重开归档旧计划 traderId={} {} {}",
                    plan.getTraderId(), plan.getSymbol(), plan.getSide());
        }
        mapper.insert(plan);
    }

    /** 补入场上下文 */
    public void setEntryContext(Long planId, String entryCtxJson) {
        mutate(reference(planId), p -> p.setEntryCtxJson(entryCtxJson));
    }

    /**
     * 加仓覆盖：同键已有持仓或开仓挂单时再下单。新论点上位，旧论点进修订史，仓龄按最初开仓算，
     * 仓位绑定保留（sim 同向并仓 id 不变；限价加仓响应无 id）。模型下单前已在提示词里看过旧计划，知情覆盖。
     * 有持仓却没计划（旧仓）就退化成新立。
     */
    public void cover(AiTraderPlan plan, AgentLang lang) {
        AiTraderPlan old = liveFor(plan.getTraderId(), plan.getRoundNo(), plan.getSymbol(), plan.getSide());
        if (old == null) {
            plan.setStatus(AiTraderPlan.STATUS_LIVE);
            mapper.insert(plan);
            return;
        }
        long revisedAt = plan.getOpenedWakeTime() == null ? 0 : plan.getOpenedWakeTime();
        mutate(old, current -> {
            appendRevision(current, revisedAt, prompts.get(lang, "trader.revise.addOn"),
                    prompts.get(lang, "trader.revise.addOnNote", Map.of(
                            "playType", String.valueOf(current.getPlayType()),
                            "invalidation", String.valueOf(current.getInvalidationCondition()))),
                    plan.getSignalsUsed());
            // 本次带来的档追加在后面，标成加仓；首批档原样留着
            if (plan.getEntriesJson() != null) {
                List<PlanEntry> entries = PlanEntry.read(current);
                List<PlanEntry> added = PlanEntry.read(plan);
                added.forEach(e -> e.setAdd(true));
                entries.addAll(added);
                PlanEntry.save(current, entries);
            }
            current.setPlayType(plan.getPlayType());
            current.setSignalsUsed(plan.getSignalsUsed());
            current.setInvalidationCondition(plan.getInvalidationCondition());
            current.setEntryPrice(plan.getEntryPrice());
            current.setStopLossPrice(plan.getStopLossPrice());
            current.setTakeProfitsJson(plan.getTakeProfitsJson());
            if (plan.getOrderGroup() != null) {
                current.setOrderGroup(plan.getOrderGroup());
            }
            if (plan.getPositionId() != null) {
                current.setPositionId(plan.getPositionId());
            }
        });
        // 调用方手里那份换成库里的最新行，后面补入场上下文要用它的 id
        BeanUtils.copyProperties(old, plan);
    }

    /** 止损/止盈移动、平仓的修订落库：只追加历史，价格快照不动（当前生效单在 sim 仓位上） */
    public void revise(AiTraderPlan plan, long time, String type, String change, String reason) {
        mutate(plan, p -> appendRevision(p, time, type, change, reason));
    }

    /** 唤醒之外的线程（预案执行）按 id 给计划追加一条修订 */
    public void appendRevision(long planId, long time, String type, String change, String reason) {
        revise(reference(planId), time, type, change, reason);
    }

    /** 模型或预案撤了一张入场挂单：计划里那一档标成已撤、记下理由，修订史加一条。不是分档计划里的单不管 */
    public void cancelEntry(long traderId, int roundNo, FuturesOrderResponse order, String reason, long time, AgentLang lang) {
        if (order.getGroupId() == null || !order.getOrderSide().startsWith("OPEN_")) {
            return;
        }
        AiTraderPlan plan = liveFor(traderId, roundNo, order.getSymbol(), order.getOrderSide().substring("OPEN_".length()));
        if (plan == null) {
            return;
        }
        mutate(plan, p -> {
            if (!AiTraderPlan.STATUS_LIVE.equals(p.getStatus()) || !order.getGroupId().equals(p.getOrderGroup())) {
                return;
            }
            List<PlanEntry> entries = PlanEntry.read(p);
            for (PlanEntry e : entries) {
                if (!Objects.equals(e.getOrderId(), order.getOrderId())) {
                    continue;
                }
                e.setStatus("CANCELLED");
                e.setClosedAt(TradePairing.msOf(order.getUpdatedAt()));
                e.setReason(reason);
                appendRevision(p, time, prompts.get(lang, "trader.revise.cancelEntry"),
                        e.getLimitPrice().stripTrailingZeros().toPlainString() + " × "
                                + e.getQuantity().stripTrailingZeros().toPlainString(), reason);
            }
            PlanEntry.save(p, entries);
        });
    }

    // ==================== 对账：唤醒开头 ====================

    /** 对账发现的一件事，进开场白的"自上次唤醒以来"块 */
    public sealed interface Event permits Closed, Filled, Cancelled, EntryFilled, EntryCancelled {
        AiTraderPlan plan();
    }

    /** 计划的一笔仓位已了结（止损/止盈/强平/主动平），position 是 sim 的已平仓位 */
    public record Closed(AiTraderPlan plan, FuturesPositionDTO position) implements Event {
    }

    /** 限价单成交，计划绑上仓位 */
    public record Filled(AiTraderPlan plan) implements Event {
    }

    /** 这份计划一共几档、已成交几档、还挂着几档 */
    public record EntryCounts(int total, long filled, long pending) {
        static EntryCounts of(List<PlanEntry> entries) {
            return new EntryCounts(entries.size(),
                    entries.stream().filter(e -> "FILLED".equals(e.getStatus())).count(),
                    entries.stream().filter(e -> "PENDING".equals(e.getStatus())).count());
        }
    }

    /** 分档计划里的一档成交了 */
    public record EntryFilled(AiTraderPlan plan, PlanEntry entry, EntryCounts counts) implements Event {
    }

    /** 分档计划里的一档被撤了（平台撤的，或没记下理由的） */
    public record EntryCancelled(AiTraderPlan plan, PlanEntry entry, EntryCounts counts) implements Event {
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
                               Function<String, List<FuturesOrderResponse>> groupOrders,
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
            // 分档计划先按订单事实同步各档；它还有没有挂着的档看同步结果，没有订单组的旧计划看键上有没有挂单
            EntrySync sync = syncEntries(p, groupOrders, events, lang);
            String k = key(p.getSymbol(), p.getSide());
            boolean hasPending = p.getOrderGroup() == null ? pendingKeys.contains(k) : !sync.pendingIds().isEmpty();
            FuturesPositionDTO open = openByKey.get(k);
            Long bound = p.getPositionId();
            if (bound != null && open != null && bound.equals(open.getId())) {
                live.add(p);
                continue;
            }
            // 绑着的仓不在持仓里就是了结了，哪怕已平列表里找不到它的详情（窗口外）也照样归档
            List<FuturesPositionDTO> outcomes = outcomes(p, k, closed, claimed);
            // 有档还没定论（订单在触发中、查不到、认领不了）先不归档；刚按订单绑上的仓，持仓快照里还没有也先等一轮
            if (sync.unresolved() || (outcomes.isEmpty() && sync.boundFromOrder() && open == null)) {
                live.add(p);
                continue;
            }
            if (bound == null && outcomes.isEmpty()) {
                if (open != null) {
                    mutate(p, row -> row.setPositionId(open.getId()));
                    // 分档计划的成交已经逐档报过了
                    if (p.getOrderGroup() == null) {
                        events.add(new Filled(p));
                    }
                    live.add(p);
                    log.info("[TraderPlan] 限价单成交，计划绑上仓位 traderId={} {} {} positionId={}",
                            traderId, p.getSymbol(), p.getSide(), open.getId());
                } else if (hasPending) {
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
            List<PlanEntry> carriedEntries = new ArrayList<>();
            archive(p, boundaryTime, sync.pendingIds(), carriedEntries);
            for (FuturesPositionDTO pos : outcomes) {
                if (!pos.getId().equals(p.getPositionId())) {
                    record(p, pos, boundaryTime, lang);
                }
            }
            boolean carried = open != null || hasPending;
            if (carried) {
                AiTraderPlan next = carryOver(p, open, boundaryTime, lang, carriedEntries);
                if (open != null && p.getOrderGroup() == null) {
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
     * 一份计划各档同步完的结果。unresolved：有档还没定论；pendingIds：确认还挂着的订单；
     * boundFromOrder：这次按成交订单上的仓位 id 给计划绑了仓
     */
    private record EntrySync(boolean unresolved, Set<Long> pendingIds, boolean boundFromOrder) {
    }

    /**
     * 拿 sim 里这个订单组的全部订单，把计划里还没定论的档（挂着的、下单结果未知的）对一遍：
     * 成交的记成交价、手续费、时刻；撤掉的记时刻和原因；还挂着的留着；订单在触发中的算没定论。
     * 结果未知的档按价位和数量在没被认领的同组订单里找：找到一张就认领，一张都没有说明当时没挂上、去掉，有几张分不清就留着不猜。
     * 新定论的成交和撤单各加一条事件。没有订单组的旧计划、各档都已定论的计划不查
     */
    private EntrySync syncEntries(AiTraderPlan plan, Function<String, List<FuturesOrderResponse>> groupOrders,
                                  List<Event> events, AgentLang lang) {
        if (plan.getOrderGroup() == null || PlanEntry.read(plan).stream().noneMatch(TraderPlanStore::unsettled)) {
            return new EntrySync(false, Set.of(), false);
        }
        // 查订单要出网，放在锁行的事务外面
        List<FuturesOrderResponse> orders;
        try {
            orders = groupOrders.apply(plan.getOrderGroup());
        } catch (Exception e) {
            log.warn("[TraderPlan] 订单组查询失败 group={} msg={}", plan.getOrderGroup(), e.toString());
            return new EntrySync(true, Set.of(), false);
        }
        Map<Long, FuturesOrderResponse> orderById = new HashMap<>();
        orders.forEach(o -> orderById.put(o.getOrderId(), o));
        Set<Long> pending = new HashSet<>();
        boolean[] unresolved = {false};
        boolean[] bound = {false};
        mutate(plan, row -> {
            List<PlanEntry> entries = PlanEntry.read(row);
            Set<Long> claimed = new HashSet<>();
            for (PlanEntry e : entries) {
                if (e.getOrderId() != null) {
                    claimed.add(e.getOrderId());
                }
            }
            List<PlanEntry> filled = new ArrayList<>();
            List<PlanEntry> cancelled = new ArrayList<>();
            for (Iterator<PlanEntry> it = entries.iterator(); it.hasNext(); ) {
                PlanEntry entry = it.next();
                if (!unsettled(entry)) {
                    continue;
                }
                if (entry.getOrderId() == null) {
                    List<FuturesOrderResponse> candidates = orders.stream()
                            .filter(o -> !claimed.contains(o.getOrderId()))
                            .filter(o -> sameNumber(entry.getLimitPrice(), o.getLimitPrice())
                                    && sameNumber(entry.getQuantity(), o.getQuantity()))
                            .toList();
                    if (candidates.isEmpty()) {
                        it.remove();
                        continue;
                    }
                    if (candidates.size() > 1) {
                        unresolved[0] = true;
                        continue;
                    }
                    entry.setOrderId(candidates.getFirst().getOrderId());
                    claimed.add(entry.getOrderId());
                }
                FuturesOrderResponse order = orderById.get(entry.getOrderId());
                if (order == null) {
                    unresolved[0] = true;
                    continue;
                }
                // 判成交只看状态：已触发又被撤的单子上还留着触发价
                switch (order.getStatus()) {
                    case "FILLED" -> {
                        entry.setStatus("FILLED");
                        entry.setFilledPrice(order.getFilledPrice());
                        entry.setFee(order.getCommission());
                        entry.setClosedAt(TradePairing.msOf(order.getUpdatedAt()));
                        filled.add(entry);
                        // 持仓快照可能早于这笔成交，计划没绑仓就按订单上的仓位 id 绑
                        if (row.getPositionId() == null && order.getPositionId() != null) {
                            row.setPositionId(order.getPositionId());
                            bound[0] = true;
                        }
                    }
                    case "CANCELLED" -> {
                        entry.setStatus("CANCELLED");
                        entry.setClosedAt(TradePairing.msOf(order.getUpdatedAt()));
                        // 撤单时自己记过理由的用自己的，没有的按 sim 的原因码成文
                        if (entry.getReason() == null || entry.getReason().isBlank()) {
                            entry.setReason(cancelReason(order.getCancelReason(), lang));
                        }
                        cancelled.add(entry);
                    }
                    case "PENDING" -> {
                        entry.setStatus("PENDING");
                        pending.add(order.getOrderId());
                    }
                    default -> unresolved[0] = true;
                }
            }
            PlanEntry.save(row, entries);
            EntryCounts counts = EntryCounts.of(entries);
            filled.forEach(e -> events.add(new EntryFilled(plan, e, counts)));
            cancelled.forEach(e -> events.add(new EntryCancelled(plan, e, counts)));
        });
        return new EntrySync(unresolved[0], pending, bound[0]);
    }

    /** 这一档还没定论：挂着，或下单结果未知 */
    private static boolean unsettled(PlanEntry e) {
        return "PENDING".equals(e.getStatus()) || "UNKNOWN".equals(e.getStatus());
    }

    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    /** sim 的撤单原因码成文；没有码或不认识的写原因未知 */
    private String cancelReason(String code, AgentLang lang) {
        String reason = switch (code == null ? "" : code) {
            case "GROUP_CLOSED" -> "groupClosed";
            case "USER" -> "user";
            case "BANKRUPT" -> "bankrupt";
            case "insufficient_available", "insufficient_balance" -> "margin";
            case "leverage_not_allowed", "leverage_mismatch" -> "leverage";
            case "sltp_split_limit" -> "protectionLimit";
            case "margin_mode_conflict" -> "marginMode";
            case "increase_removed" -> "increaseRemoved";
            default -> "unknown";
        };
        return prompts.get(lang, "trader.cancelReason." + reason);
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
    private AiTraderPlan carryOver(AiTraderPlan from, FuturesPositionDTO open, long boundaryTime, AgentLang lang,
                                   List<PlanEntry> stillPending) {
        AiTraderPlan next = lineage(from, boundaryTime, lang);
        next.setStatus(AiTraderPlan.STATUS_LIVE);
        next.setPositionId(open == null ? null : open.getId());
        next.setOpenedWakeTime(boundaryTime);
        // 分档计划续立：只带真的还挂着的那几档
        if (from.getEntriesJson() != null) {
            PlanEntry.save(next, stillPending);
        }
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
        row.setTakeProfitsJson(from.getTakeProfitsJson());
        row.setRevisionsJson(from.getRevisionsJson());
        row.setEntryCtxJson(from.getEntryCtxJson());
        // 逐档记录不跟着抄：续立行只带还挂着的档（carryOver 里放），补归档行留空走单档重放
        row.setOrderGroup(from.getOrderGroup());
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
        mutate(reference(planId), p -> p.setStale(stale));
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
        archive(plan, closedAt, Set.of(), new ArrayList<>());
    }

    /**
     * 归档。归档行里不留还标着挂着的档：确认还挂着的（pendingIds）挪进 carried 交给续立行，
     * 其余标着挂着却对不上订单的记成未知
     */
    private void archive(AiTraderPlan plan, long closedAt, Set<Long> pendingIds, List<PlanEntry> carried) {
        // 对账里刚给它认上的结局仓位还只在内存里，随归档一起写进去
        Long positionId = plan.getPositionId();
        mutate(plan, p -> {
            p.setStatus(AiTraderPlan.STATUS_CLOSED);
            p.setClosedWakeTime(closedAt);
            if (positionId != null) {
                p.setPositionId(positionId);
            }
            if (p.getEntriesJson() == null) {
                return;
            }
            List<PlanEntry> kept = new ArrayList<>();
            for (PlanEntry e : PlanEntry.read(p)) {
                if (!"PENDING".equals(e.getStatus())) {
                    kept.add(e);
                } else if (pendingIds.contains(e.getOrderId())) {
                    carried.add(e);
                } else {
                    e.setStatus("UNKNOWN");
                    kept.add(e);
                }
            }
            PlanEntry.save(p, kept);
        });
    }
}
