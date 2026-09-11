package com.mawai.wiibagent.llm;

import com.mawai.wiibcommon.i18n.MessageCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSRF 防线：baseUrl 仅允许 http(s) + 公网地址；环境变量白名单主机先于 DNS 短路放行。
 * 测试全部离线确定：字面 IP 不发 DNS，localhost 由本机 hosts 解析。
 */
class BaseUrlGuardTest {

    private final BaseUrlGuard guard = new BaseUrlGuard("", new MessageCatalog());

    @Test
    void publicLiteralIpAllowed() {
        assertThat(guard.check("https://8.8.8.8")).isNull();
        assertThat(guard.check("http://1.1.1.1:8317")).isNull();
    }

    @Test
    void loopbackAndPrivateRejected() {
        assertThat(guard.check("http://127.0.0.1:8080")).contains("内网");
        assertThat(guard.check("http://localhost:11434")).contains("内网");
        assertThat(guard.check("http://10.0.0.5")).contains("内网");
        assertThat(guard.check("http://192.168.1.5:8082")).contains("内网");
        assertThat(guard.check("http://172.18.0.3")).contains("内网");
    }

    /** 云元数据地址（169.254.x 链路本地）必须拒绝 */
    @Test
    void linkLocalMetadataRejected() {
        assertThat(guard.check("http://169.254.169.254")).contains("内网");
    }

    @Test
    void nonHttpSchemeRejected() {
        assertThat(guard.check("file:///etc/passwd")).contains("http");
        assertThat(guard.check("ftp://8.8.8.8")).contains("http");
    }

    @Test
    void malformedOrHostlessRejected() {
        assertThat(guard.check("not a url")).isNotNull();
        assertThat(guard.check("https://")).isNotNull();
    }

    /** 100.64.0.0/10 是 RFC 6598 共享地址空间、多家云的内网服务段，isSiteLocalAddress 只认 10/172.16/192.168 会漏掉，不补就是直通内网的口子 */
    @Test
    void 拦截共享地址空间() {
        // 段头、阿里云元数据实址、段尾各钉一点：掩码写窄了这三点必掉一个，只判第二字节等于某值也过不了
        assertThat(guard.check("http://100.64.0.0")).contains("内网");
        assertThat(guard.check("http://100.100.100.200")).contains("内网");
        assertThat(guard.check("http://100.127.255.255")).contains("内网");
    }

    /** 掩码写宽就会把 100.x 里本属公网的部分一起误封，用户填的合法端点会被无故拒掉 */
    @Test
    void 共享地址空间边界外的公网地址不误伤() {
        assertThat(guard.check("http://100.63.255.255")).isNull();
        assertThat(guard.check("http://100.128.0.1")).isNull();
    }

    /**
     * 嵌 v4 的 v6 形态必须按抠出来的 v4 受检：这些地址解析成真 Inet6Address，
     * 所有 IPv4 判断天然不命中；NAT64 网关会把 64:ff9b::a9fe:a9fe 翻译成
     * 169.254.169.254 打到云元数据端点——五种形态各钉一点。
     */
    @Test
    void 拦截嵌v4的v6地址() {
        assertThat(guard.check("http://[64:ff9b::a9fe:a9fe]/")).contains("内网");   // NAT64 嵌 169.254.169.254
        assertThat(guard.check("http://[64:ff9b::7f00:1]/")).contains("内网");      // NAT64 嵌 127.0.0.1
        assertThat(guard.check("http://[2002:7f00:1::1]/")).contains("内网");       // 6to4 嵌 127.0.0.1
        assertThat(guard.check("http://[::ffff:0:100.64.0.1]/")).contains("内网");  // SIIT 嵌共享地址空间
        assertThat(guard.check("http://[::127.0.0.1]/")).contains("内网");          // IPv4-compatible 嵌环回
        assertThat(guard.check("http://[::100.64.0.1]/")).contains("内网");         // IPv4-compatible 嵌共享段
    }

    /** 归一化不许误伤：普通公网 v6 与嵌着公网 v4 的形态照常放行 */
    @Test
    void 正常公网v6不误伤() {
        assertThat(guard.check("http://[2606:4700::6810:84e5]/")).isNull();  // 普通公网 v6
        assertThat(guard.check("http://[64:ff9b::808:808]/")).isNull();      // NAT64 嵌 8.8.8.8（公网）
        assertThat(guard.check("http://[2002:808:808::1]/")).isNull();       // 6to4 嵌 8.8.8.8
    }

    /** 原生 v6 属性仍在原地址上判：抠 v4 那步不许把 ::1 这类放过去 */
    @Test
    void 原生v6内网地址仍拦截() {
        assertThat(guard.check("http://[::1]/")).contains("内网");
        assertThat(guard.check("http://[fc00::1]/")).contains("内网");   // unique-local
        assertThat(guard.check("http://[fe80::1]/")).contains("内网");   // link-local
    }

    /** 运维白名单主机先于 DNS 短路放行：docker 网络主机名在开发机上解析不了也要能过 */
    @Test
    void allowlistedHostBypassesResolution() {
        BaseUrlGuard g = new BaseUrlGuard("cliproxyapi, ollama-box", new MessageCatalog());
        assertThat(g.check("http://cliproxyapi:8317")).isNull();
        assertThat(g.check("http://OLLAMA-BOX:11434")).isNull();
        // 白名单不影响其他主机照常拒绝
        assertThat(g.check("http://127.0.0.1")).contains("内网");
    }
}
