package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderExperienceMapper;
import com.mawai.wiibagent.mapper.AiTraderExperienceVerdictMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.trader.trade.TradeGuard;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderExperience;
import com.mawai.wiibcommon.entity.AiTraderExperienceVerdict;
import com.mawai.wiibcommon.enums.AgentLang;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 经验账本：复盘提议的操作在这里落账，计数、升级、举旗、退役全由代码做；
 * 渲染成紧凑文本写回 ai_trader.memory 给 trader 每轮注入。
 * <p>
 * 升级：假设支持满 {@value #PROMOTE_SUPPORTS} 次且反对不超过支持的三分之一 → 规律。
 * 举旗（待复审）：近 {@value #FLAG_RECENT} 次反对 ≥ {@value #FLAG_RECENT_OPPOSES}，或上次复审以来反对 ≥ 支持且核对 ≥ {@value #FLAG_MIN_CHECKS}，
 * 或近 {@value #FLAG_UNTESTED_STREAK} 次全未测试；复审保留后不满 {@value #AUDIT_THROTTLE} 次新结论不重举。
 * 降级只走复审，退役不删行。写库按列：计数用 SQL 加一，状态字段逐列 set。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExperienceLedger {

    static final int MAX_ACTIVE = 10;
    static final int MAX_PER_PLAY = 3;
    static final int MAX_RESTRICTION = 2;
    static final int MAX_FINDINGS = 5;
    static final int PROMOTE_SUPPORTS = 3;
    static final int FLAG_RECENT = 5;
    static final int FLAG_RECENT_OPPOSES = 3;
    static final int FLAG_MIN_CHECKS = 4;
    static final int FLAG_UNTESTED_STREAK = 6;
    static final int AUDIT_THROTTLE = 3;
    /** 退役名单给复盘看的条数 */
    static final int RETIRED_SHOWN = 10;

    public static final String AUDIT_KEEP = "KEEP";
    public static final String AUDIT_REVISE = "REVISE";
    public static final String AUDIT_SPLIT = "SPLIT";
    public static final String AUDIT_RETIRE = "RETIRE";

    private final AiTraderExperienceMapper expMapper;
    private final AiTraderExperienceVerdictMapper verdictMapper;
    private final AiTraderMapper traderMapper;
    private final PromptCatalog prompts;

    /** 复盘提议的一个操作 */
    public sealed interface Op permits Verdict, Add, Finding, Delete {
    }

    /** 对现役经验的一次结论：SUPPORT / OPPOSE / UNTESTED */
    public record Verdict(long id, String verdict, String note) implements Op {
    }

    /** 新增经验；sourceId 非空＝拆分来的，只在复审路径出现 */
    public record Add(String playType, String slot, String direction, String text, String evidence,
                      Long sourceId) implements Op {
    }

    /** 发现：给主人看的，不注入 */
    public record Finding(String text, String evidence) implements Op {
    }

    /** 删除提议：只对假设生效 */
    public record Delete(long id, String reason) implements Op {
    }

    /** 复审结论：KEEP / REVISE（带新正文）/ SPLIT / RETIRE */
    public record Audit(long id, String kind, String newText, String reason) {
    }

    public record Applied(int applied, int dropped) {
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

    /** 现役里待复审的 */
    public List<AiTraderExperience> flagged(long traderId, int roundNo) {
        return active(traderId, roundNo).stream().filter(e -> Boolean.TRUE.equals(e.getFlagged())).toList();
    }

    /** 一条经验的全部结论，按时间升序 */
    public List<AiTraderExperienceVerdict> verdicts(long experienceId) {
        return verdictMapper.selectList(new LambdaQueryWrapper<AiTraderExperienceVerdict>()
                .eq(AiTraderExperienceVerdict::getExperienceId, experienceId)
                .orderByAsc(AiTraderExperienceVerdict::getId));
    }

    /** 历史最长连续反对 */
    public static int maxOpposeStreak(List<AiTraderExperienceVerdict> verdicts) {
        int best = 0;
        int cur = 0;
        for (AiTraderExperienceVerdict v : verdicts) {
            cur = AiTraderExperienceVerdict.OPPOSE.equals(v.getVerdict()) ? cur + 1 : 0;
            best = Math.max(best, cur);
        }
        return best;
    }

    public AiTraderExperience owned(long traderId, long id) {
        AiTraderExperience e = expMapper.selectById(id);
        return e != null && Objects.equals(e.getTraderId(), traderId) ? e : null;
    }

    // ==================== 复盘落账 ====================

    /**
     * 逐行独立生效；不合法的行丢弃记日志，不中断。reviewId 为空＝迁移产生，没有复盘行可挂。
     * 同一条经验一篇只认第一行结论。positionIdsByPlay：本期仓位 id 按单子类型分组（未标类型键为空串），
     * 结论流水按经验的类型记涉及的仓位
     */
    public Applied apply(long traderId, int roundNo, Long reviewId, List<Op> ops, AgentLang lang,
                         Map<String, List<Long>> positionIdsByPlay) {
        List<AiTraderExperience> actives = active(traderId, roundNo);
        Set<Long> judged = new HashSet<>();
        int applied = 0;
        int dropped = 0;
        for (Op op : ops) {
            boolean ok = switch (op) {
                case Verdict v -> judged.add(v.id()) && verdict(traderId, roundNo, reviewId, v, positionIdsByPlay);
                case Add a -> add(traderId, roundNo, reviewId, a, actives, lang);
                case Finding f -> finding(traderId, roundNo, reviewId, f, actives, lang);
                case Delete d -> delete(traderId, roundNo, reviewId, d, actives);
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

    private boolean verdict(long traderId, int roundNo, Long reviewId, Verdict v, Map<String, List<Long>> positionIdsByPlay) {
        AiTraderExperience e = owned(traderId, v.id());
        if (e == null || reviewId == null || !Objects.equals(e.getRoundNo(), roundNo)
                || !AiTraderExperience.STATUS_ACTIVE.equals(e.getStatus())
                || AiTraderExperience.KIND_FINDING.equals(e.getKind())) {
            return false;
        }
        AiTraderExperienceVerdict row = new AiTraderExperienceVerdict();
        row.setExperienceId(e.getId());
        row.setReviewId(reviewId);
        row.setVerdict(v.verdict());
        row.setNote(cut(v.note(), 255));
        row.setPositionIds(joinIds(positionIdsFor(e.getPlayType(), positionIdsByPlay)));
        verdictMapper.insert(row);

        switch (v.verdict()) {
            case AiTraderExperienceVerdict.SUPPORT -> {
                e.setSupports(e.getSupports() + 1);
                bump(e.getId(), "supports");
            }
            case AiTraderExperienceVerdict.OPPOSE -> {
                e.setOpposes(e.getOpposes() + 1);
                bump(e.getId(), "opposes");
            }
            default -> {
                e.setUntested(e.getUntested() + 1);
                bump(e.getId(), "untested");
            }
        }
        e.setVerdictsSinceAudit(e.getVerdictsSinceAudit() + 1);
        bump(e.getId(), "verdicts_since_audit");
        if (AiTraderExperience.KIND_HYPOTHESIS.equals(e.getKind())
                && e.getSupports() >= PROMOTE_SUPPORTS && e.getOpposes() * 3 <= e.getSupports()) {
            e.setKind(AiTraderExperience.KIND_RULE);
            expMapper.update(null, upd(e.getId()).set(AiTraderExperience::getKind, AiTraderExperience.KIND_RULE));
        }
        if (!Boolean.TRUE.equals(e.getPinned()) && !Boolean.TRUE.equals(e.getFlagged())
                && e.getVerdictsSinceAudit() >= AUDIT_THROTTLE && shouldFlag(e, verdicts(e.getId()))) {
            e.setFlagged(true);
            expMapper.update(null, upd(e.getId()).set(AiTraderExperience::getFlagged, true));
        }
        return true;
    }

    /** 三条举旗规则；第二条只看上次复审以来的那几条结论（从没复审过就是全部） */
    private static boolean shouldFlag(AiTraderExperience e, List<AiTraderExperienceVerdict> all) {
        List<AiTraderExperienceVerdict> recent = all.size() <= FLAG_RECENT ? all : all.subList(all.size() - FLAG_RECENT, all.size());
        if (count(recent, AiTraderExperienceVerdict.OPPOSE) >= FLAG_RECENT_OPPOSES) {
            return true;
        }
        int since = e.getVerdictsSinceAudit();
        List<AiTraderExperienceVerdict> sinceAudit = all.size() <= since ? all : all.subList(all.size() - since, all.size());
        long supports = count(sinceAudit, AiTraderExperienceVerdict.SUPPORT);
        long opposes = count(sinceAudit, AiTraderExperienceVerdict.OPPOSE);
        if (supports + opposes >= FLAG_MIN_CHECKS && opposes >= supports) {
            return true;
        }
        if (all.size() < FLAG_UNTESTED_STREAK) {
            return false;
        }
        return all.subList(all.size() - FLAG_UNTESTED_STREAK, all.size()).stream()
                .allMatch(v -> AiTraderExperienceVerdict.UNTESTED.equals(v.getVerdict()));
    }

    private static long count(List<AiTraderExperienceVerdict> list, String verdict) {
        return list.stream().filter(v -> verdict.equals(v.getVerdict())).count();
    }

    /** 新增行合法：正文与证据非空，单子类型是认识的 */
    private static boolean valid(Add a) {
        return !blank(a.text()) && !blank(a.evidence())
                && (blank(a.playType()) || TradeGuard.PLAY_TYPES.contains(a.playType().trim().toUpperCase(Locale.ROOT)));
    }

    private boolean add(long traderId, int roundNo, Long reviewId, Add a, List<AiTraderExperience> actives, AgentLang lang) {
        if (!valid(a)) {
            return false;
        }
        String play = blank(a.playType()) ? null : a.playType().trim().toUpperCase(Locale.ROOT);
        List<AiTraderExperience> counted = actives.stream()
                .filter(e -> !AiTraderExperience.KIND_FINDING.equals(e.getKind())).toList();
        if (counted.size() >= MAX_ACTIVE
                || counted.stream().filter(e -> Objects.equals(e.getPlayType(), play)).count() >= MAX_PER_PLAY
                || (AiTraderExperience.DIR_RESTRICTION.equals(a.direction()) && counted.stream()
                        .filter(e -> AiTraderExperience.DIR_RESTRICTION.equals(e.getDirection())).count() >= MAX_RESTRICTION)) {
            return false;
        }
        AiTraderExperience e = entry(traderId, roundNo, reviewId, a, lang);
        expMapper.insert(e);
        actives.add(e);
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

    private boolean delete(long traderId, int roundNo, Long reviewId, Delete d, List<AiTraderExperience> actives) {
        AiTraderExperience e = owned(traderId, d.id());
        if (e == null || !Objects.equals(e.getRoundNo(), roundNo) || blank(d.reason())
                || !AiTraderExperience.STATUS_ACTIVE.equals(e.getStatus())
                || !AiTraderExperience.KIND_HYPOTHESIS.equals(e.getKind())
                || Boolean.TRUE.equals(e.getPinned())) {
            return false;
        }
        retire(e, reviewId, d.reason());
        actives.removeIf(x -> Objects.equals(x.getId(), e.getId()));
        return true;
    }

    // ==================== 复审 ====================

    /** 保留 / 修订 / 退役落账；只对现役且举了旗、没钉住的条目生效。拆分走 {@link #split} */
    public boolean applyAudit(long traderId, int roundNo, Long reviewId, Audit audit, AgentLang lang) {
        AiTraderExperience e = owned(traderId, audit.id());
        if (!auditable(e, roundNo)) {
            return false;
        }
        switch (audit.kind()) {
            case AUDIT_KEEP -> {
                e.setFlagged(false);
                e.setVerdictsSinceAudit(0);
                expMapper.update(null, upd(e.getId())
                        .set(AiTraderExperience::getFlagged, false)
                        .set(AiTraderExperience::getVerdictsSinceAudit, 0));
            }
            case AUDIT_REVISE -> {
                if (blank(audit.newText())) {
                    return false;
                }
                AiTraderExperience revised = blank(traderId, roundNo, reviewId);
                revised.setPlayType(e.getPlayType());
                revised.setKind(e.getKind());
                revised.setSlot(e.getSlot());
                revised.setDirection(e.getDirection());
                revised.setText(cut(audit.newText(), textMax(lang)));
                revised.setEvidence(e.getEvidence());
                revised.setSupports(e.getSupports());
                revised.setOpposes(e.getOpposes());
                revised.setUntested(e.getUntested());
                revised.setSourceId(e.getId());
                expMapper.insert(revised);
                retire(e, reviewId, audit.reason());
            }
            case AUDIT_RETIRE -> retire(e, reviewId, audit.reason());
        }
        return true;
    }

    /** 拆分：子条先全部校验，合法的满两条才入账并退役父条；差一步父条子条都不动 */
    public boolean split(long traderId, int roundNo, Long reviewId, Audit audit, List<Add> children, AgentLang lang) {
        AiTraderExperience parent = owned(traderId, audit.id());
        if (!auditable(parent, roundNo)) {
            return false;
        }
        List<Add> valid = children.stream().filter(ExperienceLedger::valid).toList();
        if (valid.size() < 2) {
            return false;
        }
        for (Add c : valid) {
            AiTraderExperience child = entry(traderId, roundNo, reviewId, c, lang);
            child.setKind(parent.getKind());
            child.setSupports(parent.getSupports());
            child.setOpposes(parent.getOpposes());
            child.setUntested(parent.getUntested());
            child.setSourceId(parent.getId());
            expMapper.insert(child);
        }
        retire(parent, reviewId, audit.reason());
        return true;
    }

    /** 复审能动的条目：本局现役、代码举了旗、主人没钉住 */
    private static boolean auditable(AiTraderExperience e, int roundNo) {
        return e != null && Objects.equals(e.getRoundNo(), roundNo)
                && AiTraderExperience.STATUS_ACTIVE.equals(e.getStatus())
                && Boolean.TRUE.equals(e.getFlagged())
                && !Boolean.TRUE.equals(e.getPinned());
    }

    private void retire(AiTraderExperience e, Long reviewId, String reason) {
        e.setStatus(AiTraderExperience.STATUS_RETIRED);
        e.setFlagged(false);
        e.setRetiredReviewId(reviewId);
        e.setRetireReason(cut(reason, 255));
        expMapper.update(null, upd(e.getId())
                .set(AiTraderExperience::getStatus, AiTraderExperience.STATUS_RETIRED)
                .set(AiTraderExperience::getFlagged, false)
                .set(AiTraderExperience::getRetiredReviewId, reviewId)
                .set(AiTraderExperience::getRetireReason, e.getRetireReason()));
    }

    // ==================== 注入 trader ====================

    /** 现役经验渲染成紧凑文本：标题行 + 按单子类型分组的条目行（不含发现）；一条都没有返回 null */
    public String render(long traderId, int roundNo, AgentLang lang) {
        List<AiTraderExperience> list = active(traderId, roundNo).stream()
                .filter(e -> !AiTraderExperience.KIND_FINDING.equals(e.getKind())).toList();
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
                sb.append(row(e, lang)).append('\n');
            }
        }
        return sb.toString();
    }

    /** 一行：方向带「·」前缀，执行缺口不带方向；核对超过 5 次才另给最近 5 次 */
    private String row(AiTraderExperience e, AgentLang lang) {
        List<AiTraderExperienceVerdict> all = verdicts(e.getId());
        String recentText = "";
        if (all.size() > FLAG_RECENT) {
            List<AiTraderExperienceVerdict> recent = all.subList(all.size() - FLAG_RECENT, all.size());
            recentText = prompts.get(lang, "trader.label.ledgerRecent", Map.of(
                    "n", FLAG_RECENT,
                    "s", count(recent, AiTraderExperienceVerdict.SUPPORT),
                    "o", count(recent, AiTraderExperienceVerdict.OPPOSE)));
        }
        String dir = AiTraderExperience.KIND_GAP.equals(e.getKind()) ? ""
                : "·" + prompts.get(lang, "trader.label.ledgerDir." + e.getDirection().toLowerCase(Locale.ROOT));
        return prompts.get(lang, "trader.label.ledgerRow", Map.of(
                "id", e.getId(),
                "kind", prompts.get(lang, "trader.label.ledgerKind." + e.getKind().toLowerCase(Locale.ROOT)),
                "dir", dir,
                "text", e.getText(),
                "supports", e.getSupports(),
                "opposes", e.getOpposes(),
                "recent", recentText,
                "flagged", Boolean.TRUE.equals(e.getFlagged()) ? prompts.get(lang, "trader.label.ledgerFlagged") : ""));
    }

    /**
     * 渲染现役账本并按列写回 ai_trader.memory（一条都没有写 null）；返回渲染文本。
     * 账本空着而 memory 还是没迁移的旧文本时不写库，返回 null
     */
    public String writeMemory(AiTrader trader, AgentLang lang) {
        String rendered = render(trader.getId(), trader.getRoundNo(), lang);
        String memory = trader.getMemory();
        if (rendered == null && memory != null && !memory.isBlank() && !isRendered(memory)) {
            return null;
        }
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

    /** 开新局带入：本局全部条目复制到新局，计数、钉住、状态、退役原因照搬，结论流水留在旧行，旗与复审节拍清零；返回带入条数 */
    public int carryOver(long traderId, int fromRound, int toRound) {
        int n = 0;
        for (AiTraderExperience e : all(traderId, fromRound)) {
            AiTraderExperience c = blank(traderId, toRound, null);
            c.setPlayType(e.getPlayType());
            c.setKind(e.getKind());
            c.setSlot(e.getSlot());
            c.setDirection(e.getDirection());
            c.setText(e.getText());
            c.setEvidence(e.getEvidence());
            c.setSupports(e.getSupports());
            c.setOpposes(e.getOpposes());
            c.setUntested(e.getUntested());
            c.setCitedOpen(e.getCitedOpen());
            c.setCitedHold(e.getCitedHold());
            c.setPinned(e.getPinned());
            c.setStatus(e.getStatus());
            c.setRetireReason(e.getRetireReason());
            c.setSourceId(e.getId());
            expMapper.insert(c);
            n++;
        }
        return n;
    }

    /** 该局号及以前的条目连同结论流水一起删 */
    public void purgeRounds(long traderId, int roundNoInclusive) {
        purge(new LambdaQueryWrapper<AiTraderExperience>()
                .eq(AiTraderExperience::getTraderId, traderId)
                .le(AiTraderExperience::getRoundNo, roundNoInclusive));
    }

    /** 删 trader：全部条目与结论流水 */
    public void purgeAll(long traderId) {
        purge(new LambdaQueryWrapper<AiTraderExperience>().eq(AiTraderExperience::getTraderId, traderId));
    }

    private void purge(LambdaQueryWrapper<AiTraderExperience> which) {
        List<Long> ids = expMapper.selectList(which).stream().map(AiTraderExperience::getId).toList();
        if (ids.isEmpty()) {
            return;
        }
        verdictMapper.delete(new LambdaQueryWrapper<AiTraderExperienceVerdict>()
                .in(AiTraderExperienceVerdict::getExperienceId, ids));
        expMapper.deleteByIds(ids);
    }

    // ==================== 主人操作 ====================

    public boolean pin(long traderId, long id, boolean pinned) {
        AiTraderExperience e = owned(traderId, id);
        if (e == null) {
            return false;
        }
        LambdaUpdateWrapper<AiTraderExperience> w = upd(id).set(AiTraderExperience::getPinned, pinned);
        if (pinned) {
            w.set(AiTraderExperience::getFlagged, false);
        }
        expMapper.update(null, w);
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
                .set(AiTraderExperience::getVerdictsSinceAudit, 0));
        return true;
    }

    public boolean delete(long traderId, long id) {
        AiTraderExperience e = owned(traderId, id);
        if (e == null) {
            return false;
        }
        verdictMapper.delete(new LambdaQueryWrapper<AiTraderExperienceVerdict>()
                .eq(AiTraderExperienceVerdict::getExperienceId, id));
        expMapper.deleteById(id);
        return true;
    }

    // ==================== 小工具 ====================

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
        e.setSupports(0);
        e.setOpposes(0);
        e.setUntested(0);
        e.setCitedOpen(0);
        e.setCitedHold(0);
        e.setStatus(AiTraderExperience.STATUS_ACTIVE);
        e.setFlagged(false);
        e.setVerdictsSinceAudit(0);
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

    /** 该经验本期涉及的仓位：没标类型就是全部，否则同类型的 */
    private static List<Long> positionIdsFor(String playType, Map<String, List<Long>> byPlay) {
        if (playType == null) {
            return byPlay.values().stream().flatMap(List::stream).toList();
        }
        return byPlay.getOrDefault(playType, List.of());
    }

    /** 逗号拼接，超过 255 字截到放得下的整数个；一个没有给 null */
    static String joinIds(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            String next = sb.isEmpty() ? String.valueOf(id) : "," + id;
            if (sb.length() + next.length() > 255) {
                break;
            }
            sb.append(next);
        }
        return sb.isEmpty() ? null : sb.toString();
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
