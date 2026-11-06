package com.mawai.wiibsim.task;

import com.mawai.wiibsim.config.TradeFilterRegistry;
import com.mawai.wiibsim.service.CryptoOrderService;
import com.mawai.wiibsim.service.CrossLiquidationService;
import com.mawai.wiibsim.service.FundingRateService;
import com.mawai.wiibsim.service.FuturesLiquidationService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesSettlementService;
import com.mawai.wiibsim.service.TraderSymbolService;
import com.mawai.wiibsim.service.RankingService;
import com.mawai.wiibsim.service.BankruptcyService;
import com.mawai.wiibsim.service.MarginAccountService;
import com.mawai.wiibsim.service.PredictionService;
import com.mawai.wiibsim.service.TradeStreamConsumer;
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
    private final TraderSymbolService traderSymbolService;
    private final FundingRateService fundingRateService;
    private final TradeStreamConsumer tradeStreamConsumer;
    private final FuturesPositionIndexService futuresPositionIndexService;
    private final TradeFilterRegistry tradeFilterRegistry;

    /** 交易员集合：每分钟按库里的仓位和登记重算，变了才写 Redis，feed 按它订行情 */
    @Scheduled(fixedRate = 60 * 1000)
    public void refreshTraderSymbols() {
        Thread.startVirtualThread(() -> {
            try {
                traderSymbolService.refresh();
            } catch (Exception e) {
                log.error("交易员集合刷新失败", e);
            }
        });
    }

    /** 交易过滤器：每天 UTC 00:30 按官方 exchangeInfo 刷一次 */
    @Scheduled(cron = "0 30 0 * * *", zone = "UTC")
    public void refreshTradeFilters() {
        Thread.startVirtualThread(() -> {
            try {
                tradeFilterRegistry.refreshFromBinance();
            } catch (Exception e) {
                log.error("交易过滤器刷新失败", e);
            }
        });
    }

    /** 全仓健康兜底轮询：价格tick是主触发，这里兜住行情静默/进程重启的空窗（交易员的币不发价格频道、没有 tick，全靠这里） */
    @Scheduled(fixedRate = 30 * 1000)
    public void sweepCrossAccounts() {
        Thread.startVirtualThread(crossLiquidationService::sweepAll);
    }

    /** 逐仓强平兜底轮询。与上面全仓那条同频 */
    @Scheduled(fixedRate = 30 * 1000)
    public void sweepFuturesLiquidation() {
        Thread.startVirtualThread(futuresLiquidationService::sweepAll);
    }

    /** 限价单、止损、止盈兜底轮询：按缓存的最新成交价再判一遍，主触发是读成交 Stream */
    @Scheduled(fixedRate = 30 * 1000)
    public void sweepTradeTriggers() {
        Thread.startVirtualThread(tradeStreamConsumer::sweep);
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

    /** 资金费：每分钟看一遍有仓位的币，到了各自的结算时刻按 Binance 这一期的实际结算费率收 */
    @Scheduled(fixedRate = 60 * 1000)
    public void settleFundingFees() {
        Thread.startVirtualThread(() -> {
            try {
                fundingRateService.settleDue();
            } catch (Exception e) {
                log.error("资金费结算失败", e);
            }
        });
    }

    /** futures每小时：限价单索引、仓位强平/止损/止盈索引对账 */
    @Scheduled(cron = "0 0 * * * *")
    public void futuresHourlyMaintenance() {
        Thread.startVirtualThread(() -> {
            try {
                futuresSettlementService.reconcileLimitOrderIndex();
                futuresPositionIndexService.reconcilePositionIndex();
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
