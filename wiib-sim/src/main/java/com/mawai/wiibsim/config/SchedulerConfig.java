package com.mawai.wiibsim.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 定时任务调度器配置。关停一开始定时器的口就关上，任务一律跳过；
 * 底层线程池到销毁阶段 shutdownNow，没到点的 cron 直接丢，不等
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService scheduledTaskPool() {
        return Executors.newScheduledThreadPool(1, Thread.ofVirtual().name("scheduled-", 0).factory());
    }

    @Bean("taskScheduler")
    public TaskScheduler taskScheduler(ScheduledExecutorService scheduledTaskPool) {
        return new ConcurrentTaskScheduler(scheduledTaskPool);
    }
}
