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
 * <p>sim、agent（有 SaToken，/internal/** 已在 excludePaths 放行）与 feed（无 SaToken，只挂 /internal/streams）共用本 filter。
 * sim、agent 的 /internal/handoff/** 是交接接口（部署脚本和对端实例调）。
 */
@Slf4j
@Component
public class InternalApiFilter extends OncePerRequestFilter {

    @Value("${internal.api.token:}")
    private String internalToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {
        // 按 Tomcat 解码、规范化后的 servletPath 判断：/%69nternal/、/internal;a=b/ 也算 /internal/
        if (request.getServletPath().startsWith("/internal/")) {
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
