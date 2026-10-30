package com.mawai.wiibcommon.aspect;

import com.mawai.wiibcommon.util.ClientIp;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * 轻量日志切面。参数打印前做字段级脱敏：BYOK key / 登录密码 / 邀请码这类值不许进日志。
 */
@Slf4j
@Aspect
@Component
@Order(1)
public class LogAspect {

    /** 命中即打码的敏感字段名词干（xxxApiKey/newPassword 这类包含式命名一并覆盖） */
    private static final String SENSITIVE_NAMES =
            "(?:password|passwd|secret|token|api[-_]?key|access[-_]?key|credential|invite[-_]?code)";
    /** record/POJO toString 的 field=value 风格：值截到下一个分隔符 */
    private static final Pattern EQ_STYLE = Pattern.compile(
            "(?i)(\\w*" + SENSITIVE_NAMES + "\\w*\\s*=\\s*)[^,)\\]]*");
    /** JSON 串直接进参数时的 "field":"value" 风格 */
    private static final Pattern JSON_STYLE = Pattern.compile(
            "(?i)(\"\\w*" + SENSITIVE_NAMES + "\\w*\"\\s*:\\s*\")[^\"]*");

    /** 按字段名识别打码，而不是按 controller 加白名单——白名单必漏，新接口没人记得加 */
    static String maskSensitive(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        s = EQ_STYLE.matcher(s).replaceAll("$1***");
        return JSON_STYLE.matcher(s).replaceAll("$1***");
    }

    @Around("execution(public * com.mawai..controller.*.*(..)) && !within(com.mawai..controller.MonitorController)")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        long start = System.currentTimeMillis();

        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String url = "";
        String httpMethod = "";
        String ip = "";

        if (attrs != null) {
            HttpServletRequest req = attrs.getRequest();
            url = req.getRequestURL().toString();
            httpMethod = req.getMethod();
            ip = ClientIp.of(req);
        }

        String className = point.getSignature().getDeclaringTypeName();
        String methodName = point.getSignature().getName();
        String args = maskSensitive(Arrays.toString(point.getArgs()));

        Object result;
        try {
            result = point.proceed();
            long cost = System.currentTimeMillis() - start;

            log.info("""

                            ========== Request ==========
                            URL: {} {}
                            IP: {}
                            Method: {}.{}
                            Args: {}
                            Cost: {}ms
                            =============================""",
                    httpMethod, url, ip, className, methodName, args, cost);
            if (log.isDebugEnabled()) {
                // 登录响应带 satoken，同样不许明文进日志
                log.debug("Result: {}", maskSensitive(String.valueOf(result)));
            }

            return result;
        } catch (Throwable e) {
            long cost = System.currentTimeMillis() - start;

            log.error("""

                            ========== Request Error ==========
                            URL: {} {}
                            IP: {}
                            Method: {}.{}
                            Args: {}
                            Error: {}
                            Cost: {}ms
                            ===================================""",
                    httpMethod, url, ip, className, methodName, args, e.getMessage(), cost);

            throw e;
        }
    }
}
