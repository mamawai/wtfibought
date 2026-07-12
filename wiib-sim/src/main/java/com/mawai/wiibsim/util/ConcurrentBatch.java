package com.mawai.wiibsim.util;

import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 定时任务批处理并发模板：虚拟线程逐项执行 + 信号量限流（防打爆数据库连接池），
 * try-with-resources 关闭 executor 时等待全部任务收尾。
 * 单项异常由 action 自行消化（各处理器内部已有 try/catch + 分布式锁）。
 */
public final class ConcurrentBatch {

    /** 信号量等待上限：拿不到就跳过该项，避免积压任务长时间挂住 */
    private static final long ACQUIRE_TIMEOUT_SECONDS = 5;

    private ConcurrentBatch() {
    }

    public static <T> void run(Collection<T> items, int maxConcurrency, Consumer<T> action) {
        Semaphore semaphore = new Semaphore(maxConcurrency);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (T item : items) {
                executor.submit(() -> {
                    try {
                        if (!semaphore.tryAcquire(ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return;
                        try {
                            action.accept(item);
                        } finally {
                            semaphore.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        }
    }
}
