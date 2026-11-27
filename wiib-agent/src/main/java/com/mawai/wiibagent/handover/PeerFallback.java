package com.mawai.wiibagent.handover;

import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.util.Result;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.function.Predicate;

/**
 * 交接期间本地没找到就转问对端：先在本地执行，结果算"没找到"、对端又在收尾，就把同一个请求转给对端，
 * 用它的结果（{@link PeerFallbackAspect}）。对端不可达用本地的
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PeerFallback {

    /** 本地"没找到"的判法，拿到的是接口的返回值 */
    Class<? extends Predicate<Object>> missing() default FailedOrFalse.class;

    /** 默认判法：返回的 Result 不成功，或者数据是 false */
    final class FailedOrFalse implements Predicate<Object> {
        @Override
        public boolean test(Object returned) {
            return returned instanceof Result<?> r
                    && (r.getCode() != ErrorCode.SUCCESS.getCode() || Boolean.FALSE.equals(r.getData()));
        }
    }
}
