package com.mawai.wiibagent.trader.trade;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.enums.AgentLang;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划与仓位怎么绑：唤醒开头的对账（结局怎么找、归档与续立、限价成交绑仓、挂单没了归档），
 * 以及唤醒中下单的新立与覆盖。一行计划对应一个仓位生命期。
 */
class TraderPlanStoreTest {

    private static final long BOUNDARY = 1785171600000L;
    /** 计划立案时刻：一小时前 */
    private static final long FILED = BOUNDARY - 3600_000L;
    private static final PromptCatalog PROMPTS = new PromptCatalog();

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderPlan.class);
    }

    private final AiTraderPlanMapper mapper = mock(AiTraderPlanMapper.class);
    private final TraderPlanStore store = new TraderPlanStore(mapper, PROMPTS, new TransactionTemplate(mock(PlatformTransactionManager.class)));

    @BeforeEach
    void lockedRows() {
        when(mapper.selectByIdForUpdate(anyLong())).thenAnswer(call ->
                mapper.selectList((Wrapper<AiTraderPlan>) null).stream()
                        .filter(p -> p.getId().equals(call.getArgument(0))).findFirst()
                        .orElseGet(() -> mapper.selectOne(null)));
    }

    private static LocalDateTime at(long ms) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault());
    }

    @Test
    void concurrentRevisionsMergeAfterLockedReread() throws Exception {
        var tx = mock(TransactionTemplate.class);
        var lock = new ReentrantLock();
        doAnswer(call -> {
            lock.lock();
            try {
                Consumer<TransactionStatus> work = call.getArgument(0);
                work.accept(mock(TransactionStatus.class));
                return null;
            } finally { lock.unlock(); }
        }).when(tx).executeWithoutResult(any());
        var row = new AtomicReference<>(livePlan("BTCUSDT", "LONG", 42L));
        row.get().setUpdatedAt(at(FILED));
        when(mapper.selectByIdForUpdate(21L)).thenAnswer(_ -> {
            assertThat(lock.isHeldByCurrentThread()).isTrue();
            AiTraderPlan fresh = new AiTraderPlan(); BeanUtils.copyProperties(row.get(), fresh); return fresh;
        });
        when(mapper.updateById(any(AiTraderPlan.class))).thenAnswer(call -> {
            AiTraderPlan updated = call.getArgument(0);
            assertThat(updated.getUpdatedAt()).isNull();
            row.set(updated);
            return 1;
        });
        var concurrent = new TraderPlanStore(mapper, PROMPTS, tx);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> concurrent.appendRevision(21L, 1L, "stop", "95→96", "preset"));
            var b = pool.submit(() -> concurrent.appendRevision(21L, 2L, "target", "110→112", "wake"));
            a.get(); b.get();
        }
        assertThat(MAPPER.readTree(row.get().getRevisionsJson())).hasSize(2);
        assertThat(row.get().getRevisionsJson()).contains("preset", "wake");
    }

    /** 一小时前立案的 LIVE 计划，行创建时刻同立案时刻 */
    private static AiTraderPlan livePlan(String symbol, String side, Long positionId) {
        AiTraderPlan p = new AiTraderPlan();
        p.setId(21L);
        p.setTraderId(7L);
        p.setRoundNo(1);
        p.setSymbol(symbol);
        p.setSide(side);
        p.setPlayType("BREAKOUT");
        p.setSignalsUsed("突破前高");
        p.setInvalidationCondition("1h收盘跌回98000下方");
        p.setStopLossPrice(new BigDecimal("95000"));
        p.setTakeProfitsJson("[{\"price\":110000,\"percent\":100}]");
        p.setStatus(AiTraderPlan.STATUS_LIVE);
        p.setOpenedWakeTime(FILED);
        p.setCreatedAt(at(FILED));
        p.setPositionId(positionId);
        return p;
    }

    private static FuturesPositionDTO position(long id, String symbol, String side, long createdMs) {
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setId(id);
        p.setSymbol(symbol);
        p.setSide(side);
        p.setCreatedAt(at(createdMs));
        p.setUpdatedAt(at(createdMs + 600_000L));
        p.setClosedPnl(new BigDecimal("12.5"));
        return p;
    }

    private static FuturesOrderResponse pendingOpen(String symbol, String side) {
        FuturesOrderResponse o = new FuturesOrderResponse();
        o.setOrderId(600L);
        o.setSymbol(symbol);
        o.setOrderSide("OPEN_" + side);
        return o;
    }

    private static Supplier<List<FuturesPositionDTO>> closed(FuturesPositionDTO... rows) {
        return () -> List.of(rows);
    }

    /** 已平仓位取数不该被碰时用它：碰了就炸 */
    private static final Supplier<List<FuturesPositionDTO>> NEVER = () -> {
        throw new AssertionError("已平仓位不该被查");
    };

    private static TraderPlanStore.Closed closedOf(TraderPlanStore.Event e) {
        assertThat(e).isInstanceOf(TraderPlanStore.Closed.class);
        return (TraderPlanStore.Closed) e;
    }

    private static PlanEntry entry(Long id, String status) {
        PlanEntry e = new PlanEntry();
        e.setOrderId(id); e.setStatus(status); e.setLimitPrice(new BigDecimal("99000"));
        e.setQuantity(new BigDecimal("0.01")); e.setStop(new BigDecimal("95000")); e.setPlacedAt(FILED);
        return e;
    }

    private static FuturesOrderResponse groupOrder(long id, String status) {
        FuturesOrderResponse o = pendingOpen("BTCUSDT", "LONG");
        o.setOrderId(id); o.setGroupId("group"); o.setStatus(status); o.setPositionId(42L);
        o.setLimitPrice(new BigDecimal("99000.00")); o.setQuantity(new BigDecimal("0.010"));
        o.setFilledPrice(new BigDecimal("98990")); o.setCommission(new BigDecimal("0.20")); o.setUpdatedAt(at(BOUNDARY - 1000));
        return o;
    }

    private AiTraderPlan grouped(Long positionId, PlanEntry... entries) {
        AiTraderPlan plan = livePlan("BTCUSDT", "LONG", positionId); plan.setOrderGroup("group");
        PlanEntry.save(plan, List.of(entries)); when(mapper.selectList(any())).thenReturn(List.of(plan));
        return plan;
    }

    @Test
    void cancelledEntriesShareOneRevisionPerPlan() {
        AiTraderPlan plan = grouped(null, entry(1L, "PENDING"), entry(2L, "PENDING"));
        when(mapper.selectOne(any())).thenReturn(plan);
        store.cancelEntries(7L, 1, List.of(groupOrder(1, "CANCELLED"), groupOrder(2, "CANCELLED")),
                "计划失效", BOUNDARY, AgentLang.ZH);
        assertThat(PlanEntry.read(plan)).allSatisfy(e -> {
            assertThat(e.getStatus()).isEqualTo("CANCELLED");
            assertThat(e.getClosedAt()).isEqualTo(BOUNDARY - 1000);
        });
        assertThat(MAPPER.readTree(plan.getRevisionsJson())).hasSize(1);
        assertThat(plan.getRevisionsJson()).contains("99000 × 0.01 / 99000 × 0.01", "计划失效");
        verify(mapper, times(1)).updateById(any(AiTraderPlan.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GROUP_CLOSED", "CROSS_LIQUIDATED"})
    void groupReconcileReportsEachFillAndCancellation(String cancelCode) {
        AiTraderPlan plan = grouped(null, entry(1L, "PENDING"), entry(2L, "PENDING"), entry(3L, "PENDING"));
        FuturesOrderResponse cancelled = groupOrder(2L, "CANCELLED"); cancelled.setCancelReason(cancelCode);
        AtomicInteger calls = new AtomicInteger();
        var result = store.reconcile(7, 1, List.of(position(42, "BTCUSDT", "LONG", FILED)), List.of(), NEVER,
                group -> { assertThat(group).isEqualTo("group"); calls.incrementAndGet();
                    return List.of(groupOrder(1, "FILLED"), cancelled, groupOrder(3, "PENDING")); }, BOUNDARY, AgentLang.ZH);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(result.events()).hasSize(2);
        assertThat(result.events().getFirst()).isInstanceOf(TraderPlanStore.EntryFilled.class);
        var event = (TraderPlanStore.EntryCancelled) result.events().getLast();
        assertThat(event.counts()).isEqualTo(new TraderPlanStore.EntryCounts(3, 1, 1));
        assertThat(event.entry().getReason()).isEqualTo("GROUP_CLOSED".equals(cancelCode) ? "仓位了结，平台撤单" : "全仓爆仓，平台撤单");
        assertThat(PlanEntry.read(plan).getFirst().getFee()).isEqualByComparingTo("0.20");
        assertThat(plan.getPositionId()).isEqualTo(42);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void unknownOrderIsClaimedOnlyWithOneMatch(int matches) {
        AiTraderPlan plan = grouped(null, entry(null, "UNKNOWN"));
        List<FuturesOrderResponse> orders = IntStream.range(0, matches)
                .mapToObj(i -> groupOrder(i + 1, "PENDING")).toList();
        var result = store.reconcile(7, 1, List.of(), List.of(), closed(), _ -> orders, BOUNDARY, AgentLang.EN);
        if (matches == 0) {
            assertThat(PlanEntry.read(plan)).isEmpty();
            assertThat(result.live()).isEmpty();
        } else {
            PlanEntry found = PlanEntry.read(plan).getFirst();
            assertThat(found.getStatus()).isEqualTo(matches == 1 ? "PENDING" : "UNKNOWN");
            assertThat(found.getOrderId()).isEqualTo(matches == 1 ? 1L : null);
            assertThat(result.live()).containsExactly(plan);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "PENDING"})
    void ambiguousEntriesOnlyWaitForActiveCandidates(String secondStatus) {
        AiTraderPlan plan = grouped(42L, entry(null, "UNKNOWN"));
        FuturesPositionDTO ended = position(42L, "BTCUSDT", "LONG", FILED + 1000);
        var result = store.reconcile(7, 1, List.of(), List.of(), closed(ended),
                _ -> List.of(groupOrder(1, "FILLED"), groupOrder(2, secondStatus)), BOUNDARY, AgentLang.EN);
        assertThat(PlanEntry.read(plan)).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo("UNKNOWN");
            assertThat(e.getOrderId()).isNull();
        });
        if ("PENDING".equals(secondStatus)) {
            assertThat(result.live()).containsExactly(plan);
            assertThat(result.events()).isEmpty();
        } else {
            assertThat(result.live()).isEmpty();
            assertThat(plan.getStatus()).isEqualTo(AiTraderPlan.STATUS_CLOSED);
            assertThat(result.events()).singleElement().satisfies(e -> assertThat(closedOf(e).position()).isSameAs(ended));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRIGGERED", "PROCESSING", "QUERY_FAILED"})
    void unresolvedEntriesAreNotArchived(String state) {
        AiTraderPlan plan = grouped(null, entry(1L, "PENDING"));
        var result = store.reconcile(7, 1, List.of(), List.of(), closed(), _ -> {
            if ("QUERY_FAILED".equals(state)) throw new IllegalStateException("offline");
            return List.of(groupOrder(1, state));
        }, BOUNDARY, AgentLang.ZH);
        assertThat(result.live()).containsExactly(plan);
        assertThat(result.events()).isEmpty();
        assertThat(PlanEntry.read(plan).getFirst().getStatus()).isEqualTo("PENDING");
    }

    @Test
    void fillBindsFromOrderWhenPositionSnapshotPredatesFill() {
        AiTraderPlan plan = grouped(null, entry(1L, "PENDING"));
        var result = store.reconcile(7, 1, List.of(), List.of(), closed(),
                _ -> List.of(groupOrder(1, "FILLED")), BOUNDARY, AgentLang.ZH);
        assertThat(plan.getPositionId()).isEqualTo(42);
        assertThat(result.live()).containsExactly(plan);
        assertThat(result.events()).singleElement().isInstanceOf(TraderPlanStore.EntryFilled.class);
    }

    @Test
    void closedGroupArchivesSettledEntriesAndCarriesOnlyStillPending() {
        AiTraderPlan plan = grouped(42L, entry(1L, "FILLED"), entry(2L, "PENDING"), entry(3L, "PENDING"));
        FuturesOrderResponse cancelled = groupOrder(2L, "CANCELLED"); cancelled.setCancelReason("GROUP_CLOSED");
        var result = store.reconcile(7, 1, List.of(), List.of(), closed(position(42, "BTCUSDT", "LONG", FILED)),
                _ -> List.of(groupOrder(1, "FILLED"), cancelled, groupOrder(3, "PENDING")), BOUNDARY, AgentLang.ZH);
        assertThat(plan.getStatus()).isEqualTo(AiTraderPlan.STATUS_CLOSED);
        assertThat(PlanEntry.read(plan)).extracting(PlanEntry::getStatus).containsExactly("FILLED", "CANCELLED");
        assertThat(result.live()).hasSize(1);
        assertThat(PlanEntry.read(result.live().getFirst())).singleElement().satisfies(e -> {
            assertThat(e.getOrderId()).isEqualTo(3); assertThat(e.getStatus()).isEqualTo("PENDING");
        });
    }

    @Test
    void reopeningInSameWakeSyncsOldEntryFactsBeforeArchiving() {
        AiTraderPlan old = grouped(42L, entry(1L, "FILLED"), entry(2L, "PENDING"));
        when(mapper.selectOne(any())).thenReturn(old);
        FuturesOrderResponse cancelled = groupOrder(2L, "CANCELLED"); cancelled.setCancelReason("GROUP_CLOSED");
        AiTraderPlan next = livePlan("BTCUSDT", "LONG", 43L); next.setId(null); next.setOrderGroup("new");
        store.open(next, _ -> List.of(cancelled), AgentLang.ZH);
        assertThat(PlanEntry.read(old)).extracting(PlanEntry::getStatus).containsExactly("FILLED", "CANCELLED");
        assertThat(PlanEntry.read(old).getLast().getReason()).isEqualTo("仓位了结，平台撤单");
        assertThat(old.getStatus()).isEqualTo(AiTraderPlan.STATUS_CLOSED);
    }

    // ==================== 页面读法：各档盖上订单的最新状态 ====================

    /** 两次唤醒之间成交、撤掉的档按订单显示，触发中的照旧，不写库 */
    @Test
    void overlayShowsSettledEntriesWithoutSaving() {
        AiTraderPlan plan = grouped(42L, entry(1L, "PENDING"), entry(2L, "PENDING"), entry(3L, "PENDING"), entry(4L, "PENDING"));
        store.overlayEntries(List.of(plan), Set.of(4L), _ -> List.of(
                groupOrder(1, "FILLED"), groupOrder(2, "CANCELLED"), groupOrder(3, "TRIGGERED"), groupOrder(4, "PENDING")));
        List<PlanEntry> entries = PlanEntry.read(plan);
        assertThat(entries).extracting(PlanEntry::getStatus).containsExactly("FILLED", "CANCELLED", "PENDING", "PENDING");
        assertThat(entries.getFirst().getFilledPrice()).isEqualByComparingTo("98990");
        assertThat(entries.getFirst().getClosedAt()).isEqualTo(BOUNDARY - 1000);
        verify(mapper, never()).updateById(any(AiTraderPlan.class));
    }

    /** 标着挂着的档都还在挂单里：不查订单组 */
    @Test
    void overlaySkipsOrderQueryWhileEntriesStillPending() {
        AiTraderPlan plan = grouped(42L, entry(1L, "FILLED"), entry(2L, "PENDING"));
        store.overlayEntries(List.of(plan), Set.of(2L), _ -> {
            throw new AssertionError("订单组不该被查");
        });
        assertThat(PlanEntry.read(plan)).extracting(PlanEntry::getStatus).containsExactly("FILLED", "PENDING");
    }

    // ==================== 对账：没事 / 成交 / 挂着 ====================

    /** 绑着的仓还在：什么都不动，已平仓位也不查 */
    @Test
    void boundPlanWithOpenPositionStaysLive() {
        when(mapper.selectList(any())).thenReturn(List.of(livePlan("BTCUSDT", "LONG", 42L)));

        TraderPlanStore.Reconcile r = store.reconcile(7L, 1,
                List.of(position(42L, "BTCUSDT", "LONG", FILED + 600_000L)), List.of(), NEVER, _ -> List.of(), BOUNDARY, AgentLang.ZH);

        assertThat(r.live()).hasSize(1);
        assertThat(r.events()).isEmpty();
        verify(mapper, never()).updateById(any(AiTraderPlan.class));
        verify(mapper, never()).insert(any(AiTraderPlan.class));
    }

    /** 限价单成交：键上有仓而计划没绑、间隙里也没别的结局，绑上落库，报成交 */
    @Test
    void limitFilledBindsPosition() {
        when(mapper.selectList(any())).thenReturn(List.of(livePlan("BTCUSDT", "LONG", null)));

        TraderPlanStore.Reconcile r = store.reconcile(7L, 1,
                List.of(position(42L, "BTCUSDT", "LONG", FILED + 600_000L)), List.of(), closed(), _ -> List.of(), BOUNDARY, AgentLang.ZH);

        assertThat(r.live()).hasSize(1);
        assertThat(r.live().getFirst().getPositionId()).isEqualTo(42L);
        assertThat(r.events()).singleElement().isInstanceOf(TraderPlanStore.Filled.class);
        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(mapper).updateById(cap.capture());
        assertThat(cap.getValue().getPositionId()).isEqualTo(42L);
        assertThat(cap.getValue().getStatus()).isEqualTo(AiTraderPlan.STATUS_LIVE);
        verify(mapper, never()).insert(any(AiTraderPlan.class));
    }

    /** 挂单还挂着、没成交、间隙里也没结局：计划原样活着 */
    @Test
    void pendingOrderPlanStaysAliveUnbound() {
        when(mapper.selectList(any())).thenReturn(List.of(livePlan("ETHUSDT", "SHORT", null)));

        TraderPlanStore.Reconcile r = store.reconcile(7L, 1,
                List.of(), List.of(pendingOpen("ETHUSDT", "SHORT")), closed(), _ -> List.of(), BOUNDARY, AgentLang.ZH);

        assertThat(r.live()).hasSize(1);
        assertThat(r.live().getFirst().getPositionId()).isNull();
        assertThat(r.events()).isEmpty();
        verify(mapper, never()).updateById(any(AiTraderPlan.class));
    }

    // ==================== 对账：绑着的仓了结了 ====================

    /** 绑着的仓不在了、键上也没别的：报结局、归档带了结时刻，仓位 id 留在归档行上给历史配对 */
    @Test
    void boundPositionGoneReportsCloseAndArchives() {
        when(mapper.selectList(any())).thenReturn(List.of(livePlan("BTCUSDT", "LONG", 42L)));
        FuturesPositionDTO closedPos = position(42L, "BTCUSDT", "LONG", FILED + 600_000L);

        TraderPlanStore.Reconcile r = store.reconcile(7L, 1, List.of(), List.of(), closed(closedPos), _ -> List.of(), BOUNDARY, AgentLang.ZH);

        assertThat(r.live()).isEmpty();
        assertThat(r.events()).hasSize(1);
        assertThat(closedOf(r.events().getFirst()).position()).isSameAs(closedPos);
        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(mapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(AiTraderPlan.STATUS_CLOSED);
        assertThat(cap.getValue().getClosedWakeTime()).isEqualTo(BOUNDARY);
        assertThat(cap.getValue().getPositionId()).isEqualTo(42L);
        verify(mapper, never()).insert(any(AiTraderPlan.class));
    }

    /**
     * 仓被止损、同键加仓单还挂着：这一轮就报结局，旧行归档，论点随挂单续立一行。
     * 续立行的生命期从本轮起算，不跟旧行重叠（stale 归属按生命期判）；createdAt 预设为本轮边界
     */
    @Test
    void boundGoneWithRestingOrderCarriesOver() {
        when(mapper.selectList(any())).thenReturn(List.of(livePlan("BTCUSDT", "LONG", 42L)));

        TraderPlanStore.Reconcile r = store.reconcile(7L, 1, List.of(), List.of(pendingOpen("BTCUSDT", "LONG")),
                closed(position(42L, "BTCUSDT", "LONG", FILED + 600_000L)), _ -> List.of(), BOUNDARY, AgentLang.ZH);

        assertThat(r.events()).singleElement().isInstanceOf(TraderPlanStore.Closed.class);
        ArgumentCaptor<AiTraderPlan> archived = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(mapper).updateById(archived.capture());
        assertThat(archived.getValue().getId()).isEqualTo(21L);
        assertThat(archived.getValue().getStatus()).isEqualTo(AiTraderPlan.STATUS_CLOSED);

        ArgumentCaptor<AiTraderPlan> next = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(mapper).insert(next.capture());
        AiTraderPlan n = next.getValue();
        assertThat(n.getStatus()).isEqualTo(AiTraderPlan.STATUS_LIVE);
        assertThat(n.getPositionId()).isNull();
        assertThat(n.getInvalidationCondition()).isEqualTo("1h收盘跌回98000下方");
        assertThat(n.getOpenedWakeTime()).isEqualTo(BOUNDARY);
        assertThat(n.getCreatedAt()).isEqualTo(at(BOUNDARY));
        assertThat(n.getRevisionsJson()).contains("续立").contains("42");
        assertThat(r.live()).containsExactly(n);
    }

    /** 旧仓死后加仓单成交成了新仓：先报旧仓结局，再报成交，续立行绑新仓 */
    @Test
    void boundGoneAndNewPositionOnKeyCarriesOverAndFills() {
        when(mapper.selectList(any())).thenReturn(List.of(livePlan("BTCUSDT", "LONG", 42L)));

        TraderPlanStore.Reconcile r = store.reconcile(7L, 1,
                List.of(position(43L, "BTCUSDT", "LONG", BOUNDARY - 600_000L)), List.of(),
                closed(position(42L, "BTCUSDT", "LONG", FILED + 600_000L)), _ -> List.of(), BOUNDARY, AgentLang.ZH);

        assertThat(r.events()).hasSize(2);
        assertThat(r.events().get(0)).isInstanceOf(TraderPlanStore.Closed.class);
        assertThat(r.events().get(1)).isInstanceOf(TraderPlanStore.Filled.class);
        assertThat(r.live()).hasSize(1);
        assertThat(r.live().getFirst().getPositionId()).isEqualTo(43L);
        assertThat(r.events().get(1).plan()).isSameAs(r.live().getFirst());
    }

    /** 一个间隙里旧仓止损、加仓单成交成新仓、新仓也止损：两笔都报，第二笔单独补一行归档行，一行一仓 */
    @Test
    void boundGoneThenAnotherOpenedAndClosedGetsOwnArchiveRow() {
        when(mapper.selectList(any())).thenReturn(List.of(livePlan("BTCUSDT", "LONG", 42L)));
        FuturesPositionDTO first = position(42L, "BTCUSDT", "LONG", FILED + 600_000L);
        FuturesPositionDTO second = position(43L, "BTCUSDT", "LONG", FILED + 2400_000L);

        TraderPlanStore.Reconcile r = store.reconcile(7L, 1, List.of(), List.of(), closed(second, first), _ -> List.of(), BOUNDARY, AgentLang.ZH);

        assertThat(r.live()).isEmpty();
        assertThat(r.events()).hasSize(2);
        assertThat(closedOf(r.events().get(0)).position()).isSameAs(first);
        assertThat(closedOf(r.events().get(1)).position()).isSameAs(second);
        ArgumentCaptor<AiTraderPlan> archived = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(mapper).updateById(archived.capture());
        assertThat(archived.getValue().getPositionId()).isEqualTo(42L);
        ArgumentCaptor<AiTraderPlan> extra = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(mapper).insert(extra.capture());
        AiTraderPlan row = extra.getValue();
        assertThat(row.getStatus()).isEqualTo(AiTraderPlan.STATUS_CLOSED);
        assertThat(row.getPositionId()).isEqualTo(43L);
        assertThat(row.getOpenedWakeTime()).isEqualTo(FILED + 2400_000L);
        assertThat(row.getClosedWakeTime()).isEqualTo(BOUNDARY);
        assertThat(row.getInvalidationCondition()).isEqualTo("1h收盘跌回98000下方");
        assertThat(row.getRevisionsJson()).contains("续立");
    }

    /** 绑着的仓不在最近 200 条已平里（窗口外）：结局不报，但照样归档、照样续立，不抛 */
    @Test
    void missingClosedPositionStillArchivesAndCarries() {
        when(mapper.selectList(any())).thenReturn(List.of(livePlan("BTCUSDT", "LONG", 42L)));

        TraderPlanStore.Reconcile r = store.reconcile(7L, 1, List.of(), List.of(pendingOpen("BTCUSDT", "LONG")),
                closed(), _ -> List.of(), BOUNDARY, AgentLang.ZH);

        assertThat(r.events()).isEmpty();
        ArgumentCaptor<AiTraderPlan> archived = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(mapper).updateById(archived.capture());
        assertThat(archived.getValue().getStatus()).isEqualTo(AiTraderPlan.STATUS_CLOSED);
        assertThat(archived.getValue().getPositionId()).isEqualTo(42L);
        verify(mapper).insert(any(AiTraderPlan.class));
        assertThat(r.live()).hasSize(1);
    }

    // ==================== 对账：计划没绑过仓 ====================

    /** 两张限价单先后成交、先成交那笔止损、后成交那笔还开着：先报止损那笔，旧行归档绑它，续立行绑新仓并报成交 */
    @Test
    void unboundWithGapClosedAndNewOpenReportsBothAndCarries() {
        when(mapper.selectList(any())).thenReturn(List.of(livePlan("BTCUSDT", "LONG", null)));
        FuturesPositionDTO stopped = position(42L, "BTCUSDT", "LONG", FILED + 300_000L);
        FuturesPositionDTO open = position(43L, "BTCUSDT", "LONG", FILED + 1800_000L);

        TraderPlanStore.Reconcile r = store.reconcile(7L, 1, List.of(open), List.of(), closed(stopped), _ -> List.of(), BOUNDARY, AgentLang.ZH);

        assertThat(r.events()).hasSize(2);
        assertThat(closedOf(r.events().get(0)).position()).isSameAs(stopped);
        assertThat(r.events().get(1)).isInstanceOf(TraderPlanStore.Filled.class);
        ArgumentCaptor<AiTraderPlan> archived = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(mapper).updateById(archived.capture());
        assertThat(archived.getValue().getStatus()).isEqualTo(AiTraderPlan.STATUS_CLOSED);
        assertThat(archived.getValue().getPositionId()).isEqualTo(42L);
        ArgumentCaptor<AiTraderPlan> next = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(mapper).insert(next.capture());
        assertThat(next.getValue().getStatus()).isEqualTo(AiTraderPlan.STATUS_LIVE);
        assertThat(next.getValue().getPositionId()).isEqualTo(43L);
        assertThat(r.live()).containsExactly(next.getValue());
    }

    /** 两张限价单、先成交那笔止损、另一张还挂着：这一轮就报止损，旧行归档绑它，论点随挂单续立 */
    @Test
    void unboundWithGapClosedAndRestingOrderReportsAndCarries() {
        when(mapper.selectList(any())).thenReturn(List.of(livePlan("BTCUSDT", "LONG", null)));
        FuturesPositionDTO stopped = position(42L, "BTCUSDT", "LONG", FILED + 300_000L);

        TraderPlanStore.Reconcile r = store.reconcile(7L, 1, List.of(), List.of(pendingOpen("BTCUSDT", "LONG")),
                closed(stopped), _ -> List.of(), BOUNDARY, AgentLang.ZH);

        assertThat(r.events()).hasSize(1);
        assertThat(closedOf(r.events().getFirst()).position()).isSameAs(stopped);
        ArgumentCaptor<AiTraderPlan> archived = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(mapper).updateById(archived.capture());
        assertThat(archived.getValue().getPositionId()).isEqualTo(42L);
        ArgumentCaptor<AiTraderPlan> next = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(mapper).insert(next.capture());
        assertThat(next.getValue().getStatus()).isEqualTo(AiTraderPlan.STATUS_LIVE);
        assertThat(next.getValue().getPositionId()).isNull();
        assertThat(r.live()).containsExactly(next.getValue());
    }

    /**
     * 限价单在两次唤醒之间成交又被止损，计划从没绑过仓：立案之后该键上开过的仓位是它的结局；
     * 立案之前就平掉的同键旧仓不算
     */
    @Test
    void unboundNoExposureReportsLateClosedPositions() {
        AiTraderPlan plan = livePlan("BTCUSDT", "SHORT", null);
        long filed = BOUNDARY - 900_000L;
        plan.setCreatedAt(at(filed));
        when(mapper.selectList(any())).thenReturn(List.of(plan));
        FuturesPositionDTO older = position(1059L, "BTCUSDT", "SHORT", filed - 60_000L);
        FuturesPositionDTO mine = position(1060L, "BTCUSDT", "SHORT", filed + 240_000L);

        TraderPlanStore.Reconcile r = store.reconcile(7L, 1, List.of(), List.of(), closed(older, mine), _ -> List.of(), BOUNDARY, AgentLang.ZH);

        assertThat(r.live()).isEmpty();
        assertThat(r.events()).hasSize(1);
        assertThat(closedOf(r.events().getFirst()).position()).isSameAs(mine);
        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(mapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(AiTraderPlan.STATUS_CLOSED);
        assertThat(cap.getValue().getPositionId()).isEqualTo(1060L);
        verify(mapper, never()).insert(any(AiTraderPlan.class));
    }

    /** 已被别的计划行认领的仓位不算新结局：归档行绑着的那笔在前几轮已经报过 */
    @Test
    void claimedPositionsAreNotReportedAgain() {
        AiTraderPlan archived = livePlan("BTCUSDT", "LONG", 41L);
        archived.setId(20L);
        archived.setStatus(AiTraderPlan.STATUS_CLOSED);
        archived.setClosedWakeTime(BOUNDARY - 1800_000L);
        AiTraderPlan live = livePlan("BTCUSDT", "LONG", null);
        live.setCreatedAt(at(BOUNDARY - 1800_000L));
        when(mapper.selectList(any())).thenReturn(List.of(archived, live));

        TraderPlanStore.Reconcile r = store.reconcile(7L, 1, List.of(), List.of(),
                closed(position(41L, "BTCUSDT", "LONG", BOUNDARY - 1500_000L)), _ -> List.of(), BOUNDARY, AgentLang.ZH);

        assertThat(r.events()).singleElement().isInstanceOf(TraderPlanStore.Cancelled.class);
        verify(mapper, times(1)).updateById(any(AiTraderPlan.class));
        verify(mapper, never()).insert(any(AiTraderPlan.class));
    }

    /** 无仓无挂单、立案后该键上一个仓都没开过：挂单没了，报撤单归档 */
    @Test
    void unboundNoExposureWithoutPositionsIsCancelled() {
        when(mapper.selectList(any())).thenReturn(List.of(livePlan("BTCUSDT", "LONG", null)));

        TraderPlanStore.Reconcile r = store.reconcile(7L, 1, List.of(), List.of(), closed(), _ -> List.of(), BOUNDARY, AgentLang.ZH);

        assertThat(r.live()).isEmpty();
        assertThat(r.events()).singleElement().isInstanceOf(TraderPlanStore.Cancelled.class);
        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(mapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(AiTraderPlan.STATUS_CLOSED);
        assertThat(cap.getValue().getClosedWakeTime()).isEqualTo(BOUNDARY);
    }

    /** 几份计划都要看已平仓位时，sim 只查一次 */
    @Test
    void closedPositionsFetchedOnce() {
        AiTraderPlan btc = livePlan("BTCUSDT", "LONG", null);
        AiTraderPlan eth = livePlan("ETHUSDT", "SHORT", null);
        eth.setId(22L);
        when(mapper.selectList(any())).thenReturn(List.of(btc, eth));
        AtomicInteger fetches = new AtomicInteger();

        store.reconcile(7L, 1, List.of(), List.of(pendingOpen("BTCUSDT", "LONG"), pendingOpen("ETHUSDT", "SHORT")),
                () -> {
                    fetches.incrementAndGet();
                    return List.of();
                }, _ -> List.of(), BOUNDARY, AgentLang.ZH);

        assertThat(fetches.get()).isEqualTo(1);
    }

    // ==================== 写路径 ====================

    /** 新立时同键还有 LIVE 旧计划＝唤醒中平掉重开：旧计划归档留档、新计划仓龄从头算、修订史不继承 */
    @Test
    void openArchivesStaleSameKeyPlanThenInserts() {
        AiTraderPlan old = livePlan("BTCUSDT", "LONG", 42L);
        when(mapper.selectOne(any())).thenReturn(old);
        AiTraderPlan neu = livePlan("BTCUSDT", "LONG", 43L);
        neu.setId(null);
        neu.setSignalsUsed("重新突破，独立新仓");
        neu.setOpenedWakeTime(BOUNDARY);

        store.open(neu, _ -> List.of(), AgentLang.ZH);

        ArgumentCaptor<AiTraderPlan> archived = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(mapper).updateById(archived.capture());
        assertThat(archived.getValue().getId()).isEqualTo(21L);
        assertThat(archived.getValue().getStatus()).isEqualTo(AiTraderPlan.STATUS_CLOSED);
        assertThat(archived.getValue().getClosedWakeTime()).isEqualTo(BOUNDARY);

        ArgumentCaptor<AiTraderPlan> inserted = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getStatus()).isEqualTo(AiTraderPlan.STATUS_LIVE);
        assertThat(inserted.getValue().getOpenedWakeTime()).isEqualTo(BOUNDARY);
        assertThat(inserted.getValue().getPositionId()).isEqualTo(43L);
        assertThat(inserted.getValue().getRevisionsJson()).isNull();
    }

    /** 加仓覆盖：新论点上位，旧论点带理由进修订史，仓龄按最初开仓；限价加仓响应无仓位 id，已有绑定不能被抹掉 */
    @Test
    void coverKeepsOldThesisAsRevisionAndBinding() {
        AiTraderPlan old = livePlan("BTCUSDT", "LONG", 42L);
        when(mapper.selectOne(any())).thenReturn(old);
        AiTraderPlan neu = livePlan("BTCUSDT", "LONG", null);
        neu.setId(null);
        neu.setSignalsUsed("回踩确认支撑，加仓");
        neu.setInvalidationCondition("4h收盘跌破97000");
        neu.setOpenedWakeTime(BOUNDARY);

        store.cover(neu, AgentLang.ZH);

        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(mapper).updateById(cap.capture());
        AiTraderPlan p = cap.getValue();
        assertThat(p.getId()).isEqualTo(21L);
        assertThat(p.getInvalidationCondition()).isEqualTo("4h收盘跌破97000");
        assertThat(p.getRevisionsJson()).contains("加仓").contains("1h收盘跌回98000下方");
        assertThat(p.getOpenedWakeTime()).isEqualTo(FILED);
        assertThat(p.getPositionId()).isEqualTo(42L);
        verify(mapper, never()).insert(any(AiTraderPlan.class));
    }

    /** 有持仓却没计划（旧仓）时再下单：没东西可覆盖，退化成新立 */
    @Test
    void coverWithoutLivePlanFallsBackToOpen() {
        when(mapper.selectOne(any())).thenReturn(null);
        AiTraderPlan neu = livePlan("BTCUSDT", "LONG", 42L);
        neu.setId(null);

        store.cover(neu, AgentLang.ZH);

        verify(mapper).insert(any(AiTraderPlan.class));
        verify(mapper, never()).updateById(any(AiTraderPlan.class));
    }
}
