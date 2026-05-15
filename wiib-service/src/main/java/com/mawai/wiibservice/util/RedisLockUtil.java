package com.mawai.wiibservice.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redis分布式锁工具类
 * 使用Redis实现的分布式锁，支持安全释放（Lua脚本保证只有持有者能释放）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLockUtil {

    private final StringRedisTemplate redisTemplate;

    /** 锁前缀 */
    private static final String LOCK_PREFIX = "lock:";

    /** 默认锁超时时间（秒） */
    private static final long DEFAULT_LOCK_TIMEOUT = 30;

    /** 默认获取锁等待时间（毫秒） */
    private static final long DEFAULT_WAIT_TIMEOUT = 5000;

    /** 获取锁失败后的重试间隔（毫秒） */
    private static final long RETRY_INTERVAL_MILLIS = 50;

    /** Lua脚本：安全释放锁（只有持有者才能释放） */
    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) " +
                    "else return 0 end";

    /**
     * 尝试获取锁
     *
     * @param key 锁的key
     * @param timeout 锁超时时间（秒）
     * @return 锁的value（用于释放），null表示获取失败
     */
    public String tryLock(String key, long timeout) {
        String lockKey = LOCK_PREFIX + key;
        String lockValue = UUID.randomUUID().toString();

        Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, timeout, TimeUnit.SECONDS);

        if (Boolean.TRUE.equals(success)) {
            log.info("获取锁成功: {}", lockKey);
            return lockValue;
        }
        return null;
    }

    /**
     * 尝试获取锁（带等待）
     *
     * @param key 锁的key
     * @param lockTimeout 锁超时时间（秒）
     * @param waitTimeout 等待超时时间（毫秒）
     * @return 锁的value，null表示获取失败
     */
    @SuppressWarnings("BusyWait")
    public String tryLockWithWait(String key, long lockTimeout, long waitTimeout) {
        long startTime = System.currentTimeMillis();
        String lockValue;

        while ((lockValue = tryLock(key, lockTimeout)) == null) {
            if (System.currentTimeMillis() - startTime > waitTimeout) {
                log.warn("获取锁超时: {}", key);
                return null;
            }
            try {
                // 短暂休眠后重试
                Thread.sleep(RETRY_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return lockValue;
    }

    /**
     * 释放锁
     *
     * @param key       锁的key
     * @param lockValue 获取锁时返回的value
     */
    public void unlock(String key, String lockValue) {
        if (lockValue == null) {
            return;
        }

        String lockKey = LOCK_PREFIX + key;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class);
        Long result = redisTemplate.execute(script, Collections.singletonList(lockKey), lockValue);

        boolean success = result == 1;
        if (success) {
            log.info("释放锁成功: {}", lockKey);
        } else {
            log.warn("释放锁失败（锁已过期或被他人持有）: {}", lockKey);
        }
    }

    /**
     * 在锁保护下执行操作
     * 注意：此方法内部不包含事务，如需事务请在supplier内部处理
     *
     * @param key      锁的key
     * @param supplier 要执行的操作
     * @throws LockAcquisitionException 如果获取锁失败
     */
    @SuppressWarnings("UnusedReturnValue")
    public <T> T executeWithLock(String key, Supplier<T> supplier) {
        return executeWithLock(key, DEFAULT_LOCK_TIMEOUT, DEFAULT_WAIT_TIMEOUT, supplier);
    }

    /**
     * 在锁保护下执行操作
     */
    public <T> T executeWithLock(String key, long lockTimeout, long waitTimeout, Supplier<T> supplier) {
        String lockValue = tryLockWithWait(key, lockTimeout, waitTimeout);
        if (lockValue == null) {
            throw new LockAcquisitionException(key);
        }

        try {
            return supplier.get();
        } finally {
            unlock(key, lockValue);
        }
    }

    /**
     * 在锁保护下执行操作（无返回值）
     */
    public void executeWithLock(String key, Runnable runnable) {
        executeWithLock(key, () -> {
            runnable.run();
            return null;
        });
    }
}
