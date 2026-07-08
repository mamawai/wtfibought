package com.mawai.wiibcommon.annotation;

import java.lang.annotation.*;

/**
 * 管理员权限校验。
 * 标注在类上=该类全部接口需管理员；标注在方法上=单个接口需管理员。
 * 仅 userId=1（平台所有者）放行，否则抛 BizException(FORBIDDEN)；
 * 未登录时取 loginId 会抛 NotLoginException，由全局处理器转 401。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireAdmin {
}
