package com.mawai.wiibsim.config;

import org.springframework.util.StringUtils;

/**
 * OAuth 登录方（LinuxDo、Google）的接入配置：换 token、取用户信息两步共用一套请求
 */
public interface OAuthClientConfig {

    String getClientId();

    String getClientSecret();

    /** 授权完跳回的前端地址，换 token 时要原样再带一次 */
    String getRedirectUri();

    String getTokenUrl();

    String getUserUrl();

    /** client-id 配了就开这种登录 */
    default boolean isEnabled() {
        return StringUtils.hasText(getClientId());
    }
}
