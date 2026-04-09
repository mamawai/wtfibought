package com.mawai.wiibcommon.config;

import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.listener.SaTokenListener;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Sa-Token 基础配置类
 * 各模块继承并自定义
 */
@RequiredArgsConstructor
public abstract class BaseSaTokenConfig implements WebMvcConfigurer {

    private final StringRedisTemplate stringRedisTemplate;

    @Bean
    @Primary
    public SaTokenConfig baseSaTokenConfig() {
        SaTokenConfig config = new SaTokenConfig();
        config.setTokenName("satoken");
        config.setActiveTimeout(60 * 60 * 24 * 7); // 7天无操作过期
        config.setIsConcurrent(false);
        config.setIsShare(true);
        config.setTokenStyle("uuid");
        config.setIsLog(true);
        config.setIsReadCookie(false);
        config.setIsReadHeader(true);
        return config;
    }

    @Bean
    public SaTokenListener saTokenListener() {
        return new SaTokenListener() {
            @Override
            public void doLogin(String loginType, Object loginId, String tokenValue, SaLoginParameter loginParameter) {
            }

            @Override
            public void doLogout(String loginType, Object loginId, String tokenValue) {
            }

            @Override
            public void doKickout(String loginType, Object loginId, String tokenValue) {
            }

            @Override
            public void doReplaced(String loginType, Object loginId, String tokenValue) {
                // 被顶下线时删除旧token
                try {
                    String prefix = "satoken:login:token:";
                    Set<String> keys = stringRedisTemplate.keys(prefix + tokenValue);
                    if (!keys.isEmpty()) {
                        stringRedisTemplate.delete(keys);
                    }
                } catch (Exception e) {
                    System.err.println("删除被顶下线的token失败: " + e.getMessage());
                }
            }

            @Override
            public void doDisable(String loginType, Object loginId, String service, int level, long disableTime) {
            }

            @Override
            public void doUntieDisable(String loginType, Object loginId, String service) {
            }

            @Override
            public void doOpenSafe(String loginType, String tokenValue, String service, long safeTime) {
            }

            @Override
            public void doCloseSafe(String loginType, String tokenValue, String service) {
            }

            @Override
            public void doCreateSession(String id) {
            }

            @Override
            public void doLogoutSession(String id) {
            }

            @Override
            public void doRenewTimeout(String tokenValue, Object loginId, long timeout) {
            }
        };
    }

    protected List<String> getDefaultExcludePaths() {
        return Arrays.asList(
                "/doc.html",
                "/webjars/**",
                "/swagger-ui/**",
                "/swagger-ui.html",
                "/swagger-resources/**",
                "/v3/api-docs/**",
                "/favicon.ico",
                "/error"
        );
    }

    protected abstract List<String> getExcludePaths();

    protected boolean isInterceptorEnabled() {
        return true;
    }

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        if (!isInterceptorEnabled()) {
            return;
        }
        registry.addInterceptor(new SaInterceptor(handle ->
                SaRouter.match("/**")
                        .notMatch(getExcludePaths())
                        .check(r -> StpUtil.checkLogin())
        ) {
            @Override
            public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) throws Exception {
                if (request.getDispatcherType() == DispatcherType.ASYNC) {
                    // 跳过流式返回的鉴权，只需要鉴权第一次即可
                    return true;
                }
                return super.preHandle(request, response, handler);
            }
        }).addPathPatterns("/**");
    }
}
