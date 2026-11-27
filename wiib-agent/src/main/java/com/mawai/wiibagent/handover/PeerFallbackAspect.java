package com.mawai.wiibagent.handover;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.BeanUtils;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** {@link PeerFallback} 的切面：本地先执行，"没找到"再交给 {@link PeerRelay} 转问对端，请求体由标了 @RequestBody 的参数重新序列化 */
@Aspect
@Component
@RequiredArgsConstructor
public class PeerFallbackAspect {

    private final PeerRelay relay;
    /** 判法类 → 实例 */
    private final Map<Class<? extends Predicate<Object>>, Predicate<Object>> judges = new ConcurrentHashMap<>();

    @Around("@annotation(fallback)")
    public Object around(ProceedingJoinPoint pjp, PeerFallback fallback) throws Throwable {
        Object local = pjp.proceed();
        if (!judges.computeIfAbsent(fallback.missing(), BeanUtils::instantiateClass).test(local)) {
            return local;
        }
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        return relay.relay(body(method, pjp.getArgs()), ParameterizedTypeReference.forType(method.getGenericReturnType()))
                .orElse(local);
    }

    /** 标了 @RequestBody 的那个参数；没有是 null */
    private static Object body(Method method, Object[] args) {
        Annotation[][] params = method.getParameterAnnotations();
        for (int i = 0; i < params.length; i++) {
            for (Annotation a : params[i]) {
                if (a instanceof RequestBody) {
                    return args[i];
                }
            }
        }
        return null;
    }
}
