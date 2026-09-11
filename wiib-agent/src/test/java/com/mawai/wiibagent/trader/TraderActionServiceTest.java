package com.mawai.wiibagent.trader;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibagent.learning.ReviewRunner;
import com.mawai.wiibagent.trader.TraderActionService.ActionPanel;
import com.mawai.wiibagent.trader.TraderActionService.ActionResult;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.trader.wakeup.TraderScheduler;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 三个动作（留言 / 手动唤醒 / 点播复盘）的唯一实现。
 * <p>
 * 这里钉的是<b>准入</b>与<b>如实回报</b>：唤醒与复盘都是花钱且动真仓位的事，
 * 什么情况下不许做必须写死；做没做成也得一字不差地传回给用户，不能报"已唤醒"了事。
 * <p>
 * 归属（按 userId 取自己的 trader）与查询归 {@link TraderChatServiceTest}。
 */
class TraderActionServiceTest {

    private static final long ME = 1L;

    /**
     * Lambda 条件构造器要查 TableInfo：留言/撤回走 LambdaUpdateWrapper&lt;AiTrader&gt;，
     * 面板取最近决策行走 LambdaQueryWrapper&lt;AiTraderDecision&gt;。
     * 不预热本类单独跑会炸、全量跑却因别的类先热过而假绿。
     */
    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTrader.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AiTraderDecision.class);
    }

    private final TraderService traderService = mock(TraderService.class);
    private final TraderScheduler scheduler = mock(TraderScheduler.class);
    private final ReviewRunner reviewRunner = mock(ReviewRunner.class);
    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);

    private final TraderActionService service = new TraderActionService(
            traderService, scheduler, reviewRunner, traderMapper, decisionMapper, new MessageCatalog());

    /**
     * 调度器的 inFlight 是点播复盘与例行唤醒共用的互斥位，拿真集合当替身——
     * 桩成固定 true/false 就测不出"占了之后第二次会被拦"这条。
     */
    @BeforeEach
    void stubOccupancy() {
        Set<Long> occupied = ConcurrentHashMap.newKeySet();
        when(scheduler.tryOccupy(anyLong())).thenAnswer(inv -> occupied.add(inv.getArgument(0)));
        when(scheduler.isBusy(anyLong())).thenAnswer(inv -> occupied.contains(inv.getArgument(0)));
        doAnswer(inv -> {
            occupied.remove(inv.<Long>getArgument(0));
            return null;
        }).when(scheduler).release(anyLong());
    }

    // ---------- 夹具 ----------

    private AiTrader running() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(ME);
        t.setName("测试员");
        t.setStatus(AiTrader.STATUS_RUNNING);
        t.setRoundNo(1);
        t.setIntervalCode("1h");
        t.setSymbols("BTCUSDT");
        t.setMarginPctMin(new BigDecimal("5"));
        t.setMarginPctMax(new BigDecimal("20"));
        return t;
    }

    private void mine(AiTrader t) {
        when(traderService.mine(ME)).thenReturn(t);
    }

    /** 唯一那次列级更新的条件构造器；落库了什么以它为准 */
    private LambdaUpdateWrapper<AiTrader> capturedUpdate() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaUpdateWrapper<AiTrader>> up = ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(traderMapper).update(isNull(), up.capture());
        return up.getValue();
    }

    /** set 进去的留言正文：整条 update 里唯一的字符串值（null=正文被清空） */
    private static String writtenNote(LambdaUpdateWrapper<AiTrader> w) {
        return w.getParamNameValuePairs().values().stream()
                .filter(String.class::isInstance).map(String.class::cast)
                .findFirst().orElse(null);
    }

    /** set 进去的剩余轮次：整条 update 里唯一的整型值 */
    private static Integer writtenRounds(LambdaUpdateWrapper<AiTrader> w) {
        return w.getParamNameValuePairs().values().stream()
                .filter(Integer.class::isInstance).map(Integer.class::cast)
                .findFirst().orElse(null);
    }

    // ---------- 没有 trader ----------

    /** 没有 trader 的用户去动作：要拿到"你还没有"，而不是 NPE，更不能真派活出去 */
    @Test
    void 没有trader时动作不炸() {
        mine(null);

        assertThat(service.saveNote(ME, "随便说说", 1).ok()).isFalse();
        assertThat(service.clearNote(ME).ok()).isFalse();
        assertThat(service.wake(ME).ok()).isFalse();
        assertThat(service.review(ME).ok()).isFalse();

        verify(scheduler, never()).tryManualWake(any());
        verify(reviewRunner, never()).review(any(), anyLong());
        verify(traderMapper, never()).update(any(), any());
    }

    // ---------- 留言 ----------

    @Test
    void 留言落库并说明还剩几轮() {
        mine(running());

        ActionResult r = service.saveNote(ME, "  今晚有 CPI，仓位轻点  ", 3);

        assertThat(r.ok()).isTrue();
        assertThat(r.message()).contains("3");
        LambdaUpdateWrapper<AiTrader> w = capturedUpdate();
        // 列级更新：并发的唤醒回路正在改同一行别的列
        assertThat(w.getSqlSet()).contains("owner_note=", "owner_note_rounds=");
        // 前后空白不进库：留言原样拼进下一轮系统提示词
        assertThat(writtenNote(w)).isEqualTo("今晚有 CPI，仓位轻点");
        assertThat(writtenRounds(w)).isEqualTo(3);
    }

    /**
     * 轮次决定这条话要被念多少次、也就是跟多少次唤醒一起烧钱：0 和负数按"念一次"算，
     * 超上限压回 24，不填同样是一次性的一句话。钳制看的是落库值，不是返回话术。
     */
    @ParameterizedTest
    @CsvSource(value = {"0, 1", "-3, 1", "25, 24", "null, 1"}, nullValues = "null")
    void 留言轮次钳在1到24(Integer given, int expected) {
        mine(running());

        ActionResult r = service.saveNote(ME, "仓位轻点", given);

        assertThat(writtenRounds(capturedUpdate())).isEqualTo(expected);
        assertThat(r.message()).contains(String.valueOf(expected));
    }

    /** 超长留言只截不拒——用户已经把话说了，拒收等于让他重写；但截了必须明说 */
    @Test
    void 超长留言截到上限且明说截掉了() {
        mine(running());
        String tooLong = "啊".repeat(TraderActionService.MAX_NOTE_CHARS + 100);

        ActionResult r = service.saveNote(ME, tooLong, 1);

        assertThat(r.ok()).isTrue();
        assertThat(writtenNote(capturedUpdate())).hasSize(TraderActionService.MAX_NOTE_CHARS);
        assertThat(r.message()).contains("截掉");
    }

    /** 覆盖写要告诉用户：不然他以为两条都在，实际上只剩最后一条 */
    @Test
    void 覆盖未读留言时提示() {
        AiTrader t = running();
        t.setOwnerNote("上一条还没被读走");
        mine(t);

        assertThat(service.saveNote(ME, "新的一条", 1).message()).contains("覆盖");
    }

    /** 反过来，本来就没有旧留言还提"覆盖"，用户会以为自己刚弄丢了什么 */
    @Test
    void 没有未读留言时不提覆盖() {
        mine(running());

        assertThat(service.saveNote(ME, "新的一条", 1).message()).doesNotContain("覆盖");
    }

    @Test
    void 空留言不落库() {
        mine(running());

        ActionResult r = service.saveNote(ME, "   ", 1);

        assertThat(r.ok()).isFalse();
        verify(traderMapper, never()).update(any(), any());
    }

    /** 撤回要连轮次一起归零：只清正文的话，注入处还拿着 rounds>0 去念一条空留言 */
    @Test
    void 撤回留言时正文与轮次一起清() {
        AiTrader t = running();
        t.setOwnerNote("说错了");
        t.setOwnerNoteRounds(5);
        mine(t);

        ActionResult r = service.clearNote(ME);

        assertThat(r.ok()).isTrue();
        LambdaUpdateWrapper<AiTrader> w = capturedUpdate();
        assertThat(w.getSqlSet()).contains("owner_note=", "owner_note_rounds=");
        assertThat(writtenNote(w)).isNull();
        assertThat(writtenRounds(w)).isZero();
    }

    /** 没东西可撤是"本来就没有"，不是失败：面板不该为此红一下，库也不该白写一次 */
    @Test
    void 没有留言时撤回不写库且算成功() {
        mine(running());

        ActionResult r = service.clearNote(ME);

        assertThat(r.ok()).isTrue();
        verify(traderMapper, never()).update(any(), any());
    }

    // ---------- 手动唤醒 ----------

    @Test
    void 唤醒触发调度器() {
        AiTrader t = running();
        mine(t);
        when(scheduler.tryManualWake(t)).thenReturn(null);   // null=已触发

        ActionResult r = service.wake(ME);

        assertThat(r.ok()).isTrue();
        verify(scheduler).tryManualWake(t);
    }

    /** 调度器的拒因（停工窗口/距例行太近/上一轮还在跑）原样转述，不能报"已唤醒" */
    @Test
    void 调度器给出拒因时不触发唤醒() {
        AiTrader t = running();
        mine(t);
        when(scheduler.manualWakeBlockedReason(t)).thenReturn("距下一次例行唤醒不足30秒");

        ActionResult r = service.wake(ME);

        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("距下一次例行唤醒不足30秒");
        verify(scheduler, never()).tryManualWake(any());
    }

    /** 预检过了、真占位时被别人抢先：这一次同样没醒，如实说 */
    @Test
    void 抢占失败时如实回报() {
        AiTrader t = running();
        mine(t);
        when(scheduler.tryManualWake(t)).thenReturn("上一轮唤醒还在跑");

        ActionResult r = service.wake(ME);

        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("上一轮唤醒还在跑");
    }

    /** 暂停就是暂停：手动唤醒要是能绕过它，连败自动暂停的 trader 会被一句话叫起来接着亏 */
    @Test
    void 暂停中的trader不许被唤醒() {
        AiTrader t = running();
        t.setStatus(AiTrader.STATUS_PAUSED);
        t.setPausedReason("连续5次唤醒失败");
        mine(t);

        ActionResult r = service.wake(ME);

        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("暂停").contains("连续5次唤醒失败");
        verify(scheduler, never()).tryManualWake(any());
    }

    /** 爆仓是本局终局，只能重置开新一局；这里放行等于让爆掉的一局无限续命 */
    @Test
    void 爆仓终局的trader不许被唤醒() {
        AiTrader t = running();
        t.setStatus(AiTrader.STATUS_LIQUIDATED);
        mine(t);

        ActionResult r = service.wake(ME);

        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("爆仓");
        verify(scheduler, never()).tryManualWake(any());
    }

    // ---------- 点播复盘 ----------

    /**
     * 复盘预算 600s、工作台整条 SSE 也只有 600s——同步跑满就没时间让汇总模型开口了。
     * 所以点播是"准入同步、执行异步"：当场答复派没派出去，结果去时间线上看。
     */
    @Test
    void 有素材时异步开跑并当场答复() {
        AiTrader t = running();
        mine(t);
        when(reviewRunner.hasMaterial(eq(t), anyLong())).thenReturn(true);

        ActionResult r = service.review(ME);

        assertThat(r.ok()).isTrue();
        assertThat(r.message()).contains("时间线");
        verify(reviewRunner, timeout(2000)).review(eq(t), anyLong());
    }

    /**
     * 没有新的已了结交易就没什么可复的。跳过是省下一次深模型调用的正确决定，
     * <b>ok 必须为 true</b>——压成失败会让面板红一下，用户以为出了故障。
     */
    @Test
    void 无素材时算成功且不开跑() {
        AiTrader t = running();
        mine(t);
        when(reviewRunner.hasMaterial(eq(t), anyLong())).thenReturn(false);

        ActionResult r = service.review(ME);

        assertThat(r.ok()).isTrue();
        assertThat(r.message()).contains("跳过");
        verify(reviewRunner, never()).review(any(), anyLong());
    }

    /** 停工窗口内旁路写复盘，learner 会读到"半天"的复盘、脏读进记忆——连素材都不该去查 */
    @Test
    void 停工窗口内拒绝点播复盘() {
        mine(running());
        when(scheduler.isHandoverActive()).thenReturn(true);

        ActionResult r = service.review(ME);

        assertThat(r.ok()).isFalse();
        verify(reviewRunner, never()).hasMaterial(any(), anyLong());
        verify(reviewRunner, never()).review(any(), anyLong());
    }

    /**
     * 同一 trader 不并行：一次复盘是 600s 预算的深模型大调用，连点两下就是花两份钱写两篇，
     * 而 ai_trader.memory 是全文覆盖写，后完成的那篇会把另一篇的教训直接顶掉。
     * 占的是调度器那个 inFlight，所以交接阶段的全体复盘同样会被这个位子挡在外面。
     * 让 mock 卡在 review() 里不返回，复现"上一次还在跑"的那段窗口。
     */
    @Test
    void 同一trader第二次点播被互斥拦下() throws Exception {
        AiTrader t = running();
        mine(t);
        when(reviewRunner.hasMaterial(eq(t), anyLong())).thenReturn(true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);   // 超时兜底，绝不永久挂住
            return null;
        }).when(reviewRunner).review(eq(t), anyLong());

        try {
            assertThat(service.review(ME).ok()).isTrue();
            assertThat(started.await(5, TimeUnit.SECONDS)).as("第一次的异步复盘要先跑起来").isTrue();

            ActionResult second = service.review(ME);

            assertThat(second.ok()).isFalse();
            assertThat(second.message()).contains("跑完再点");
        } finally {
            release.countDown();   // 断言失败也要放行，别让虚拟线程把这条用例吊死
        }
        verify(reviewRunner, times(1)).review(eq(t), anyLong());
    }

    // ---------- 面板 ----------

    /** 没 trader 的用户打开面板：三张卡都是空的，且一次库都不查 */
    @Test
    void 没有trader时面板是空壳() {
        mine(null);

        ActionPanel p = service.panel(ME);

        assertThat(p.hasTrader()).isFalse();
        assertThat(p.name()).isNull();
        assertThat(p.note()).isNull();
        assertThat(p.noteRounds()).isZero();
        // 两个上限是前端画输入框的依据，没 trader 也得给
        assertThat(p.noteMaxRounds()).isEqualTo(TraderActionService.MAX_NOTE_ROUNDS);
        assertThat(p.noteMaxChars()).isEqualTo(TraderActionService.MAX_NOTE_CHARS);
        verify(decisionMapper, never()).selectOne(any());
    }

    /**
     * 三张卡的状态一次取齐：唤醒卡、复盘卡、留言卡。
     * 复盘那条要连 ERROR 一起取——面板得说得清"上次那篇是失败的"，而不是装作没跑过。
     */
    @Test
    void 面板一次取齐三张卡的状态() {
        AiTrader t = running();
        t.setOwnerNote("今晚有 CPI");
        t.setOwnerNoteRounds(3);
        mine(t);
        when(scheduler.manualWakeBlockedReason(t)).thenReturn("距下一次例行唤醒不足30秒");
        when(scheduler.nextRoutineWakeAt(t)).thenReturn(1_700_000_000_000L);
        when(reviewRunner.hasMaterial(eq(t), anyLong())).thenReturn(true);

        LocalDateTime wokeAt = LocalDateTime.now().withNano(0).minusMinutes(37);
        AiTraderDecision wakeRow = decision(wokeAt, AiTraderDecision.STATUS_OK);
        wakeRow.setWakeTime(epochMs(wokeAt.truncatedTo(ChronoUnit.HOURS)));   // K线边界，比真实时刻早37分钟
        LocalDateTime reviewedAt = LocalDateTime.now().withNano(0).minusHours(9);
        AiTraderDecision reviewRow = decision(reviewedAt, AiTraderDecision.STATUS_ERROR);
        when(decisionMapper.selectOne(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<AiTraderDecision> w = inv.getArgument(0);
            // MP 的条件值是懒求值的：先拼一次 SQL，kind 才会落进 paramNameValuePairs
            w.getTargetSql();
            return w.getParamNameValuePairs().containsValue(AiTraderDecision.KIND_REVIEW) ? reviewRow : wakeRow;
        });

        ActionPanel p = service.panel(ME);

        assertThat(p.hasTrader()).isTrue();
        assertThat(p.name()).isEqualTo("测试员");
        assertThat(p.status()).isEqualTo(AiTrader.STATUS_RUNNING);
        assertThat(p.pausedReason()).isNull();
        // 唤醒时刻取 created_at，不是那条行上的 wake_time（后者是K线边界，会把 10:37 显示成 10:00）
        assertThat(p.lastWakeAt()).isEqualTo(epochMs(wokeAt)).isNotEqualTo(wakeRow.getWakeTime());
        assertThat(p.nextWakeAt()).isEqualTo(1_700_000_000_000L);
        // 面板显示的拒因，就是真点下去会拿到的那一句
        assertThat(p.wakeBlockedReason()).isEqualTo("距下一次例行唤醒不足30秒");
        assertThat(p.lastReviewAt()).isEqualTo(epochMs(reviewedAt));
        assertThat(p.lastReviewStatus()).isEqualTo(AiTraderDecision.STATUS_ERROR);
        assertThat(p.hasReviewMaterial()).isTrue();
        assertThat(p.reviewBlockedReason()).isNull();
        assertThat(p.note()).isEqualTo("今晚有 CPI");
        assertThat(p.noteRounds()).isEqualTo(3);
    }

    /** 列是 NOT NULL DEFAULT 0，但实体层拿到 null 时面板得给 0，不能炸在拆箱上 */
    @Test
    void 面板留言轮次为空时给0() {
        AiTrader t = running();
        t.setOwnerNote("有正文，轮次是空的");
        t.setOwnerNoteRounds(null);
        mine(t);

        assertThat(service.panel(ME).noteRounds()).isZero();
    }

    private static AiTraderDecision decision(LocalDateTime createdAt, String status) {
        AiTraderDecision d = new AiTraderDecision();
        d.setCreatedAt(createdAt);
        d.setStatus(status);
        return d;
    }

    private static long epochMs(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
