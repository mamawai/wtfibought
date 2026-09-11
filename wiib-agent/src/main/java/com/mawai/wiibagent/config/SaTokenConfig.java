package com.mawai.wiibagent.config;

import com.mawai.wiibcommon.config.BaseSaTokenConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * Sa-Token 配置：白名单制，放行清单之外的接口一律需要登录。
 * 本进程自己不签发登录（token 由 sim 签发，两进程共享同一份 Redis），
 * 所以放行的只有 swagger/error 这些静态门面，actuator 不放。
 */
@Configuration
public class SaTokenConfig extends BaseSaTokenConfig {

    public SaTokenConfig(StringRedisTemplate stringRedisTemplate) {
        super(stringRedisTemplate);
    }

    @Override
    protected List<String> getExcludePaths() {
        return getDefaultExcludePaths();
    }

    /**
     * 游客只读：快讯/财经日历、竞技场。
     * /api/ai/trader/* 单段通配连 GET /mine、/action-panel 也会放进来，它们形参是 @CurrentUserId long，游客照样 401；
     * /{id}/live 和 trace 不放，只有主人能看
     */
    @Override
    protected List<String> getAnonymousGetPaths() {
        return List.of(
                "/api/ai/quant/news",
                "/api/ai/quant/econ-calendar",
                "/api/ai/quant/econ-calendar/events",
                "/api/ai/trader/arena",
                "/api/ai/trader/*",
                "/api/ai/trader/*/decisions",
                "/api/ai/trader/*/trades",
                "/api/ai/trader/*/equity-curve",
                "/api/ai/trader/*/token-usage"
        );
    }
}
