package com.mawai.wiibsim.task;

import com.mawai.wiibsim.service.OrderService;
import com.mawai.wiibsim.service.OptionOrderService;
import com.mawai.wiibsim.service.CryptoOrderService;
import com.mawai.wiibsim.service.FuturesSettlementService;
import com.mawai.wiibsim.service.QuotePushService;
import com.mawai.wiibsim.service.RankingService;
import com.mawai.wiibsim.service.SettlementService;
import com.mawai.wiibsim.service.BankruptcyService;
import com.mawai.wiibsim.service.MarginAccountService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 定时任务
 * <p>
 * 1. 每日16:00生成次日行情
 * <p>
 * 2. 交易时段每10秒推送行情（上午9:30-11:30 + 下午13:00-15:00，共1440个点）
 * <p>
 * 3. 交易时段每10秒触发限价单检测
 * <p>
 * 4. 交易时段每10秒执行已触发的限价单
 * <p>
 * 5. 每日9:00处理过期限价单
 * <p>
 * 6. 每日9:20启动T+1结算任务，15:10停止（每10分钟执行一次）
 *
 * <p>执行说明：</p>
 * <ul>
 *   <li>cron 任务由 Spring Scheduling 触发</li>
 *   <li>10s/10min 周期任务：固定触发，提交到新虚拟线程执行；若上一轮未结束则 cancel(true) 丢弃</li>
 *   <li>应用启动时自动检查并补启动遗漏的任务</li>
 * </ul>
 */
@Slf4j
@Component
public class ScheduledTasks {

    private final QuotePushService quotePushService;
    private final OrderService orderService;
    private final OptionOrderService optionOrderService;
    private final CryptoOrderService cryptoOrderService;
    private final FuturesSettlementService futuresSettlementService;
    private final SettlementService settlementService;
    private final RankingService rankingService;
    private final MarginAccountService marginAccountService;
    private final BankruptcyService bankruptcyService;
    private final TaskScheduler taskScheduler;

    private final ExecutorService virtualTaskExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** 每日数据点数：240分钟 × 6点/分钟 = 1440 */
    private static final int TOTAL_TICKS = 1440;

    // 当前推送的索引（0-1439）
    private int currentTickIndex = 0;

    // 是否在交易时段
    private boolean isTradingTime = false;

    // 动态定时任务的引用
    private ScheduledFuture<?> marketDataTask;
    private ScheduledFuture<?> settlementTask;
    private ScheduledFuture<?> orderExecutionTask;
    private ScheduledFuture<?> rankingTask;

    // 当前执行（用于丢弃）
    private final AtomicReference<Future<?>> marketDataRun = new AtomicReference<>();
    private final AtomicReference<Future<?>> orderExecutionRun = new AtomicReference<>();
    private final AtomicReference<Future<?>> settlementRun = new AtomicReference<>();
    private final AtomicReference<Future<?>> rankingRun = new AtomicReference<>();

    public ScheduledTasks(
            QuotePushService quotePushService,
            OrderService orderService,
            OptionOrderService optionOrderService,
            CryptoOrderService cryptoOrderService,
            FuturesSettlementService futuresSettlementService,
            SettlementService settlementService,
            RankingService rankingService,
            MarginAccountService marginAccountService,
            BankruptcyService bankruptcyService,
            @Qualifier("taskScheduler") TaskScheduler taskScheduler
    ) {
        this.quotePushService = quotePushService;
        this.orderService = orderService;
        this.optionOrderService = optionOrderService;
        this.cryptoOrderService = cryptoOrderService;
        this.futuresSettlementService = futuresSettlementService;
        this.settlementService = settlementService;
        this.rankingService = rankingService;
        this.marginAccountService = marginAccountService;
        this.bankruptcyService = bankruptcyService;
        this.taskScheduler = taskScheduler;
    }

    /**
     * 交易日09:00恢复破产用户（幂等）
     */
    @Scheduled(cron = "0 0 9 * * MON-FRI")
    public void resetBankruptUsers() {
        Thread.startVirtualThread(()-> {
            try {
                bankruptcyService.resetBankruptUsers(LocalDate.now());
            } catch (Exception e) {
                log.error("破产恢复任务失败", e);
            }
        });
    }

    /**
     * 交易日17:00计息并执行爆仓检查
     */
    @Scheduled(cron = "0 0 17 * * MON-FRI")
    public void accrueInterestAndCheckBankruptcy() {
        Thread.startVirtualThread(()-> {
            try {
                log.info("交易日17:00计息并执行爆仓检查");
                LocalDate today = LocalDate.now();
                marginAccountService.accrueDailyInterest(today);
                bankruptcyService.checkAndLiquidateAll();
            } catch (Exception e) {
                log.error("计息/爆仓检查任务失败", e);
            }
        });
    }

    /**
     * 每日15:00收盘时结算当天到期的所有期权合约
     */
    @Scheduled(cron = "0 0 15 * * MON-FRI")
    public void processOptionSettlement() {
        Thread.startVirtualThread(()-> {
            try {
                optionOrderService.processExpirySettlement();
            } catch (Exception e) {
                log.error("期权结算任务失败", e);
            }
        });
    }

    /**
     * 应用启动时检查并补启动遗漏的任务
     */
    @PostConstruct
    public void onStartup() {
        LocalTime now = LocalTime.now();
        DayOfWeek dayOfWeek = LocalDate.now().getDayOfWeek();

        // 只在工作日处理
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            log.info("周末不启动交易任务");
            return;
        }

        log.info("应用启动，当前时间: {}，检查是否需要补启动任务", now);

        // 9:20-15:10 之间需要启动结算任务
        if (!now.isBefore(LocalTime.of(9, 20)) && now.isBefore(LocalTime.of(15, 10))) {
            log.info("补启动T+1结算任务");
            startSettlementTask();
        }

        // 9:25-15:00 之间需要启动行情推送和订单执行任务
        if (!now.isBefore(LocalTime.of(9, 25)) && now.isBefore(LocalTime.of(15, 0))) {
            log.info("补启动行情推送和订单执行任务");
            startMarketDataPush();
        }
    }

    /**
     * crypto每小时：过期 + 孤儿TRIGGERED执行
     */
    @Scheduled(cron = "0 0 * * * *")
    public void cryptoHourlyMaintenance() {
        Thread.startVirtualThread(() -> {
            try {
                cryptoOrderService.executeTriggeredOrders();
                cryptoOrderService.expireLimitOrders();
            } catch (Exception e) {
                log.error("crypto小时维护失败", e);
            }
        });
    }

    /**
     * futures资金费率扣除（每8小时：00:00、08:00、16:00）
     */
    @Scheduled(cron = "0 0 0,8,16 * * *")
    public void chargeFuturesFundingFee() {
        Thread.startVirtualThread(() -> {
            try {
                futuresSettlementService.chargeFundingFeeAll();
            } catch (Exception e) {
                log.error("futures资金费率扣除失败", e);
            }
        });
    }

    /**
     * futures每小时：过期 + 孤儿TRIGGERED执行
     */
    @Scheduled(cron = "0 0 * * * *")
    public void futuresHourlyMaintenance() {
        Thread.startVirtualThread(() -> {
            try {
                futuresSettlementService.executeTriggeredOrders();
                futuresSettlementService.expireLimitOrders();
            } catch (Exception e) {
                log.error("futures小时维护失败", e);
            }
        });
    }

    @PreDestroy
    public void shutdownVirtualTaskExecutor() {
        virtualTaskExecutor.shutdownNow();
    }

    /**
     * 每日9:25重置索引并启动推送任务（仅周一到周五）
     */
    @Scheduled(cron = "0 25 9 * * MON-FRI")
    public void startMarketDataPush() {
        currentTickIndex = 0;
        isTradingTime = false;
        log.info("重置行情索引，准备开盘");

        // 如果任务已经在运行，先停止
        if (marketDataTask != null && !marketDataTask.isCancelled()) {
            marketDataTask.cancel(false);
            cancelIfRunning(marketDataRun.getAndSet(null));
            log.info("停止旧的推送任务");
        }
        if (orderExecutionTask != null && !orderExecutionTask.isCancelled()) {
            orderExecutionTask.cancel(false);
            cancelIfRunning(orderExecutionRun.getAndSet(null));
            log.info("停止旧的订单执行任务");
        }

        // 10s 固定触发；上一轮未结束则丢弃
        marketDataTask = taskScheduler.scheduleAtFixedRate(
                () -> submitReplacingIfRunning(marketDataRun, this::pushMarketData, "pushMarketData"),
                Duration.ofSeconds(10)
        );
        log.info("启动行情推送任务");

        // 10s 固定触发；上一轮未结束则丢弃
        orderExecutionTask = taskScheduler.scheduleAtFixedRate(
                () -> submitReplacingIfRunning(orderExecutionRun, this::executeTriggeredLimitOrders, "executeTriggeredLimitOrders"),
                Duration.ofSeconds(10)
        );
        log.info("启动订单执行任务");

        // 启动排行榜刷新任务
        startRankingTask();
    }

    /**
     * 启动排行榜刷新任务
     */
    private void startRankingTask() {
        if (rankingTask != null && !rankingTask.isCancelled()) {
            rankingTask.cancel(false);
            cancelIfRunning(rankingRun.getAndSet(null));
        }

        // 10min 固定触发
        rankingTask = taskScheduler.scheduleAtFixedRate(
                () -> submitReplacingIfRunning(rankingRun, this::doRefreshRanking, "refreshRanking"),
                Duration.ofMinutes(10)
        );
        log.info("启动排行榜刷新任务");
    }

    private void doRefreshRanking() {
        try {
            rankingService.refreshRanking();
        } catch (Exception e) {
            log.error("刷新排行榜失败", e);
        }
    }

    /**
     * 每日15:00停止推送任务（仅周一到周五）
     */
    @Scheduled(cron = "0 0 15 * * MON-FRI")
    public void stopMarketDataPush() {
        if (marketDataTask != null && !marketDataTask.isCancelled()) {
            marketDataTask.cancel(false);
            marketDataTask = null;
            cancelIfRunning(marketDataRun.getAndSet(null));
            isTradingTime = false;
            log.info("收盘，停止推送任务");
        }
        if (orderExecutionTask != null && !orderExecutionTask.isCancelled()) {
            orderExecutionTask.cancel(false);
            orderExecutionTask = null;
            cancelIfRunning(orderExecutionRun.getAndSet(null));
            log.info("收盘，停止订单执行任务");
        }
        if (rankingTask != null && !rankingTask.isCancelled()) {
            rankingTask.cancel(false);
            rankingTask = null;
            cancelIfRunning(rankingRun.getAndSet(null));
            log.info("收盘，停止排行榜刷新任务");
        }
    }

    /**
     * 推送行情数据（由动态任务调用）
     */
    private void pushMarketData() {
        LocalTime now = LocalTime.now();

        // 判断是否在交易时段（上午9:30-11:30 或 下午13:00-15:00）
        boolean inMorning = !now.isBefore(LocalTime.of(9, 30)) && !now.isAfter(LocalTime.of(11, 30));
        boolean inAfternoon = !now.isBefore(LocalTime.of(13, 0)) && !now.isAfter(LocalTime.of(15, 0));
        boolean inTradingTime = inMorning || inAfternoon;

        // 开盘时重置索引
        if (inTradingTime && !isTradingTime) {
            currentTickIndex = 0;
            isTradingTime = true;
            log.info("开盘，重置行情索引");
        }

        // 收盘时停止推送
        if (!inTradingTime && isTradingTime) {
            isTradingTime = false;
            log.info("收盘，停止推送");
            return;
        }

        // 非交易时段不推送
        if (!inTradingTime) {
            return;
        }

        // 索引超出范围，停止推送
        if (currentTickIndex >= TOTAL_TICKS) {
            log.warn("行情索引超出范围: {}", currentTickIndex);
            return;
        }

        try {
            LocalDate today = LocalDate.now();
            // 推送所有股票行情（内部使用虚拟线程并发推送）
            quotePushService.pushAllQuotes(today);
            // 触发限价单检测（标记触发状态）
            orderService.triggerLimitOrders();
            currentTickIndex++;
        } catch (Exception e) {
            log.error("推送行情失败", e);
        }
    }

    /**
     * 执行已触发的限价单（由动态任务调用）
     * 批量处理TRIGGERED状态的订单
     */
    private void executeTriggeredLimitOrders() {
        try {
            orderService.executeTriggeredOrders();
        } catch (Exception e) {
            log.error("执行已触发订单失败", e);
        }
    }

    /**
     * 每周一到周五16:00处理过期限价单
     * 将超时的PENDING订单标记为EXPIRED，回退资金/股票
     */
    @Scheduled(cron = "0 0 16 * * MON-FRI")
    public void expireLimitOrders() {
        Thread.startVirtualThread(()-> {
            try {
                orderService.expireLimitOrders();
            } catch (Exception e) {
                log.error("处理过期订单失败", e);
            }
        });
    }

    /**
     * 每日9:20启动T+1结算任务（仅周一到周五）
     */
    @Scheduled(cron = "0 20 9 * * MON-FRI")
    public void startSettlementTask() {
        // 如果任务已经在运行，先停止
        if (settlementTask != null && !settlementTask.isCancelled()) {
            settlementTask.cancel(false);
            cancelIfRunning(settlementRun.getAndSet(null));
            log.info("停止旧的结算任务");
        }

        // 10min 固定触发；上一轮未结束则丢弃
        settlementTask = taskScheduler.scheduleAtFixedRate(
                () -> submitReplacingIfRunning(settlementRun, this::processSettlements, "processSettlements"),
                Duration.ofMinutes(10)
        );
        log.info("启动T+1结算任务");
    }

    /**
     * 每日15:10停止T+1结算任务（仅周一到周五）
     */
    @Scheduled(cron = "0 10 15 * * MON-FRI")
    public void stopSettlementTask() {
        if (settlementTask != null && !settlementTask.isCancelled()) {
            settlementTask.cancel(false);
            settlementTask = null;
            cancelIfRunning(settlementRun.getAndSet(null));
            log.info("停止T+1结算任务");
        }
    }

    /**
     * 执行 T+1 资金结算（由动态任务调用）
     * 精确24小时后到账：10:30卖出 → 次日10:30到账
     * 延迟：最多10分钟
     */
    private void processSettlements() {
        try {
            settlementService.processSettlements();
        } catch (Exception e) {
            log.error("T+1资金结算失败", e);
        }
    }

    private void submitReplacingIfRunning(AtomicReference<Future<?>> currentRun, Runnable task, String taskName) {
        Future<?> previous = currentRun.getAndSet(null);
        cancelIfRunning(previous);

        Future<?> next = virtualTaskExecutor.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("任务执行失败: {}", taskName, e);
            }
        });
        currentRun.set(next);
    }

    private void cancelIfRunning(Future<?> future) {
        if (future == null) {
            return;
        }
        if (future.isDone() || future.isCancelled()) {
            return;
        }
        future.cancel(true);
    }

    public Map<String, Object> getTaskStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("marketDataTask", taskRunning(marketDataTask));
        status.put("orderExecutionTask", taskRunning(orderExecutionTask));
        status.put("settlementTask", taskRunning(settlementTask));
        status.put("rankingTask", taskRunning(rankingTask));
        status.put("isTradingTime", isTradingTime);
        status.put("currentTickIndex", currentTickIndex);
        return status;
    }

    private boolean taskRunning(ScheduledFuture<?> task) {
        return task != null && !task.isCancelled() && !task.isDone();
    }
}
