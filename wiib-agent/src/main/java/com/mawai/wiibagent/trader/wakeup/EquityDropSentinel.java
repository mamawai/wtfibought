package com.mawai.wiibagent.trader.wakeup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibquant.market.domain.KlineClosedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 权益回落哨兵：主人给 trader 设了比例的，每根 5m K 线收盘查一次它的权益。
 * 参照点是上次报警之后权益到过的最高点（没报过警就是启用以来的最高点），存在 ai_trader 上。
 * 权益比参照点高，参照点跟着抬；从参照点回落到比例，广播一条 {@link TriggerFired}，参照点从这一刻的权益重新记。
 * <p>
 * 只叫醒，不平仓，不限制开仓。休眠时段里不报，参照点也不重新记：回到时段内权益还在比例以下，照样报。
 * 两次例行轮之间只有单币轮在跑，没有哪一轮看整个账户，它补的是这一块。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EquityDropSentinel {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final AiTraderMapper traderMapper;
    private final SimTradeClient simTradeClient;
    private final ApplicationEventPublisher events;

    /** 查权益要调 sim，在哪跑：缺省起虚拟线程，不占 K 线事件的线程；测试里换成就地跑 */
    Executor offload = Thread::startVirtualThread;
    /** 墙钟注入点 */
    LongSupplier nowMs = System::currentTimeMillis;

    /** 已经查过的最近一根收盘时刻：每个币的 5m 收盘各来一次事件，同一刻只查一遍 */
    private final AtomicLong checkedCloseTime = new AtomicLong();

    @EventListener
    public void onKlineClosed(KlineClosedEvent event) {
        if (!"5m".equalsIgnoreCase(event.interval())) {
            return;
        }
        long now = nowMs.getAsLong();
        // 停机后重放的积压不查：权益是现查的，和旧 K 线对不上
        if (now - (event.closeTime() + 1) > TraderScheduler.STALE_EVENT_MS) {
            return;
        }
        if (checkedCloseTime.getAndAccumulate(event.closeTime(), Math::max) >= event.closeTime()) {
            return;
        }
        offload.execute(() -> checkAll(now));
    }

    private void checkAll(long now) {
        for (AiTrader t : traderMapper.selectList(new LambdaQueryWrapper<AiTrader>()
                .eq(AiTrader::getStatus, AiTrader.STATUS_RUNNING)
                .isNotNull(AiTrader::getEquityDropPct))) {
            try {
                check(t, now);
            } catch (Exception e) {
                // 一个查不到不连累别的，下一根 5m 再查
                log.warn("[EquityDrop] 权益检查失败 traderId={} msg={}", t.getId(), e.toString());
            }
        }
    }

    private void check(AiTrader t, long now) {
        BigDecimal equity = simTradeClient.getEquity(t.getSimUserId());
        BigDecimal peak = t.getEquityPeak();
        if (peak == null || equity.compareTo(peak) > 0) {
            mark(t, equity, now);
            return;
        }
        BigDecimal dropPct = peak.subtract(equity).multiply(HUNDRED).divide(peak, 2, RoundingMode.HALF_UP);
        if (dropPct.compareTo(t.getEquityDropPct()) < 0) {
            return;
        }
        WakeWindow window = WakeWindow.of(t);
        if (window != null && !window.contains(now)) {
            return;
        }
        EquityDropTrigger trigger = new EquityDropTrigger(peak, t.getEquityPeakAt(), equity, dropPct, t.getEquityDropPct(), now);
        mark(t, equity, now);
        log.info("[EquityDrop] 权益回落警报 traderId={} 参照点={} 现在={} 回落={}%", t.getId(), peak, equity, dropPct);
        events.publishEvent(new TriggerFired(t.getId(), trigger));
    }

    /** 参照点记到这一刻的权益上；查权益这一会儿它被重置开了新局、或者警报被关掉了，就不写 */
    private void mark(AiTrader t, BigDecimal equity, long now) {
        traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                .eq(AiTrader::getId, t.getId())
                .eq(AiTrader::getRoundNo, t.getRoundNo())
                .isNotNull(AiTrader::getEquityDropPct)
                .set(AiTrader::getEquityPeak, equity)
                .set(AiTrader::getEquityPeakAt, now));
    }
}
