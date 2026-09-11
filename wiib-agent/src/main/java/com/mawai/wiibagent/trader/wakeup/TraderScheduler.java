package com.mawai.wiibagent.trader.wakeup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibagent.learning.LearningRunner;
import com.mawai.wiibagent.learning.PeerInsightService;
import com.mawai.wiibagent.learning.ReviewRunner;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * trader 调度器：5m K线收盘事件当唯一时钟，对齐到各 trader 的 interval 边界触发唤醒。
 * 并发上限 10（信号量）+ 每 trader 互斥（上一唤醒未完 → 记 SKIPPED，要最新信号不排陈旧任务）；
 * 事件是 per-symbol 的，同一边界多币多次触发靠 firedBoundary 去重。
 * 不做兜底补漏：WS/事件断流丢的K线就丢了——陈旧信号唤醒没有意义（迟到事件由唤醒预算兜底：
 * 距下一边界不足 30s 直接放弃，见 TraderWakeupRunner）。
 * 唤醒时段（ai_trader.wake_window，见 {@link WakeWindow}）只在这里过滤：例行/警报时段外静默跳过（不写 SKIPPED，
 * 5m 档一天会写 150 行"休眠中"），手动唤醒不拦，日线交接的复盘/学习不看它。
 * <p>
 * 日线边界走三阶段交接（见 {@link #startDailyHandover}）：先交易、再全体复盘、最后全体学习，
 * 复盘与学习之间是全局屏障——learning 读的是同侪<b>刚写好</b>的复盘，没有屏障，同一轮学习里
 * 各人看到的世界就不一样。停工窗口在阶段0的唤醒发出后就开、交接结束才关，期间拒绝一切新唤醒
 * （例行/警报/手动/点播复盘），复盘与学习读的必须是"已定格的一天"，边写边读的脏读会进记忆污染后续每一轮。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraderScheduler {

    static final int MAX_CONCURRENT_WAKEUPS = 10;
    /** interval → 毫秒；唤醒预算、建 trader 校验、提示词都共用同一份。1d 只留数学（日线交接边界用），不再是唤醒档位 */
    public static final Map<String, Long> INTERVAL_MS = Map.of(
            "5m", 300_000L, "15m", 900_000L, "1h", 3_600_000L, "4h", 14_400_000L, "1d", 86_400_000L);
    /** 例行唤醒只遍历四档（1d 档位已下线，存量 1d trader 自然停摆） */
    static final Set<String> WAKE_INTERVALS = Set.of("5m", "15m", "1h", "4h");
    /**
     * 学习的同侪门槛：同侪池（{@link PeerInsightService#peers()}：同意学习 + 未暂停 + 在场）里
     * 除自己外不足 2 人，该 learner 本日不学。池是同一把尺子：不在池里的既凑不了人数，也进不了任何人的素材。
     * 学习者自己不必在池里——刚开局还没开过仓的新人恰恰最该学。
     */
    static final int MIN_PEERS_FOR_LEARNING = 2;

    private final AiTraderMapper traderMapper;
    private final TraderWakeupRunner runner;
    private final ReviewRunner reviewRunner;
    private final LearningRunner learningRunner;
    private final PeerInsightService peerInsightService;
    /** 手动唤醒的拦因当场回给用户，跟界面语言 */
    private final MessageCatalog messages;

    /** 警报冷静期：距该 trader 上一次任何唤醒（例行/警报）不足 5 分钟不再警报 */
    static final long ALERT_COOLDOWN_MS = 5 * 60_000L;

    /** 同一 trader 不并行的拒因；预检与真占位两处共用一份措辞 */

    private final Semaphore slots = new Semaphore(MAX_CONCURRENT_WAKEUPS);
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    /** 每 trader 最近已触发的边界时刻：多个 watch 币在同一刻收盘会各发一次事件，靠它去重 */
    private final Map<Long, Long> firedBoundary = new ConcurrentHashMap<>();
    /** 每 trader 最近一次唤醒起始时刻（例行+警报都记）：警报冷静期的基准；重启清零无所谓 */
    private final Map<Long, Long> lastWakeAt = new ConcurrentHashMap<>();
    /** 日线交接的边界去重位：多 symbol 在日线边界各发一次事件，交接只许启动一次 */
    private final AtomicLong lastHandoverBoundary = new AtomicLong(-1);
    /** 停工窗口位：交接编排线程独写，事件/警报/手动入口只读
     * -- GETTER --
     * 停工窗口是否开着：点播复盘（chat 轨）入场前也要看它，三阶段期间不许旁路写复盘
     */
    @Getter
    private volatile boolean handoverActive = false;

    /** 墙钟注入点：警报准入的冷静期/预算预检要可测 */
    java.util.function.LongSupplier nowMs = System::currentTimeMillis;

    /** 主触发：任意 watch 币的 5m 收盘都是一次时钟滴答。 */
    @EventListener
    public void onKlineClosed(KlineClosedEvent event) {
        if (!"5m".equalsIgnoreCase(event.interval())) {
            return;
        }
        if (handoverActive) {
            // 窗口内的K线事件直接丢弃不补跑：与"不做兜底补漏"同一条哲学——陈旧信号没有意义。
            // 代价是 5m 档最多跳过 5 根K线（窗口上界＝唤醒600s＋复盘600s＋学习300s），每天只有一次，可接受
            log.info("[TraderSched] 日线交接中，丢弃K线事件 {} closeTime={}", event.symbol(), event.closeTime());
            return;
        }
        long dayBoundary = boundaryOf(event.closeTime(), "1d");
        if (dayBoundary > 0) {
            startDailyHandover(dayBoundary);
            return;
        }
        for (String ic : WAKE_INTERVALS) {
            long boundary = boundaryOf(event.closeTime(), ic);
            if (boundary > 0) {
                fireInterval(ic, boundary);
            }
        }
    }

    /**
     * 日线交接编排（本功能唯一的全局同步点）：
     * 阶段0 全体例行唤醒发出 → 【停工窗口开】→ 等交易跑完 → 阶段1 全体复盘并行 →
     * 屏障（等全部复盘落库）→ 阶段2 全体学习并行 → 【停工窗口关】。
     * 屏障保证每个 learner 读到的是同侪同一天的复盘；窗口保证复盘/学习读的是定格数据。
     * 各阶段单人超时都有硬顶（唤醒600s/复盘600s/学习300s），join 不会永久卡住。
     */
    private void startDailyHandover(long boundary) {
        // 原子抢占：日线边界的多 symbol 事件里只有一个赢家启动交接
        long prev = lastHandoverBoundary.get();
        if (prev >= boundary || !lastHandoverBoundary.compareAndSet(prev, boundary)) {
            return;
        }
        Thread.startVirtualThread(() -> {
            // 阶段0：日线边界同时是四档的边界，全体例行唤醒照常发出
            List<Thread> wakes = new ArrayList<>();
            for (String ic : WAKE_INTERVALS) {
                wakes.addAll(fireInterval(ic, boundary));
            }
            // 发完就关门：在途的照跑，新来的K线/警报/手动全拒——不然 5m 档在阶段0期间又醒一次占住 inFlight，复盘就被跳过
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
                // ===== 屏障已过：全部复盘落库，learning 读到的同侪世界是同一天的 =====
                List<AiTrader> pool = peerInsightService.peers();
                if (pool.size() < MIN_PEERS_FOR_LEARNING) {
                    // 设计定案的降级：同侪不足整体静默跳过，不写空话也不留 ERROR 行
                    log.info("[TraderSched] 同侪池不足{}人（现{}人），本日学习整体跳过", MIN_PEERS_FOR_LEARNING, pool.size());
                    return;
                }
                Set<Long> poolIds = pool.stream().map(AiTrader::getId).collect(java.util.stream.Collectors.toSet());
                // 阶段2：全体学习并行。再 fresh 一次——阶段1刚写完 memory，learner 注入要拿最新的
                joinAll(phase(running().stream()
                        .filter(t -> !Boolean.FALSE.equals(t.getLearningEnabled()))
                        // 自己在池里就占一格，剩下的才是同侪
                        .filter(t -> pool.size() - (poolIds.contains(t.getId()) ? 1 : 0) >= MIN_PEERS_FOR_LEARNING)
                        .toList(),
                        t -> learningRunner.learn(t, boundary), "学习"));
            } finally {
                // 异常也不许卡死窗口：窗口关不上，全体 trader 就永久停摆了
                handoverActive = false;
                log.info("[TraderSched] 日线交接结束 boundary={}，停工窗口关", boundary);
            }
        });
    }

    /**
     * 交接的一个阶段：每 trader 一个虚拟线程（复用 slots 并发闸与 inFlight 互斥），
     * 返回线程列表由调用方 join 成屏障。
     * inFlight 抢不到＝上一边界的交易还没跑完（预算最长600s），该 trader 本阶段跳过——
     * 硬等会拖住全体，而复盘/学习明天还有机会；这也是屏障不脏读的第二道闸：
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
                    slots.acquire();
                    try {
                        action.accept(t);
                    } finally {
                        slots.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    // 单人异常不许拖垮屏障：runner 内部已有 ERROR 行留痕，这里兜住意外逃逸的
                    log.warn("[TraderSched] {}异常逃逸 traderId={} msg={}", label, t.getId(), e.getMessage());
                } finally {
                    inFlight.remove(t.getId());
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
        lastWakeAt.put(trader.getId(), nowMs.getAsLong());
        return Thread.startVirtualThread(() -> {
            try {
                slots.acquire();
                try {
                    runner.wake(trader, boundary);
                } finally {
                    slots.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.remove(trader.getId());
            }
        });
    }

    /**
     * 波动哨兵警报唤醒准入（唤醒治理全在调度器）：冷静期→预算预检→互斥，全过才真唤醒。
     * 任一环节不过都静默放弃只留日志——警报是补充不是义务，SKIPPED 决策行只属于例行调度。
     */
    public void tryAlertWake(AiTrader trader, AlertTrigger trigger) {
        if (handoverActive) {
            // 停工窗口挡警报：复盘/学习读的是定格数据，警报唤醒一开仓就是脏读
            log.info("[TraderSched] 日线交接中，警报放弃 traderId={} {}", trader.getId(), trigger.symbol());
            return;
        }
        long now = nowMs.getAsLong();
        WakeWindow window = WakeWindow.of(trader);
        if (window != null && !window.contains(now)) {
            // 休眠时段：警报也不叫——"其他时间段就不唤醒"的另一半
            return;
        }
        Long last = lastWakeAt.get(trader.getId());
        if (last != null && now - last < ALERT_COOLDOWN_MS) {
            return;
        }
        Long intervalMs = INTERVAL_MS.get(trader.getIntervalCode());
        if (intervalMs == null) {
            return;
        }
        // 例行唤醒将至就不抢戏：马上有新鲜K线信号，警报没有增量价值
        long boundary = now - Math.floorMod(now, intervalMs);
        if (TraderWakeupRunner.wakeBudgetSeconds(boundary, intervalMs, now) < TraderWakeupRunner.MIN_WAKE_SECONDS) {
            log.info("[TraderSched] 例行唤醒将至，警报放弃 traderId={} {}", trader.getId(), trigger.symbol());
            return;
        }
        if (!inFlight.add(trader.getId())) {
            return;
        }
        lastWakeAt.put(trader.getId(), now);
        log.info("[TraderSched] 波动警报唤醒 traderId={} {} 振幅{}% {}",
                trader.getId(), trigger.symbol(), trigger.amplitudePct(), trigger.direction());
        Thread.startVirtualThread(() -> {
            try {
                slots.acquire();
                try {
                    runner.wakeAlert(trader, trigger);
                } finally {
                    slots.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.remove(trader.getId());
            }
        });
    }

    /**
     * 手动唤醒的准入预检：不能唤醒时给出原因，能唤醒返回 null。
     * 动作面板拿它决定按钮点不点得动，与 {@link #tryManualWake} 共用同一份判断——
     * 面板显示的拒因就是真点下去会拿到的那一句。
     * <p>
     * inFlight 这条只查不占：面板是轮询刷新的，占位会把 trader 锁死。
     */
    public String manualWakeBlockedReason(AiTrader trader) {
        if (handoverActive) {
            return messages.get("trader.wake.handover");
        }
        Long intervalMs = INTERVAL_MS.get(trader.getIntervalCode());
        if (intervalMs == null) {
            return messages.get("trader.wake.intervalRetired");
        }
        long now = nowMs.getAsLong();
        long boundary = now - Math.floorMod(now, intervalMs);
        // 距下一根K线太近就别烧这一次：例行唤醒马上到，内容几乎一样
        if (TraderWakeupRunner.wakeBudgetSeconds(boundary, intervalMs, now) < TraderWakeupRunner.MIN_WAKE_SECONDS) {
            // 措辞不提"例行马上来"：休眠时段里没有"稍等就来"的例行唤醒，手动是那时唯一的通道；拦本身保留（预算不够会落 SKIPPED）
            return messages.get("trader.wake.budgetTooTight",
                    Map.of("seconds", TraderWakeupRunner.MIN_WAKE_SECONDS));
        }
        if (inFlight.contains(trader.getId())) {
            return messages.get("trader.wake.busy");
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
    }

    /** trader 已删：摘掉两份 traderId 记账。inFlight 不用摘——删除前已用 {@link #isBusy} 拦掉在途 */
    public void forget(long traderId) {
        firedBoundary.remove(traderId);
        lastWakeAt.remove(traderId);
    }

    /**
     * 下一次例行唤醒的时刻；档位已下线返回 null。动作面板用它显示"再等多久就自动醒了"。
     * 有唤醒时段的跳到时段内的下一根——面板"下次例行"显示的必须是真会醒的那一刻
     */
    public Long nextRoutineWakeAt(AiTrader trader) {
        Long intervalMs = INTERVAL_MS.get(trader.getIntervalCode());
        if (intervalMs == null) {
            return null;
        }
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
     * 预算预检 → 每 trader 互斥 → 信号量，然后虚拟线程上异步跑。
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
        String blocked = manualWakeBlockedReason(trader);
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
        // 一并记进冷静期基准：刚手动醒过，紧接着的波动警报就没有增量价值了
        lastWakeAt.put(trader.getId(), now);
        log.info("[TraderSched] 手动唤醒 traderId={} boundary={}", trader.getId(), boundary);
        Thread.startVirtualThread(() -> {
            try {
                slots.acquire();
                try {
                    runner.wakeManual(trader, boundary);
                } finally {
                    slots.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.remove(trader.getId());
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
