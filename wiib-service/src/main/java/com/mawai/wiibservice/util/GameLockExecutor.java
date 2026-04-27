package com.mawai.wiibservice.util;

import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibservice.service.CacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 游戏通用：分布式锁 + 编程式事务 + Redis Session 管理
 * <p>
 * 执行顺序：加锁 → 开事务 → 业务 → 提交事务 → 释放锁
 */
@Component
@RequiredArgsConstructor
public class GameLockExecutor {

    private final RedisLockUtil redisLockUtil;
    private final TransactionTemplate transactionTemplate;
    private final CacheService cacheService;

    private static final long LOCK_TIMEOUT_SECONDS = 20;
    private static final long LOCK_WAIT_MILLIS = 3_000;

    /**
     * 在锁 + 事务保护下执行（有返回值，需要写库的场景）
     */
    public <T> T executeInLockTx(String lockKeyPrefix, Long userId, Supplier<T> supplier) {
        String lockKey = lockKeyPrefix + userId;
        try {
            return redisLockUtil.executeWithLock(lockKey, LOCK_TIMEOUT_SECONDS, LOCK_WAIT_MILLIS, () ->
                    transactionTemplate.execute(status -> supplier.get())
            );
        } catch (LockAcquisitionException ex) {
            throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
        }
    }

    /**
     * 在锁保护下执行（有返回值，只读不需要事务）
     */
    public <T> T executeInLock(String lockKeyPrefix, Long userId, Supplier<T> supplier) {
        String lockKey = lockKeyPrefix + userId;
        try {
            return redisLockUtil.executeWithLock(lockKey, LOCK_TIMEOUT_SECONDS, LOCK_WAIT_MILLIS, supplier);
        } catch (LockAcquisitionException ex) {
            throw new BizException(ErrorCode.CONCURRENT_UPDATE_FAILED);
        }
    }

    // ==================== Session CRUD ====================

    public <T> T getSession(String prefix, Long userId) {
        return cacheService.getObject(prefix + userId);
    }

    public <T> T requireSession(String prefix, Long userId, ErrorCode notFoundError) {
        T session = getSession(prefix, userId);
        if (session == null) throw new BizException(notFoundError);
        return session;
    }

    public void saveSession(String prefix, Long userId, Object session, long ttlHours) {
        cacheService.setObject(prefix + userId, session, ttlHours, TimeUnit.HOURS);
    }

    public void deleteSession(String prefix, Long userId) {
        cacheService.delete(prefix + userId);
    }
}
