package com.mawai.wiibsim.task;

import com.mawai.wiibsim.service.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketDataTask {

    private final MarketDataService marketDataService;

    @Scheduled(cron = "0 0 21 * * SUN-THU")
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

    @Scheduled(cron = "0 10 9 * * MON-FRI")
    public void loadTodayDataToRedis() {
        Thread.startVirtualThread(() -> {
            LocalDate today = LocalDate.now();
            log.info("定时任务：加载今日行情到Redis {}", today);
            marketDataService.loadDayDataToRedis(today);
        });
    }
}
