package com.mawai.wiibagent.trader.wakeup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.handover.Carried;
import com.mawai.wiibcommon.handover.InFlight;
import com.mawai.wiibcommon.handover.Intake;
import com.mawai.wiibcommon.handover.Start;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.i18n.RequestLang;
import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import com.mawai.wiibquant.market.domain.KlineClosedEvent;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * trader 调度器：5m K线收盘事件当唯一时钟，对齐到各 trader 的 interval 边界发例行唤醒；
 * 四个触发源（波动哨兵、盯盘哨兵、持仓事件监听、权益回落哨兵）广播来的非例行触发在这里分发。
 * <p>
 * 一个 trader 可以同时跑几轮：一轮整盘的（例行、手动、不归某一个币的触发），外加每个可交易币各一轮只处理它的单币轮。
 * 一条触发交给谁：先交给正在处理这个币的单币轮（插进它的对话）；没有就交给正在跑的整盘轮；都没有，新起一个单币轮。
 * 例行到点时有整盘轮在跑就插进它，不另起第二轮整盘的；有单币轮在跑不影响，例行轮照常开始。
 * 一轮结束时没被读到的触发另起一轮，不丢。轮和占用的登记都在 {@link Desks}，这里收事件、查库、判唤醒时段、定去处。
 * <p>
 * 事件是 per-symbol 的，同一边界多币多次触发靠 firedBoundary 去重。
 * 不做兜底补漏：WS/事件断流丢的K线就丢了——陈旧信号唤醒没有意义。收盘超过 5 分钟的事件（停机后重放的积压）
 * 只判收盘类盯盘条件，不发例行；5 分钟内迟到的由唤醒预算兜底：距下一边界不足 30s 直接放弃，见 TraderWakeupRunner。
 * 唤醒时段（ai_trader.wake_window，见 {@link WakeWindow}）只在这里过滤：例行/警报/持仓事件时段外静默跳过，手动唤醒不拦。
 * 边界撞上 High 级数据公布时刻，例行触发先过 {@link EconCalendarGate}（最多等 30s 拿实际值）再发。
 * <p>
 * 复盘不归这里管：单笔复盘和统一复盘有自己的调度和位子（{@code ReviewScheduler}），不占唤醒位，日线边界和别的边界一样只发例行唤醒。
 * <p>
 * 进活的口是"起新轮"：关着时不起新轮，到来的事交还给对端（交接时是新实例；冷切时没有对端，就丢了，
 * 持仓事件和盯盘命中库里有，下一轮开场白照样报）。在跑的轮是一份手上的活（wake-rounds）。
 * 交接时带走的是"trader-desks"：旧实例还持有的 trader（让位时有轮在跑、被占着或有预案在做），和每个 trader 最近发过的例行边界。
 * 新实例上被旧实例持有的 trader：到来的事先转过去，没接就停着，旧实例放手后分发；旧实例上最后一轮跑完就放手、通知新实例
 */
@Slf4j
@Component
@Order(60)
@RequiredArgsConstructor
public class TraderScheduler implements Intake, InFlight, Carried {

    /** 收盘超过这么久（一根 5m）的 K 线事件算旧事件 */
    static final long STALE_EVENT_MS = 300_000L;

    private final AiTraderMapper traderMapper;
    private final TraderWakeupRunner runner;
    /** 手动唤醒的拦因当场回给用户，跟界面语言 */
    private final MessageCatalog messages;
    private final EconCalendarGate calendarGate;
    /** 盯盘哨兵：5m 收盘时问它这根收盘命中了哪些收盘类条件 */
    private final WatchSentinel watchSentinel;
    /** 起新一轮之前，别的轮已经告诉过模型的盯盘命中不再单独叫 */
    private final TraderWatchStore watchStore;
    private final Desks desks;
    /** 交接期间的另一个实例 */
    private final TraderPeer peer;
    /** 旧实例放手前，等这个 trader 在途的预案做完 */
    private final PresetExecutor presets;
    /** 旧实例放手后，完成这个 trader 的实时页连接 */
    private final TraderLiveHub liveHub;

    /** 每 trader 最近已触发的边界时刻：多个 watch 币在同一刻收盘会各发一次事件，靠它去重 */
    private final Map<Long, Long> firedBoundary = new ConcurrentHashMap<>();
    /** 起新轮的口 */
    private volatile boolean open;

    /** 墙钟注入点：手动唤醒的预算预检、休眠时段判断要可测 */
    java.util.function.LongSupplier nowMs = System::currentTimeMillis;

    /** 开口：不再替对端持有任何 trader；口关着时交还来、停着的事现在分发 */
    @Override
    public void open(Start how) {
        open = true;
        desks.clearHeldHere();
        desks.drainParked().forEach(this::redispatch);
    }

    /** 关口：有轮在跑、被占着、有预案在做的 trader 记成"还持有"，只有预案在做的等预案做完就放手 */
    @Override
    public void close() {
        open = false;
        desks.markHeldHere(presets.busyTraders());
        for (long id : desks.heldHere()) {
            presets.whenIdle(id).thenRun(() -> releaseIfIdle(id));
        }
    }

    @Override
    public String name() {
        return "wake-rounds";
    }

    /** 在跑的轮数 */
    @Override
    public int count() {
        return desks.roundCount();
    }

    @Override
    public String key() {
        return "trader-desks";
    }

    /** 还持有的 trader，和每个 trader 最近发过的例行边界 */
    @Override
    public JsonNode export() {
        ObjectNode out = MAPPER.createObjectNode();
        desks.heldHere().forEach(out.putArray("held")::add);
        ObjectNode fired = out.putObject("firedBoundary");
        firedBoundary.forEach((id, b) -> fired.put(String.valueOf(id), b));
        return out;
    }

    /** 持有名单里的标成旧实例持有；例行边界并进来，取大的，同一个边界不再发一遍 */
    @Override
    public void accept(JsonNode state) {
        List<Long> held = new ArrayList<>();
        state.path("held").forEach(n -> held.add(n.asLong()));
        desks.markHeldByOld(held);
        state.path("firedBoundary").properties().forEach(e ->
                firedBoundary.merge(Long.parseLong(e.getKey()), e.getValue().asLong(), Math::max));
        log.info("[TraderSched] 收下交接单：旧实例持有 {}", held);
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
            if (claim(trader.getId(), boundary)) {
                fireRoutine(trader, boundary, true);
            }
        }
    }

    /** 占下这个 trader 的这根例行边界：没发过这根或更晚的才占得上 */
    private boolean claim(long traderId, long boundary) {
        boolean[] won = new boolean[1];
        firedBoundary.compute(traderId, (_, prev) -> {
            if (prev == null || prev < boundary) {
                won[0] = true;
                return boundary;
            }
            return prev;
        });
        return won[0];
    }

    /**
     * 例行到点：有整盘轮在跑就插进它，没有就起一轮；重置或删除占着的时候这根不发；旧实例持有的转过去。
     * fresh：这根是这里到点发的，不是没读到重发的
     */
    private void fireRoutine(AiTrader trader, long boundary, boolean fresh) {
        long id = trader.getId();
        Desks.Placement p = desks.routine(id, WakeScope.book(trader), boundary, () -> open, nowMs.getAsLong());
        switch (p.place()) {
            case STARTED -> run(trader, p, round -> runner.wake(trader, boundary, round));
            case TAKEN -> log.info("[TraderSched] 整盘轮在跑，例行插进去 traderId={} boundary={}", id, boundary);
            case DROPPED -> log.info("[TraderSched] 重置或删除进行中，这根例行不发 traderId={} boundary={}", id, boundary);
            case CLOSED -> handBack(id, new Arrival.Routine(boundary), fresh);
            // 收盘事件线程上逐个 trader 发例行，调对端不占它
            case HELD_BY_OLD -> Thread.startVirtualThread(() -> toOld(id, new Arrival.Routine(boundary)));
            default -> throw new IllegalStateException("例行不会落到 " + p.place());
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
        // 别的轮已经把这条盯盘命中告诉过模型的，不再单独叫
        Desks.Placement p = desks.trigger(traderId, scope, trigger, () -> open,
                t -> t instanceof WatchTrigger hit && !watchStore.wokenIds(List.of(hit.watch().getId())).isEmpty(),
                nowMs.getAsLong());
        switch (p.place()) {
            case STARTED -> {
                log.info("[TraderSched] 非例行唤醒 traderId={} 范围={} {}", traderId, scope.single() ? scope.symbol() : "整盘", trigger);
                run(trader, p, round -> runner.wakeTriggered(trader, List.of(trigger), round));
            }
            case TAKEN -> log.info("[TraderSched] 触发插进在跑的轮 traderId={} {}", traderId, trigger);
            case CLOSED -> handBack(traderId, new Arrival.Trigger(trigger), false);
            case HELD_BY_OLD -> toOld(traderId, new Arrival.Trigger(trigger));
            default -> {
                // PARKED：占着的时候停着，放手后分发；SKIPPED：别的轮已经告诉过模型
            }
        }
    }

    /**
     * 起登记好的那一轮的线程；跑完摘掉登记，没被读到的例行重新发、触发重新分发，旧实例上再看能不能放手这个 trader。
     * 漏了摘登记这个范围就再也起不了轮，所以只写这一处
     */
    private void run(AiTrader trader, Desks.Placement placement, Consumer<WakeRound> work) {
        long id = trader.getId();
        Thread.startVirtualThread(() -> {
            try {
                work.accept(placement.round());
            } finally {
                WakeRound.Arrivals unread = desks.finish(id, placement.key(), placement.round());
                // 这一轮已经在写结论、没再调模型，插进去的它读不到：例行重新发，触发重新分发
                if (unread.routineBoundary() != null) {
                    redispatch(id, new Arrival.Routine(unread.routineBoundary()));
                }
                unread.triggers().forEach(t -> dispatch(id, t));
                releaseIfIdle(id);
            }
        });
    }

    /** 起新轮的口关着：交还给对端（旧实例收尾时交给新实例；冷切没有对端，就丢了） */
    private void handBack(long traderId, Arrival arrival, boolean fresh) {
        log.info("[TraderSched] 起新轮的口关着，交还对端 traderId={} {}", traderId, arrival);
        try {
            peer.handBack(traderId, arrival, fresh);
        } catch (Exception e) {
            log.warn("[TraderSched] 交还对端失败 traderId={} {} msg={}", traderId, arrival, e.toString());
        }
    }

    /** 新实例：旧实例持有的 trader，到来的事转过去；接了就结束，没接或调不通就停着，已不持有就本地处理 */
    private void toOld(long traderId, Arrival arrival) {
        Offer offer;
        try {
            offer = peer.offer(traderId, arrival);
        } catch (Exception e) {
            log.warn("[TraderSched] 转给旧实例失败，先停着 traderId={} {} msg={}", traderId, arrival, e.toString());
            offer = Offer.NOT_TAKEN;
        }
        switch (offer) {
            case TAKEN -> log.info("[TraderSched] 旧实例接了 traderId={} {}", traderId, arrival);
            case NOT_TAKEN -> {
                if (desks.parkWhileHeld(traderId, arrival)) {
                    log.info("[TraderSched] 旧实例没接，停着等它放手 traderId={} {}", traderId, arrival);
                } else {
                    redispatch(traderId, arrival);
                }
            }
            case NOT_HELD -> {
                releasedByOld(traderId);
                redispatch(traderId, arrival);
            }
        }
    }

    /** 按平时规则重新处理一件事：例行重新读一遍 trader、还在运行才发；触发重新分发 */
    private void redispatch(long traderId, Arrival arrival) {
        switch (arrival) {
            case Arrival.Routine r -> {
                AiTrader now = traderMapper.selectById(traderId);
                if (now != null && AiTrader.STATUS_RUNNING.equals(now.getStatus())) {
                    fireRoutine(now, r.boundary(), false);
                }
            }
            case Arrival.Trigger t -> dispatch(traderId, t.trigger());
        }
    }

    private void redispatch(long traderId, Desks.Parked parked) {
        if (parked.routine() != null) {
            redispatch(traderId, new Arrival.Routine(parked.routine()));
        }
        parked.triggers().forEach(t -> dispatch(traderId, t));
    }

    // ---------- 对端打进来的 ----------

    /** 旧实例：新实例转来的事，交给在跑的轮 */
    public Offer offerFromNew(long traderId, Arrival arrival) {
        AiTrader trader = traderMapper.selectById(traderId);
        if (trader == null) {
            return Offer.NOT_HELD;
        }
        WakeScope scope = arrival instanceof Arrival.Trigger t ? WakeScope.of(trader, t.trigger().symbol()) : WakeScope.book(trader);
        return desks.offer(traderId, scope, arrival);
    }

    /**
     * 新实例：旧实例交还来的。fresh 的例行（旧实例关口后才到点发的）先占边界，这里已经发过这根就不要了；
     * 还被持有或还没开口就停着，否则按平时规则
     */
    public void handedBack(long traderId, Arrival arrival, boolean fresh) {
        if (fresh && arrival instanceof Arrival.Routine r && !claim(traderId, r.boundary())) {
            log.info("[TraderSched] 交还来的例行这里已经发过 traderId={} boundary={}", traderId, r.boundary());
            return;
        }
        if (!desks.parkHandedBack(traderId, arrival, () -> open)) {
            redispatch(traderId, arrival);
        }
    }

    /** 新实例：旧实例放手了这个 trader，停着的例行和触发现在分发；重复收到或本来就不被持有忽略 */
    public void releasedByOld(long traderId) {
        Desks.Parked parked = desks.releaseFromOld(traderId);
        if (parked != null) {
            log.info("[TraderSched] 旧实例放手 traderId={}，分发停着的 {}", traderId, parked);
            redispatch(traderId, parked);
        }
    }

    /** 新实例：旧实例没了（探活失败），持有的全部当作放手 */
    public void releaseAllHeldByOld() {
        desks.heldByOld().forEach(this::releasedByOld);
    }

    /** 旧实例：此刻还持有的 trader */
    public java.util.Set<Long> heldHere() {
        return desks.heldHere();
    }

    /**
     * 旧实例：这个 trader 没有轮、没被占着、没有预案在做了就放手——摘出持有名单（之后对端转来的回"已不持有"），
     * 通知新实例，完成它的实时页连接
     */
    public void releaseIfIdle(long traderId) {
        if (!desks.releaseHere(traderId, id -> !presets.busy(id))) {
            return;
        }
        log.info("[TraderSched] 放手 traderId={}", traderId);
        Thread.startVirtualThread(() -> {
            peer.released(traderId, TraderPeer.Held.WAKE);
            liveHub.completeFor(traderId);
        });
    }

    // ---------- 动作面板 ----------

    /**
     * 有没有 atMs 那一刻就在跑、到现在还没跑完、看得到这个币的轮：整盘轮，或者只处理这个币的单币轮。
     * 立即复盘用：平掉这笔的那一轮在平仓那一刻就在跑，它的决策行要整轮跑完才落库。旧实例持有的当作有
     */
    public boolean runningAt(long traderId, String symbol, long atMs) {
        return desks.runningAt(traderId, symbol, atMs);
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
        return desks.manualBlocked(trader.getId(), () -> open) ? messages.get(lang, "trader.wake.busy") : null;
    }

    /**
     * 占住这个 trader——重置和删除走这里，占着的时候它起不了新轮。有任何一轮在跑、或被旧实例持有就占不上。
     * 占上必须在 finally 里 {@link #release}，否则这个 trader 从此醒不过来。
     */
    public boolean tryOccupy(long traderId) {
        return desks.tryOccupy(traderId);
    }

    /** 放手：占着的时候来的触发现在分发；旧实例上再看能不能放手这个 trader */
    public void release(long traderId) {
        desks.releaseOccupied(traderId).forEach(t -> dispatch(traderId, t));
        releaseIfIdle(traderId);
    }

    /** trader 已删：摘掉两份 traderId 记账。删除那边全程 {@link #tryOccupy} 占着，删完在 finally 里 {@link #release} */
    public void forget(long traderId) {
        firedBoundary.remove(traderId);
        desks.forget(traderId);
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
        // 预检只是"看一眼"不占位；预检到现在之间可能有别的整盘轮先起来了，起新轮的口也可能刚关上
        Desks.Placement p = desks.manual(trader.getId(), WakeScope.book(trader), () -> open, nowMs.getAsLong());
        if (p.place() != Desks.Place.STARTED) {
            return messages.get("trader.wake.busy");
        }
        run(trader, p, round -> runner.wakeManual(trader, boundary, round));
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
