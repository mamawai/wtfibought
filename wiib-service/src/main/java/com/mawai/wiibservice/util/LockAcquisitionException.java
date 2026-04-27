package com.mawai.wiibservice.util;

/**
 * 获取分布式锁失败。只表达并发占用，不混用普通运行时异常。
 */
public class LockAcquisitionException extends RuntimeException {

    public LockAcquisitionException(String lockKey) {
        super("获取锁失败: " + lockKey);
    }
}
