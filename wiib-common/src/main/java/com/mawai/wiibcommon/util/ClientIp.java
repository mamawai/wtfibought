package com.mawai.wiibcommon.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * 请求方的真实 IP。线上 nginx 用 realip 从 Cloudflare 的 CF-Connecting-IP 取出真实 IP 填进 X-Real-IP，
 * 客户端自己带的会被 nginx 覆盖；本地开发没这个头，用连接来源。
 * 不认 X-Forwarded-For：第一段客户端能自己写
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(HttpServletRequest req) {
        String ip = req.getHeader("X-Real-IP");
        return ip != null ? ip : req.getRemoteAddr();
    }

    /** 当前 HTTP 请求的；不在请求线程里调用会抛 IllegalStateException */
    public static String current() {
        return of(((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest());
    }

    /** 限流分桶用：IPv4 用整个地址，IPv6 只取前 /64（一台机器通常分到一整段 /64） */
    public static String currentForLimit() {
        InetAddress a = InetAddress.ofLiteral(current());
        if (a instanceof Inet4Address) {
            return a.getHostAddress();
        }
        byte[] b = a.getAddress();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i += 2) {
            sb.append(Integer.toHexString(((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF))).append(':');
        }
        return sb.append(":/64").toString();
    }
}
