package com.mawai.wiibcommon.annotation;

import java.lang.annotation.*;

/** {@link RateLimiter} 叠用时编译器自动套的容器，不用手写 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimiters {

    RateLimiter[] value();
}
