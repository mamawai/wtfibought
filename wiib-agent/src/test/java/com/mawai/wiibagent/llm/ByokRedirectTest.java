package com.mawai.wiibagent.llm;

import com.mawai.wiibcommon.constant.AiProtocols;
import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BYOK 重定向落点必须还是用户填的那台主机。
 * <p>
 * 两台假服务器：origin 是用户填在 baseUrl 里的那台，landing 是跳转落点。
 * origin 的 /cross 往 landing 跳（跨主机），/same 往自己跳（同主机，模拟 http→https 那类 301）。
 */
class ByokRedirectTest {

    /** 用户填的那台，绑 127.0.0.1 */
    private static HttpServer origin;
    /** 跳转落点，绑 localhost——与 origin 是不同的主机名，才测得出跨主机 */
    private static HttpServer landing;
    private static final AtomicInteger landingHits = new AtomicInteger();

    private ByokModelBuilder builder;
    private String keyEnc;

    @BeforeAll
    static void startServers() throws IOException {
        landing = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        landing.createContext("/", exchange -> {
            landingHits.incrementAndGet();
            json(exchange, "{\"data\":[{\"id\":\"leaked\"}]}");
        });
        landing.setExecutor(Executors.newCachedThreadPool());
        landing.start();

        origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 正常应答：同主机跳转最终落在这里
        origin.createContext("/v1/models", exchange -> json(exchange, "{\"data\":[{\"id\":\"ok\"}]}"));
        origin.createContext("/cross", exchange -> redirect(exchange,
                "http://localhost:" + landing.getAddress().getPort() + exchange.getRequestURI()));
        origin.createContext("/same", exchange -> redirect(exchange,
                "http://127.0.0.1:" + origin.getAddress().getPort()
                        + exchange.getRequestURI().toString().replace("/same", "")));
        origin.setExecutor(Executors.newCachedThreadPool());
        origin.start();
    }

    private static void json(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    @AfterAll
    static void stopServers() {
        origin.stop(0);
        landing.stop(0);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        landingHits.set(0);
        ApiKeyCrypto crypto = new ApiKeyCrypto(Base64.getEncoder().encodeToString(new byte[32]));
        keyEnc = crypto.encrypt("sk-test");
        ObjectProvider<ObservationRegistry> registry = mock(ObjectProvider.class);
        when(registry.getIfUnique(any())).thenReturn(ObservationRegistry.NOOP);
        builder = new ByokModelBuilder(crypto, mock(ToolCallingManager.class), registry);
    }

    private String originBaseUrl(String path) {
        return "http://127.0.0.1:" + origin.getAddress().getPort() + path;
    }

    private UserLlmEndpoint endpoint(String protocol, String path) {
        UserLlmEndpoint e = new UserLlmEndpoint();
        e.setApiProtocol(protocol);
        e.setBaseUrl(originBaseUrl(path));
        e.setModel("m");
        e.setApiKeyEnc(keyEnc);
        return e;
    }

    @Test
    void 跨主机重定向的模型清单拿不到落点数据() {
        assertThatThrownBy(() -> builder.listModels(AiProtocols.OPENAI, originBaseUrl("/cross"), keyEnc))
                .hasStackTraceContaining("已拒绝");
    }

    @Test
    void 跨主机重定向的openai调用被拒() {
        assertThatThrownBy(() -> builder.build(endpoint(AiProtocols.OPENAI, "/cross"))
                .call(new Prompt(new UserMessage("ping"))))
                .hasStackTraceContaining("已拒绝");
    }

    /** responses 协议走 WebClient，压根不跟随重定向，落点一次都碰不到 */
    @Test
    void responses协议不跟随重定向() {
        try {
            builder.build(endpoint(AiProtocols.RESPONSES, "/cross"))
                    .call(new Prompt(new UserMessage("ping")));
        } catch (Exception ignored) {
            // 302 不跟随时按非 2xx 处理，抛什么不重要
        }
        assertThat(landingHits).hasValue(0);
    }

    /** 存量用户填 http:// 被 301 到 https:// 同一域名就是这种形状，不能误杀 */
    @Test
    void 同主机重定向照常放行() {
        assertThat(builder.listModels(AiProtocols.OPENAI, originBaseUrl("/same"), keyEnc)).containsExactly("ok");
    }
}
