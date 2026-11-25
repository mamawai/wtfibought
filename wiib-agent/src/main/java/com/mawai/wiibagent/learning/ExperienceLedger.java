package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderExperienceMapper;
import com.mawai.wiibagent.mapper.AiTraderExperienceTradeMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.trader.trade.TradeGuard;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderExperience;
import com.mawai.wiibcommon.entity.AiTraderExperienceTrade;
import com.mawai.wiibcommon.enums.AgentLang;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.mawai.wiibcommon.entity.AiTraderExperienceTrade.MARK_BROKEN;
import static com.mawai.wiibcommon.entity.AiTraderExperienceTrade.MARK_KEPT;
import static com.mawai.wiibcommon.entity.AiTraderExperienceTrade.MARK_MISCITED;
import static com.mawai.wiibcommon.entity.AiTraderExperienceTrade.MARK_UNDER;

/**
 * 经验账本：复盘模型把本期单子按仓位编号归到经验名下，钱、笔数、天数由代码按仓位记（ai_trader_experience_trade）；
 * 转正、降级、闲置退役由代码在复盘落账后按账判。渲染成紧凑文本写回 ai_trader.memory 给 trader 每轮注入。
 * <p>
 * 单子的对错看净盈亏，主人标了不算数的单子在所有账里都不算。「站在它这边」：限制型是名下合计小于 0，其余大于 0。
 * 新增只收入场条件、风险限制、执行缺口三个格子。
 * 转正：入场条件、风险限制的假设，当前段名下满 {@value #PROMOTE_TRADES} 笔、来自至少 {@value #PROMOTE_DAYS} 个平仓日、合计站在它这边。
 * 降级：规律转正后名下满 {@value #DEMOTE_TRADES} 笔、合计反过来，钉住的不降。转正、降级各起一段，判断只看当前段；
 * 只在本篇有新单子记进它名下时判，本篇新建的不判转正。
 * 退役：连续 {@value #IDLE_RETIRE} 篇复盘名下没有新单子，钉住的、已转正的限制型不退；本期一笔已了结单子都没有的复盘不数。
 * 写库按列，状态字段逐列 set。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExperienceLedger {

    static final int MAX_FINDINGS = 5;
    static final int PROMOTE_TRADES = 5;
    static final int PROMOTE_DAYS = 3;
    static final int DEMOTE_TRADES = 5;
    static final int IDLE_RETIRE = 10;
    /** 执行缺口破满几次才给 trader 看；次数按开仓那一轮数（见 {@link #gapRounds}） */
    static final int GAP_SHOWN_BROKEN = 2;
    /** 新增收的三种格子 */
    static final Set<String> ADD_SLOTS = Set.of(
            AiTraderExperience.SLOT_ENTRY, AiTraderExperience.SLOT_RISK, AiTraderExperience.SLOT_EXEC);
    /** 能转正的格子 */
    static final Set<String> PROMOTE_SLOTS = Set.of(AiTraderExperience.SLOT_ENTRY, AiTraderExperience.SLOT_RISK);
    /** 退役名单给复盘看的条数 */
    static final int RETIRED_SHOWN = 10;

    private final AiTraderExperienceMapper expMapper;
    private final AiTraderExperienceTradeMapper tradeMapper;
    private final AiTraderMapper traderMapper;
    /** 只为查主人标了不算数的单子 */
    private final TraderPlanStore planStore;
    private final PromptCatalog prompts;

    /**
     * 本期一笔单子，按仓位编号作键：净盈亏（案例行那个数）、平仓的新加坡日期、开这笔仓的那一轮实际跑的时段（ms）。
     * 认不出是哪一轮开的，时段两头都为空
     */
    public record PeriodTrade(BigDecimal net, LocalDate closedDay, Long openRoundFrom, Long openRoundTo) {
    }

    /** 一笔单子归到经验名下的哪个标记（AiTraderExperienceTrade.MARK_*） */
    public record Mark(String mark, long positionId) {
    }

    /** 复盘提议的一个操作 */
    public sealed interface Op permits Classify, Add, Finding, Delete {
    }

    /** 现役条目本期的归类；marks 为空＝写了「无」 */
    public record Classify(long id, List<Mark> marks) implements Op {
    }

    /** 新增；marks 是新增行末尾列的本期单子，sourceId 是「来源 #编号」 */
    public record Add(String playType, String slot, String direction, String text, String evidence,
                      List<Mark> marks, Long sourceId) implements Op {
    }

    /** 发现：给主人看的，不注入 */
    public record Finding(String text, String evidence) implements Op {
    }

    /** 删除提议：只对没钉住的假设和执行缺口生效 */
    public record Delete(long id, String reason) implements Op {
    }

    /** 一篇复盘的操作生效了几条、丢了几条 */
    public record Applied(int applied, int dropped) {
    }

    /**
     * 一条经验名下单子的账。under 系列只算「名下」：笔数、赚几笔、亏几笔、合计、覆盖几个平仓日；
     * segment 系列是当前段的；kept / broken 是守、破的次数，按开仓那一轮数；miscited 是引用不符的笔数
     */
    public record Tally(int under, int wins, int losses, BigDecimal net, int days,
                        int segmentUnder, BigDecimal segmentNet, int segmentDays,
                        int kept, int broken, int miscited) {
    }

    public enum ChangeKind { PROMOTED, DEMOTED, RETIRED }

    /** 复盘落账后代码结账的一条结果；tally 是判它那一刻的账 */
    public record Change(ChangeKind kind, AiTraderExperience entry, Tally tally) {
    }

    // ==================== 查 ====================

    public List<AiTraderExperience> active(long traderId, int roundNo) {
        return expMapper.selectList(new LambdaQueryWrapper<AiTraderExperience>()
                .eq(AiTraderExperience::getTraderId, traderId)
                .eq(AiTraderExperience::getRoundNo, roundNo)
                .eq(AiTraderExperience::getStatus, AiTraderExperience.STATUS_ACTIVE)
                .orderByAsc(AiTraderExperience::getId));
    }

    /** 本局全部条目（现役、发现、退役），给主人页与开新局带入 */
    public List<AiTraderExperience> all(long traderId, int roundNo) {
        return expMapper.selectList(new LambdaQueryWrapper<AiTraderExperience>()
                .eq(AiTraderExperience::getTraderId, traderId)
                .eq(AiTraderExperience::getRoundNo, roundNo)
                .orderByAsc(AiTraderExperience::getId));
    }

    public List<AiTraderExperience> retired(long traderId, int roundNo) {
        return expMapper.selectList(new LambdaQueryWrapper<AiTraderExperience>()
                .eq(AiTraderExperience::getTraderId, traderId)
                .eq(AiTraderExperience::getRoundNo, roundNo)
                .eq(AiTraderExperience::getStatus, AiTraderExperience.STATUS_RETIRED)
                .orderByDesc(AiTraderExperience::getId)
                .last("LIMIT " + RETIRED_SHOWN));
    }

    public AiTraderExperience owned(long traderId, long id) {
        AiTraderExperience e = expMapper.selectById(id);
        return e != null && Objects.equals(e.getTraderId(), traderId) ? e : null;
    }

    /** 这些条目各自名下单子的账，每条都有；主人标了不算数的单子不算 */
    public Map<Long, Tally> tallies(List<AiTraderExperience> entries) {
        return entries.isEmpty() ? Map.of() : tallies(entries, rows(entries));
    }

    /** 同上，名下单子行已经查好 */
    private Map<Long, Tally> tallies(List<AiTraderExperience> entries, List<AiTraderExperienceTrade> rows) {
        Set<Long> stale = planStore.stalePositionIds(entries.getFirst().getTraderId());
        Map<Long, List<AiTraderExperienceTrade>> byEntry = rows.stream()
                .filter(r -> !stale.contains(r.getPositionId()))
                .collect(Collectors.groupingBy(AiTraderExperienceTrade::getExperienceId));
        Map<Long, Tally> out = new HashMap<>();
        for (AiTraderExperience e : entries) {
            out.put(e.getId(), tally(e.getSegmentNo(), byEntry.getOrDefault(e.getId(), List.of())));
        }
        return out;
    }

    /** 一条的账：名下行算笔数、赚亏、合计、平仓日，当前段的另算一份；守、破按开仓那一轮数，引用不符数笔数 */
    static Tally tally(int segment, List<AiTraderExperienceTrade> rows) {
        List<AiTraderExperienceTrade> under = rows.stream().filter(r -> MARK_UNDER.equals(r.getMark())).toList();
        List<AiTraderExperienceTrade> current = under.stream().filter(r -> r.getSegmentNo() == segment).toList();
        List<Boolean> rounds = gapRounds(rows);
        int broken = (int) rounds.stream().filter(b -> b).count();
        return new Tally(under.size(),
                (int) under.stream().filter(r -> r.getNet().signum() > 0).count(),
                (int) under.stream().filter(r -> r.getNet().signum() < 0).count(),
                sum(under), days(under),
                current.size(), sum(current), days(current),
                rounds.size() - broken, broken, count(rows, MARK_MISCITED));
    }

    /**
     * 守、破的单子按开仓那一轮分开，每一轮给出算不算破：同一轮开的几笔算一次，其中有一笔破就算破。
     * 几个币各跑各的轮时，同时在跑的（时段有交集或首尾相接）算同一轮；认不出是哪一轮开的单子各算一次。
     * 持仓途中才破的也按开仓那一轮并
     */
    private static List<Boolean> gapRounds(List<AiTraderExperienceTrade> rows) {
        List<AiTraderExperienceTrade> marked = rows.stream()
                .filter(r -> MARK_KEPT.equals(r.getMark()) || MARK_BROKEN.equals(r.getMark())).toList();
        List<Boolean> rounds = new ArrayList<>();
        marked.stream().filter(r -> r.getOpenRoundFrom() == null).forEach(r -> rounds.add(MARK_BROKEN.equals(r.getMark())));
        // 其余按开跑的先后排：开跑时上一组还没跑完的，并进上一组
        long groupTo = Long.MIN_VALUE;
        for (AiTraderExperienceTrade r : marked.stream().filter(x -> x.getOpenRoundFrom() != null)
                .sorted(Comparator.comparingLong(AiTraderExperienceTrade::getOpenRoundFrom)).toList()) {
            boolean broken = MARK_BROKEN.equals(r.getMark());
            if (r.getOpenRoundFrom() <= groupTo) {
                rounds.set(rounds.size() - 1, rounds.getLast() || broken);
            } else {
                rounds.add(broken);
            }
            groupTo = Math.max(groupTo, r.getOpenRoundTo());
        }
        return rounds;
    }

    /** trader 看得到的条目：规律，和破满 {@value #GAP_SHOWN_BROKEN} 次的执行缺口 */
    public static boolean shown(AiTraderExperience e, Tally t) {
        return AiTraderExperience.KIND_RULE.equals(e.getKind())
                || AiTraderExperience.KIND_GAP.equals(e.getKind()) && t.broken() >= GAP_SHOWN_BROKEN;
    }

    // ==================== 复盘落账 ====================

    /**
     * 逐行独立生效；不合法的行丢弃记日志，不中断。reviewId 是这篇统一复盘的决策行编号。
     * 同一条经验一篇只认第一行归类。
     * trades：本期单子，归类和新增里的仓位编号只认这些
     */
    public Applied apply(long traderId, int roundNo, Long reviewId, List<Op> ops, AgentLang lang,
                         Map<Long, PeriodTrade> trades) {
        // 只为数现役的发现有几条：发现有上限
        List<AiTraderExperience> actives = active(traderId, roundNo);
        Set<Long> judged = new HashSet<>();
        int applied = 0;
        int dropped = 0;
        for (Op op : ops) {
            boolean ok = switch (op) {
                case Classify c -> judged.add(c.id()) && classify(traderId, roundNo, reviewId, c, trades);
                case Add a -> add(traderId, roundNo, reviewId, a, lang, trades);
                case Finding f -> finding(traderId, roundNo, reviewId, f, actives, lang);
                case Delete d -> delete(traderId, roundNo, reviewId, d);
            };
            if (ok) {
                applied++;
            } else {
                dropped++;
                log.info("[Ledger] 丢弃操作 traderId={} op={}", traderId, op);
            }
        }
        return new Applied(applied, dropped);
    }

    /** 现役条目的归类：标记对得上种类、编号都在本期，才逐笔记下 */
    private boolean classify(long traderId, int roundNo, Long reviewId, Classify c, Map<Long, PeriodTrade> trades) {
        AiTraderExperience e = owned(traderId, c.id());
        if (e == null || !Objects.equals(e.getRoundNo(), roundNo)
                || !AiTraderExperience.STATUS_ACTIVE.equals(e.getStatus())
                || AiTraderExperience.KIND_FINDING.equals(e.getKind())
                || !fits(classifyMarks(e), c.marks(), trades)) {
            return false;
        }
        record(e, reviewId, c.marks(), trades);
        return true;
    }

    /** 这条经验的归类行能用哪几个标记：执行缺口是守、破，假设和规律是名下、引用不符 */
    private static Set<String> classifyMarks(AiTraderExperience e) {
        return AiTraderExperience.KIND_GAP.equals(e.getKind())
                ? Set.of(MARK_KEPT, MARK_BROKEN) : Set.of(MARK_UNDER, MARK_MISCITED);
    }

    /**
     * 各笔单笔复盘的归类行合成每条经验一行，交给 {@link #apply}。
     * 先逐笔筛：条目得是现役的假设、规律或执行缺口，标记对得上条目种类，仓位编号只能是这一笔自己的，
     * 同一笔对同一条目只认第一行；不合的行丢掉。再按 actives 的顺序把各笔的标记并到一起，一笔都没有的条目不出行
     *
     * @param byPosition 仓位编号 → 这一笔单笔复盘写的归类行
     */
    public static List<Classify> mergeDebriefs(Map<Long, List<Classify>> byPosition, List<AiTraderExperience> actives) {
        Map<Long, List<Mark>> merged = new LinkedHashMap<>();
        for (AiTraderExperience e : actives) {
            if (!AiTraderExperience.KIND_FINDING.equals(e.getKind())) {
                merged.put(e.getId(), new ArrayList<>());
            }
        }
        Map<Long, AiTraderExperience> byId = actives.stream().collect(Collectors.toMap(AiTraderExperience::getId, e -> e));
        byPosition.forEach((positionId, lines) -> {
            Set<Long> seen = new HashSet<>();
            for (Classify c : lines) {
                List<Mark> marks = merged.get(c.id());
                if (marks == null || !seen.add(c.id())) {
                    continue;
                }
                Set<String> allowed = classifyMarks(byId.get(c.id()));
                if (c.marks().stream().allMatch(m -> m.positionId() == positionId && allowed.contains(m.mark()))) {
                    marks.addAll(c.marks());
                }
            }
        });
        return merged.entrySet().stream().filter(e -> !e.getValue().isEmpty())
                .map(e -> new Classify(e.getKey(), List.copyOf(e.getValue()))).toList();
    }

    /** 标记都是认的那几个，编号都在本期单子里 */
    private static boolean fits(Set<String> allowed, List<Mark> marks, Map<Long, PeriodTrade> trades) {
        return marks.stream().allMatch(m -> allowed.contains(m.mark()) && trades.containsKey(m.positionId()));
    }

    /** 逐笔记下，挂在这篇复盘上，段号取条目当前的段；这条经验名下已经有的那笔不再记 */
    private void record(AiTraderExperience e, Long reviewId, List<Mark> marks, Map<Long, PeriodTrade> trades) {
        for (Mark m : marks) {
            PeriodTrade t = trades.get(m.positionId());
            AiTraderExperienceTrade row = new AiTraderExperienceTrade();
            row.setExperienceId(e.getId());
            row.setPositionId(m.positionId());
            row.setReviewId(reviewId);
            row.setMark(m.mark());
            row.setNet(t.net());
            row.setClosedDay(t.closedDay());
            row.setOpenRoundFrom(t.openRoundFrom());
            row.setOpenRoundTo(t.openRoundTo());
            row.setSegmentNo(e.getSegmentNo());
            tradeMapper.insertIgnore(row);
        }
    }

    /** 新增行合法：格子是收的那三个，正文与证据非空，单子类型是认识的 */
    private static boolean valid(Add a) {
        return ADD_SLOTS.contains(a.slot()) && !blank(a.text()) && !blank(a.evidence())
                && (blank(a.playType()) || TradeGuard.PLAY_TYPES.contains(a.playType().trim().toUpperCase(Locale.ROOT)));
    }

    /** 新增一条，连同它末尾列的本期单子（执行缺口带守、破，假设带名下）；条数没有上限。来源是本局的条目才记 */
    private boolean add(long traderId, int roundNo, Long reviewId, Add a, AgentLang lang, Map<Long, PeriodTrade> trades) {
        boolean gap = AiTraderExperience.SLOT_EXEC.equals(a.slot());
        if (!valid(a) || !fits(gap ? Set.of(MARK_KEPT, MARK_BROKEN) : Set.of(MARK_UNDER), a.marks(), trades)) {
            return false;
        }
        AiTraderExperience e = entry(traderId, roundNo, reviewId, a, lang);
        AiTraderExperience source = a.sourceId() == null ? null : owned(traderId, a.sourceId());
        if (source != null && Objects.equals(source.getRoundNo(), roundNo)) {
            e.setSourceId(source.getId());
        }
        expMapper.insert(e);
        record(e, reviewId, a.marks(), trades);
        return true;
    }

    private boolean finding(long traderId, int roundNo, Long reviewId, Finding f, List<AiTraderExperience> actives, AgentLang lang) {
        if (blank(f.text()) || blank(f.evidence())
                || actives.stream().filter(e -> AiTraderExperience.KIND_FINDING.equals(e.getKind())).count() >= MAX_FINDINGS) {
            return false;
        }
        AiTraderExperience e = blank(traderId, roundNo, reviewId);
        e.setKind(AiTraderExperience.KIND_FINDING);
        e.setSlot(AiTraderExperience.SLOT_RISK);
        e.setDirection(AiTraderExperience.DIR_EXECUTION);
        e.setText(cut(f.text(), textMax(lang)));
        e.setEvidence(cut(f.evidence(), evidenceMax(lang)));
        expMapper.insert(e);
        actives.add(e);
        return true;
    }

    /** 删除：只对没钉住的现役假设和执行缺口生效，效果是退役并记理由 */
    private boolean delete(long traderId, int roundNo, Long reviewId, Delete d) {
        AiTraderExperience e = owned(traderId, d.id());
        if (e == null || !Objects.equals(e.getRoundNo(), roundNo) || blank(d.reason())
                || !AiTraderExperience.STATUS_ACTIVE.equals(e.getStatus())
                || !(AiTraderExperience.KIND_HYPOTHESIS.equals(e.getKind()) || AiTraderExperience.KIND_GAP.equals(e.getKind()))
                || Boolean.TRUE.equals(e.getPinned())) {
            return false;
        }
        retire(e, reviewId, d.reason());
        return true;
    }

    // ==================== 复盘结账 ====================

    /**
     * 复盘落账后代码结账。本篇新记的单子是挂在这篇复盘上的行，引用不符不算。
     * 转正、降级只判本篇有新单子记进名下的条目，本篇新建的不判转正。
     * 闲置：本篇有新单子记进名下、守、破的清零；没有的加一，满 {@value #IDLE_RETIRE} 篇退役，
     * 钉住的、本篇新建的、已转正的限制型不加一。发现不管。hasClosed：本期有没有已了结的单子，一笔都没有就什么都不动
     */
    public List<Change> settle(long traderId, int roundNo, long reviewId, boolean hasClosed, AgentLang lang) {
        List<AiTraderExperience> entries = active(traderId, roundNo).stream()
                .filter(e -> !AiTraderExperience.KIND_FINDING.equals(e.getKind())).toList();
        if (!hasClosed || entries.isEmpty()) {
            return List.of();
        }
        List<AiTraderExperienceTrade> rows = rows(entries);
        Map<Long, Tally> tallies = tallies(entries, rows);
        Set<Long> under = recordedBy(rows, reviewId, Set.of(MARK_UNDER));
        Set<Long> recorded = recordedBy(rows, reviewId, Set.of(MARK_UNDER, MARK_KEPT, MARK_BROKEN));
        List<Change> changes = new ArrayList<>();
        for (AiTraderExperience e : entries) {
            Tally t = tallies.get(e.getId());
            boolean created = Objects.equals(e.getCreatedReviewId(), reviewId);
            if (under.contains(e.getId())) {
                if (!created && promotes(e, t)) {
                    move(e, AiTraderExperience.KIND_RULE);
                    changes.add(new Change(ChangeKind.PROMOTED, e, t));
                } else if (demotes(e, t)) {
                    move(e, AiTraderExperience.KIND_HYPOTHESIS);
                    changes.add(new Change(ChangeKind.DEMOTED, e, t));
                }
            }
            // 已转正的限制型没有新单子＝被守住了
            boolean keptRestriction = AiTraderExperience.KIND_RULE.equals(e.getKind())
                    && AiTraderExperience.DIR_RESTRICTION.equals(e.getDirection());
            int idle;
            if (recorded.contains(e.getId())) {
                idle = 0;
            } else if (Boolean.TRUE.equals(e.getPinned()) || created || keptRestriction) {
                continue;
            } else {
                idle = e.getIdleReviews() + 1;
            }
            if (idle >= IDLE_RETIRE) {
                retire(e, reviewId, prompts.get(lang, "reviewer.retire.idle", Map.of("n", IDLE_RETIRE)));
                changes.add(new Change(ChangeKind.RETIRED, e, t));
            } else if (idle != e.getIdleReviews()) {
                e.setIdleReviews(idle);
                expMapper.update(null, upd(e.getId()).set(AiTraderExperience::getIdleReviews, idle));
            }
        }
        return changes;
    }

    /** 这篇复盘新记了这几种标记的条目 */
    private static Set<Long> recordedBy(List<AiTraderExperienceTrade> rows, long reviewId, Set<String> marks) {
        return rows.stream()
                .filter(r -> Objects.equals(r.getReviewId(), reviewId) && marks.contains(r.getMark()))
                .map(AiTraderExperienceTrade::getExperienceId)
                .collect(Collectors.toSet());
    }

    /** 入场条件、风险限制的假设，当前段名下够笔数、够平仓日，合计站在它这边 */
    private static boolean promotes(AiTraderExperience e, Tally t) {
        return AiTraderExperience.KIND_HYPOTHESIS.equals(e.getKind()) && PROMOTE_SLOTS.contains(e.getSlot())
                && t.segmentUnder() >= PROMOTE_TRADES && t.segmentDays() >= PROMOTE_DAYS
                && favours(e.getDirection(), t.segmentNet());
    }

    /** 规律转正后名下满笔数、合计反过来；钉住的不降 */
    private static boolean demotes(AiTraderExperience e, Tally t) {
        return AiTraderExperience.KIND_RULE.equals(e.getKind()) && !Boolean.TRUE.equals(e.getPinned())
                && t.segmentUnder() >= DEMOTE_TRADES && favours(e.getDirection(), t.segmentNet().negate());
    }

    /** 合计站在它这边：限制型是小于 0，其余大于 0；等于 0 哪边都不算 */
    static boolean favours(String direction, BigDecimal net) {
        return AiTraderExperience.DIR_RESTRICTION.equals(direction) ? net.signum() < 0 : net.signum() > 0;
    }

    /** 转正或降级：换种类，另起一段 */
    private void move(AiTraderExperience e, String kind) {
        e.setKind(kind);
        e.setSegmentNo(e.getSegmentNo() + 1);
        expMapper.update(null, upd(e.getId())
                .set(AiTraderExperience::getKind, kind)
                .set(AiTraderExperience::getSegmentNo, e.getSegmentNo()));
    }

    private void retire(AiTraderExperience e, Long reviewId, String reason) {
        e.setStatus(AiTraderExperience.STATUS_RETIRED);
        e.setRetiredReviewId(reviewId);
        e.setRetireReason(cut(reason, 255));
        expMapper.update(null, upd(e.getId())
                .set(AiTraderExperience::getStatus, AiTraderExperience.STATUS_RETIRED)
                .set(AiTraderExperience::getRetiredReviewId, reviewId)
                .set(AiTraderExperience::getRetireReason, e.getRetireReason()));
    }

    // ==================== 注入 trader ====================

    /** 现役里 trader 看得到的条目渲染成紧凑文本：标题行 + 按单子类型分组的条目行；一条都没有返回 null */
    public String render(long traderId, int roundNo, AgentLang lang) {
        List<AiTraderExperience> entries = active(traderId, roundNo).stream()
                .filter(e -> !AiTraderExperience.KIND_FINDING.equals(e.getKind())).toList();
        Map<Long, Tally> tallies = tallies(entries);
        List<AiTraderExperience> list = entries.stream().filter(e -> shown(e, tallies.get(e.getId()))).toList();
        if (list.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(prompts.get(lang, "trader.label.ledgerTitle")).append('\n');
        Map<String, List<AiTraderExperience>> byPlay = new LinkedHashMap<>();
        for (AiTraderExperience e : list) {
            byPlay.computeIfAbsent(Objects.toString(e.getPlayType(), ""), k -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<String, List<AiTraderExperience>> g : byPlay.entrySet()) {
            sb.append(prompts.get(lang, "trader.label.ledgerGroup", Map.of("play",
                    g.getKey().isEmpty() ? prompts.get(lang, "trader.label.ledgerGeneral") : g.getKey()))).append('\n');
            for (AiTraderExperience e : g.getValue()) {
                sb.append(row(e, tallies.get(e.getId()), lang)).append('\n');
            }
        }
        return sb.toString();
    }

    /** 一行：规律按方向写照做或违反，引用不符大于 0 才带；执行缺口写碰到几次、做到几次（按开仓那一轮数）。数字都是累计 */
    private String row(AiTraderExperience e, Tally t, AgentLang lang) {
        String kind = prompts.get(lang, "trader.label.ledgerKind." + e.getKind().toLowerCase(Locale.ROOT));
        if (AiTraderExperience.KIND_GAP.equals(e.getKind())) {
            return prompts.get(lang, "trader.label.ledgerRowGap", Map.of(
                    "id", e.getId(), "kind", kind, "text", e.getText(),
                    "met", t.kept() + t.broken(), "kept", t.kept()));
        }
        String key = AiTraderExperience.DIR_RESTRICTION.equals(e.getDirection())
                ? "trader.label.ledgerRowViolated" : "trader.label.ledgerRowFollowed";
        return prompts.get(lang, key, Map.of(
                "id", e.getId(),
                "kind", kind,
                "dir", "·" + prompts.get(lang, "trader.label.ledgerDir." + e.getDirection().toLowerCase(Locale.ROOT)),
                "text", e.getText(),
                "n", t.under(),
                "wins", t.wins(),
                "losses", t.losses(),
                "net", money(t.net()),
                "miscited", t.miscited() > 0
                        ? prompts.get(lang, "trader.label.ledgerMiscited", Map.of("m", t.miscited())) : ""));
    }

    /** 渲染现役账本并按列写回 ai_trader.memory（trader 一条都看不到就写 null）；返回渲染文本 */
    public String writeMemory(AiTrader trader, AgentLang lang) {
        String rendered = render(trader.getId(), trader.getRoundNo(), lang);
        traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                .eq(AiTrader::getId, trader.getId())
                .set(AiTrader::getMemory, rendered)
                .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
        return rendered;
    }

    /** 这段 memory 是不是账本渲染出来的（两门语言的标题都认） */
    public boolean isRendered(String memory) {
        if (memory == null) {
            return false;
        }
        for (AgentLang l : AgentLang.values()) {
            if (memory.startsWith(prompts.get(l, "trader.label.ledgerTitle"))) {
                return true;
            }
        }
        return false;
    }

    /** trader 结论里引用了哪些编号：开仓轮计 citedOpen，否则计 citedHold */
    public void recordCitations(long traderId, int roundNo, Collection<Long> ids, boolean open) {
        for (Long id : ids) {
            AiTraderExperience e = owned(traderId, id);
            if (e == null || !Objects.equals(e.getRoundNo(), roundNo)
                    || !AiTraderExperience.STATUS_ACTIVE.equals(e.getStatus())
                    || AiTraderExperience.KIND_FINDING.equals(e.getKind())) {
                continue;
            }
            bump(e.getId(), open ? "cited_open" : "cited_hold");
        }
    }

    // ==================== 开新局 / 清理 ====================

    /**
     * 开新局带入：本局全部条目复制到新局，种类、段号、钉住、状态、退役原因、引用计数照搬，来源指回旧行，闲置清零；
     * 名下的单子行一并复制到新条目上，主人标了不算数的不带。返回带入条数
     */
    public int carryOver(long traderId, int fromRound, int toRound) {
        List<AiTraderExperience> entries = all(traderId, fromRound);
        if (entries.isEmpty()) {
            return 0;
        }
        Set<Long> stale = planStore.stalePositionIds(traderId);
        Map<Long, List<AiTraderExperienceTrade>> rows = rows(entries).stream()
                .filter(r -> !stale.contains(r.getPositionId()))
                .collect(Collectors.groupingBy(AiTraderExperienceTrade::getExperienceId));
        for (AiTraderExperience e : entries) {
            AiTraderExperience c = blank(traderId, toRound, null);
            c.setPlayType(e.getPlayType());
            c.setKind(e.getKind());
            c.setSlot(e.getSlot());
            c.setDirection(e.getDirection());
            c.setText(e.getText());
            c.setEvidence(e.getEvidence());
            c.setCitedOpen(e.getCitedOpen());
            c.setCitedHold(e.getCitedHold());
            c.setPinned(e.getPinned());
            c.setStatus(e.getStatus());
            c.setRetireReason(e.getRetireReason());
            c.setSegmentNo(e.getSegmentNo());
            c.setSourceId(e.getId());
            expMapper.insert(c);
            for (AiTraderExperienceTrade r : rows.getOrDefault(e.getId(), List.of())) {
                r.setExperienceId(c.getId());
                tradeMapper.insertIgnore(r);
            }
        }
        return entries.size();
    }

    /** 该局号及以前的条目连同名下单子一起删 */
    public void purgeRounds(long traderId, int roundNoInclusive) {
        purge(new LambdaQueryWrapper<AiTraderExperience>()
                .eq(AiTraderExperience::getTraderId, traderId)
                .le(AiTraderExperience::getRoundNo, roundNoInclusive));
    }

    /** 删 trader：全部条目与名下单子 */
    public void purgeAll(long traderId) {
        purge(new LambdaQueryWrapper<AiTraderExperience>().eq(AiTraderExperience::getTraderId, traderId));
    }

    private void purge(LambdaQueryWrapper<AiTraderExperience> which) {
        List<Long> ids = expMapper.selectList(which).stream().map(AiTraderExperience::getId).toList();
        if (ids.isEmpty()) {
            return;
        }
        tradeMapper.delete(new LambdaQueryWrapper<AiTraderExperienceTrade>()
                .in(AiTraderExperienceTrade::getExperienceId, ids));
        expMapper.deleteByIds(ids);
    }

    // ==================== 主人操作 ====================

    public boolean pin(long traderId, long id, boolean pinned) {
        AiTraderExperience e = owned(traderId, id);
        if (e == null) {
            return false;
        }
        expMapper.update(null, upd(id).set(AiTraderExperience::getPinned, pinned));
        return true;
    }

    public boolean retireByOwner(long traderId, long id, String reason) {
        AiTraderExperience e = owned(traderId, id);
        if (e == null || !AiTraderExperience.STATUS_ACTIVE.equals(e.getStatus())) {
            return false;
        }
        retire(e, null, reason);
        return true;
    }

    public boolean revive(long traderId, long id) {
        AiTraderExperience e = owned(traderId, id);
        if (e == null || !AiTraderExperience.STATUS_RETIRED.equals(e.getStatus())) {
            return false;
        }
        expMapper.update(null, upd(id)
                .set(AiTraderExperience::getStatus, AiTraderExperience.STATUS_ACTIVE)
                .set(AiTraderExperience::getRetiredReviewId, null)
                .set(AiTraderExperience::getRetireReason, null)
                .set(AiTraderExperience::getIdleReviews, 0));
        return true;
    }

    public boolean delete(long traderId, long id) {
        AiTraderExperience e = owned(traderId, id);
        if (e == null) {
            return false;
        }
        tradeMapper.delete(new LambdaQueryWrapper<AiTraderExperienceTrade>()
                .eq(AiTraderExperienceTrade::getExperienceId, id));
        expMapper.deleteById(id);
        return true;
    }

    // ==================== 小工具 ====================

    /** 这些条目名下的全部单子行 */
    private List<AiTraderExperienceTrade> rows(Collection<AiTraderExperience> entries) {
        return tradeMapper.selectList(new LambdaQueryWrapper<AiTraderExperienceTrade>()
                .in(AiTraderExperienceTrade::getExperienceId, entries.stream().map(AiTraderExperience::getId).toList()));
    }

    private static BigDecimal sum(List<AiTraderExperienceTrade> rows) {
        return rows.stream().map(AiTraderExperienceTrade::getNet).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static int days(List<AiTraderExperienceTrade> rows) {
        return (int) rows.stream().map(AiTraderExperienceTrade::getClosedDay).distinct().count();
    }

    private static int count(List<AiTraderExperienceTrade> rows, String mark) {
        return (int) rows.stream().filter(r -> mark.equals(r.getMark())).count();
    }

    /** 格子在词表里的键（reviewer.op.slot.*） */
    static String slotKey(String slot) {
        return switch (slot) {
            case AiTraderExperience.SLOT_ENTRY -> "entry";
            case AiTraderExperience.SLOT_FAIL_DEF -> "failDef";
            case AiTraderExperience.SLOT_FAIL_HANDLE -> "failHandle";
            case AiTraderExperience.SLOT_PROFIT_HANDLE -> "profitHandle";
            case AiTraderExperience.SLOT_RISK -> "risk";
            default -> "exec";
        };
    }

    /** 金额：两位小数，带正负号 */
    static String money(BigDecimal v) {
        return CaseFileRenderer.signed(v.setScale(2, RoundingMode.HALF_UP));
    }

    /** 由新增行造一条：类型大写、格子是执行缺口就是 GAP 否则假设、正文证据按语言截 */
    private static AiTraderExperience entry(long traderId, int roundNo, Long reviewId, Add a, AgentLang lang) {
        AiTraderExperience e = blank(traderId, roundNo, reviewId);
        e.setPlayType(blank(a.playType()) ? null : a.playType().trim().toUpperCase(Locale.ROOT));
        e.setKind(AiTraderExperience.SLOT_EXEC.equals(a.slot()) ? AiTraderExperience.KIND_GAP : AiTraderExperience.KIND_HYPOTHESIS);
        e.setSlot(a.slot());
        e.setDirection(a.direction());
        e.setText(cut(a.text(), textMax(lang)));
        e.setEvidence(cut(a.evidence(), evidenceMax(lang)));
        return e;
    }

    private static AiTraderExperience blank(long traderId, int roundNo, Long reviewId) {
        AiTraderExperience e = new AiTraderExperience();
        e.setTraderId(traderId);
        e.setRoundNo(roundNo);
        e.setCitedOpen(0);
        e.setCitedHold(0);
        e.setStatus(AiTraderExperience.STATUS_ACTIVE);
        e.setSegmentNo(0);
        e.setIdleReviews(0);
        e.setPinned(false);
        e.setCreatedReviewId(reviewId);
        return e;
    }

    /** 按 id 改列的起手：带上 updated_at */
    private static LambdaUpdateWrapper<AiTraderExperience> upd(long id) {
        return new LambdaUpdateWrapper<AiTraderExperience>()
                .eq(AiTraderExperience::getId, id)
                .set(AiTraderExperience::getUpdatedAt, LocalDateTime.now());
    }

    /** 计数列原子加一 */
    private void bump(long id, String column) {
        expMapper.update(null, upd(id).setSql(column + " = " + column + " + 1"));
    }

    static int textMax(AgentLang lang) {
        return lang == AgentLang.EN ? 160 : 80;
    }

    static int evidenceMax(AgentLang lang) {
        return lang == AgentLang.EN ? 120 : 60;
    }

    private static String cut(String s, int max) {
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
