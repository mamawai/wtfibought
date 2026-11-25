package com.mawai.wiibcommon.handover;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 标在 Spring 事件类上：事务提交后由 {@link EventBridge} 转到同一服务的另一个实例，在那边本地重新发布 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Clustered {
}
