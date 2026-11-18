package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderExperienceMapper;
import com.mawai.wiibagent.mapper.AiTraderExperienceTradeMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderExperience;
import com.mawai.wiibcommon.entity.AiTraderExperienceTrade;
import com.mawai.wiibcommon.enums.AgentLang;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mawai.wiibcommon.entity.AiTraderExperience.DIR_EXECUTION;
import static com.mawai.wiibcommon.entity.AiTraderExperience.DIR_OPPORTUNITY;
import static com.mawai.wiibcommon.entity.AiTraderExperience.DIR_RESTRICTION;
import static com.mawai.wiibcommon.entity.AiTraderExperience.KIND_FINDING;
import static com.mawai.wiibcommon.entity.AiTraderExperience.KIND_GAP;
import static com.mawai.wiibcommon.entity.AiTraderExperience.KIND_HYPOTHESIS;
import static com.mawai.wiibcommon.entity.AiTraderExperience.KIND_RULE;
import static com.mawai.wiibcommon.entity.AiTraderExperience.STATUS_ACTIVE;
import static com.mawai.wiibcommon.entity.AiTraderExperience.STATUS_RETIRED;
import static com.mawai.wiibcommon.entity.AiTraderExperienceTrade.MARK_BROKEN;
import static com.mawai.wiibcommon.entity.AiTraderExperienceTrade.MARK_KEPT;
import static com.mawai.wiibcommon.entity.AiTraderExperienceTrade.MARK_MISCITED;
import static com.mawai.wiibcommon.entity.AiTraderExperienceTrade.MARK_UNDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账本的记账规则：归类逐笔记、标记对种类、编号只认本期；转正、降级看当前段的账、只判本篇有新单子记进名下的，
 * 闲置满 10 篇退役、本期没有已了结单子的复盘不数；新增带单子与来源、上限丢弃、删除只对假设和执行缺口；
 * 渲染、带新局与引用计数。写库按列。名下单子行默认是之前那篇复盘（90）记的，这篇复盘是 100
 */
class ExperienceLedgerTest {

    @BeforeAll
    static void initTableInfoCache() {
        for (Class<?> c : List.of(AiTraderExperience.class, AiTraderExperienceTrade.class, AiTrader.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), c);
        }
    }

    /** 本期两笔：42 赚、43 亏 */
    private static final Map<Long, ExperienceLedger.PeriodTrade> TRADES = Map.of(
            42L, new ExperienceLedger.PeriodTrade(new BigDecimal("12.50"), LocalDate.of(2026, 11, 10)),
            43L, new ExperienceLedger.PeriodTrade(new BigDecimal("-3.00"), LocalDate.of(2026, 11, 11)));

    private final AiTraderExperienceMapper expMapper = mock(AiTraderExperienceMapper.class);
    private final AiTraderExperienceTradeMapper tradeMapper = mock(AiTraderExperienceTradeMapper.class);
    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final TraderPlanStore planStore = mock(TraderPlanStore.class);
    private final ExperienceLedger ledger = new ExperienceLedger(expMapper, tradeMapper, traderMapper, planStore, new PromptCatalog());

    // ==================== 归类 ====================

    /** 归类逐笔记下：挂在这篇复盘上，净盈亏和平仓日取本期单子，段号取条目当前的段；引用不符也记一行 */
    @Test
    void classifyRecordsEachTrade() {
        AiTraderExperience rule = exp(5L, KIND_RULE, DIR_OPPORTUNITY, "BREAKOUT");
        rule.setSegmentNo(2);
        when(expMapper.selectById(5L)).thenReturn(rule);
        when(expMapper.selectById(6L)).thenReturn(exp(6L, KIND_HYPOTHESIS, DIR_RESTRICTION, null));

        ExperienceLedger.Applied r = ledger.apply(7L, 1, 100L, List.of(
                new ExperienceLedger.Classify(5L, List.of(mark(MARK_UNDER, 42L), mark(MARK_MISCITED, 43L))),
                new ExperienceLedger.Classify(6L, List.of(mark(MARK_MISCITED, 42L)))), AgentLang.ZH, TRADES);

        assertThat(r.applied()).isEqualTo(2);
        ArgumentCaptor<AiTraderExperienceTrade> rows = ArgumentCaptor.forClass(AiTraderExperienceTrade.class);
        verify(tradeMapper, times(3)).insertIgnore(rows.capture());
        AiTraderExperienceTrade first = rows.getAllValues().getFirst();
        assertThat(first.getExperienceId()).isEqualTo(5L);
        assertThat(first.getPositionId()).isEqualTo(42L);
        assertThat(first.getReviewId()).isEqualTo(100L);
        assertThat(first.getMark()).isEqualTo(MARK_UNDER);
        assertThat(first.getNet()).isEqualByComparingTo("12.50");
        assertThat(first.getClosedDay()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(first.getSegmentNo()).isEqualTo(2);
        assertThat(rows.getAllValues()).extracting(AiTraderExperienceTrade::getMark)
                .containsExactly(MARK_UNDER, MARK_MISCITED, MARK_MISCITED);
    }

    /** 整行丢弃：标记用错种类、有一笔不在本期、发现、同一条的第二行；丢的那几行一笔都不记 */
    @Test
    void classifyDropsTheWholeLineWhenAnyPartIsWrong() {
        when(expMapper.selectById(5L)).thenReturn(exp(5L, KIND_HYPOTHESIS, DIR_OPPORTUNITY, "BREAKOUT"));
        when(expMapper.selectById(6L)).thenReturn(exp(6L, KIND_GAP, DIR_EXECUTION, null));
        when(expMapper.selectById(8L)).thenReturn(exp(8L, KIND_FINDING, DIR_EXECUTION, null));
        when(expMapper.selectById(9L)).thenReturn(exp(9L, KIND_HYPOTHESIS, DIR_OPPORTUNITY, null));

        ExperienceLedger.Applied r = ledger.apply(7L, 1, 100L, List.of(
                // 假设用了守
                new ExperienceLedger.Classify(5L, List.of(mark(MARK_UNDER, 42L), mark(MARK_KEPT, 43L))),
                // 同一条的第二行：头一行丢了也不再认
                new ExperienceLedger.Classify(5L, List.of(mark(MARK_UNDER, 42L))),
                // 执行缺口用了名下
                new ExperienceLedger.Classify(6L, List.of(mark(MARK_UNDER, 42L))),
                // 发现不归类
                new ExperienceLedger.Classify(8L, List.of(mark(MARK_UNDER, 42L))),
                // 99 不在本期
                new ExperienceLedger.Classify(9L, List.of(mark(MARK_UNDER, 42L), mark(MARK_UNDER, 99L)))), AgentLang.ZH, TRADES);

        assertThat(r.applied()).isZero();
        assertThat(r.dropped()).isEqualTo(5);
        verify(tradeMapper, never()).insertIgnore(any());
    }

    /** 「无」：条目对就算生效，一笔都不记 */
    @Test
    void noneIsAppliedWithoutRecordingAnything() {
        when(expMapper.selectById(5L)).thenReturn(exp(5L, KIND_RULE, DIR_EXECUTION, null));

        ExperienceLedger.Applied r = ledger.apply(7L, 1, 100L, List.of(
                new ExperienceLedger.Classify(5L, List.of())), AgentLang.ZH, TRADES);

        assertThat(r.applied()).isEqualTo(1);
        verify(tradeMapper, never()).insertIgnore(any());
    }

    // ==================== 新增 / 发现 / 删除 ====================

    @Test
    void addRespectsCapsAndValidation() {
        List<AiTraderExperience> actives = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            actives.add(exp(10L + i, KIND_HYPOTHESIS, DIR_EXECUTION, "PULLBACK"));
        }
        actives.add(exp(20L, KIND_HYPOTHESIS, DIR_RESTRICTION, "BREAKOUT"));
        actives.add(exp(21L, KIND_RULE, DIR_RESTRICTION, null));
        when(expMapper.selectList(any())).thenReturn(actives);

        ExperienceLedger.Applied r = ledger.apply(7L, 1, 100L, List.of(
                // 同类已满 3 条
                add("PULLBACK", AiTraderExperience.SLOT_ENTRY, DIR_EXECUTION, "x", "e"),
                // 限制型已满 2 条
                add("BREAKOUT", AiTraderExperience.SLOT_RISK, DIR_RESTRICTION, "x", "e"),
                // playType 不合法
                add("MOON", AiTraderExperience.SLOT_ENTRY, DIR_OPPORTUNITY, "x", "e"),
                // 没证据
                add("BREAKOUT", AiTraderExperience.SLOT_ENTRY, DIR_OPPORTUNITY, "x", " "),
                // 持仓途中怎么处理的格子不收
                add("BREAKOUT", AiTraderExperience.SLOT_FAIL_DEF, DIR_EXECUTION, "2 根 1h 收不出新高即判失败", "ETH 09:32"),
                add(null, AiTraderExperience.SLOT_PROFIT_HANDLE, DIR_EXECUTION, "浮盈 1R 后止损挪到成本", "SOL 23:33"),
                // 合法：执行缺口格子 → GAP，正文截到 80 字
                add("BREAKOUT", AiTraderExperience.SLOT_EXEC, DIR_EXECUTION, "止".repeat(100), "SOL 10-05 0.58% < 1.0%"),
                // 合法：普通假设
                add("breakout", AiTraderExperience.SLOT_ENTRY, DIR_OPPORTUNITY, "回踩前高不破再挂多", "ETH 09:32")),
                AgentLang.ZH, TRADES);

        assertThat(r.applied()).isEqualTo(2);
        assertThat(r.dropped()).isEqualTo(6);
        // 名额满了丢的那两条单独带出来，不合法的四条不在里面
        assertThat(r.overCap()).extracting(ExperienceLedger.Add::playType).containsExactly("PULLBACK", "BREAKOUT");
        ArgumentCaptor<AiTraderExperience> cap = ArgumentCaptor.forClass(AiTraderExperience.class);
        verify(expMapper, times(2)).insert(cap.capture());
        AiTraderExperience gap = cap.getAllValues().get(0);
        assertThat(gap.getKind()).isEqualTo(KIND_GAP);
        assertThat(gap.getText()).hasSize(80);
        assertThat(gap.getCreatedReviewId()).isEqualTo(100L);
        assertThat(gap.getStatus()).isEqualTo(STATUS_ACTIVE);
        assertThat(gap.getSegmentNo()).isZero();
        assertThat(gap.getIdleReviews()).isZero();
        AiTraderExperience hyp = cap.getAllValues().get(1);
        assertThat(hyp.getKind()).isEqualTo(KIND_HYPOTHESIS);
        assertThat(hyp.getPlayType()).isEqualTo("BREAKOUT");
    }

    /** 新增带它末尾列的本期单子（假设带名下、执行缺口带守破）；来源是本局条目才记；标记用错、有一笔不在本期的整条不加 */
    @Test
    void addRecordsItsTradesAndOnlyASourceFromThisRound() {
        when(expMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(expMapper.selectById(3L)).thenReturn(exp(3L, KIND_RULE, DIR_OPPORTUNITY, "BREAKOUT"));
        AiTraderExperience lastRound = exp(4L, KIND_HYPOTHESIS, DIR_OPPORTUNITY, "BREAKOUT");
        lastRound.setRoundNo(0);
        when(expMapper.selectById(4L)).thenReturn(lastRound);
        long[] ids = {20L};
        when(expMapper.insert(any(AiTraderExperience.class))).thenAnswer(inv -> {
            ((AiTraderExperience) inv.getArgument(0)).setId(ids[0]++);
            return 1;
        });

        ExperienceLedger.Applied r = ledger.apply(7L, 1, 100L, List.of(
                new ExperienceLedger.Add("BREAKOUT", AiTraderExperience.SLOT_ENTRY, DIR_RESTRICTION,
                        "跌破颈线后不市价追", "HYPE 4 笔 -783", List.of(mark(MARK_UNDER, 42L)), 3L),
                new ExperienceLedger.Add(null, AiTraderExperience.SLOT_EXEC, DIR_EXECUTION,
                        "开仓设止损时 → 不小于 0.75 个 1h ATR", "43 只有 0.62", List.of(mark(MARK_BROKEN, 43L)), 4L),
                // 假设带了守
                new ExperienceLedger.Add("PULLBACK", AiTraderExperience.SLOT_ENTRY, DIR_OPPORTUNITY,
                        "x", "e", List.of(mark(MARK_KEPT, 42L)), null),
                // 99 不在本期
                new ExperienceLedger.Add("PULLBACK", AiTraderExperience.SLOT_ENTRY, DIR_OPPORTUNITY,
                        "x", "e", List.of(mark(MARK_UNDER, 99L)), null)), AgentLang.ZH, TRADES);

        assertThat(r.applied()).isEqualTo(2);
        ArgumentCaptor<AiTraderExperience> added = ArgumentCaptor.forClass(AiTraderExperience.class);
        verify(expMapper, times(2)).insert(added.capture());
        assertThat(added.getAllValues().get(0).getSourceId()).isEqualTo(3L);
        assertThat(added.getAllValues().get(1).getKind()).isEqualTo(KIND_GAP);
        // 来源是上一局的条目，不记
        assertThat(added.getAllValues().get(1).getSourceId()).isNull();
        ArgumentCaptor<AiTraderExperienceTrade> rows = ArgumentCaptor.forClass(AiTraderExperienceTrade.class);
        verify(tradeMapper, times(2)).insertIgnore(rows.capture());
        assertThat(rows.getAllValues())
                .extracting(AiTraderExperienceTrade::getExperienceId, AiTraderExperienceTrade::getMark,
                        AiTraderExperienceTrade::getSegmentNo, AiTraderExperienceTrade::getReviewId)
                .containsExactly(tuple(20L, MARK_UNDER, 0, 100L), tuple(21L, MARK_BROKEN, 0, 100L));
    }

    /** 发现：正文与证据按语言上限截断，不占现役上限 */
    @Test
    void findingIsCutToLanguageLimits() {
        when(expMapper.selectList(any())).thenReturn(new ArrayList<>());

        ledger.apply(7L, 1, 100L, List.of(new ExperienceLedger.Finding("长".repeat(300), "证".repeat(300))), AgentLang.ZH, TRADES);

        ArgumentCaptor<AiTraderExperience> cap = ArgumentCaptor.forClass(AiTraderExperience.class);
        verify(expMapper).insert(cap.capture());
        assertThat(cap.getValue().getKind()).isEqualTo(KIND_FINDING);
        assertThat(cap.getValue().getText()).hasSize(80);
        assertThat(cap.getValue().getEvidence()).hasSize(60);
    }

    /** 删除：没钉住的现役假设、执行缺口退役并记理由；规律、钉住的不动 */
    @Test
    void deleteRetiresUnpinnedHypothesesAndGaps() {
        AiTraderExperience hyp = exp(15L, KIND_HYPOTHESIS, DIR_EXECUTION, null);
        AiTraderExperience gap = exp(16L, KIND_GAP, DIR_EXECUTION, null);
        AiTraderExperience rule = exp(17L, KIND_RULE, DIR_EXECUTION, null);
        AiTraderExperience pinned = exp(18L, KIND_HYPOTHESIS, DIR_EXECUTION, null);
        pinned.setPinned(true);
        for (AiTraderExperience e : List.of(hyp, gap, rule, pinned)) {
            when(expMapper.selectById(e.getId())).thenReturn(e);
        }
        when(expMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(hyp, gap, rule, pinned)));

        ExperienceLedger.Applied r = ledger.apply(7L, 1, 100L, List.of(
                new ExperienceLedger.Delete(15L, "与主人指令第五节重复"),
                new ExperienceLedger.Delete(16L, "指令里那条硬线已经删了"),
                new ExperienceLedger.Delete(17L, "不想要了"),
                new ExperienceLedger.Delete(18L, "重复")), AgentLang.ZH, TRADES);

        assertThat(r.applied()).isEqualTo(2);
        assertThat(hyp.getStatus()).isEqualTo(STATUS_RETIRED);
        assertThat(hyp.getRetireReason()).isEqualTo("与主人指令第五节重复");
        assertThat(hyp.getRetiredReviewId()).isEqualTo(100L);
        assertThat(gap.getStatus()).isEqualTo(STATUS_RETIRED);
        assertThat(rule.getStatus()).isEqualTo(STATUS_ACTIVE);
        assertThat(pinned.getStatus()).isEqualTo(STATUS_ACTIVE);
        assertThat(updateSets()).anyMatch(s -> s.contains("status=") && s.contains("retire_reason="));
    }

    /** 新增排到最后落账：同一篇里写在它后面的删除，也先把名额腾出来 */
    @Test
    void deleteInSameReviewFreesQuotaForAnAddWrittenBeforeIt() {
        AiTraderExperience rule = exp(20L, KIND_RULE, DIR_RESTRICTION, "PULLBACK");
        AiTraderExperience hyp = exp(21L, KIND_HYPOTHESIS, DIR_RESTRICTION, null);
        when(expMapper.selectById(21L)).thenReturn(hyp);
        when(expMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(rule, hyp)));

        ExperienceLedger.Applied r = ledger.apply(7L, 1, 100L, List.of(
                add("BREAKOUT", AiTraderExperience.SLOT_ENTRY, DIR_RESTRICTION, "破位后不市价追", "HYPE 4 笔净 -783"),
                new ExperienceLedger.Delete(21L, "给新的一条腾名额")), AgentLang.ZH, TRADES);

        assertThat(r.applied()).isEqualTo(2);
        assertThat(r.overCap()).isEmpty();
        assertThat(hyp.getStatus()).isEqualTo(STATUS_RETIRED);
        verify(expMapper).insert(any(AiTraderExperience.class));
    }

    // ==================== 结账 ====================

    /**
     * 转正：入场条件、风险限制的假设，当前段名下满 5 笔、来自 3 个平仓日、合计站在它这边（机会型看正数、限制型看负数）；
     * 只差天数的、合计正好是 0 的、单子都在旧段的、持仓途中怎么处理那几个格子的不转。转正后另起一段。
     * 这几条本篇都有新单子记进名下
     */
    @Test
    void hypothesisPromotesOnFiveTradesThreeDaysAndNetOnItsSide() {
        AiTraderExperience opportunity = exp(1L, KIND_HYPOTHESIS, DIR_OPPORTUNITY, "BREAKOUT");
        opportunity.setSlot(AiTraderExperience.SLOT_ENTRY);
        AiTraderExperience restriction = exp(2L, KIND_HYPOTHESIS, DIR_RESTRICTION, "BREAKOUT");
        AiTraderExperience twoDays = exp(3L, KIND_HYPOTHESIS, DIR_OPPORTUNITY, "PULLBACK");
        AiTraderExperience flat = exp(4L, KIND_HYPOTHESIS, DIR_OPPORTUNITY, "PULLBACK");
        AiTraderExperience oldSegment = exp(5L, KIND_HYPOTHESIS, DIR_OPPORTUNITY, null);
        oldSegment.setSegmentNo(1);
        AiTraderExperience handling = exp(6L, KIND_HYPOTHESIS, DIR_EXECUTION, null);
        handling.setSlot(AiTraderExperience.SLOT_PROFIT_HANDLE);
        when(expMapper.selectList(any())).thenReturn(List.of(opportunity, restriction, twoDays, flat, oldSegment, handling));
        int[] threeDays = {10, 11, 12, 12, 12};
        List<AiTraderExperienceTrade> rows = new ArrayList<>();
        rows.addAll(lastNow(underRows(1L, 0, threeDays, "30.00", "-10.00", "5.00", "5.00", "1.00")));
        rows.addAll(lastNow(underRows(2L, 0, threeDays, "-30.00", "10.00", "-5.00", "-5.00", "-1.00")));
        rows.addAll(lastNow(underRows(3L, 0, new int[]{10, 10, 11, 11, 11}, "30.00", "10.00", "5.00", "5.00", "1.00")));
        rows.addAll(lastNow(underRows(4L, 0, threeDays, "10.00", "-10.00", "5.00", "-5.00", "0.00")));
        rows.addAll(underRows(5L, 0, threeDays, "30.00", "10.00", "5.00", "5.00", "1.00"));
        rows.addAll(lastNow(underRows(5L, 1, new int[]{13}, "1.00")));
        rows.addAll(lastNow(underRows(6L, 0, threeDays, "30.00", "10.00", "5.00", "5.00", "1.00")));
        when(tradeMapper.selectList(any())).thenReturn(rows);

        List<ExperienceLedger.Change> changes = ledger.settle(7L, 1, 100L, true, AgentLang.ZH);

        assertThat(changes).extracting(ExperienceLedger.Change::kind, c -> c.entry().getId())
                .containsExactly(tuple(ExperienceLedger.ChangeKind.PROMOTED, 1L), tuple(ExperienceLedger.ChangeKind.PROMOTED, 2L));
        assertThat(changes.getFirst().tally().segmentUnder()).isEqualTo(5);
        assertThat(opportunity.getKind()).isEqualTo(KIND_RULE);
        assertThat(opportunity.getSegmentNo()).isEqualTo(1);
        assertThat(restriction.getKind()).isEqualTo(KIND_RULE);
        assertThat(List.of(twoDays, flat, oldSegment, handling)).allMatch(e -> KIND_HYPOTHESIS.equals(e.getKind()));
        assertThat(updateSets()).anyMatch(s -> s.contains("kind=") && s.contains("segment_no="));
    }

    /** 降级：规律转正后这一段名下满 5 笔、合计反过来；钉住的、不满 5 笔的、合计还站在它这边的不降。这几条本篇都有新单子记进名下 */
    @Test
    void ruleDemotesWhenItsSegmentTurnsAgainstIt() {
        AiTraderExperience falling = exp(1L, KIND_RULE, DIR_OPPORTUNITY, "BREAKOUT");
        AiTraderExperience pinned = exp(2L, KIND_RULE, DIR_OPPORTUNITY, "BREAKOUT");
        pinned.setPinned(true);
        AiTraderExperience few = exp(3L, KIND_RULE, DIR_RESTRICTION, null);
        AiTraderExperience holding = exp(4L, KIND_RULE, DIR_RESTRICTION, null);
        for (AiTraderExperience e : List.of(falling, pinned, few, holding)) {
            e.setSegmentNo(1);
        }
        when(expMapper.selectList(any())).thenReturn(List.of(falling, pinned, few, holding));
        int[] days = {10, 11, 12, 13, 14};
        List<AiTraderExperienceTrade> rows = new ArrayList<>();
        // 转正前那一段赚得多，不算进这一段
        rows.addAll(underRows(1L, 0, days, "100.00", "100.00", "100.00", "100.00", "100.00"));
        rows.addAll(lastNow(underRows(1L, 1, days, "-10.00", "5.00", "-10.00", "5.00", "-1.00")));
        rows.addAll(lastNow(underRows(2L, 1, days, "-10.00", "5.00", "-10.00", "5.00", "-1.00")));
        rows.addAll(lastNow(underRows(3L, 1, new int[]{10, 11, 12, 13}, "10.00", "5.00", "10.00", "5.00")));
        rows.addAll(lastNow(underRows(4L, 1, days, "-10.00", "5.00", "-10.00", "5.00", "-1.00")));
        when(tradeMapper.selectList(any())).thenReturn(rows);

        List<ExperienceLedger.Change> changes = ledger.settle(7L, 1, 100L, true, AgentLang.ZH);

        assertThat(changes).extracting(ExperienceLedger.Change::kind, c -> c.entry().getId())
                .containsExactly(tuple(ExperienceLedger.ChangeKind.DEMOTED, 1L));
        assertThat(falling.getKind()).isEqualTo(KIND_HYPOTHESIS);
        assertThat(falling.getSegmentNo()).isEqualTo(2);
        assertThat(List.of(pinned, few, holding)).allMatch(e -> KIND_RULE.equals(e.getKind()));
    }

    /**
     * 转正、降级只判本篇有新单子记进名下的：数字够了但这篇没有新单子的不转也不降，只有引用不符的也不算；
     * 本篇新建的不转，哪怕新增行带的单子已经够数
     */
    @Test
    void promotionAndDemotionNeedANewTradeUnderItAndNotTheReviewThatAddedIt() {
        AiTraderExperience quiet = exp(1L, KIND_HYPOTHESIS, DIR_OPPORTUNITY, "BREAKOUT");
        AiTraderExperience miscited = exp(2L, KIND_HYPOTHESIS, DIR_OPPORTUNITY, "BREAKOUT");
        AiTraderExperience fresh = exp(3L, KIND_HYPOTHESIS, DIR_OPPORTUNITY, "PULLBACK");
        fresh.setCreatedReviewId(100L);
        AiTraderExperience losing = exp(4L, KIND_RULE, DIR_OPPORTUNITY, "PULLBACK");
        when(expMapper.selectList(any())).thenReturn(List.of(quiet, miscited, fresh, losing));
        int[] threeDays = {10, 11, 12, 12, 12};
        List<AiTraderExperienceTrade> rows = new ArrayList<>();
        rows.addAll(underRows(1L, 0, threeDays, "30.00", "10.00", "5.00", "5.00", "1.00"));
        rows.addAll(underRows(2L, 0, threeDays, "30.00", "10.00", "5.00", "5.00", "1.00"));
        rows.add(now(row(2L, 299L, MARK_MISCITED, "-50.00", 13, 0)));
        // 新增行带的五笔，都是这篇复盘记的
        rows.addAll(underRows(3L, 0, threeDays, "30.00", "10.00", "5.00", "5.00", "1.00").stream()
                .map(ExperienceLedgerTest::now).toList());
        rows.addAll(underRows(4L, 0, threeDays, "-30.00", "-10.00", "-5.00", "-5.00", "-1.00"));
        when(tradeMapper.selectList(any())).thenReturn(rows);

        assertThat(ledger.settle(7L, 1, 100L, true, AgentLang.ZH)).isEmpty();
        assertThat(List.of(quiet, miscited, fresh)).allMatch(e -> KIND_HYPOTHESIS.equals(e.getKind()));
        assertThat(losing.getKind()).isEqualTo(KIND_RULE);
    }

    /**
     * 闲置：本篇有新单子记进名下、守、破的清零（钉住的也清）；以前记的、本篇只有引用不符的不算，加一；
     * 满 10 篇退役并写固定理由；钉住的、本篇新建的、已转正的限制型不加一（限制型没有新单子＝被守住了）；限制型的假设照常数
     */
    @Test
    void idleReviewsResetCountUpAndRetireAtTen() {
        AiTraderExperience busy = exp(1L, KIND_HYPOTHESIS, DIR_OPPORTUNITY, "BREAKOUT");
        busy.setIdleReviews(4);
        AiTraderExperience quiet = exp(2L, KIND_HYPOTHESIS, DIR_OPPORTUNITY, "BREAKOUT");
        quiet.setIdleReviews(3);
        AiTraderExperience miscited = exp(3L, KIND_HYPOTHESIS, DIR_OPPORTUNITY, null);
        miscited.setIdleReviews(2);
        AiTraderExperience dormant = exp(4L, KIND_GAP, DIR_EXECUTION, null);
        dormant.setIdleReviews(9);
        AiTraderExperience kept = exp(5L, KIND_GAP, DIR_EXECUTION, null);
        kept.setIdleReviews(6);
        AiTraderExperience pinned = exp(6L, KIND_RULE, DIR_OPPORTUNITY, null);
        pinned.setPinned(true);
        pinned.setIdleReviews(9);
        AiTraderExperience pinnedBusy = exp(7L, KIND_HYPOTHESIS, DIR_OPPORTUNITY, null);
        pinnedBusy.setPinned(true);
        pinnedBusy.setIdleReviews(5);
        AiTraderExperience fresh = exp(8L, KIND_HYPOTHESIS, DIR_OPPORTUNITY, null);
        fresh.setCreatedReviewId(100L);
        AiTraderExperience guard = exp(9L, KIND_RULE, DIR_RESTRICTION, "BREAKOUT");
        guard.setIdleReviews(9);
        AiTraderExperience guardBroken = exp(10L, KIND_RULE, DIR_RESTRICTION, "PULLBACK");
        guardBroken.setIdleReviews(5);
        AiTraderExperience restrictionHyp = exp(11L, KIND_HYPOTHESIS, DIR_RESTRICTION, null);
        restrictionHyp.setIdleReviews(9);
        when(expMapper.selectList(any())).thenReturn(List.of(busy, quiet, miscited, dormant, kept, pinned, pinnedBusy,
                fresh, guard, guardBroken, restrictionHyp));
        when(tradeMapper.selectList(any())).thenReturn(List.of(
                now(row(1L, 41L, MARK_UNDER, "10.00", 10, 0)),
                row(2L, 42L, MARK_UNDER, "10.00", 9, 0),
                now(row(3L, 43L, MARK_MISCITED, "10.00", 10, 0)),
                now(row(5L, 45L, MARK_KEPT, "10.00", 10, 0)),
                now(row(7L, 47L, MARK_UNDER, "10.00", 10, 0)),
                now(row(10L, 48L, MARK_UNDER, "-10.00", 10, 0))));

        List<ExperienceLedger.Change> changes = ledger.settle(7L, 1, 100L, true, AgentLang.ZH);

        assertThat(busy.getIdleReviews()).isZero();
        assertThat(quiet.getIdleReviews()).isEqualTo(4);
        assertThat(miscited.getIdleReviews()).isEqualTo(3);
        assertThat(dormant.getStatus()).isEqualTo(STATUS_RETIRED);
        assertThat(dormant.getRetireReason()).isEqualTo("连续 10 篇复盘名下没有新单子，代码退役");
        assertThat(dormant.getRetiredReviewId()).isEqualTo(100L);
        assertThat(kept.getIdleReviews()).isZero();
        assertThat(pinned.getStatus()).isEqualTo(STATUS_ACTIVE);
        assertThat(pinned.getIdleReviews()).isEqualTo(9);
        assertThat(pinnedBusy.getIdleReviews()).isZero();
        assertThat(fresh.getIdleReviews()).isZero();
        assertThat(guard.getStatus()).isEqualTo(STATUS_ACTIVE);
        assertThat(guard.getIdleReviews()).isEqualTo(9);
        assertThat(guardBroken.getIdleReviews()).isZero();
        assertThat(restrictionHyp.getStatus()).isEqualTo(STATUS_RETIRED);
        assertThat(changes).extracting(ExperienceLedger.Change::kind, c -> c.entry().getId())
                .containsExactly(tuple(ExperienceLedger.ChangeKind.RETIRED, 4L), tuple(ExperienceLedger.ChangeKind.RETIRED, 11L));
        // 按列写：清零四条、加一两条、退役两条
        assertThat(updateSets()).hasSize(8);
    }

    /** 本期一笔已了结的单子都没有：闲置不加也不清零，什么都不判，名下单子都不用查 */
    @Test
    void reviewWithoutClosedTradesLeavesTheLedgerAlone() {
        AiTraderExperience e = exp(1L, KIND_HYPOTHESIS, DIR_OPPORTUNITY, "BREAKOUT");
        e.setIdleReviews(9);
        when(expMapper.selectList(any())).thenReturn(List.of(e));

        assertThat(ledger.settle(7L, 1, 100L, false, AgentLang.ZH)).isEmpty();

        assertThat(e.getIdleReviews()).isEqualTo(9);
        assertThat(e.getStatus()).isEqualTo(STATUS_ACTIVE);
        assertThat(updateSets()).isEmpty();
        verify(tradeMapper, never()).selectList(any());
    }

    // ==================== 账 ====================

    /** 一条的账：名下算笔数、赚亏（正好 0 两边都不算）、合计、平仓日，当前段另算一份；守、破、引用不符只数笔数 */
    @Test
    void tallyCountsUnderMoneyDaysSegmentAndOtherMarks() {
        ExperienceLedger.Tally t = ExperienceLedger.tally(1, List.of(
                row(1L, 41L, MARK_UNDER, "10.00", 10, 0),
                row(1L, 42L, MARK_UNDER, "-4.00", 10, 1),
                row(1L, 43L, MARK_UNDER, "0.00", 11, 1),
                row(1L, 44L, MARK_MISCITED, "7.00", 11, 1),
                row(1L, 45L, MARK_KEPT, "1.00", 12, 0),
                row(1L, 46L, MARK_BROKEN, "-1.00", 12, 0),
                row(1L, 47L, MARK_BROKEN, "-2.00", 12, 0)));

        assertThat(t).isEqualTo(new ExperienceLedger.Tally(3, 1, 1, new BigDecimal("6.00"), 2,
                2, new BigDecimal("-4.00"), 2, 1, 2, 1));
    }

    /** 主人标了不算数的单子在账里不算 */
    @Test
    void talliesSkipTradesTheOwnerMarkedStale() {
        AiTraderExperience e = exp(1L, KIND_RULE, DIR_OPPORTUNITY, null);
        when(tradeMapper.selectList(any())).thenReturn(List.of(
                row(1L, 41L, MARK_UNDER, "10.00", 10, 0), row(1L, 42L, MARK_UNDER, "-4.00", 11, 0)));
        when(planStore.stalePositionIds(7L)).thenReturn(Set.of(42L));

        ExperienceLedger.Tally t = ledger.tallies(List.of(e)).get(1L);

        assertThat(t.under()).isEqualTo(1);
        assertThat(t.net()).isEqualByComparingTo("10");
    }

    /** 金额：两位小数（四舍五入），零和正数带 + */
    @Test
    void moneyHasTwoDecimalsAndASign() {
        assertThat(ExperienceLedger.money(new BigDecimal("19.745"))).isEqualTo("+19.75");
        assertThat(ExperienceLedger.money(new BigDecimal("-4"))).isEqualTo("-4.00");
        assertThat(ExperienceLedger.money(BigDecimal.ZERO)).isEqualTo("+0.00");
    }

    // ==================== 渲染 ====================

    /**
     * 规律按方向写照做或违反，引用不符大于 0 才带；执行缺口破满 2 笔才出现，写碰到几次、做到几次；
     * 假设、发现、只破过 1 笔的执行缺口不出现；数字都是累计（跨段）
     */
    @Test
    void renderShowsRulesAndGapsBrokenTwiceWithCumulativeTallies() {
        AiTraderExperience follow = exp(1L, KIND_RULE, DIR_OPPORTUNITY, "BREAKOUT");
        follow.setText("破位后挂限价等反抽");
        follow.setSegmentNo(1);
        AiTraderExperience restrict = exp(2L, KIND_RULE, DIR_RESTRICTION, "BREAKOUT");
        restrict.setText("破位后不市价追");
        AiTraderExperience gap = exp(3L, KIND_GAP, DIR_EXECUTION, null);
        gap.setText("止损不小于 0.75 个 1h ATR");
        AiTraderExperience gapOnce = exp(4L, KIND_GAP, DIR_EXECUTION, null);
        AiTraderExperience hyp = exp(5L, KIND_HYPOTHESIS, DIR_OPPORTUNITY, "BREAKOUT");
        AiTraderExperience finding = exp(6L, KIND_FINDING, DIR_EXECUTION, null);
        when(expMapper.selectList(any())).thenReturn(List.of(follow, restrict, gap, gapOnce, hyp, finding));
        when(tradeMapper.selectList(any())).thenReturn(List.of(
                row(1L, 11L, MARK_UNDER, "30.00", 10, 0), row(1L, 12L, MARK_UNDER, "-10.25", 11, 1),
                row(2L, 21L, MARK_UNDER, "-20.00", 10, 0), row(2L, 22L, MARK_MISCITED, "5.00", 11, 0),
                row(3L, 31L, MARK_KEPT, "1.00", 10, 0), row(3L, 32L, MARK_BROKEN, "-1.00", 11, 0),
                row(3L, 33L, MARK_BROKEN, "-2.00", 12, 0),
                row(4L, 41L, MARK_BROKEN, "-1.00", 10, 0),
                row(5L, 51L, MARK_UNDER, "9.00", 10, 0)));

        String text = ledger.render(7L, 1, AgentLang.ZH);

        assertThat(ledger.isRendered(text)).isTrue();
        assertThat(text).isEqualTo("""
                【经验账本】
                〔BREAKOUT〕
                #1 规律·机会型 ｜ 破位后挂限价等反抽 ｜ 照做 2 笔，赚 1 亏 1，合计 +19.75 USDT
                #2 规律·限制型 ｜ 破位后不市价追 ｜ 违反 1 笔，赚 0 亏 1，合计 -20.00 USDT ｜ 另有 1 次引用时情况不符
                〔通用〕
                #3 执行缺口 ｜ 止损不小于 0.75 个 1h ATR ｜ 碰到 3 次，做到 1 次
                """);
        assertThat(ledger.isRendered("【已验证纪律】\n1. 旧记忆")).isFalse();
    }

    /** 一条都看不到（只有假设、破了 1 笔的执行缺口、发现）：整段为 null */
    @Test
    void renderIsNullWhenNothingIsShown() {
        when(expMapper.selectList(any())).thenReturn(List.of(
                exp(1L, KIND_HYPOTHESIS, DIR_OPPORTUNITY, "BREAKOUT"),
                exp(2L, KIND_GAP, DIR_EXECUTION, null),
                exp(3L, KIND_FINDING, DIR_EXECUTION, null)));
        when(tradeMapper.selectList(any())).thenReturn(List.of(row(2L, 21L, MARK_BROKEN, "-1.00", 10, 0)));

        assertThat(ledger.render(7L, 1, AgentLang.EN)).isNull();
    }

    /** writeMemory：渲染文本按列写 ai_trader.memory；账本空了而 memory 是旧渲染文本时写 null 清空 */
    @Test
    void writeMemoryWritesRenderedTextByColumn() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setRoundNo(1);
        t.setMemory("【经验账本】\n〔PULLBACK〕\n#1 规律·限制型 ｜ 旧的 ｜ 违反 4 笔");
        when(expMapper.selectList(any())).thenReturn(List.of(exp(1L, KIND_RULE, DIR_RESTRICTION, "PULLBACK")), List.of());

        String first = ledger.writeMemory(t, AgentLang.ZH);
        String second = ledger.writeMemory(t, AgentLang.ZH);

        assertThat(first).startsWith("【经验账本】\n〔PULLBACK〕\n#1 规律·限制型 ｜ t1 ｜ 违反 0 笔，赚 0 亏 0，合计 +0.00 USDT");
        assertThat(second).isNull();
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<AiTrader>> cap = ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(traderMapper, times(2)).update(isNull(), cap.capture());
        assertThat(cap.getAllValues().get(0).getSqlSet()).contains("memory");
        assertThat(cap.getAllValues().get(0).getParamNameValuePairs()).containsValue(first);
        assertThat(cap.getAllValues().get(1).getParamNameValuePairs().containsValue(null)).isTrue();
    }

    /**
     * memory 还是没迁移的旧文本：本局账本一条都没有时不写库，旧文本留给迁移；
     * 账本里有了条目（迁移过了），哪怕 trader 一条都还看不到，也写 null，旧文本不再注入
     */
    @Test
    void writeMemoryKeepsLegacyTextOnlyWhileLedgerIsEmpty() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setRoundNo(1);
        t.setMemory("【已验证纪律】\n1. 旧记忆");
        when(expMapper.selectList(any())).thenReturn(List.of(), List.of(), List.of(exp(1L, KIND_HYPOTHESIS, DIR_OPPORTUNITY, "BREAKOUT")));

        assertThat(ledger.writeMemory(t, AgentLang.ZH)).isNull();
        verify(traderMapper, never()).update(any(), any());

        assertThat(ledger.writeMemory(t, AgentLang.ZH)).isNull();
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<AiTrader>> cap = ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(traderMapper).update(isNull(), cap.capture());
        assertThat(cap.getValue().getSqlSet()).contains("memory");
        assertThat(cap.getValue().getParamNameValuePairs().containsValue(null)).isTrue();
    }

    // ==================== 开新局 / 清理 / 主人操作 ====================

    /**
     * 开新局带入：条目复制到新局，种类、段号、钉住、状态、退役原因、引用计数照搬，来源指回旧行，闲置清零、不挂复盘行；
     * 名下单子行复制到新条目上，主人标了不算数的不带
     */
    @Test
    void carryOverCopiesEntriesWithTheirTradesExceptStaleOnes() {
        AiTraderExperience rule = exp(5L, KIND_RULE, DIR_EXECUTION, "PULLBACK");
        rule.setSegmentNo(1);
        rule.setIdleReviews(6);
        rule.setPinned(true);
        rule.setCitedOpen(3);
        rule.setCitedHold(1);
        AiTraderExperience gone = exp(6L, KIND_HYPOTHESIS, DIR_OPPORTUNITY, "BREAKOUT");
        gone.setStatus(STATUS_RETIRED);
        gone.setRetireReason("连续 10 篇复盘名下没有新单子，代码退役");
        when(expMapper.selectList(any())).thenReturn(List.of(rule, gone));
        when(tradeMapper.selectList(any())).thenReturn(List.of(
                row(5L, 42L, MARK_UNDER, "12.50", 10, 1), row(5L, 43L, MARK_UNDER, "-3.00", 11, 1)));
        when(planStore.stalePositionIds(7L)).thenReturn(Set.of(43L));
        long[] ids = {50L};
        when(expMapper.insert(any(AiTraderExperience.class))).thenAnswer(inv -> {
            ((AiTraderExperience) inv.getArgument(0)).setId(ids[0]++);
            return 1;
        });

        assertThat(ledger.carryOver(7L, 1, 2)).isEqualTo(2);

        ArgumentCaptor<AiTraderExperience> copy = ArgumentCaptor.forClass(AiTraderExperience.class);
        verify(expMapper, times(2)).insert(copy.capture());
        AiTraderExperience c = copy.getAllValues().get(0);
        assertThat(c.getRoundNo()).isEqualTo(2);
        assertThat(c.getKind()).isEqualTo(KIND_RULE);
        assertThat(c.getText()).isEqualTo(rule.getText());
        assertThat(c.getSegmentNo()).isEqualTo(1);
        assertThat(c.getIdleReviews()).isZero();
        assertThat(c.getPinned()).isTrue();
        assertThat(c.getCitedOpen()).isEqualTo(3);
        assertThat(c.getSourceId()).isEqualTo(5L);
        assertThat(c.getCreatedReviewId()).isNull();
        AiTraderExperience r = copy.getAllValues().get(1);
        assertThat(r.getStatus()).isEqualTo(STATUS_RETIRED);
        assertThat(r.getRetireReason()).isEqualTo("连续 10 篇复盘名下没有新单子，代码退役");
        assertThat(r.getSourceId()).isEqualTo(6L);
        ArgumentCaptor<AiTraderExperienceTrade> rows = ArgumentCaptor.forClass(AiTraderExperienceTrade.class);
        verify(tradeMapper).insertIgnore(rows.capture());
        assertThat(rows.getValue().getExperienceId()).isEqualTo(50L);
        assertThat(rows.getValue().getPositionId()).isEqualTo(42L);
        assertThat(rows.getValue().getSegmentNo()).isEqualTo(1);
    }

    /** 清旧局：条目与它们名下的单子一起删；没东西可删就一条 delete 都不发 */
    @Test
    void purgeDeletesEntriesWithTheirTrades() {
        when(expMapper.selectList(any())).thenReturn(List.of(
                exp(5L, KIND_RULE, DIR_EXECUTION, "PULLBACK"),
                exp(6L, KIND_FINDING, DIR_EXECUTION, null)));

        ledger.purgeRounds(7L, 2);

        verify(tradeMapper).delete(any());
        verify(expMapper).deleteByIds(List.of(5L, 6L));

        when(expMapper.selectList(any())).thenReturn(List.of());
        ledger.purgeAll(8L);
        verify(expMapper, never()).deleteByIds(List.of());
    }

    /** 主人复活：回到现役，闲置清零 */
    @Test
    void reviveResetsIdleReviews() {
        AiTraderExperience e = exp(5L, KIND_HYPOTHESIS, DIR_EXECUTION, null);
        e.setStatus(STATUS_RETIRED);
        when(expMapper.selectById(5L)).thenReturn(e);

        assertThat(ledger.revive(7L, 5L)).isTrue();
        assertThat(updateSets()).anyMatch(s -> s.contains("status=") && s.contains("idle_reviews="));
    }

    @Test
    void citationsCountOpenAndHoldSeparately() {
        AiTraderExperience e = exp(5L, KIND_RULE, DIR_EXECUTION, null);
        when(expMapper.selectById(5L)).thenReturn(e);
        when(expMapper.selectById(9L)).thenReturn(null);

        ledger.recordCitations(7L, 1, List.of(5L, 9L), true);
        ledger.recordCitations(7L, 1, List.of(5L), false);

        List<String> sets = updateSets();
        assertThat(sets).hasSize(2);
        assertThat(sets.get(0)).contains("cited_open = cited_open + 1");
        assertThat(sets.get(1)).contains("cited_hold = cited_hold + 1");
    }

    /** 到目前为止发给 expMapper 的按列更新，每条取 set 子句 */
    private List<String> updateSets() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<AiTraderExperience>> cap = ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(expMapper, atLeast(0)).update(isNull(), cap.capture());
        return cap.getAllValues().stream().map(LambdaUpdateWrapper::getSqlSet).toList();
    }

    private static ExperienceLedger.Mark mark(String mark, long positionId) {
        return new ExperienceLedger.Mark(mark, positionId);
    }

    private static ExperienceLedger.Add add(String playType, String slot, String direction, String text, String evidence) {
        return new ExperienceLedger.Add(playType, slot, direction, text, evidence, List.of(), null);
    }

    /** 一行名下单子，是之前那篇复盘（90）记的 */
    private static AiTraderExperienceTrade row(long experienceId, long positionId, String mark, String net, int day, int segment) {
        AiTraderExperienceTrade r = new AiTraderExperienceTrade();
        r.setExperienceId(experienceId);
        r.setPositionId(positionId);
        r.setReviewId(90L);
        r.setMark(mark);
        r.setNet(new BigDecimal(net));
        r.setClosedDay(LocalDate.of(2026, 11, day));
        r.setSegmentNo(segment);
        return r;
    }

    /** 改成这篇复盘（100）记的 */
    private static AiTraderExperienceTrade now(AiTraderExperienceTrade r) {
        r.setReviewId(100L);
        return r;
    }

    /** 最后一笔是这篇复盘记的 */
    private static List<AiTraderExperienceTrade> lastNow(List<AiTraderExperienceTrade> rows) {
        now(rows.getLast());
        return rows;
    }

    /** 一条经验名下的几笔：第 i 笔在 11 月 days[i] 号平仓、净盈亏 nets[i]，仓位编号按条目编出来 */
    private static List<AiTraderExperienceTrade> underRows(long experienceId, int segment, int[] days, String... nets) {
        List<AiTraderExperienceTrade> out = new ArrayList<>();
        for (int i = 0; i < nets.length; i++) {
            out.add(row(experienceId, experienceId * 100 + segment * 10 + i, MARK_UNDER, nets[i], days[i], segment));
        }
        return out;
    }

    private static AiTraderExperience exp(long id, String kind, String direction, String playType) {
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
        e.setCitedOpen(0);
        e.setCitedHold(0);
        e.setStatus(STATUS_ACTIVE);
        e.setSegmentNo(0);
        e.setIdleReviews(0);
        e.setPinned(false);
        return e;
    }
}
