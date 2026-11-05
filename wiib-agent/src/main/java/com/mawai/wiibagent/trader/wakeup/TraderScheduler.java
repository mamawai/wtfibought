package com.mawai.wiibagent.trader.wakeup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.i18n.RequestLang;
import com.mawai.wiibagent.learning.ReviewRunner;
import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import com.mawai.wiibquant.market.domain.KlineClosedEvent;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * trader 调度器：5m K线收盘事件当唯一时钟，对齐到各 trader 的 interval 边界触发唤醒。
 * 每 trader 互斥（上一唤醒未完 → 记 SKIPPED，要最新信号不排陈旧任务），不设全局并发上限；
 * 事件是 per-symbol 的，同一边界多币多次触发靠 firedBoundary 去重。
 * 不做兜底补漏：WS/事件断流丢的K线就丢了——陈旧信号唤醒没有意义（迟到事件由唤醒预算兜底：
 * 距下一边界不足 30s 直接放弃，见 TraderWakeupRunner）。
 * 唤醒时段（ai_trader.wake_window，见 {@link WakeWindow}）只在这里过滤：例行/警报/持仓事件时段外静默跳过（不写 SKIPPED，
 * 15m 档一天会写 96 行"休眠中"），手动唤醒不拦，日线交接的复盘不看它。
 * 边界撞上 High 级数据公布时刻，例行触发先过 {@link EconCalendarGate}（最多等 30s 拿实际值）再发。
 * <p>
 * 日线边界走两阶段交接（见 {@link #startDailyHandover}）：先交易、再全体复盘。
 * 停工窗口在阶段0的唤醒发出后就开、交接结束才关，期间拒绝一切新唤醒（例行/警报/持仓事件/手动/点播复盘），
 * 复盘读的必须是"已定格的一天"，边写边读的脏读会进记忆污染后续每一轮。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraderScheduler {

    /** interval → 毫秒；唤醒预算、建 trader 校验、提示词都共用同一份。1d 只留数学（日线交接边界用），不再是唤醒档位 */
    public static final Map<String, Long> INTERVAL_MS = Map.of(
            "5m", 300_000L, "15m", 900_000L, "1h", 3_600_000L, "4h", 14_400_000L, "1d", 86_400_000L);
    /** 心跳档位只有三档：5m、1d 已下线，不在这里的档位例行 / 手动 / 非例行都不叫（存量 5m 由 init.sql 迁 15m） */
    public static final Set<String> WAKE_INTERVALS = Set.of("15m", "1h", "4h");
    /** 新建 trader 和没 trader 时的预览都按这个档位 */
    public static final String DEFAULT_INTERVAL = "1h";

    private final AiTraderMapper traderMapper;
    private final TraderWakeupRunner runner;
    private final ReviewRunner reviewRunner;
    /** 手动唤醒的拦因当场回给用户，跟界面语言 */
    private final MessageCatalog messages;
    private final EconCalendarGate calendarGate;
    /** 盯盘哨兵：5m 收盘时问它这根收盘命中了哪些收盘类条件，命中先落库，同一刻的例行轮开场白就能并进去念 */
    private final WatchSentinel watchSentinel;
    /** 领排队触发时，别的轮已经告诉过模型的盯盘命中不再单独叫 */
    private final TraderWatchStore watchStore;

    /** 同一 trader 不并行的拒因；预检与真占位两处共用一份措辞 */

    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    /** 每 trader 最近已触发的边界时刻：多个 watch 币在同一刻收盘会各发一次事件，靠它去重 */
    private final Map<Long, Long> firedBoundary = new ConcurrentHashMap<>();
    /** 每 trader 排队中的非例行触发（警报/持仓事件）：上一轮在跑时先攒着，它一结束就合成一轮叫醒，先来后到 */
    private final Map<Long, Queue<WakeTrigger>> pendingTriggers = new ConcurrentHashMap<>();
    /** 日线交接的边界去重位：多 symbol 在日线边界各发一次事件，交接只许启动一次 */
    private final AtomicLong lastHandoverBoundary = new AtomicLong(-1);
    /** 停工窗口位：交接编排线程独写，事件/警报/手动入口只读
     * -- GETTER --
     * 停工窗口是否开着：点播复盘（chat 轨）入场前也要看它，交接期间不许旁路写复盘
     */
    @Getter
    private volatile boolean handoverActive = false;

    /** 墙钟注入点：手动唤醒的预算预检、休眠时段判断要可测 */
    java.util.function.LongSupplier nowMs = System::currentTimeMillis;

    /** 主触发：任意 watch 币的 5m 收盘都是一次时钟滴答。 */
    @EventListener
    public void onKlineClosed(KlineClosedEvent event) {
        if (!"5m".equalsIgnoreCase(event.interval())) {
            return;
        }
        if (handoverActive) {
            // 窗口内的K线事件直接丢弃不补跑：与"不做兜底补漏"同一条哲学——陈旧信号没有意义。
            // 代价是 15m 档最多跳过 1 根K线（窗口上界＝唤醒600s＋复盘600s），每天只有一次，可接受
            log.info("[TraderSched] 日线交接中，丢弃K线事件 {} closeTime={}", event.symbol(), event.closeTime());
            return;
        }
        // 收盘类盯盘命中先落库，再发例行：同一刻醒的例行轮开场白里就带着它们；排进队列的那份等例行轮念过后被过滤掉。
        // 每个币的收盘事件都要评估，不只日线交接抢到启动权的那个
        List<WatchTrigger> hits = closeHits(event);
        long dayBoundary = boundaryOf(event.closeTime(), "1d");
        if (dayBoundary > 0) {
            // 只进队列不领：阶段0的例行轮马上要来，命中排在它后面；交接期间领不到的由下一轮开场白补报
            hits.forEach(t -> pendingTriggers.computeIfAbsent(t.watch().getTraderId(), _ -> new ConcurrentLinkedQueue<>()).add(t));
            startDailyHandover(dayBoundary);
            return;
        }
        // 撞上数据公布时刻先等实际值再发本轮；没有待公布的 future 已完成，thenRun 就地同步执行，与不等一样。
        calendarGate.released(event.closeTime() + 1).thenRun(() -> {
            for (String ic : WAKE_INTERVALS) {
                long boundary = boundaryOf(event.closeTime(), ic);
                if (boundary > 0) {
                    fireInterval(ic, boundary);
                }
            }
            hits.forEach(t -> tryTriggerWake(t.watch().getTraderId(), t));
        }).exceptionally(e -> {
            log.error("[TraderSched] 例行触发失败 closeTime={} msg={}", event.closeTime(), e.toString());
            return null;
        });
    }

    /**
     * 日线交接编排（本功能唯一的全局同步点）：
     * 阶段0 全体例行唤醒发出 → 【停工窗口开】→ 等交易跑完 → 阶段1 全体复盘并行 → 等全部复盘落库 → 【停工窗口关】。
     * 窗口保证复盘读的是定格数据。各阶段单人超时都有硬顶（唤醒600s/复盘600s），join 不会永久卡住。
     */
    private void startDailyHandover(long boundary) {
        // 原子抢占：日线边界的多 symbol 事件里只有一个赢家启动交接
        long prev = lastHandoverBoundary.get();
        if (prev >= boundary || !lastHandoverBoundary.compareAndSet(prev, boundary)) {
            return;
        }
        Thread.startVirtualThread(() -> {
            // 阶段0 同样过闸：日线边界撞上数据公布也先等实际值
            calendarGate.released(boundary).join();
            // 阶段0：日线边界同时是三档的边界，全体例行唤醒照常发出
            List<Thread> wakes = new ArrayList<>();
            for (String ic : WAKE_INTERVALS) {
                wakes.addAll(fireInterval(ic, boundary));
            }
            // 发完就关门：在途的照跑，新来的K线/警报/手动全拒——不然 15m 档在阶段0期间又醒一次占住 inFlight，复盘就被跳过
            handoverActive = true;
            log.info("[TraderSched] 日线交接开始 boundary={}，停工窗口开", boundary);
            try {
                // 等交易全部跑完，复盘读的才是定格的一天
                joinAll(wakes);
                // 阶段1：全体复盘并行。fresh 查库——阶段0可能刚改过状态（爆仓/暂停）。
                // 无素材跳过/失败语义都在 ReviewRunner 内部，这里只管准入与时序
                joinAll(phase(running().stream()
                        .filter(t -> !Boolean.FALSE.equals(t.getReviewEnabled())).toList(),
                        t -> reviewRunner.review(t, boundary), "复盘"));
            } finally {
                // 异常也不许卡死窗口：窗口关不上，全体 trader 就永久停摆了
                handoverActive = false;
                log.info("[TraderSched] 日线交接结束 boundary={}，停工窗口关", boundary);
            }
        });
    }

    /**
     * 交接的一个阶段：每 trader 一个虚拟线程（复用 inFlight 互斥），
     * 返回线程列表由调用方 join 成屏障。
     * inFlight 抢不到＝上一边界的交易还没跑完（预算最长600s），该 trader 本阶段跳过——
     * 硬等会拖住全体，而复盘明天还有机会；这也是复盘不脏读的第二道闸：
     * 停工窗口挡住新唤醒，inFlight 挡住残留的旧唤醒。
     */
    private List<Thread> phase(List<AiTrader> traders, java.util.function.Consumer<AiTrader> action, String label) {
        List<Thread> threads = new ArrayList<>();
        for (AiTrader t : traders) {
            if (!inFlight.add(t.getId())) {
                log.info("[TraderSched] {}跳过（上轮交易未完）traderId={}", label, t.getId());
                continue;
            }
            threads.add(Thread.startVirtualThread(() -> {
                try {
                    action.accept(t);
                } catch (Exception e) {
                    // 单人异常不许拖垮屏障：runner 内部已有 ERROR 行留痕，这里兜住意外逃逸的
                    log.warn("[TraderSched] {}异常逃逸 traderId={} msg={}", label, t.getId(), e.getMessage());
                } finally {
                    inFlight.remove(t.getId());
                    drainTriggers(t.getId());
                }
            }));
        }
        return threads;
    }

    private List<AiTrader> running() {
        return traderMapper.selectList(new LambdaQueryWrapper<AiTrader>()
                .eq(AiTrader::getStatus, AiTrader.STATUS_RUNNING));
    }

    private static void joinAll(List<Thread> threads) {
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private List<Thread> fireInterval(String intervalCode, long boundary) {
        List<AiTrader> traders = traderMapper.selectList(new LambdaQueryWrapper<AiTrader>()
                .eq(AiTrader::getStatus, AiTrader.STATUS_RUNNING)
                .eq(AiTrader::getIntervalCode, intervalCode));
        List<Thread> started = new ArrayList<>();
        for (AiTrader trader : traders) {
            // 时段外静默跳过：不占 firedBoundary、不写 SKIPPED——休眠就是没这回事
            WakeWindow window = WakeWindow.of(trader);
            if (window != null && !window.contains(boundary)) {
                continue;
            }
            Thread t = fireTrader(trader, boundary);
            if (t != null) {
                started.add(t);
            }
        }
        return started;
    }

    /** 这里可能会并发firTrader，所以要做去重 */
    private Thread fireTrader(AiTrader trader, long boundary) {
        boolean[] won = new boolean[1];
        firedBoundary.compute(trader.getId(), (_, prev) -> {
            if (prev == null || prev < boundary) {
                won[0] = true;
                return boundary;
            }
            return prev;
        });
        if (!won[0]) {
            return null;
        }
        if (!inFlight.add(trader.getId())) {
            runner.recordSkipped(trader, boundary); // 上次唤醒还在跑，本次边界作废并留痕，等下一根K线的新鲜信号
            log.info("[TraderSched] 上轮未完跳过 traderId={} boundary={}", trader.getId(), boundary);
            return null;
        }
        return Thread.startVirtualThread(() -> {
            try {
                runner.wake(trader, boundary);
            } finally {
                inFlight.remove(trader.getId());
                drainTriggers(trader.getId());
            }
        });
    }

    /** 盯盘哨兵在 tick 上命中的（触及 / 到时刻）：广播过来，走非例行入口 */
    @EventListener
    public void onWatchHit(WatchTrigger trigger) {
        tryTriggerWake(trigger.watch().getTraderId(), trigger);
    }

    /** 这根 5m 收盘命中的收盘类盯盘条件（CAS 已标 FIRED）；哨兵那边出错不许连累例行唤醒 */
    private List<WatchTrigger> closeHits(KlineClosedEvent event) {
        try {
            return watchSentinel.closeHits(event.symbol(), event.closeTime(), event.bar());
        } catch (Exception e) {
            log.warn("[TraderSched] 收盘类盯盘条件匹配失败 {} closeTime={} msg={}", event.symbol(), event.closeTime(), e.toString());
            return List.of();
        }
    }

    /**
     * 非例行唤醒（波动警报 / 持仓事件 / 盯盘命中）：进队列，然后立刻尝试领走。只有两种情况不叫：日线交接停工
     * （复盘要读定格的一天）和主人配的休眠时段，都在领的时候判。上一轮还在跑就先排着，先来后到，它一结束立刻补醒；
     * 排队期间攒下的几条合成一轮一起报。跑过例行边界时那根的例行轮跳过（SKIPPED 留痕）——突发行情里这一轮比按点醒更要紧。
     * 没叫的持仓事件和盯盘命中不会丢：下一次唤醒开头照样报。
     */
    public void tryTriggerWake(long traderId, WakeTrigger trigger) {
        pendingTriggers.computeIfAbsent(traderId, _ -> new ConcurrentLinkedQueue<>()).add(trigger);
        drainTriggers(traderId);
    }

    /**
     * 把该 trader 排队中的触发一次性领走叫醒；每一轮（例行/手动/非例行/点播复盘）结束时也来领一次。
     * 抢不到互斥位就算了：在跑的那轮结束时会再来。领的时候重新查库：排队这几分钟里它可能已经爆仓、被暂停或进了休眠时段。
     */
    private void drainTriggers(long traderId) {
        Queue<WakeTrigger> queue = pendingTriggers.get(traderId);
        if (queue == null || queue.isEmpty()) {
            return;
        }
        if (!inFlight.add(traderId)) {
            return;
        }
        // 没真正开跑的所有出口都要放掉互斥位，不然这个 trader 从此醒不过来
        boolean started = false;
        try {
            List<WakeTrigger> batch = new ArrayList<>();
            for (WakeTrigger t; (t = queue.poll()) != null; ) {
                batch.add(t);
            }
            // 排队期间别的轮（多半是同一刻的例行轮）已经把这条盯盘命中告诉过模型的，不再单独叫
            Set<Long> told = watchStore.wokenIds(batch.stream()
                    .filter(t -> t instanceof WatchTrigger).map(t -> ((WatchTrigger) t).watch().getId()).toList());
            batch.removeIf(t -> t instanceof WatchTrigger w && told.contains(w.watch().getId()));
            if (batch.isEmpty()) {
                return;
            }
            if (handoverActive) {
                log.info("[TraderSched] 日线交接中，丢弃触发 traderId={} {}", traderId, batch);
                return;
            }
            AiTrader trader = traderMapper.selectById(traderId);
            if (trader == null || !AiTrader.STATUS_RUNNING.equals(trader.getStatus())
                    || !WAKE_INTERVALS.contains(trader.getIntervalCode())) {
                log.info("[TraderSched] trader 已不在运行或档位已下线，丢弃触发 traderId={} {}", traderId, batch);
                return;
            }
            WakeWindow window = WakeWindow.of(trader);
            if (window != null && !window.contains(nowMs.getAsLong())) {
                // 休眠时段：警报和持仓事件都不叫——"其他时间段就不唤醒"的另一半
                log.info("[TraderSched] 休眠时段，丢弃触发 traderId={} {}", traderId, batch);
                return;
            }
            log.info("[TraderSched] 非例行唤醒 traderId={} 触发 {} 条 {}", traderId, batch.size(), batch);
            Thread.startVirtualThread(() -> {
                try {
                    runner.wakeTriggered(trader, batch);
                } finally {
                    inFlight.remove(traderId);
                    drainTriggers(traderId);
                }
            });
            started = true;
        } finally {
            if (!started) {
                inFlight.remove(traderId);
            }
        }
    }

    /**
     * 手动唤醒的准入预检：不能唤醒时给出原因，能唤醒返回 null。
     * 动作面板拿它决定按钮点不点得动，与 {@link #tryManualWake} 共用同一份判断——
     * 面板显示的拒因就是真点下去会拿到的那一句。
     * <p>
     * inFlight 这条只查不占：面板是轮询刷新的，占位会把 trader 锁死。
     * 拒因文案按 lang 取：chat 的 trader_overview 在非请求线程上调它，取不到请求语言。
     */
    public String manualWakeBlockedReason(AiTrader trader, AgentLang lang) {
        if (handoverActive) {
            return messages.get(lang, "trader.wake.handover");
        }
        if (!WAKE_INTERVALS.contains(trader.getIntervalCode())) {
            return messages.get(lang, "trader.wake.intervalRetired");
        }
        long intervalMs = INTERVAL_MS.get(trader.getIntervalCode());
        long now = nowMs.getAsLong();
        long boundary = now - Math.floorMod(now, intervalMs);
        // 距下一根K线太近就别烧这一次：例行唤醒马上到，内容几乎一样
        if (TraderWakeupRunner.wakeBudgetSeconds(boundary, intervalMs, now) < TraderWakeupRunner.MIN_WAKE_SECONDS) {
            // 措辞不提"例行马上来"：休眠时段里没有"稍等就来"的例行唤醒，手动是那时唯一的通道；拦本身保留（预算不够会落 SKIPPED）
            return messages.get(lang, "trader.wake.budgetTooTight",
                    Map.of("seconds", TraderWakeupRunner.MIN_WAKE_SECONDS));
        }
        if (inFlight.contains(trader.getId())) {
            return messages.get(lang, "trader.wake.busy");
        }
        return null;
    }

    /** 这个 trader 手上有没有活（例行唤醒 / 交接阶段 / 点播复盘共用同一个位子）。只查不占。 */
    public boolean isBusy(long traderId) {
        return inFlight.contains(traderId);
    }

    /**
     * 占住这个 trader 的"正在跑"位——点播复盘走这里，与调度侧共用同一个 inFlight，
     * 两边才不会对同一个 trader 各跑一篇复盘、把 ai_trader.memory 互相覆盖掉。
     * 抢到必须在 finally 里 {@link #release}，否则这个 trader 从此醒不过来。
     */
    public boolean tryOccupy(long traderId) {
        return inFlight.add(traderId);
    }

    public void release(long traderId) {
        inFlight.remove(traderId);
        drainTriggers(traderId);
    }

    /** trader 已删：摘掉两份 traderId 记账。inFlight 不用摘——删除前已用 {@link #isBusy} 拦掉在途 */
    public void forget(long traderId) {
        firedBoundary.remove(traderId);
        pendingTriggers.remove(traderId);
    }

    /**
     * 下一次例行唤醒的时刻；档位已下线返回 null。动作面板用它显示"再等多久就自动醒了"。
     * 有唤醒时段的跳到时段内的下一根——面板"下次例行"显示的必须是真会醒的那一刻
     */
    public Long nextRoutineWakeAt(AiTrader trader) {
        if (!WAKE_INTERVALS.contains(trader.getIntervalCode())) {
            return null;
        }
        long intervalMs = INTERVAL_MS.get(trader.getIntervalCode());
        long now = nowMs.getAsLong();
        long next = now - Math.floorMod(now, intervalMs) + intervalMs;
        WakeWindow window = WakeWindow.of(trader);
        if (window == null) {
            return next;
        }
        long inWindow = window.nextBoundaryFrom(next, intervalMs);
        return inWindow < 0 ? null : inWindow;
    }

    /**
     * 手动唤醒（动作面板的按钮扣扳机）：走与例行/警报同一套治理——
     * 预算预检 → 每 trader 互斥，然后虚拟线程上异步跑。
     * <p>
     * <b>异步而不是同步等结果</b>：唤醒预算最长 600s，同步等于把调用方的连接压死几分钟；
     * trader 本来就是"后台醒来做完事睡去"的回路，面板只负责扣扳机，结果去竞技场看。
     * <p>
     * <b>不占 firedBoundary</b>：那是例行调度的去重位，手动唤醒占了它会把本边界真正的
     * K线收盘信号顶掉——手动是额外补一次，不该顶替例行。警报路径同理。
     *
     * @return null=已触发；非空=没触发的原因（原样给用户看）
     */
    public String tryManualWake(AiTrader trader) {
        // 面板按钮打过来的，在请求线程上，拒因跟界面语言
        String blocked = manualWakeBlockedReason(trader, RequestLang.current());
        if (blocked != null) {
            return blocked;
        }
        long intervalMs = INTERVAL_MS.get(trader.getIntervalCode());
        long now = nowMs.getAsLong();
        long boundary = now - Math.floorMod(now, intervalMs);
        // 预检只是"看一眼"不占位；真占位要在这里再抢一次——预检到现在之间可能有别人先进来了
        if (!inFlight.add(trader.getId())) {
            return messages.get("trader.wake.busy");
        }
        log.info("[TraderSched] 手动唤醒 traderId={} boundary={}", trader.getId(), boundary);
        Thread.startVirtualThread(() -> {
            try {
                runner.wakeManual(trader, boundary);
            } finally {
                inFlight.remove(trader.getId());
                drainTriggers(trader.getId());
            }
        });
        return null;
    }

    /**
     * K线收盘时刻 → interval 边界：Binance closeTime 是 xx:x9:59.999，
     * (closeTime+1) 恰好整除 interval 毫秒数才是该 interval 的收盘边界；否则 -1。
     */
    static long boundaryOf(long closeTime, String intervalCode) {
        Long ms = INTERVAL_MS.get(intervalCode);
        if (ms == null) {
            return -1;
        }
        long instant = closeTime + 1;
        return instant % ms == 0 ? instant : -1;
    }
}
