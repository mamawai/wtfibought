package com.mawai.wiibcommon.config;

import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.resolver.CurrentUserIdArgumentResolver;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @CurrentUserId} 防越权链的看门测试：{@link CurrentUserIdArgumentResolver} 没生效时，
 * {@code @CurrentUserId Long userId} 会被 Spring 当成 {@code ?userId=} 绑定，接口照样 200。
 */
class WebConfigTest {

    /** 解析器必须真被加进列表——这行没了，全站 @CurrentUserId 静默退化成 ?userId= */
    @Test
    void CurrentUserId解析器必须被注册() {
        List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();

        new WebConfig(new MessageCatalog()).addArgumentResolvers(resolvers);

        assertThat(resolvers).hasAtLeastOneElementOfType(CurrentUserIdArgumentResolver.class);
    }

    /** WebConfig 得是能被组件扫描捡起来的 WebMvcConfigurer，摘了 @Configuration 或 implements，addArgumentResolvers 就不会被调 */
    @Test
    void WebConfig必须是能被扫到的WebMvcConfigurer() {
        assertThat(WebConfig.class.isAnnotationPresent(Configuration.class))
                .as("WebConfig 摘了 @Configuration 就不会被注册，参数解析器随之缺席")
                .isTrue();
        assertThat(WebMvcConfigurer.class).isAssignableFrom(WebConfig.class);
    }

    /** 认领的判据就是 {@code @CurrentUserId}：带注解的认领，不带的（如 symbol）不认领，认领了会被塞成当前用户 id */
    @Test
    void 解析器只认领挂了CurrentUserId的形参() throws Exception {
        Method probe = WebConfigTest.class.getDeclaredMethod("探针", Long.class, String.class);
        CurrentUserIdArgumentResolver resolver = new CurrentUserIdArgumentResolver();

        assertThat(resolver.supportsParameter(new MethodParameter(probe, 0))).isTrue();
        assertThat(resolver.supportsParameter(new MethodParameter(probe, 1))).isFalse();
    }

    /** 只为上面那条用例提供两个形参，不会被调用 */
    @SuppressWarnings("unused")
    private void 探针(@CurrentUserId Long userId, String symbol) {
    }
}
