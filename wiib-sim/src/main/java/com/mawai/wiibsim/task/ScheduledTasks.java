package com.mawai.wiibsim.task;

import com.mawai.wiibsim.service.CryptoOrderService;
import com.mawai.wiibsim.service.CrossLiquidationService;
import com.mawai.wiibsim.service.FuturesLiquidationService;
import com.mawai.wiibsim.service.FuturesSettlementService;
import com.mawai.wiibsim.service.RankingService;
import com.mawai.wiibsim.service.BankruptcyService;
import com.mawai.wiibsim.service.MarginAccountService;
import com.mawai.wiibsim.service.PredictionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 定时任务（老 GBM 股市/期权已退，仅留 crypto/futures/margin/破产/排行）。
 * <p>随老股市一并移除：10s 级行情推送、限价单触发/执行、T+1 结算、期权到期结算。
 * <p>排行榜原绑股票盘口时段，老股退后改固定每 10 分钟常驻刷新（配合 crypto/futures 24/7）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledTasks {

    private final CryptoOrderService cryptoOrderService;
    private final FuturesSettlementService futuresSettlementService;
    private final RankingService rankingService;
    private final MarginAccountService marginAccountService;
    private final BankruptcyService bankruptcyService;
    private final CrossLiquidationService crossLiquidationService;
    private final FuturesLiquidationService futuresLiquidationService;
    private final PredictionService predictionService;

    /** 全仓健康兜底轮询：价格tick是主触发，这里兜住行情静默/进程重启的空窗 */
    @Scheduled(fixedRate = 30 * 1000)
    public void sweepCrossAccounts() {
        Thread.startVirtualThread(crossLiquidationService::sweepAll);
    }

    /**
     * 逐仓强平/止损/止盈兜底轮询。与上面全仓那条同频
     */
    @Scheduled(fixedRate = 30 * 1000)
    public void sweepFuturesLiquidation() {
        Thread.startVirtualThread(futuresLiquidationService::sweepAll);
    }

    /** futures孤儿TRIGGERED补扫：触发了没成交掉的限价单（等锁超时、进程重启） */
    @Scheduled(fixedRate = 30 * 1000)
    public void sweepFuturesTriggeredOrders() {
        Thread.startVirtualThread(() -> {
            try {
                futuresSettlementService.executeTriggeredOrders();
            } catch (Exception e) {
                log.error("futures孤儿TRIGGERED补扫失败", e);
            }
        });
    }

    /** 交易日09:00恢复破产用户（幂等） */
    @Scheduled(cron = "0 0 9 * * MON-FRI")
    public void resetBankruptUsers() {
        Thread.startVirtualThread(() -> {
            try {
                bankruptcyService.resetBankruptUsers(LocalDate.now());
            } catch (Exception e) {
                log.error("破产恢复任务失败", e);
            }
        });
    }

    /** 交易日17:00计息并执行爆仓检查 */
    @Scheduled(cron = "0 0 17 * * MON-FRI")
    public void accrueInterestAndCheckBankruptcy() {
        Thread.startVirtualThread(() -> {
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

    /** crypto每小时：孤儿TRIGGERED执行 + 限价单索引对账 */
    @Scheduled(cron = "0 0 * * * *")
    public void cryptoHourlyMaintenance() {
        Thread.startVirtualThread(() -> {
            try {
                cryptoOrderService.executeTriggeredOrders();
                cryptoOrderService.reconcileLimitOrderIndex();
            } catch (Exception e) {
                log.error("crypto小时维护失败", e);
            }
        });
    }

    /** futures资金费率（每8小时：00:00、08:00、16:00）：拉官方费率刷缓存 + 扣费。全站只有这里调官方接口 */
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

    /** futures每小时：限价单索引对账 */
    @Scheduled(cron = "0 0 * * * *")
    public void futuresHourlyMaintenance() {
        Thread.startVirtualThread(() -> {
            try {
                futuresSettlementService.reconcileLimitOrderIndex();
            } catch (Exception e) {
                log.error("futures小时维护失败", e);
            }
        });
    }

    /**
     * 预测回合补结算巡检（每5分钟，错开窗口边界30秒让正常结算先走）。
     * 捞的是窗口早该结束、却还停在 OPEN / LOCKED 的回合，补锁重跑，口径见 sweepStuckRounds。
     */
    @Scheduled(cron = "30 */5 * * * *")
    public void sweepStuckPredictionRounds() {
        Thread.startVirtualThread(() -> {
            try {
                predictionService.sweepStuckRounds();
            } catch (Exception e) {
                log.error("预测回合卡死巡检失败", e);
            }
        });
    }

    /** 排行榜刷新：固定每10分钟（原绑股票盘口时段，老股退后改常驻 24/7） */
    @Scheduled(fixedRate = 10 * 60 * 1000)
    public void refreshRanking() {
        Thread.startVirtualThread(() -> {
            try {
                rankingService.refreshRanking();
            } catch (Exception e) {
                log.error("刷新排行榜失败", e);
            }
        });
    }
}
