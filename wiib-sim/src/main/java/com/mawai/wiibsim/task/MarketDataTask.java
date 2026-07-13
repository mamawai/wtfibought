package com.mawai.wiibsim.task;

import com.mawai.wiibsim.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketDataTask {

    private final MarketDataService marketDataService;

    // 已停：bStock 取代老 GBM 股市，不再生成股票 tick / 期权链 / AI 新闻（方法保留，可手动触发）
    // @Scheduled(cron = "0 0 21 * * SUN-THU")
    public void generateNextDayData() {
        generateData(1);
    }

    public void generateData(int offsetDays) {
        LocalDate target = LocalDate.now().plusDays(offsetDays);
        Thread.startVirtualThread(() -> {
            log.info("生成行情数据: {} (offset={})", target, offsetDays);
            marketDataService.generateNextDayMarketData(target);
        });
    }

    // 已停：无新生成数据可加载（随老 GBM 股市停用）
    // @Scheduled(cron = "0 10 9 * * MON-FRI")
    public void loadTodayDataToRedis() {
        Thread.startVirtualThread(() -> {
            LocalDate today = LocalDate.now();
            log.info("定时任务：加载今日行情到Redis {}", today);
            marketDataService.loadDayDataToRedis(today);
        });
    }
}
