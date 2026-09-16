package com.mawai.wiibsim.campaign.ldc;

import com.mawai.wiibsim.campaign.LdcProperties;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * LDC 分发客户端的判定与重试。
 * <p>
 * 起真 HTTP 桩不 mock：要测的正是"客户端不跟 307"，那是 HttpClient 的行为，mock 掉就没了。
 * 307 重试安全：请求压根没到后端，不可能重复发放（与超时重试不同，那个靠 out_trade_no 兜幂等）。
 */
class LdcClientTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    /** 收到的请求体原文，用来钉死请求契约（尤其 user_id 的 JSON 类型） */
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private volatile java.util.function.IntFunction<int[]> plan;   // 第 n 次 → {状态码}
    private volatile String body = "";
    /** 307 的 Location 落点。真被跟过去了这里就不是 0，等于把"跟随了"抓个现行 */
    private final AtomicInteger loginPageHits = new AtomicInteger();

    private long savedInitialBackoff;
    private long savedMaxBackoff;

    @BeforeEach
    void setUp() throws IOException {
        // 退避调到毫秒级，否则「307 重试到上限」一条要真睡 16.5 秒。
        // 只动测试进程里的值，MAX_ATTEMPTS=8 与生产退避常量都不动
        savedInitialBackoff = LdcClient.initialBackoffMs;
        savedMaxBackoff = LdcClient.maxBackoffMs;
        LdcClient.initialBackoffMs = 1;
        LdcClient.maxBackoffMs = 2;

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/lpay/distribute", ex -> {
            int n = hits.incrementAndGet();
            authHeaders.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            requestBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = plan.apply(n)[0];
            // 线上那个 307 是带 Location 的（指向前端登录页）。桩必须照带，
            // 否则"客户端会不会跟过去"根本无从暴露——没有 Location 谁都跟不了
            if (status == 307) {
                ex.getResponseHeaders().add("Location", baseUrl() + "/login");
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.createContext("/login", ex -> {
            loginPageHits.incrementAndGet();
            byte[] out = "<html>login</html>".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        LdcClient.initialBackoffMs = savedInitialBackoff;
        LdcClient.maxBackoffMs = savedMaxBackoff;
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private LdcClient client() {
        LdcProperties p = new LdcProperties();
        p.setBaseUrl(baseUrl());
        p.setClientId("cid");
        p.setClientSecret("secret");
        p.setEnabled(true);
        return new LdcClient(p, new MessageCatalog());
    }

    private LdcResult call() {
        return client().distribute("12345", "someone", new BigDecimal("12.34"), "WIIB_T_1");
    }

    @Test
    void 成功判定看HTTP200加tradeNo非空() {
        plan = n -> new int[]{200};
        body = "{\"error_msg\":\"\",\"data\":{\"out_trade_no\":\"WIIB_T_1\",\"trade_no\":\"87597927425376256\"}}";

        LdcResult r = call();

        assertThat(r.success()).isTrue();
        assertThat(r.tradeNo()).isEqualTo("87597927425376256");
        assertThat(hits).hasValue(1);
    }

    /** 官方文档那套 {"code":1} 是错的：200 但没有 trade_no 不算成功 */
    @Test
    void 二百但没有tradeNo不算成功() {
        plan = n -> new int[]{200};
        body = "{\"code\":1,\"data\":{}}";

        assertThat(call().success()).isFalse();
    }

    /** 307 是前端误路由，退避重试；后端根本没收到，重试不会重复发放 */
    @Test
    void 三零七退避重试直到成功() {
        plan = n -> new int[]{n <= 3 ? 307 : 200};
        body = "{\"error_msg\":\"\",\"data\":{\"trade_no\":\"999\"}}";

        LdcResult r = call();

        assertThat(r.success()).isTrue();
        assertThat(r.tradeNo()).isEqualTo("999");
        assertThat(hits).hasValue(4);
        // 全类最要紧的不变量：重试只许原样重发。单号一变就等于另开一笔订单，
        // 幂等锚点当场失效，"重试安全"这个前提也跟着塌了
        assertThat(requestBodies).hasSize(4);
        assertThat(requestBodies).containsOnly(requestBodies.getFirst());
    }

    /** 绝不能自己跟着 307 跑：跟过去拿到的是登录页 HTML，成败就分不清了 */
    @Test
    void 三零七重试到上限仍失败且不跟随重定向() {
        plan = n -> new int[]{307};
        body = "<html>login</html>";

        LdcResult r = call();

        assertThat(r.success()).isFalse();
        assertThat(r.errorMsg()).contains("307");
        assertThat(hits).hasValue(8);
        // 跟过去就只剩一坨登录页 HTML，"没发出去"和"发出去了没读到响应"再也分不开
        assertThat(loginPageHits).hasValue(0);
    }

    /** 撞唯一索引 = 上次其实发成功了，判 SUCCESS 而不是 FAILED */
    @Test
    void 撞单号幂等判成功() {
        plan = n -> new int[]{400};
        body = "{\"error_msg\":\"ERROR: duplicate key value violates unique constraint "
                + "\\\"idx_orders_client_merchant_order\\\" (SQLSTATE 23505)\",\"data\":null}";

        LdcResult r = call();

        assertThat(r.success()).isTrue();
        assertThat(r.tradeNo()).isNull();
        assertThat(hits).hasValue(1);       // 不重试
    }

    /**
     * 200 只认 data.trade_no，绝不去扫 duplicate key / 23505。
     * <p>
     * trade_no 是 17 位雪花数，正常流水号里就可能含 "23505" 这个子串。
     * 判定顺序一反，这笔真发成功的钱会被判成 alreadySent()（tradeNo=null），
     * 于是 campaign_reward 记下 SUCCESS 却没有 external_ref——
     * 唯一对不上 LinuxDo 侧账的那笔，恰恰是真发出去了的那笔。
     */
    @Test
    void 流水号里含23505仍按成功带回流水号() {
        plan = n -> new int[]{200};
        body = "{\"error_msg\":\"\",\"data\":{\"trade_no\":\"87597927423505256\"}}";

        LdcResult r = call();

        assertThat(r.success()).isTrue();
        assertThat(r.tradeNo()).isEqualTo("87597927423505256");
    }

    /**
     * 失败响应里恰好蹦出个 "23505"（网关错误页的 ray id、时间戳都可能）不等于幂等命中。
     * <p>
     * 判据必须窄：误判成"已发放"不可恢复（SUCCESS + external_ref=NULL，用户静悄悄少一份），
     * 漏判安全（同单号重发撞唯一索引）。
     */
    @Test
    void 错误页里恰好含23505不算幂等命中() {
        plan = n -> new int[]{400};
        body = "{\"error_msg\":\"gateway error, ray=8f23505ab\",\"data\":null}";

        LdcResult r = call();

        assertThat(r.success()).isFalse();
        assertThat(r.errorMsg()).contains("23505");
        assertThat(hits).hasValue(1);
    }

    /** 其他 4xx 是真失败，记原因、不重试 */
    @Test
    void 收款人不存在直接失败不重试() {
        plan = n -> new int[]{400};
        body = "{\"error_msg\":\"收款人不存在\",\"data\":null}";

        LdcResult r = call();

        assertThat(r.success()).isFalse();
        assertThat(r.errorMsg()).contains("收款人不存在");
        assertThat(hits).hasValue(1);
    }

    @Test
    void 用BasicAuth带凭证() {
        plan = n -> new int[]{200};
        body = "{\"data\":{\"trade_no\":\"1\"}}";

        call();

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("cid:secret".getBytes(StandardCharsets.UTF_8));
        assertThat(authHeaders).containsExactly(expected);
    }

    /** 未配凭证时不该发出任何请求 */
    @Test
    void 未启用时直接失败不发请求() {
        LdcProperties p = new LdcProperties();
        p.setBaseUrl("http://localhost:" + server.getAddress().getPort());

        LdcResult r = new LdcClient(p, new MessageCatalog()).distribute("1", "x", BigDecimal.ONE, "WIIB_T_2");

        assertThat(r.success()).isFalse();
        assertThat(hits).hasValue(0);
    }

    /**
     * 钉死请求契约。重点是 user_id 必须是 JSON 数字：官方文档写"数字"，
     * 后端 Go 侧若声明成 int64，收到 "12345" 会直接 unmarshal 失败。
     * 这是全部字段里唯一有残余不确定性的一处，钉住它，将来要改是明知故改而不是漂移。
     */
    @Test
    void 请求体字段与类型符合接口契约() {
        plan = n -> new int[]{200};
        body = "{\"data\":{\"trade_no\":\"1\"}}";

        call();

        assertThat(requestBodies).hasSize(1);
        String raw = requestBodies.getFirst();
        assertThat(raw).contains("\"user_id\":12345")
                .doesNotContain("\"user_id\":\"12345\"");
        // 逐字钉住：字段顺序、类型、中文不转义
        assertThat(raw).isEqualTo("{\"user_id\":12345,\"username\":\"someone\",\"amount\":\"12.34\","
                + "\"out_trade_no\":\"WIIB_T_1\",\"remark\":\"WhatIfIBought 五维交易赛奖励\"}");

        JsonNode sent = MAPPER.readTree(raw);
        assertThat(sent.get("user_id").isNumber()).isTrue();
        assertThat(sent.get("amount").isString()).isTrue();   // 金额发字符串，不发浮点
        assertThat(sent.get("amount").asString()).isEqualTo("12.34");
        assertThat(sent.get("out_trade_no").asString()).isEqualTo("WIIB_T_1");
        assertThat(sent.get("username").asString()).isEqualTo("someone");
        assertThat(sent.get("remark").asString()).isNotBlank();
    }

    /** 超过两位小数服务端直接拒，按 DOWN 截断成两位再发（不进位，宁少发不多发） */
    @Test
    void 金额超两位小数截断成两位() {
        plan = n -> new int[]{200};
        body = "{\"data\":{\"trade_no\":\"1\"}}";

        client().distribute("12345", "someone", new BigDecimal("12.3499"), "WIIB_T_3");
        client().distribute("12345", "someone", new BigDecimal("7"), "WIIB_T_4");

        assertThat(MAPPER.readTree(requestBodies.get(0)).get("amount").asString()).isEqualTo("12.34");
        assertThat(MAPPER.readTree(requestBodies.get(1)).get("amount").asString()).isEqualTo("7.00");
    }

    /** 非数字 ID（AI_TRADER / 邀请码用户）物理上收不了款，本地就拦掉，别浪费一次请求 */
    @Test
    void ID非数字时直接失败不发请求() {
        plan = n -> new int[]{200};
        body = "{\"data\":{\"trade_no\":\"1\"}}";

        LdcResult r = client().distribute("AI_TRADER", "x", BigDecimal.ONE, "WIIB_T_5");

        assertThat(r.success()).isFalse();
        assertThat(r.errorMsg()).contains("AI_TRADER");
        assertThat(hits).hasValue(0);
    }
}
