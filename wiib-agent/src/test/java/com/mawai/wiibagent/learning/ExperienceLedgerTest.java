package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderExperienceMapper;
import com.mawai.wiibagent.mapper.AiTraderExperienceVerdictMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderExperience;
import com.mawai.wiibcommon.entity.AiTraderExperienceVerdict;
import com.mawai.wiibcommon.enums.AgentLang;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账本的记账规则：升级靠计数、举旗靠近况与比例、上限丢弃、删除只对假设、复审四种结论、渲染与引用计数。
 * 写库全是按列：计数 SQL 加一、状态逐列 set。
 */
class ExperienceLedgerTest {

    @BeforeAll
    static void initTableInfoCache() {
        for (Class<?> c : List.of(AiTraderExperience.class, AiTraderExperienceVerdict.class, AiTrader.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), c);
        }
    }

    private final AiTraderExperienceMapper expMapper = mock(AiTraderExperienceMapper.class);
    private final AiTraderExperienceVerdictMapper verdictMapper = mock(AiTraderExperienceVerdictMapper.class);
    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final ExperienceLedger ledger = new ExperienceLedger(expMapper, verdictMapper, traderMapper, new PromptCatalog());

    @Test
    void thirdSupportPromotesHypothesisToRule() {
        AiTraderExperience e = exp(5L, AiTraderExperience.KIND_HYPOTHESIS, AiTraderExperience.DIR_EXECUTION, "PULLBACK", 2, 0);
        when(expMapper.selectById(5L)).thenReturn(e);
        when(verdictMapper.selectList(any())).thenReturn(List.of());

        ExperienceLedger.Applied r = ledger.apply(7L, 1, 100L, List.of(
                new ExperienceLedger.Verdict(5L, AiTraderExperienceVerdict.SUPPORT, "09-21 BTC +3073")), AgentLang.ZH, Map.of());

        assertThat(r.applied()).isEqualTo(1);
        assertThat(e.getKind()).isEqualTo(AiTraderExperience.KIND_RULE);
        assertThat(e.getSupports()).isEqualTo(3);
        ArgumentCaptor<AiTraderExperienceVerdict> row = ArgumentCaptor.forClass(AiTraderExperienceVerdict.class);
        verify(verdictMapper).insert(row.capture());
        assertThat(row.getValue().getReviewId()).isEqualTo(100L);
        assertThat(row.getValue().getExperienceId()).isEqualTo(5L);
        // 计数 SQL 加一、升级按列写 kind，不整行 updateById
        assertThat(updateSets()).anyMatch(s -> s.contains("supports = supports + 1"))
                .anyMatch(s -> s.contains("verdicts_since_audit = verdicts_since_audit + 1"))
                .anyMatch(s -> s.contains("kind="));
        verify(expMapper, never()).updateById(any(AiTraderExperience.class));
    }

    @Test
    void threeOpposesInLastFiveFlagsForAudit() {
        AiTraderExperience e = exp(5L, AiTraderExperience.KIND_RULE, AiTraderExperience.DIR_EXECUTION, "PULLBACK", 100, 2);
        e.setVerdictsSinceAudit(2);
        when(expMapper.selectById(5L)).thenReturn(e);
        when(verdictMapper.selectList(any())).thenReturn(verdicts("S", "S", "O", "O", "S", "O"));

        ledger.apply(7L, 1, 100L, List.of(new ExperienceLedger.Verdict(5L, AiTraderExperienceVerdict.OPPOSE, "x")), AgentLang.ZH, Map.of());

        assertThat(e.getFlagged()).isTrue();
        assertThat(e.getKind()).isEqualTo(AiTraderExperience.KIND_RULE);
        assertThat(updateSets()).anyMatch(s -> s.contains("flagged="));
    }

    @Test
    void pinnedEntryIsNeverFlagged() {
        AiTraderExperience e = exp(5L, AiTraderExperience.KIND_RULE, AiTraderExperience.DIR_EXECUTION, "PULLBACK", 2, 2);
        e.setPinned(true);
        e.setVerdictsSinceAudit(5);
        when(expMapper.selectById(5L)).thenReturn(e);
        when(verdictMapper.selectList(any())).thenReturn(verdicts("O", "O", "O", "O"));

        ledger.apply(7L, 1, 100L, List.of(new ExperienceLedger.Verdict(5L, AiTraderExperienceVerdict.OPPOSE, "x")), AgentLang.ZH, Map.of());

        assertThat(e.getFlagged()).isFalse();
    }

    @Test
    void opposesAtLeastSupportsAfterFourChecksFlags() {
        AiTraderExperience e = exp(5L, AiTraderExperience.KIND_HYPOTHESIS, AiTraderExperience.DIR_EXECUTION, null, 2, 1);
        e.setVerdictsSinceAudit(3);
        when(expMapper.selectById(5L)).thenReturn(e);
        when(verdictMapper.selectList(any())).thenReturn(verdicts("S", "S", "O", "O"));

        ledger.apply(7L, 1, 100L, List.of(new ExperienceLedger.Verdict(5L, AiTraderExperienceVerdict.OPPOSE, "x")), AgentLang.ZH, Map.of());

        assertThat(e.getFlagged()).isTrue();
    }

    /** 第二条举旗规则只看上次复审以来的结论：窗口 4 条里 2 反 2 支举旗，窗口 3 条不够核对数不举 */
    @Test
    void secondFlagRuleOnlyCountsVerdictsSinceLastAudit() {
        List<AiTraderExperienceVerdict> log = verdicts("S", "S", "S", "S", "S", "S", "S", "S", "S", "O", "S", "O");
        when(verdictMapper.selectList(any())).thenReturn(log);
        AiTraderExperience four = exp(5L, AiTraderExperience.KIND_RULE, AiTraderExperience.DIR_EXECUTION, "PULLBACK", 10, 2);
        four.setVerdictsSinceAudit(3);
        AiTraderExperience three = exp(6L, AiTraderExperience.KIND_RULE, AiTraderExperience.DIR_EXECUTION, "PULLBACK", 10, 2);
        three.setVerdictsSinceAudit(2);
        when(expMapper.selectById(5L)).thenReturn(four);
        when(expMapper.selectById(6L)).thenReturn(three);

        ledger.apply(7L, 1, 100L, List.of(
                new ExperienceLedger.Verdict(5L, AiTraderExperienceVerdict.OPPOSE, "x"),
                new ExperienceLedger.Verdict(6L, AiTraderExperienceVerdict.OPPOSE, "x")), AgentLang.ZH, Map.of());

        assertThat(four.getFlagged()).isTrue();
        assertThat(three.getFlagged()).isFalse();
    }

    /** 同一篇复盘对同一条经验只认第一行结论 */
    @Test
    void duplicateVerdictForSameEntryIsDropped() {
        AiTraderExperience e = exp(5L, AiTraderExperience.KIND_RULE, AiTraderExperience.DIR_EXECUTION, "PULLBACK", 5, 0);
        when(expMapper.selectById(5L)).thenReturn(e);
        when(verdictMapper.selectList(any())).thenReturn(List.of());

        ExperienceLedger.Applied r = ledger.apply(7L, 1, 100L, List.of(
                new ExperienceLedger.Verdict(5L, AiTraderExperienceVerdict.SUPPORT, "a"),
                new ExperienceLedger.Verdict(5L, AiTraderExperienceVerdict.OPPOSE, "b")), AgentLang.ZH, Map.of());

        assertThat(r.applied()).isEqualTo(1);
        assertThat(r.dropped()).isEqualTo(1);
        verify(verdictMapper, times(1)).insert(any(AiTraderExperienceVerdict.class));
        assertThat(e.getSupports()).isEqualTo(6);
        assertThat(e.getOpposes()).isZero();
    }

    /** 开新局带入：本局全部条目复制到新局，计数/引用/钉住/状态/退役原因照搬，来源指回旧行，旗与复审节拍清零、不挂复盘行 */
    @Test
    void carryOverCopiesAllEntriesToNewRound() {
        AiTraderExperience rule = exp(5L, AiTraderExperience.KIND_RULE, AiTraderExperience.DIR_EXECUTION, "PULLBACK", 6, 1);
        rule.setPinned(true);
        rule.setFlagged(true);
        rule.setVerdictsSinceAudit(4);
        rule.setCitedOpen(3);
        rule.setCitedHold(1);
        AiTraderExperience gone = exp(6L, AiTraderExperience.KIND_HYPOTHESIS, AiTraderExperience.DIR_OPPORTUNITY, "BREAKOUT", 1, 4);
        gone.setStatus(AiTraderExperience.STATUS_RETIRED);
        gone.setRetireReason("近 20 次反对 14");
        when(expMapper.selectList(any())).thenReturn(List.of(rule, gone));

        assertThat(ledger.carryOver(7L, 1, 2)).isEqualTo(2);

        ArgumentCaptor<AiTraderExperience> copy = ArgumentCaptor.forClass(AiTraderExperience.class);
        verify(expMapper, times(2)).insert(copy.capture());
        AiTraderExperience c = copy.getAllValues().get(0);
        assertThat(c.getRoundNo()).isEqualTo(2);
        assertThat(c.getKind()).isEqualTo(AiTraderExperience.KIND_RULE);
        assertThat(c.getText()).isEqualTo(rule.getText());
        assertThat(c.getSupports()).isEqualTo(6);
        assertThat(c.getOpposes()).isEqualTo(1);
        assertThat(c.getCitedOpen()).isEqualTo(3);
        assertThat(c.getPinned()).isTrue();
        assertThat(c.getFlagged()).isFalse();
        assertThat(c.getVerdictsSinceAudit()).isZero();
        assertThat(c.getSourceId()).isEqualTo(5L);
        assertThat(c.getCreatedReviewId()).isNull();
        AiTraderExperience r = copy.getAllValues().get(1);
        assertThat(r.getStatus()).isEqualTo(AiTraderExperience.STATUS_RETIRED);
        assertThat(r.getRetireReason()).isEqualTo("近 20 次反对 14");
        assertThat(r.getSourceId()).isEqualTo(6L);
    }

    /** 清旧局：条目与它们的结论流水一起删；没东西可删就一条 delete 都不发 */
    @Test
    void purgeDeletesEntriesWithVerdicts() {
        when(expMapper.selectList(any())).thenReturn(List.of(
                exp(5L, AiTraderExperience.KIND_RULE, AiTraderExperience.DIR_EXECUTION, "PULLBACK", 1, 0),
                exp(6L, AiTraderExperience.KIND_FINDING, AiTraderExperience.DIR_EXECUTION, null, 0, 0)));

        ledger.purgeRounds(7L, 2);

        verify(verdictMapper).delete(any());
        verify(expMapper).deleteByIds(List.of(5L, 6L));

        when(expMapper.selectList(any())).thenReturn(List.of());
        ledger.purgeAll(8L);
        verify(expMapper, never()).deleteByIds(List.of());
    }

    @Test
    void addRespectsCapsAndValidation() {
        List<AiTraderExperience> actives = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            actives.add(exp(10L + i, AiTraderExperience.KIND_HYPOTHESIS, AiTraderExperience.DIR_EXECUTION, "PULLBACK", 0, 0));
        }
        actives.add(exp(20L, AiTraderExperience.KIND_HYPOTHESIS, AiTraderExperience.DIR_RESTRICTION, "BREAKOUT", 0, 0));
        actives.add(exp(21L, AiTraderExperience.KIND_RULE, AiTraderExperience.DIR_RESTRICTION, null, 0, 0));
        when(expMapper.selectList(any())).thenReturn(actives);

        ExperienceLedger.Applied r = ledger.apply(7L, 1, 100L, List.of(
                // 同类已满 3 条
                new ExperienceLedger.Add("PULLBACK", AiTraderExperience.SLOT_PROFIT_HANDLE, AiTraderExperience.DIR_EXECUTION, "x", "e", null),
                // 限制型已满 2 条
                new ExperienceLedger.Add("BREAKOUT", AiTraderExperience.SLOT_RISK, AiTraderExperience.DIR_RESTRICTION, "x", "e", null),
                // playType 不合法
                new ExperienceLedger.Add("MOON", AiTraderExperience.SLOT_ENTRY, AiTraderExperience.DIR_OPPORTUNITY, "x", "e", null),
                // 没证据
                new ExperienceLedger.Add("BREAKOUT", AiTraderExperience.SLOT_ENTRY, AiTraderExperience.DIR_OPPORTUNITY, "x", " ", null),
                // 合法：执行缺口格子 → GAP，正文截到 80 字
                new ExperienceLedger.Add("BREAKOUT", AiTraderExperience.SLOT_EXEC, AiTraderExperience.DIR_EXECUTION,
                        "止".repeat(100), "SOL 10-05 0.58% < 1.0%", null),
                // 合法：普通假设
                new ExperienceLedger.Add("breakout", AiTraderExperience.SLOT_FAIL_DEF, AiTraderExperience.DIR_EXECUTION,
                        "2 根 1h 收不出新高即判失败", "ETH 09:32", null)), AgentLang.ZH, Map.of());

        assertThat(r.applied()).isEqualTo(2);
        assertThat(r.dropped()).isEqualTo(4);
        ArgumentCaptor<AiTraderExperience> cap = ArgumentCaptor.forClass(AiTraderExperience.class);
        verify(expMapper, times(2)).insert(cap.capture());
        AiTraderExperience gap = cap.getAllValues().get(0);
        assertThat(gap.getKind()).isEqualTo(AiTraderExperience.KIND_GAP);
        assertThat(gap.getText()).hasSize(80);
        assertThat(gap.getCreatedReviewId()).isEqualTo(100L);
        assertThat(gap.getStatus()).isEqualTo(AiTraderExperience.STATUS_ACTIVE);
        AiTraderExperience hyp = cap.getAllValues().get(1);
        assertThat(hyp.getKind()).isEqualTo(AiTraderExperience.KIND_HYPOTHESIS);
        assertThat(hyp.getPlayType()).isEqualTo("BREAKOUT");
    }

    /** 发现：正文与证据按语言上限截断，不占现役上限 */
    @Test
    void findingIsCutToLanguageLimits() {
        when(expMapper.selectList(any())).thenReturn(new ArrayList<>());

        ledger.apply(7L, 1, 100L, List.of(new ExperienceLedger.Finding("长".repeat(300), "证".repeat(300))), AgentLang.ZH, Map.of());

        ArgumentCaptor<AiTraderExperience> cap = ArgumentCaptor.forClass(AiTraderExperience.class);
        verify(expMapper).insert(cap.capture());
        assertThat(cap.getValue().getKind()).isEqualTo(AiTraderExperience.KIND_FINDING);
        assertThat(cap.getValue().getText()).hasSize(80);
        assertThat(cap.getValue().getEvidence()).hasSize(60);
    }

    /** 拆分：合法子条满两条才入账并退役父条，子条继承种类与计数、不受上限；只有一条合法就什么都不动 */
    @Test
    void splitNeedsTwoValidChildrenAndIgnoresCaps() {
        List<AiTraderExperience> actives = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            actives.add(exp(10L + i, AiTraderExperience.KIND_HYPOTHESIS, AiTraderExperience.DIR_EXECUTION, i % 2 == 0 ? "RANGE" : null, 0, 0));
        }
        when(expMapper.selectList(any())).thenReturn(actives);
        AiTraderExperience parent = exp(3L, AiTraderExperience.KIND_RULE, AiTraderExperience.DIR_EXECUTION, "PULLBACK", 9, 1);
        parent.setFlagged(true);
        when(expMapper.selectById(3L)).thenReturn(parent);
        ExperienceLedger.Audit split = new ExperienceLedger.Audit(3L, ExperienceLedger.AUDIT_SPLIT, null, "ADX 高低结局相反");

        assertThat(ledger.split(7L, 1, 200L, split, List.of(
                new ExperienceLedger.Add("PULLBACK", AiTraderExperience.SLOT_ENTRY, AiTraderExperience.DIR_OPPORTUNITY, "ADX>40 做", "6 笔", 3L),
                new ExperienceLedger.Add("PULLBACK", AiTraderExperience.SLOT_ENTRY, AiTraderExperience.DIR_RESTRICTION, "ADX<30 不做", " ", 3L)),
                AgentLang.ZH)).isFalse();
        verify(expMapper, never()).insert(any(AiTraderExperience.class));
        assertThat(parent.getStatus()).isEqualTo(AiTraderExperience.STATUS_ACTIVE);

        assertThat(ledger.split(7L, 1, 200L, split, List.of(
                new ExperienceLedger.Add("PULLBACK", AiTraderExperience.SLOT_ENTRY, AiTraderExperience.DIR_OPPORTUNITY, "ADX>40 做", "6 笔", 3L),
                new ExperienceLedger.Add("PULLBACK", AiTraderExperience.SLOT_ENTRY, AiTraderExperience.DIR_RESTRICTION, "ADX<30 不做", "4 笔", 3L)),
                AgentLang.ZH)).isTrue();
        ArgumentCaptor<AiTraderExperience> cap = ArgumentCaptor.forClass(AiTraderExperience.class);
        verify(expMapper, times(2)).insert(cap.capture());
        assertThat(cap.getAllValues()).allSatisfy(c -> {
            assertThat(c.getSourceId()).isEqualTo(3L);
            assertThat(c.getKind()).isEqualTo(AiTraderExperience.KIND_RULE);
            assertThat(c.getSupports()).isEqualTo(9);
            assertThat(c.getCreatedReviewId()).isEqualTo(200L);
        });
        assertThat(parent.getStatus()).isEqualTo(AiTraderExperience.STATUS_RETIRED);
        assertThat(parent.getRetiredReviewId()).isEqualTo(200L);
        assertThat(parent.getRetireReason()).isEqualTo("ADX 高低结局相反");
    }

    @Test
    void deleteOnlyRetiresHypotheses() {
        AiTraderExperience hyp = exp(5L, AiTraderExperience.KIND_HYPOTHESIS, AiTraderExperience.DIR_EXECUTION, null, 1, 0);
        AiTraderExperience rule = exp(6L, AiTraderExperience.KIND_RULE, AiTraderExperience.DIR_EXECUTION, null, 5, 0);
        when(expMapper.selectById(5L)).thenReturn(hyp);
        when(expMapper.selectById(6L)).thenReturn(rule);
        when(expMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(hyp, rule)));

        ExperienceLedger.Applied r = ledger.apply(7L, 1, 100L, List.of(
                new ExperienceLedger.Delete(5L, "与主人指令第五节重复"),
                new ExperienceLedger.Delete(6L, "不想要了")), AgentLang.ZH, Map.of());

        assertThat(r.applied()).isEqualTo(1);
        assertThat(hyp.getStatus()).isEqualTo(AiTraderExperience.STATUS_RETIRED);
        assertThat(hyp.getRetireReason()).isEqualTo("与主人指令第五节重复");
        assertThat(hyp.getRetiredReviewId()).isEqualTo(100L);
        assertThat(rule.getStatus()).isEqualTo(AiTraderExperience.STATUS_ACTIVE);
        assertThat(updateSets()).anyMatch(s -> s.contains("status=") && s.contains("retire_reason="));
    }

    /** 钉住的条目：删除、复审、拆分一律不动 */
    @Test
    void pinnedEntryRejectsDeleteAuditAndSplit() {
        AiTraderExperience e = exp(5L, AiTraderExperience.KIND_HYPOTHESIS, AiTraderExperience.DIR_EXECUTION, "PULLBACK", 1, 3);
        e.setPinned(true);
        when(expMapper.selectById(5L)).thenReturn(e);
        when(expMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(e)));

        assertThat(ledger.apply(7L, 1, 100L, List.of(new ExperienceLedger.Delete(5L, "重复")), AgentLang.ZH, Map.of()).applied()).isZero();
        assertThat(ledger.applyAudit(7L, 1, 200L, new ExperienceLedger.Audit(5L, ExperienceLedger.AUDIT_RETIRE, null, "x"), AgentLang.ZH)).isFalse();
        assertThat(ledger.split(7L, 1, 200L, new ExperienceLedger.Audit(5L, ExperienceLedger.AUDIT_SPLIT, null, "x"), List.of(
                new ExperienceLedger.Add("PULLBACK", AiTraderExperience.SLOT_ENTRY, AiTraderExperience.DIR_OPPORTUNITY, "a", "b", 5L),
                new ExperienceLedger.Add("PULLBACK", AiTraderExperience.SLOT_ENTRY, AiTraderExperience.DIR_RESTRICTION, "c", "d", 5L)),
                AgentLang.ZH)).isFalse();
        assertThat(e.getStatus()).isEqualTo(AiTraderExperience.STATUS_ACTIVE);
        verify(expMapper, never()).update(isNull(), any());
    }

    @Test
    void auditReviseInheritsCountsAndRetiresOld() {
        AiTraderExperience old = exp(5L, AiTraderExperience.KIND_RULE, AiTraderExperience.DIR_RESTRICTION, "PULLBACK", 100, 3);
        old.setFlagged(true);
        when(expMapper.selectById(5L)).thenReturn(old);

        assertThat(ledger.applyAudit(7L, 1, 200L,
                new ExperienceLedger.Audit(5L, ExperienceLedger.AUDIT_REVISE, "regime 非 SHOCK 时才成立" + "长".repeat(100), "3 次反对全在 SHOCK"),
                AgentLang.ZH)).isTrue();

        ArgumentCaptor<AiTraderExperience> cap = ArgumentCaptor.forClass(AiTraderExperience.class);
        verify(expMapper).insert(cap.capture());
        assertThat(cap.getValue().getSourceId()).isEqualTo(5L);
        assertThat(cap.getValue().getSupports()).isEqualTo(100);
        assertThat(cap.getValue().getOpposes()).isEqualTo(3);
        assertThat(cap.getValue().getKind()).isEqualTo(AiTraderExperience.KIND_RULE);
        // 修订正文按语言上限截
        assertThat(cap.getValue().getText()).hasSize(80).startsWith("regime 非 SHOCK 时才成立");
        assertThat(old.getStatus()).isEqualTo(AiTraderExperience.STATUS_RETIRED);
        assertThat(old.getRetiredReviewId()).isEqualTo(200L);
        assertThat(old.getFlagged()).isFalse();
    }

    @Test
    void auditKeepClearsFlagAndThrottles() {
        AiTraderExperience e = exp(5L, AiTraderExperience.KIND_RULE, AiTraderExperience.DIR_EXECUTION, null, 10, 3);
        e.setFlagged(true);
        e.setVerdictsSinceAudit(7);
        when(expMapper.selectById(5L)).thenReturn(e);

        ledger.applyAudit(7L, 1, 200L, new ExperienceLedger.Audit(5L, ExperienceLedger.AUDIT_KEEP, null, "历史最长连败 4 次"), AgentLang.ZH);

        assertThat(e.getFlagged()).isFalse();
        assertThat(e.getVerdictsSinceAudit()).isZero();
        assertThat(e.getStatus()).isEqualTo(AiTraderExperience.STATUS_ACTIVE);
        verify(expMapper, never()).insert(any(AiTraderExperience.class));
        assertThat(updateSets()).anyMatch(s -> s.contains("flagged=") && s.contains("verdicts_since_audit="));
    }

    @Test
    void renderGroupsByPlayAndSkipsFindings() {
        AiTraderExperience a = exp(1L, AiTraderExperience.KIND_RULE, AiTraderExperience.DIR_EXECUTION, "PULLBACK", 5, 1);
        a.setText("出场只认目标位或已收盘 1h 跌破最近 1h 低点");
        AiTraderExperience b = exp(2L, AiTraderExperience.KIND_GAP, AiTraderExperience.DIR_EXECUTION, null, 2, 2);
        b.setText("止损距离不小于币种缓冲");
        b.setFlagged(true);
        AiTraderExperience f = exp(3L, AiTraderExperience.KIND_FINDING, AiTraderExperience.DIR_EXECUTION, null, 0, 0);
        f.setText("指令第六条本期少赚 4800");
        when(expMapper.selectList(any())).thenReturn(List.of(a, b, f));
        // 7 次结论：超过 5 次才另给最近 5 次
        when(verdictMapper.selectList(any())).thenReturn(verdicts("S", "S", "O", "S", "S", "S", "O"));

        String text = ledger.render(7L, 1, AgentLang.ZH);

        assertThat(ledger.isRendered(text)).isTrue();
        assertThat(text).startsWith("【经验账本】\n〔PULLBACK〕\n")
                .doesNotContain("不开仓的理由")
                .contains("#1 规律·执行型 ｜ 出场只认目标位或已收盘 1h 跌破最近 1h 低点 ｜ 支持 5 反对 1（最近 5 次：支持 3 反对 2）")
                .contains("〔通用〕")
                .contains("#2 执行缺口 ｜ 止损距离不小于币种缓冲 ｜ 支持 2 反对 2（最近 5 次：支持 3 反对 2） ｜ 待复审")
                .doesNotContain("4800");
        assertThat(ledger.isRendered("【已验证纪律】\n1. 旧记忆")).isFalse();
    }

    /** 核对不超过 5 次：总数就是全部，不另给最近几次 */
    @Test
    void renderOmitsRecentBreakdownUpToFiveChecks() {
        when(expMapper.selectList(any())).thenReturn(List.of(
                exp(1L, AiTraderExperience.KIND_HYPOTHESIS, AiTraderExperience.DIR_OPPORTUNITY, "BREAKOUT", 3, 2)));
        when(verdictMapper.selectList(any())).thenReturn(verdicts("S", "S", "O", "S", "O"));

        assertThat(ledger.render(7L, 1, AgentLang.ZH))
                .contains("#1 假设·机会型 ｜ t1 ｜ 支持 3 反对 2\n")
                .doesNotContain("最近");
    }

    @Test
    void renderIsNullWithoutActiveEntries() {
        when(expMapper.selectList(any())).thenReturn(List.of(
                exp(3L, AiTraderExperience.KIND_FINDING, AiTraderExperience.DIR_EXECUTION, null, 0, 0)));

        assertThat(ledger.render(7L, 1, AgentLang.EN)).isNull();
    }

    /** writeMemory：渲染文本按列写 ai_trader.memory；账本空了而 memory 是旧渲染文本时写 null 清空 */
    @Test
    void writeMemoryWritesRenderedTextByColumn() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setRoundNo(1);
        t.setMemory("【经验账本】\n〔PULLBACK〕\n#1 规律·限制型 ｜ 旧的 ｜ 支持 4 反对 1");
        AiTraderExperience a = exp(1L, AiTraderExperience.KIND_RULE, AiTraderExperience.DIR_RESTRICTION, "PULLBACK", 5, 1);
        when(expMapper.selectList(any())).thenReturn(List.of(a), List.of());
        when(verdictMapper.selectList(any())).thenReturn(List.of());

        String first = ledger.writeMemory(t, AgentLang.ZH);
        String second = ledger.writeMemory(t, AgentLang.ZH);

        assertThat(first).startsWith("【经验账本】\n〔PULLBACK〕\n#1 规律·限制型 ｜ t1 ｜ 支持 5 反对 1");
        assertThat(second).isNull();
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<AiTrader>> cap = ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(traderMapper, times(2)).update(isNull(), cap.capture());
        assertThat(cap.getAllValues().get(0).getSqlSet()).contains("memory");
        assertThat(cap.getAllValues().get(0).getParamNameValuePairs()).containsValue(first);
        assertThat(cap.getAllValues().get(1).getParamNameValuePairs().containsValue(null)).isTrue();
    }

    /** 账本空着、memory 还是没迁移的旧文本：不写库，旧文本留给迁移 */
    @Test
    void writeMemoryKeepsLegacyTextWhenLedgerEmpty() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setRoundNo(1);
        t.setMemory("【已验证纪律】\n1. 旧记忆");
        when(expMapper.selectList(any())).thenReturn(List.of());

        assertThat(ledger.writeMemory(t, AgentLang.ZH)).isNull();
        verify(traderMapper, never()).update(any(), any());
    }

    /** 没被代码举旗的条目：复审行与拆分都不动它 */
    @Test
    void unflaggedEntryIgnoresAuditAndSplit() {
        AiTraderExperience e = exp(5L, AiTraderExperience.KIND_RULE, AiTraderExperience.DIR_EXECUTION, "PULLBACK", 9, 1);
        when(expMapper.selectById(5L)).thenReturn(e);

        assertThat(ledger.applyAudit(7L, 1, 200L, new ExperienceLedger.Audit(5L, ExperienceLedger.AUDIT_RETIRE, null, "x"), AgentLang.ZH)).isFalse();
        assertThat(ledger.split(7L, 1, 200L, new ExperienceLedger.Audit(5L, ExperienceLedger.AUDIT_SPLIT, null, "x"), List.of(
                new ExperienceLedger.Add("PULLBACK", AiTraderExperience.SLOT_ENTRY, AiTraderExperience.DIR_OPPORTUNITY, "a", "b", 5L),
                new ExperienceLedger.Add("PULLBACK", AiTraderExperience.SLOT_ENTRY, AiTraderExperience.DIR_RESTRICTION, "c", "d", 5L)),
                AgentLang.ZH)).isFalse();
        assertThat(e.getStatus()).isEqualTo(AiTraderExperience.STATUS_ACTIVE);
        verify(expMapper, never()).update(isNull(), any());
        verify(expMapper, never()).insert(any(AiTraderExperience.class));
    }

    @Test
    void citationsCountOpenAndHoldSeparately() {
        AiTraderExperience e = exp(5L, AiTraderExperience.KIND_RULE, AiTraderExperience.DIR_EXECUTION, null, 1, 0);
        when(expMapper.selectById(5L)).thenReturn(e);
        when(expMapper.selectById(9L)).thenReturn(null);

        ledger.recordCitations(7L, 1, List.of(5L, 9L), true);
        ledger.recordCitations(7L, 1, List.of(5L), false);

        List<String> sets = updateSets();
        assertThat(sets).hasSize(2);
        assertThat(sets.get(0)).contains("cited_open = cited_open + 1");
        assertThat(sets.get(1)).contains("cited_hold = cited_hold + 1");
    }

    /** 结论流水记本期涉及的仓位：标了类型只记同类型的，通用记全部 */
    @Test
    void verdictRecordsPositionIdsByPlayType() {
        AiTraderExperience typed = exp(5L, AiTraderExperience.KIND_RULE, AiTraderExperience.DIR_EXECUTION, "PULLBACK", 1, 0);
        AiTraderExperience general = exp(6L, AiTraderExperience.KIND_GAP, AiTraderExperience.DIR_EXECUTION, null, 1, 0);
        when(expMapper.selectById(5L)).thenReturn(typed);
        when(expMapper.selectById(6L)).thenReturn(general);
        when(verdictMapper.selectList(any())).thenReturn(List.of());

        ledger.apply(7L, 1, 100L, List.of(
                        new ExperienceLedger.Verdict(5L, AiTraderExperienceVerdict.SUPPORT, "a"),
                        new ExperienceLedger.Verdict(6L, AiTraderExperienceVerdict.OPPOSE, "b")), AgentLang.ZH,
                Map.of("PULLBACK", List.of(42L, 43L), "BREAKOUT", List.of(44L), "", List.of(45L)));

        ArgumentCaptor<AiTraderExperienceVerdict> rows = ArgumentCaptor.forClass(AiTraderExperienceVerdict.class);
        verify(verdictMapper, times(2)).insert(rows.capture());
        assertThat(rows.getAllValues().get(0).getPositionIds()).isEqualTo("42,43");
        assertThat(rows.getAllValues().get(1).getPositionIds().split(",")).containsExactlyInAnyOrder("42", "43", "44", "45");
    }

    /** position_ids 超过 255 字截到放得下的整数个 id；一个没有给 null */
    @Test
    void joinIdsCutsToWholeIdsWithin255() {
        // 6 位 id：36 个 = 216 字 + 35 个逗号 = 251，第 37 个放不下
        String joined = ExperienceLedger.joinIds(LongStream.rangeClosed(100000, 100060).boxed().toList());

        assertThat(joined).hasSizeLessThanOrEqualTo(255).doesNotEndWith(",");
        assertThat(joined.split(",")).hasSize(36);
        assertThat(ExperienceLedger.joinIds(List.of())).isNull();
    }

    @Test
    void maxOpposeStreakCountsConsecutiveOnly() {
        assertThat(ExperienceLedger.maxOpposeStreak(verdicts("O", "O", "S", "O", "O", "O", "S"))).isEqualTo(3);
        assertThat(ExperienceLedger.maxOpposeStreak(List.of())).isZero();
    }

    /** 到目前为止发给 expMapper 的按列更新，每条取 set 子句 */
    private List<String> updateSets() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<AiTraderExperience>> cap = ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(expMapper, atLeast(0)).update(isNull(), cap.capture());
        return cap.getAllValues().stream().map(LambdaUpdateWrapper::getSqlSet).toList();
    }

    private static AiTraderExperience exp(long id, String kind, String direction, String playType, int supports, int opposes) {
        AiTraderExperience e = new AiTraderExperience();
        e.setId(id);
        e.setTraderId(7L);
        e.setRoundNo(1);
        e.setPlayType(playType);
        e.setKind(kind);
        e.setSlot(AiTraderExperience.SLOT_RISK);
        e.setDirection(direction);
        e.setText("t" + id);
        e.setEvidence("e");
        e.setSupports(supports);
        e.setOpposes(opposes);
        e.setUntested(0);
        e.setCitedOpen(0);
        e.setCitedHold(0);
        e.setStatus(AiTraderExperience.STATUS_ACTIVE);
        e.setFlagged(false);
        e.setVerdictsSinceAudit(0);
        e.setPinned(false);
        return e;
    }

    private static List<AiTraderExperienceVerdict> verdicts(String... codes) {
        List<AiTraderExperienceVerdict> out = new ArrayList<>();
        for (String c : codes) {
            AiTraderExperienceVerdict v = new AiTraderExperienceVerdict();
            v.setVerdict(switch (c) {
                case "S" -> AiTraderExperienceVerdict.SUPPORT;
                case "O" -> AiTraderExperienceVerdict.OPPOSE;
                default -> AiTraderExperienceVerdict.UNTESTED;
            });
            out.add(v);
        }
        return out;
    }
}
