package com.mawai.wiibcommon.aspect;

import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;

import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 管理员权限切面。
 * 拦截 @RequireAdmin 标注的类(@within)或方法(@annotation)，非管理员抛 FORBIDDEN。
 */
@Aspect
@Component
@Slf4j
public class RequireAdminAspect {

    /** 管理员固定为 userId=1（全站唯一管理员/平台所有者账号） */
    private static final long ADMIN_USER_ID = 1L;

    @Around("@within(com.mawai.wiibcommon.annotation.RequireAdmin) "
            + "|| @annotation(com.mawai.wiibcommon.annotation.RequireAdmin)")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        // 未登录会在此抛 NotLoginException → 全局处理器转 401
        long userId = StpUtil.getLoginIdAsLong();
        if (userId != ADMIN_USER_ID) {
            log.warn("非管理员访问被拦截: userId={}, method={}", userId, point.getSignature().toShortString());
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        return point.proceed();
    }
}
