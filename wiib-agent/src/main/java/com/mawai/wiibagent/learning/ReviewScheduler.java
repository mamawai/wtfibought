package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.trader.wakeup.WakeIntervals;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.handover.InFlight;
import com.mawai.wiibcommon.handover.Intake;
import com.mawai.wiibcommon.handover.Start;
import com.mawai.wiibquant.market.domain.KlineClosedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 复盘的调度：5m K 线收盘当时钟。每根收盘给每个交易员扫一次到期的单笔复盘；
 * 日线边界（新加坡时间 08:00）那一根改成给每个交易员起一篇统一复盘。
 * 管的是开着复盘开关、运行中或已爆仓的交易员，不看唤醒时段；暂停的不管，恢复运行后补上。
 * <p>
 * 复盘位：每个交易员一个，单笔复盘、统一复盘、点播复盘、交易卡上的立即复盘共用，一次只跑一个。它和唤醒位是两回事，复盘不挡唤醒。
 * 到期的单笔复盘抢不到就等下一根 5m；08:00 的统一复盘抢不到就等占着的那一篇跑完；点播复盘和立即复盘抢不到当场回绝。
 * 重置和删除要同时拿到唤醒位和这里的复盘位。
 * <p>
 * 进活的口是"起新复盘"：关着时 {@link #tryOccupy} 一律抢不到（点播复盘、立即复盘也从这里占位），
 * 统一复盘等到位子也不跑。占着的复盘位是一份手上的活（reviews）。
 */
@Slf4j
@Component
@Order(50)
@RequiredArgsConstructor
public class ReviewScheduler implements Intake, InFlight {

    /** 收盘超过这么久（一根 5m）的 K 线事件算旧事件，不扫 */
    static final long STALE_EVENT_MS = 300_000L;
    /** 哪些状态的交易员要复盘：运行中的和已爆仓的，暂停的不在内 */
    private static final List<String> REVIEWED_STATUSES = List.of(AiTrader.STATUS_RUNNING, AiTrader.STATUS_LIQUIDATED);

    private final AiTraderMapper traderMapper;
    private final DebriefRunner debriefRunner;
    private final ReviewRunner reviewRunner;

    /** 复盘位，每个交易员一把 */
    private final Map<Long, Semaphore> slots = new ConcurrentHashMap<>();
    /** 最近处理过的 5m 边界：每个币的收盘各发一次事件，一根只处理一次 */
    private final AtomicLong lastBoundary = new AtomicLong(-1);
    /** 起新复盘的口 */
    private volatile boolean open;

    /** 墙钟注入点 */
    LongSupplier nowMs = System::currentTimeMillis;

    @Override
    public void open(Start how) {
        open = true;
    }

    @Override
    public void close() {
        open = false;
    }

    @Override
    public String name() {
        return "reviews";
    }

    /** 占着的复盘位数 */
    @Override
    public int count() {
        return (int) slots.values().stream().filter(s -> s.availablePermits() == 0).count();
    }

    @EventListener
    public void onKlineClosed(KlineClosedEvent event) {
        if (!"5m".equalsIgnoreCase(event.interval())) {
            return;
        }
        long boundary = event.closeTime() + 1;
        // 停机后重放的积压不处理
        if (nowMs.getAsLong() - boundary > STALE_EVENT_MS) {
            return;
        }
        long prev = lastBoundary.get();
        if (prev >= boundary || !lastBoundary.compareAndSet(prev, boundary)) {
            return;
        }
        boolean daily = boundary % WakeIntervals.MS.get("1d") == 0;
        // 这里出错不许抛回事件发布方：同一根 K 线上别的监听（发例行唤醒的、哨兵）还要收这个事件
        try {
            for (AiTrader t : traderMapper.selectList(new LambdaQueryWrapper<AiTrader>()
                    .in(AiTrader::getStatus, REVIEWED_STATUSES))) {
                if (Boolean.FALSE.equals(t.getReviewEnabled())) {
                    continue;
                }
                long traderId = t.getId();
                Thread.startVirtualThread(daily ? () -> unifiedReview(traderId, boundary) : () -> debriefIfDue(traderId));
            }
        } catch (Exception e) {
            log.error("[ReviewSched] 复盘调度失败 boundary={} msg={}", boundary, e.toString());
        }
    }

    /** 这个交易员有到期的单笔复盘就跑一笔；复盘位被占着就算了，下一根 5m 再来 */
    private void debriefIfDue(long traderId) {
        if (!tryOccupy(traderId)) {
            return;
        }
        try {
            AiTrader trader = eligible(traderId);
            DebriefRunner.Due due = trader == null ? null : debriefRunner.next(trader, nowMs.getAsLong());
            if (due != null) {
                debriefRunner.debrief(trader, due);
            }
        } catch (Exception e) {
            log.warn("[ReviewSched] 单笔复盘异常逃逸 traderId={} msg={}", traderId, e.toString());
        } finally {
            release(traderId);
        }
    }

    /** 日线边界的统一复盘：复盘位被占着就等它放出来 */
    private void unifiedReview(long traderId, long boundary) {
        slot(traderId).acquireUninterruptibly();
        try {
            // 等位子的时候起新复盘的口关上了，就不跑
            AiTrader trader = open ? eligible(traderId) : null;
            if (trader != null) {
                reviewRunner.review(trader, boundary);
            }
        } catch (Exception e) {
            // review() 自己兜住模型调用的失败并留 ERROR 行；这里兜的是它之前那几步库查询
            log.warn("[ReviewSched] 统一复盘异常逃逸 traderId={} msg={}", traderId, e.toString());
        } finally {
            release(traderId);
        }
    }

    /** 拿到复盘位之后重新读一遍：等位子这段时间它可能被暂停、关了复盘开关，或者已经删了 */
    private AiTrader eligible(long traderId) {
        AiTrader t = traderMapper.selectById(traderId);
        return t != null && REVIEWED_STATUSES.contains(t.getStatus())
                && !Boolean.FALSE.equals(t.getReviewEnabled()) ? t : null;
    }

    private Semaphore slot(long traderId) {
        return slots.computeIfAbsent(traderId, _ -> new Semaphore(1));
    }

    /** 这个交易员现在起不了复盘：正在复盘，或者起新复盘的口关着。只查不占 */
    public boolean isBusy(long traderId) {
        return !open || slot(traderId).availablePermits() == 0;
    }

    /** 抢复盘位（到期的单笔复盘、点播复盘、立即复盘、重置、删除）；口关着一律抢不到。抢到必须在 finally 里 {@link #release} */
    public boolean tryOccupy(long traderId) {
        if (!slot(traderId).tryAcquire()) {
            return false;
        }
        // 占上了再看口：关口那边先关口再数占着的，两边总有一边看得到对方
        if (!open) {
            release(traderId);
            return false;
        }
        return true;
    }

    public void release(long traderId) {
        slot(traderId).release();
    }
}
