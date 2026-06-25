package com.mawai.wiibsim.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * internal API 鉴权：仅放行带正确 X-Internal-Token 的 /internal/** 请求（quant 等内部服务调用）。
 * <p>防 internal 数据接口被外部直接访问。token 配 {@code internal.api.token}；未配或不匹配一律 401。
 * 该路径已在 SaToken excludePaths 放行（不走用户登录），鉴权完全由本 filter 负责。
 */
@Slf4j
@Component
public class InternalApiFilter extends OncePerRequestFilter {

    @Value("${internal.api.token:}")
    private String internalToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/internal/")) {
            String token = request.getHeader("X-Internal-Token");
            if (internalToken == null || internalToken.isBlank() || !internalToken.equals(token)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getWriter().write("internal api unauthorized");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
