package com.mawai.wiibsim.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 进程内的按 key 公平锁（合约仓位锁、币种锁、全仓用户级锁），只在单实例部署下互斥。
 * <p>
 * 用户操作不排队，系统操作排队等：锁被占着时排队的只会是系统操作，占锁的一放手就轮到它们。
 * 拿到的 Lock 由调用方在同一线程的 finally 里 unlock。
 */
@Component
public class FairLockRegistry {

    /** 系统操作排队等锁的上限（秒） */
    private static final long SYSTEM_WAIT_SECONDS = 10;

    /**
     * key → 锁。值是弱引用：有线程拿着、在排队或刚取到手时都持有强引用，不会被回收；
     * 没人用了才回收，下次再来按 key 新建一把
     */
    private final Cache<String, ReentrantLock> locks = Caffeine.newBuilder().weakValues().build();

    /**
     * 用户操作：锁被占着、或者有系统操作在排队，立即返回 null，不排队。
     * 超时传 0 的 tryLock 守公平，有人排队就失败；无参 tryLock() 会插队。
     */
    public Lock tryLockAsUser(String key) {
        return tryLock(key, 0);
    }

    /** 系统操作：按到达顺序排队，最多等 10 秒，超时返回 null */
    public Lock tryLockAsSystem(String key) {
        return tryLock(key, SYSTEM_WAIT_SECONDS);
    }

    private Lock tryLock(String key, long waitSeconds) {
        ReentrantLock lock = locks.get(key, k -> new ReentrantLock(true));
        try {
            return lock.tryLock(waitSeconds, TimeUnit.SECONDS) ? lock : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
