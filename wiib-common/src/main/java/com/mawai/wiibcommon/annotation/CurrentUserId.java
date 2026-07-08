package com.mawai.wiibcommon.annotation;

import io.swagger.v3.oas.annotations.Parameter;

import java.lang.annotation.*;

/**
 * 注入当前登录用户 ID（由 CurrentUserIdArgumentResolver 从 Sa-Token 取）。
 * 参数类型 Long/long → getLoginIdAsLong；String → getLoginIdAsString；Integer/int → getLoginIdAsInt。
 * 未登录时取值会抛 NotLoginException，由全局处理器转 401——等价于隐式登录校验。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Parameter(hidden = true) // 由解析器注入，非真实请求参数，从 Swagger 文档隐藏
public @interface CurrentUserId {
}
