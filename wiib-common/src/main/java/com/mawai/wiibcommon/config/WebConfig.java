package com.mawai.wiibcommon.config;

import com.mawai.wiibcommon.resolver.CurrentUserIdArgumentResolver;
import com.mawai.wiibcommon.resolver.SymbolArgumentResolver;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 通用 Web MVC 配置（三模块共享）：注册自定义参数解析器。
 * 与各模块自己的 SaToken WebMvcConfigurer 并存，Spring 会合并全部 configurer。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(@NonNull List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserIdArgumentResolver());
        resolvers.add(new SymbolArgumentResolver());
    }
}
