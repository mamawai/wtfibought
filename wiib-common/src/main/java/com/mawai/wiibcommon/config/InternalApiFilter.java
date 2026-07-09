package com.mawai.wiibcommon.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * internal API 鉴权（共享层）：仅放行带正确 X-Internal-Token 的 /internal/** 请求（服务间内部调用）。
 * <p>防 internal 数据接口被外部直接访问。token 配 {@code internal.api.token}；未配或不匹配一律 401。
 * <p>sim（用户端有 SaToken，/internal/** 已在 excludePaths 放行）与 feed（无 SaToken，纯上游）共用本 filter；
 * quant 无 /internal 端点，本 filter 对其永不匹配（inert）。
 */
@Slf4j
@Component
public class InternalApiFilter extends OncePerRequestFilter {

    @Value("${internal.api.token:}")
    private String internalToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
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
