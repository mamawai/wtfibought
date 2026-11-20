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

    /** 游客只读：行情、全站成交、榜单、爆仓、留言板列表。都不读登录态，参数服务端已夹紧 */
    @Override
    protected List<String> getAnonymousGetPaths() {
        return List.of(
                "/api/crypto/klines",              // /crypto/price 不放：游客页用不到
                "/api/crypto/order/live",          // 全站最新现货成交
                "/api/bstock/*",                    // list / price / klines / {symbol}，下单在 /bstock/order/** 不受影响
                "/api/futures/klines",
                "/api/futures/brackets",
                "/api/futures/funding-rate",
                "/api/futures/live",                // 全站最新合约成交
                "/api/futures/force-orders",
                "/api/futures/force-orders/latest",
                "/api/trades/public",
                "/api/ranking",                     // 只放榜单分页；/me、/users/* 要登录
                // 留言板三条读接口，@CurrentUserId(optional) 游客给 null；POST /api/comments 同路径但不是 GET，照样要登录
                "/api/comments",
                "/api/comments/*/children",
                "/api/comments/context/*"
        );
    }
}
