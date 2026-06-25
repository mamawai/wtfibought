package com.mawai.wiibsim.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 定时任务调度器配置
 */
@Slf4j
@Configuration
@EnableScheduling
public class SchedulerConfig {

    @Bean("taskScheduler")
    public TaskScheduler taskScheduler() {
        var virtualThreadFactory = Thread.ofVirtual().name("scheduled-", 0).factory();
        var virtualScheduler = Executors.newScheduledThreadPool(1, virtualThreadFactory);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("关闭任务调度器");
            virtualScheduler.shutdown();
            try {
                if (!virtualScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    virtualScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                virtualScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }, "scheduler-shutdown"));

        return new ConcurrentTaskScheduler(virtualScheduler);
    }
}
