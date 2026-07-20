package com.mawai.wiibsim.config;

import com.mawai.wiibcommon.config.BaseSaTokenConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Sa-Token 配置
 */
@Configuration
public class SaTokenConfig extends BaseSaTokenConfig {

    public SaTokenConfig(StringRedisTemplate stringRedisTemplate) {
        super(stringRedisTemplate);
    }

    @Override
    protected List<String> getExcludePaths() {
        List<String> paths = new ArrayList<>(getDefaultExcludePaths());
        paths.add("/api/auth/callback/**");
        paths.add("/api/auth/mode");        // 登录前拉取登录模式
        paths.add("/api/auth/login/**");    // 管理员直登/密码登录，登录前访问
        paths.add("/api/auth/register");    // 邀请码注册，登录前访问
        paths.add("/api/agora/**");
        paths.add("/linuxdo/**");
        paths.add("/ws/**");
        paths.add("/internal/**");   // internal API 走 InternalApiFilter 的 token 校验，不走用户登录
        // 留言板要让游客能浏览。放行的是整段路径（拦截器按路径匹配，分不了 GET/POST），
        // 但发表/赞踩/删除挂着 @CurrentUserId、禁言挂着 @RequireAdmin，取不到 loginId 照样 401
        // 两条都写：/** 能否匹配不带后缀的裸路径取决于匹配器实现，而 /api/comments 正是游客拉列表的入口
        paths.add("/api/comments");
        paths.add("/api/comments/**");
        return paths;
    }
}
