package com.mawai.wiibservice.config;

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
        paths.add("/api/agora/**");
        paths.add("/api/invite/**");
        paths.add("/linuxdo/**");
        paths.add("/ws/**");
        return paths;
    }
}
