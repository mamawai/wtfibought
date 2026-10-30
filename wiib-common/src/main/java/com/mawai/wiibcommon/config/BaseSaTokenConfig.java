package com.mawai.wiibcommon.config;

import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.listener.SaTokenListener;
import cn.dev33.satoken.router.SaHttpMethod;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * Sa-Token 基础配置类
 * 各模块继承并自定义
 */
@Slf4j
@RequiredArgsConstructor
public abstract class BaseSaTokenConfig implements WebMvcConfigurer {

    private final StringRedisTemplate stringRedisTemplate;

    @Bean
    @Primary
    public SaTokenConfig baseSaTokenConfig() {
        SaTokenConfig config = new SaTokenConfig();
        config.setTokenName("satoken");
        config.setActiveTimeout(60 * 60 * 24 * 7); // 7天无操作过期
        config.setIsConcurrent(true);   // 允许同账号多端同时在线，不再互相顶下线
        config.setIsShare(false);       // 每次登录签发独立token，一端退出不影响另一端
        config.setMaxLoginCount(2);     // 最多两端在线，第三端登录顶掉最早那个（仅 isConcurrent+非share 时生效）
        config.setTokenStyle("uuid");
        config.setIsLog(false);         // 开了会把登录 token 明文打进日志
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
                // 双端并发后只剩一种触发场景：登第三端，最早那端被顶掉，清掉残留token
                try {
                    stringRedisTemplate.delete("satoken:login:token:" + tokenValue);
                } catch (Exception e) {
                    log.warn("删除被顶下线的token失败: {}", e.getMessage());
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

            // 1.45.0 起签名多了 loginType，与其余回调的 (loginType, loginId, tokenValue, …) 对齐
            @Override
            public void doRenewTimeout(String loginType, Object loginId, String tokenValue, long timeout) {
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

    /**
     * 游客可读的接口：只放 GET，同一路径上的 POST/PUT/DELETE 照样要登录。
     * 写精确路径或单段 *，别用 **，否则会把下面挂着的写接口一起放出去
     */
    protected List<String> getAnonymousGetPaths() {
        return List.of();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(_ ->
                SaRouter.match("/**")
                        .notMatch(getExcludePaths())
                        .notMatch(SaRouter.isMatchCurrMethod(new SaHttpMethod[]{SaHttpMethod.GET}) && SaRouter.isMatchCurrURI(getAnonymousGetPaths()))
                        .check(_ -> StpUtil.checkLogin())
        ) {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                if (request.getDispatcherType() == DispatcherType.ASYNC) {
                    // 跳过流式返回的鉴权，只需要鉴权第一次即可
                    return true;
                }
                return super.preHandle(request, response, handler);
            }
        }).addPathPatterns("/**");
    }
}
