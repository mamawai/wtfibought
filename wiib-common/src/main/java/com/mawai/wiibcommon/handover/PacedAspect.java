package com.mawai.wiibcommon.handover;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringValueResolver;

import java.util.function.LongSupplier;

/** {@link Paced} 的切面。排在定时器的口里面：口关着连 Redis 都不读 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class PacedAspect implements EmbeddedValueResolverAware {

    private final ObjectProvider<StringRedisTemplate> redis;
    private StringValueResolver resolver;

    /** 墙钟注入点 */
    LongSupplier nowMs = System::currentTimeMillis;

    public PacedAspect(ObjectProvider<StringRedisTemplate> redis) {
        this.redis = redis;
    }

    @Override
    public void setEmbeddedValueResolver(StringValueResolver resolver) {
        this.resolver = resolver;
    }

    /** 没到点返回 null 不执行；执行完不管成败都记下这一刻 */
    @Around("@annotation(paced)")
    public Object around(ProceedingJoinPoint point, Paced paced) throws Throwable {
        String key = "wiib:job:" + paced.key() + ":last";
        String last = redis.getObject().opsForValue().get(key);
        long every = Long.parseLong(resolver.resolveStringValue(paced.every()));
        if (last != null && nowMs.getAsLong() - Long.parseLong(last) < every) {
            return null;
        }
        try {
            return point.proceed();
        } finally {
            redis.getObject().opsForValue().set(key, String.valueOf(nowMs.getAsLong()));
        }
    }
}
