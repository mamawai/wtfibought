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
        paths.add("/linuxdo/**");
        paths.add("/ws/**");
        paths.add("/internal/**");   // internal API 走 InternalApiFilter 的 token 校验，不走用户登录
        return paths;
    }
}
