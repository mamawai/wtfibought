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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * trader 调度器：5m K线收盘事件当唯一时钟，对齐到各 trader 的 interval 边界发例行唤醒；
 * 四个触发源（波动哨兵、盯盘哨兵、持仓事件监听、权益回落哨兵）广播来的非例行触发在这里分发。
 * <p>
 * 一个 trader 可以同时跑几轮：一轮整盘的（例行、手动、不归某一个币的触发），外加每个可交易币各一轮只处理它的单币轮。
 * 一条触发交给谁：先交给正在处理这个币的单币轮（插进它的对话）；没有就交给正在跑的整盘轮；都没有，新起一个单币轮。
 * 例行到点时有整盘轮在跑就插进它，不另起第二轮整盘的；有单币轮在跑不影响，例行轮照常开始。
 * 一轮结束时没被读到的触发另起一轮，不丢。
 * <p>
 * 事件是 per-symbol 的，同一边界多币多次触发靠 firedBoundary 去重。
 * 不做兜底补漏：WS/事件断流丢的K线就丢了——陈旧信号唤醒没有意义。收盘超过 5 分钟的事件（停机后重放的积压）
 * 只判收盘类盯盘条件，不发例行；5 分钟内迟到的由唤醒预算兜底：距下一边界不足 30s 直接放弃，见 TraderWakeupRunner。
 * 唤醒时段（ai_trader.wake_window，见 {@link WakeWindow}）只在这里过滤：例行/警报/持仓事件时段外静默跳过，手动唤醒不拦。
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
    /** 整盘轮在 {@link Desk#rounds} 里的键；单币轮的键是币码 */
    private static final String BOOK = "*";

    private final AiTraderMapper traderMapper;
    private final TraderWakeupRunner runner;
    /** 手动唤醒的拦因当场回给用户，跟界面语言 */
    private final MessageCatalog messages;
    private final EconCalendarGate calendarGate;
    /** 盯盘哨兵：5m 收盘时问它这根收盘命中了哪些收盘类条件 */
    private final WatchSentinel watchSentinel;
    /** 起新一轮之前，别的轮已经告诉过模型的盯盘命中不再单独叫 */
    private final TraderWatchStore watchStore;

    /**
     * 一个 trader 手上的几轮。读写都锁这个对象。
     * rounds：正在跑的轮，整盘轮至多一轮，每个币的单币轮至多一轮；
     * occupied：重置或删除占着，这期间不起新轮；parked：占着的时候来的触发，放手后再分发
     */
    private static final class Desk {
        final Map<String, WakeRound> rounds = new HashMap<>();
        final List<WakeTrigger> parked = new ArrayList<>();
        boolean occupied;
    }

    private final Map<Long, Desk> desks = new ConcurrentHashMap<>();
    /** 每 trader 最近已触发的边界时刻：多个 watch 币在同一刻收盘会各发一次事件，靠它去重 */
    private final Map<Long, Long> firedBoundary = new ConcurrentHashMap<>();

    /** 墙钟注入点：手动唤醒的预算预检、休眠时段判断要可测 */
    java.util.function.LongSupplier nowMs = System::currentTimeMillis;

    private Desk desk(long traderId) {
        return desks.computeIfAbsent(traderId, _ -> new Desk());
    }

    /** 主触发：任意 watch 币的 5m 收盘都是一次时钟滴答。 */
    @EventListener
    public void onKlineClosed(KlineClosedEvent event) {
        if (!"5m".equalsIgnoreCase(event.interval())) {
            return;
        }
        // 收盘类盯盘命中先落库，再发例行，最后分发命中：例行轮已经登记在跑，同一刻的命中交给它，不另起一轮。
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
            hits.forEach(t -> dispatch(t.watch().getTraderId(), t));
        }).exceptionally(e -> {
            log.error("[TraderSched] 例行触发失败 closeTime={} msg={}", event.closeTime(), e.toString());
            return null;
        });
    }

    private void fireInterval(String intervalCode, long boundary) {
        List<AiTrader> traders = traderMapper.selectList(new LambdaQueryWrapper<AiTrader>()
                .eq(AiTrader::getStatus, AiTrader.STATUS_RUNNING)
                .eq(AiTrader::getIntervalCode, intervalCode));
        for (AiTrader trader : traders) {
            // 时段外静默跳过：不占 firedBoundary——休眠就是没这回事
            WakeWindow window = WakeWindow.of(trader);
            if (window != null && !window.contains(boundary)) {
                continue;
            }
            // 这里可能并发进来，同一边界只发一次
            boolean[] won = new boolean[1];
            firedBoundary.compute(trader.getId(), (_, prev) -> {
                if (prev == null || prev < boundary) {
                    won[0] = true;
                    return boundary;
                }
                return prev;
            });
            if (won[0]) {
                fireRoutine(trader, boundary);
            }
        }
    }

    /** 例行到点：有整盘轮在跑就插进它，没有就起一轮；重置或删除占着的时候这根不发 */
    private void fireRoutine(AiTrader trader, long boundary) {
        Desk desk = desk(trader.getId());
        synchronized (desk) {
            if (desk.occupied) {
                log.info("[TraderSched] 重置或删除进行中，这根例行不发 traderId={} boundary={}", trader.getId(), boundary);
                return;
            }
            WakeRound book = desk.rounds.get(BOOK);
            if (book != null) {
                book.offerRoutine(boundary);
                log.info("[TraderSched] 整盘轮在跑，例行插进去 traderId={} boundary={}", trader.getId(), boundary);
                return;
            }
            start(desk, trader, WakeScope.book(trader), round -> runner.wake(trader, boundary, round));
        }
    }

    /**
     * 四个触发源（波动哨兵、盯盘哨兵、持仓事件监听、权益回落哨兵）广播过来的非例行触发都从这里进。
     * 广播的那条线程可能是行情订阅线程，分发要查库，统一挪到虚拟线程
     */
    @EventListener
    public void onTrigger(TriggerFired fired) {
        Thread.startVirtualThread(() -> dispatch(fired.traderId(), fired.trigger()));
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
     * 分发一条非例行触发（波动警报 / 持仓事件 / 盯盘命中 / 权益回落警报）。只有主人配的休眠时段不叫；
     * 分发时重新查库：它可能已经爆仓、被暂停或进了休眠时段。
     * 没叫的持仓事件和盯盘命中不会丢：下一次唤醒开头照样报。
     * 归某个可交易币的：先交给正在处理这个币的单币轮，没有就交给正在跑的整盘轮，都没有新起一个单币轮。
     * 不归某一个可交易币的（全仓爆仓、权益回落警报）：交给正在跑的整盘轮，没有就起一轮整盘的。
     */
    public void dispatch(long traderId, WakeTrigger trigger) {
        AiTrader trader = traderMapper.selectById(traderId);
        if (trader == null || !AiTrader.STATUS_RUNNING.equals(trader.getStatus())
                || !WakeIntervals.CODES.contains(trader.getIntervalCode())) {
            log.info("[TraderSched] trader 已不在运行或档位已下线，丢弃触发 traderId={} {}", traderId, trigger);
            return;
        }
        WakeWindow window = WakeWindow.of(trader);
        if (window != null && !window.contains(nowMs.getAsLong())) {
            // 休眠时段：警报和持仓事件都不叫——"其他时间段就不唤醒"的另一半
            log.info("[TraderSched] 休眠时段，丢弃触发 traderId={} {}", traderId, trigger);
            return;
        }
        WakeScope scope = WakeScope.of(trader, trigger.symbol());
        Desk desk = desk(traderId);
        synchronized (desk) {
            if (desk.occupied) {
                desk.parked.add(trigger);
                return;
            }
            WakeRound own = scope.single() ? desk.rounds.get(scope.symbol()) : null;
            WakeRound taker = own != null ? own : desk.rounds.get(BOOK);
            if (taker != null) {
                taker.offer(trigger);
                log.info("[TraderSched] 触发插进在跑的{}轮 traderId={} {}", own != null ? "单币" : "整盘", traderId, trigger);
                return;
            }
            // 别的轮已经把这条盯盘命中告诉过模型的，不再单独叫
            if (trigger instanceof WatchTrigger hit && !watchStore.wokenIds(List.of(hit.watch().getId())).isEmpty()) {
                return;
            }
            log.info("[TraderSched] 非例行唤醒 traderId={} 范围={} {}", traderId, scope.single() ? scope.symbol() : "整盘", trigger);
            start(desk, trader, scope, round -> runner.wakeTriggered(trader, List.of(trigger), round));
        }
    }

    /**
     * 登记一轮并起虚拟线程跑；跑完摘掉登记，没被读到的触发和例行另行处理。调用方已经锁着 desk。
     * 漏了摘登记这个范围就再也起不了轮，所以只写这一处
     */
    private void start(Desk desk, AiTrader trader, WakeScope scope, Consumer<WakeRound> work) {
        String key = scope.single() ? scope.symbol() : BOOK;
        WakeRound round = new WakeRound(scope, () -> singleSymbols(desk), nowMs.getAsLong());
        desk.rounds.put(key, round);
        Thread.startVirtualThread(() -> {
            try {
                work.accept(round);
            } finally {
                WakeRound.Arrivals unread;
                synchronized (desk) {
                    desk.rounds.remove(key);
                    unread = round.take();
                }
                // 这一轮已经在写结论、没再调模型，插进去的它读不到：例行重新发，触发重新分发
                if (unread.routineBoundary() != null) {
                    AiTrader now = traderMapper.selectById(trader.getId());
                    if (now != null && AiTrader.STATUS_RUNNING.equals(now.getStatus())) {
                        fireRoutine(now, unread.routineBoundary());
                    }
                }
                unread.triggers().forEach(t -> dispatch(trader.getId(), t));
            }
        });
    }

    /**
     * 有没有 atMs 那一刻就在跑、到现在还没跑完、看得到这个币的轮：整盘轮，或者只处理这个币的单币轮。
     * 立即复盘用：平掉这笔的那一轮在平仓那一刻就在跑，它的决策行要整轮跑完才落库
     */
    public boolean runningAt(long traderId, String symbol, long atMs) {
        Desk desk = desk(traderId);
        synchronized (desk) {
            return desk.rounds.entrySet().stream()
                    .anyMatch(e -> (BOOK.equals(e.getKey()) || e.getKey().equals(symbol)) && e.getValue().startedAt() <= atMs);
        }
    }

    /** 此刻有单币轮在处理的币 */
    private static Set<String> singleSymbols(Desk desk) {
        synchronized (desk) {
            Set<String> out = new TreeSet<>(desk.rounds.keySet());
            out.remove(BOOK);
            return out;
        }
    }

    /**
     * 手动唤醒的准入预检：不能唤醒时给出原因，能唤醒返回 null。
     * 动作面板拿它决定按钮点不点得动，与 {@link #tryManualWake} 共用同一份判断——
     * 面板显示的拒因就是真点下去会拿到的那一句。
     * <p>
     * 只查不占：面板是轮询刷新的，占位会把 trader 锁死。有单币轮在跑不拦，已经有一轮整盘的在跑才拦。
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
        Desk desk = desk(trader.getId());
        synchronized (desk) {
            return desk.occupied || desk.rounds.containsKey(BOOK) ? messages.get(lang, "trader.wake.busy") : null;
        }
    }

    /**
     * 占住这个 trader——重置和删除走这里，占着的时候它起不了新轮。有任何一轮在跑就占不上。
     * 占上必须在 finally 里 {@link #release}，否则这个 trader 从此醒不过来。
     */
    public boolean tryOccupy(long traderId) {
        Desk desk = desk(traderId);
        synchronized (desk) {
            if (desk.occupied || !desk.rounds.isEmpty()) {
                return false;
            }
            desk.occupied = true;
            return true;
        }
    }

    /** 放手：占着的时候来的触发现在分发 */
    public void release(long traderId) {
        Desk desk = desk(traderId);
        List<WakeTrigger> parked;
        synchronized (desk) {
            desk.occupied = false;
            parked = List.copyOf(desk.parked);
            desk.parked.clear();
        }
        parked.forEach(t -> dispatch(traderId, t));
    }

    /** trader 已删：摘掉两份 traderId 记账。删除那边全程 {@link #tryOccupy} 占着，删完在 finally 里 {@link #release} */
    public void forget(long traderId) {
        firedBoundary.remove(traderId);
        desks.remove(traderId);
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
     * 手动唤醒（动作面板的按钮扣扳机）：和例行轮一样是一轮整盘的，虚拟线程上异步跑。
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
        long boundary = WakeIntervals.align(nowMs.getAsLong(), intervalMs);
        Desk desk = desk(trader.getId());
        synchronized (desk) {
            // 预检只是"看一眼"不占位；预检到现在之间可能有别的整盘轮先起来了
            if (desk.occupied || desk.rounds.containsKey(BOOK)) {
                return messages.get("trader.wake.busy");
            }
            start(desk, trader, WakeScope.book(trader), round -> runner.wakeManual(trader, boundary, round));
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
