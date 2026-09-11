package com.mawai.wiibagent.llm;

import com.mawai.wiibcommon.i18n.MessageCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * BYOK baseUrl 的 SSRF 防线：仅 http(s) + 公网地址——服务器会向该地址发请求，
 * 放任内网地址等于把 quant 变成任意用户的内网探测器（云上还有 169.254 元数据服务）。
 * 运维白名单（环境变量 WIIB_TRADER_BASEURL_ALLOWLIST，逗号分隔主机名）先于 DNS 短路放行，
 * 服务于 docker 同网络的代理网关（如 cliproxyapi）。
 * 明确不防 DNS rebinding（校验时解析一次，请求时不钉连接层）：攻击成本高、模拟盘收益低，接受残余风险。
 */
@Component
public class BaseUrlGuard {

    private final Set<String> allowlist;
    private final MessageCatalog messages;

    public BaseUrlGuard(@Value("${WIIB_TRADER_BASEURL_ALLOWLIST:}") String allowlistCsv,
                        MessageCatalog messages) {
        this.messages = messages;
        this.allowlist = allowlistCsv == null || allowlistCsv.isBlank()
                ? Set.of()
                : Arrays.stream(allowlistCsv.split(","))
                        .map(s -> s.trim().toLowerCase(Locale.ROOT))
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet());
    }

    /** 校验 baseUrl；返回给用户看的错误文案（跟当次请求的界面语言），通过返回 null。 */
    public String check(String baseUrl) {
        URI uri;
        try {
            uri = new URI(baseUrl.trim());
        } catch (Exception e) {
            return messages.get("agent.endpoint.baseUrl.malformed");
        }
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return messages.get("agent.endpoint.baseUrl.schemeUnsupported");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return messages.get("agent.endpoint.baseUrl.hostMissing");
        }
        if (allowlist.contains(host.toLowerCase(Locale.ROOT))) {
            return null;
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return messages.get("agent.endpoint.baseUrl.hostUnresolvable");
        }
        for (InetAddress a : addrs) {
            // 原地址与嵌入其中的 v4 双重受检：::1 这类原生 v6 属性只在原地址上，
            // 而 NAT64 嵌着的 169.254.169.254 只在抠出来的 v4 上——判一头必漏另一头
            if (isBlocked(a) || isBlocked(unwrapEmbeddedV4(a))) {
                return messages.get("agent.endpoint.baseUrl.privateBlocked");
            }
        }
        return null;
    }

    private static boolean isBlocked(InetAddress a) {
        return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                || a.isAnyLocalAddress() || a.isMulticastAddress()
                || isUniqueLocalV6(a) || isSharedAddressSpace(a);
    }

    /**
     * 嵌 v4 的 v6 归一化：NAT64(64:ff9b::/96) / 6to4(2002::/16) / SIIT(::ffff:0:a.b.c.d) /
     * IPv4-compatible(::a.b.c.d) 解析出来是真 Inet6Address，全部 IPv4 判断都不命中——
     * 而有 NAT64 网关的网络里，64:ff9b::a9fe:a9fe 会被翻译成 169.254.169.254 打到云元数据端点。
     * 把嵌着的 v4 抠出来按 v4 受检；不含 v4 的普通 v6 原样返回。
     * （标准 mapped 形态 ::ffff:a.b.c.d 被 Java 直接解析成 Inet4Address，不经这里。）
     */
    private static InetAddress unwrapEmbeddedV4(InetAddress a) {
        if (!(a instanceof Inet6Address)) {
            return a;
        }
        byte[] b = a.getAddress();
        int off;
        if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x02) {
            off = 2;   // 6to4：v4 在字节 2~5
        } else if ((b[0] & 0xFF) == 0 && (b[1] & 0xFF) == 0x64
                && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B && isZero(b, 4, 12)) {
            off = 12;  // NAT64 well-known 前缀
        } else if (isZero(b, 0, 8) && (b[8] & 0xFF) == 0xFF && (b[9] & 0xFF) == 0xFF
                && b[10] == 0 && b[11] == 0) {
            off = 12;  // SIIT translated ::ffff:0:a.b.c.d
        } else if (isZero(b, 0, 12)) {
            off = 12;  // IPv4-compatible ::a.b.c.d
        } else {
            return a;
        }
        try {
            return InetAddress.getByAddress(Arrays.copyOfRange(b, off, off + 4));
        } catch (UnknownHostException e) {
            return a;  // 4 字节的 getByAddress 不会抛，只为编译器
        }
    }

    private static boolean isZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /** IPv6 unique-local fc00::/7：isSiteLocalAddress 只认已废弃的 fec0::/10，这段要手判 */
    private static boolean isUniqueLocalV6(InetAddress a) {
        return a instanceof Inet6Address && (a.getAddress()[0] & 0xFE) == 0xFC;
    }

    /**
     * RFC 6598 共享地址空间 100.64.0.0/10：运营商级 NAT 用它，多家云也拿它当内网服务段
     * （阿里云元数据 100.100.100.200、内网 DNS 100.100.2.136 都在这段）。
     * isSiteLocalAddress 只认 10/172.16/192.168，这一段是它的盲区
     */
    private static boolean isSharedAddressSpace(InetAddress a) {
        if (a instanceof Inet6Address) {
            return false;
        }
        byte[] b = a.getAddress();
        // 100.64.0.0/10 = 第一字节 100（8 位全定）+ 第二字节高 2 位为 0b01（即 64..127）
        return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 0x40;
    }
}
