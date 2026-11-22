package com.mawai.wiibagent.trader.wakeup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.i18n.RequestLang;
import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import com.mawai.wiibquant.market.domain.KlineClosedEvent;
import com.mawai.wiibagent.mapper.AiTraderMapper;
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

/**
 * trader 调度器：5m K线收盘事件当唯一时钟，对齐到各 trader 的 interval 边界触发唤醒。
 * 每 trader 互斥（上一唤醒未完 → 记 SKIPPED，要最新信号不排陈旧任务），不设全局并发上限；
 * 事件是 per-symbol 的，同一边界多币多次触发靠 firedBoundary 去重。
 * 不做兜底补漏：WS/事件断流丢的K线就丢了——陈旧信号唤醒没有意义。收盘超过 5 分钟的事件（停机后重放的积压）
 * 只判收盘类盯盘条件，不发例行；5 分钟内迟到的由唤醒预算兜底：距下一边界不足 30s 直接放弃，见 TraderWakeupRunner。
 * 唤醒时段（ai_trader.wake_window，见 {@link WakeWindow}）只在这里过滤：例行/警报/持仓事件时段外静默跳过（不写 SKIPPED，
 * 15m 档一天会写 96 行"休眠中"），手动唤醒不拦。
 * 边界撞上 High 级数据公布时刻，例行触发先过 {@link EconCalendarGate}（最多等 30s 拿实际值）再发。
 * <p>
 * 复盘不归这里管：单笔复盘和统一复盘有自己的调度和位子（{@code ReviewScheduler}），不占唤醒位，日线边界和别的边界一样只发例行唤醒。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraderScheduler {

    /** 收盘超过这么久（一根 5m）的 K 线事件算旧事件 */
    static final long STALE_EVENT_MS = 300_000L;

    private final AiTraderMapper traderMapper;
    private final TraderWakeupRunner runner;
    /** 手动唤醒的拦因当场回给用户，跟界面语言 */
    private final MessageCatalog messages;
    private final EconCalendarGate calendarGate;
    /** 盯盘哨兵：5m 收盘时问它这根收盘命中了哪些收盘类条件，命中先落库，同一刻的例行轮开场白就能并进去念 */
    private final WatchSentinel watchSentinel;
    /** 领排队触发时，别的轮已经告诉过模型的盯盘命中不再单独叫 */
    private final TraderWatchStore watchStore;

    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    /** 每 trader 最近已触发的边界时刻：多个 watch 币在同一刻收盘会各发一次事件，靠它去重 */
    private final Map<Long, Long> firedBoundary = new ConcurrentHashMap<>();
    /** 每 trader 排队中的非例行触发（警报/持仓事件）：上一轮在跑时先攒着，它一结束就合成一轮叫醒，先来后到 */
    private final Map<Long, Queue<WakeTrigger>> pendingTriggers = new ConcurrentHashMap<>();

    /** 墙钟注入点：手动唤醒的预算预检、休眠时段判断要可测 */
    java.util.function.LongSupplier nowMs = System::currentTimeMillis;

    /** 主触发：任意 watch 币的 5m 收盘都是一次时钟滴答。 */
    @EventListener
    public void onKlineClosed(KlineClosedEvent event) {
        if (!"5m".equalsIgnoreCase(event.interval())) {
            return;
        }
        // 收盘类盯盘命中先落库，再发例行：同一刻醒的例行轮开场白里就带着它们；排进队列的那份等例行轮念过后被过滤掉。
        // 每个币的收盘事件都要评估
        List<WatchTrigger> hits = closeHits(event);
        // 停机后重放的积压：命中已落库，下一轮开场白补报；不发例行
        if (nowMs.getAsLong() - (event.closeTime() + 1) > STALE_EVENT_MS) {
            log.debug("[TraderSched] 旧K线事件不发唤醒 {} closeTime={}", event.symbol(), event.closeTime());
            return;
        }
        // 撞上数据公布时刻先等实际值再发本轮；没有待公布的 future 已完成，thenRun 就地同步执行，与不等一样。
        calendarGate.released(event.closeTime() + 1).thenRun(() -> {
            for (String ic : WakeIntervals.CODES) {
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

    /** 抢这个 trader 的互斥位，抢到就起虚拟线程跑；抢不到返回 null，怎么处理归调用方 */
    private Thread runIfFree(long traderId, Runnable work) {
        return inFlight.add(traderId) ? runHolding(traderId, work) : null;
    }

    /** 互斥位已经在手：起虚拟线程跑，跑完放位并领排队的触发。漏了放位这个 trader 就再也醒不过来，所以只写这一处 */
    private Thread runHolding(long traderId, Runnable work) {
        return Thread.startVirtualThread(() -> {
            try {
                work.run();
            } finally {
                release(traderId);
            }
        });
    }

    private void fireInterval(String intervalCode, long boundary) {
        List<AiTrader> traders = traderMapper.selectList(new LambdaQueryWrapper<AiTrader>()
                .eq(AiTrader::getStatus, AiTrader.STATUS_RUNNING)
                .eq(AiTrader::getIntervalCode, intervalCode));
        for (AiTrader trader : traders) {
            // 时段外静默跳过：不占 firedBoundary、不写 SKIPPED——休眠就是没这回事
            WakeWindow window = WakeWindow.of(trader);
            if (window != null && !window.contains(boundary)) {
                continue;
            }
            fireTrader(trader, boundary);
        }
    }

    /** 这里可能会并发firTrader，所以要做去重 */
    private void fireTrader(AiTrader trader, long boundary) {
        boolean[] won = new boolean[1];
        firedBoundary.compute(trader.getId(), (_, prev) -> {
            if (prev == null || prev < boundary) {
                won[0] = true;
                return boundary;
            }
            return prev;
        });
        if (!won[0]) {
            return;
        }
        if (runIfFree(trader.getId(), () -> runner.wake(trader, boundary)) == null) {
            runner.recordSkipped(trader, boundary); // 上次唤醒还在跑，本次边界作废并留痕，等下一根K线的新鲜信号
            log.info("[TraderSched] 上轮未完跳过 traderId={} boundary={}", trader.getId(), boundary);
        }
    }

    /**
     * 三个触发源（波动哨兵、盯盘哨兵、持仓事件监听）广播过来的非例行触发都从这里进。
     * 广播的那条线程可能是行情订阅线程，领触发要查库，统一挪到虚拟线程
     */
    @EventListener
    public void onTrigger(TriggerFired fired) {
        Thread.startVirtualThread(() -> tryTriggerWake(fired.traderId(), fired.trigger()));
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
     * 非例行唤醒（波动警报 / 持仓事件 / 盯盘命中）：进队列，然后立刻尝试领走。只有主人配的休眠时段不叫，
     * 在领的时候判。上一轮还在跑就先排着，先来后到，它一结束立刻补醒；
     * 排队期间攒下的几条合成一轮一起报。跑过例行边界时那根的例行轮跳过（SKIPPED 留痕）——突发行情里这一轮比按点醒更要紧。
     * 没叫的持仓事件和盯盘命中不会丢：下一次唤醒开头照样报。
     */
    public void tryTriggerWake(long traderId, WakeTrigger trigger) {
        pendingTriggers.computeIfAbsent(traderId, _ -> new ConcurrentLinkedQueue<>()).add(trigger);
        drainTriggers(traderId);
    }

    /**
     * 把该 trader 排队中的触发一次性领走叫醒；每一轮（例行/手动/非例行）结束时也来领一次。
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
            AiTrader trader = traderMapper.selectById(traderId);
            if (trader == null || !AiTrader.STATUS_RUNNING.equals(trader.getStatus())
                    || !WakeIntervals.CODES.contains(trader.getIntervalCode())) {
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
            runHolding(traderId, () -> runner.wakeTriggered(trader, batch));
            started = true;
        } finally {
            if (!started) {
                inFlight.remove(traderId);
                // 领的过程里又来了触发、它抢位失败：这里再领一次，不然要等下一轮结束才有人领
                if (!queue.isEmpty()) {
                    drainTriggers(traderId);
                }
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
        if (!WakeIntervals.CODES.contains(trader.getIntervalCode())) {
            return messages.get(lang, "trader.wake.intervalRetired");
        }
        long intervalMs = WakeIntervals.MS.get(trader.getIntervalCode());
        long now = nowMs.getAsLong();
        long boundary = WakeIntervals.align(now, intervalMs);
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

    /**
     * 占住这个 trader 的唤醒位——重置和删除走这里，占着的时候它醒不过来。
     * 抢到必须在 finally 里 {@link #release}，否则这个 trader 从此醒不过来。
     */
    public boolean tryOccupy(long traderId) {
        return inFlight.add(traderId);
    }

    public void release(long traderId) {
        inFlight.remove(traderId);
        drainTriggers(traderId);
    }

    /** trader 已删：摘掉两份 traderId 记账。inFlight 不在这摘——删除那边全程 {@link #tryOccupy} 占着唤醒位，删完在 finally 里 {@link #release} */
    public void forget(long traderId) {
        firedBoundary.remove(traderId);
        pendingTriggers.remove(traderId);
    }

    /**
     * 下一次例行唤醒的时刻；档位已下线返回 null。动作面板用它显示"再等多久就自动醒了"。
     * 有唤醒时段的跳到时段内的下一根——面板"下次例行"显示的必须是真会醒的那一刻
     */
    public Long nextRoutineWakeAt(AiTrader trader) {
        if (!WakeIntervals.CODES.contains(trader.getIntervalCode())) {
            return null;
        }
        long intervalMs = WakeIntervals.MS.get(trader.getIntervalCode());
        long now = nowMs.getAsLong();
        long next = WakeIntervals.align(now, intervalMs) + intervalMs;
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
     * <b>异步而不是同步等结果</b>：唤醒预算最长 900s，同步等于把调用方的连接压死几分钟；
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
        long intervalMs = WakeIntervals.MS.get(trader.getIntervalCode());
        long now = nowMs.getAsLong();
        long boundary = WakeIntervals.align(now, intervalMs);
        // 预检只是"看一眼"不占位；真占位要在这里再抢一次——预检到现在之间可能有别人先进来了
        if (runIfFree(trader.getId(), () -> runner.wakeManual(trader, boundary)) == null) {
            return messages.get("trader.wake.busy");
        }
        log.info("[TraderSched] 手动唤醒 traderId={} boundary={}", trader.getId(), boundary);
        return null;
    }

    /**
     * K线收盘时刻 → interval 边界：Binance closeTime 是 xx:x9:59.999，
     * (closeTime+1) 恰好整除 interval 毫秒数才是该 interval 的收盘边界；否则 -1。
     */
    static long boundaryOf(long closeTime, String intervalCode) {
        Long ms = WakeIntervals.MS.get(intervalCode);
        if (ms == null) {
            return -1;
        }
        long instant = closeTime + 1;
        return instant % ms == 0 ? instant : -1;
    }
}
