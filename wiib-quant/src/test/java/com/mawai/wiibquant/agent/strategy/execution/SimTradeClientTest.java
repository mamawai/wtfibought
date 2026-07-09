package com.mawai.wiibquant.agent.strategy.execution;

import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SimTradeApi 声明式接口的真实 HTTP 层验证（JDK 内置 HttpServer 假扮 sim）：
 * 路径模板展开、可选查询参数、鉴权头、Result&lt;T&gt; 泛型反序列化与业务失败拆壳。
 * SimExecutionServiceTest 的桩覆盖不到这些——它把 client 方法整个覆写掉了。
 */
class SimTradeClientTest {

    private static HttpServer server;
    private static SimTradeClient client;

    private static volatile String lastMethod;
    private static volatile URI lastUri;
    private static volatile String lastToken;
    private static volatile String lastBody;
    private static volatile String responseJson;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            lastMethod = exchange.getRequestMethod();
            lastUri = exchange.getRequestURI();
            lastToken = exchange.getRequestHeaders().getFirst("X-Internal-Token");
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] resp = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        client = new SimTradeClient("http://localhost:" + server.getAddress().getPort(), "test-token");
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @Test
    void 开仓_路径展开_请求体_鉴权头_泛型拆壳() {
        responseJson = "{\"code\":0,\"msg\":\"成功\",\"data\":{\"orderId\":123,\"status\":\"PENDING\"}}";
        FuturesOpenRequest req = new FuturesOpenRequest();
        req.setSymbol("ETHUSDT");

        FuturesOrderResponse resp = client.openPosition(42L, req);

        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastUri.getPath()).isEqualTo("/internal/futures/42/open");
        assertThat(lastToken).isEqualTo("test-token");
        assertThat(lastBody).contains("\"symbol\":\"ETHUSDT\"");
        assertThat(resp.getOrderId()).isEqualTo(123L);
        assertThat(resp.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void 持仓查询_symbol传值带参数_传null省略参数() {
        responseJson = "{\"code\":0,\"msg\":\"成功\",\"data\":[{\"id\":7,\"status\":\"OPEN\"}]}";

        List<FuturesPositionDTO> positions = client.getPositions(42L, "ETHUSDT");
        assertThat(lastUri.toString()).isEqualTo("/internal/futures/42/positions?symbol=ETHUSDT");
        assertThat(positions).singleElement().satisfies(p -> assertThat(p.getId()).isEqualTo(7L));

        client.getAllPositions(42L);
        assertThat(lastUri.toString()).isEqualTo("/internal/futures/42/positions");
    }

    @Test
    void ensureAccount_POST查询参数_解析userId() {
        responseJson = "{\"code\":0,\"msg\":\"成功\",\"data\":{\"userId\":99,\"balance\":10000}}";

        Long userId = client.ensureAccount("quant-FIBO", new BigDecimal("10000"));

        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastUri.getPath()).isEqualTo("/internal/futures/ensure-account");
        assertThat(lastUri.getQuery()).contains("username=quant-FIBO").contains("initialBalance=10000");
        assertThat(userId).isEqualTo(99L);
    }

    @Test
    void 业务失败200加Resultfail_拆壳转异常() {
        responseJson = "{\"code\":500,\"msg\":\"余额不足\",\"data\":null}";

        assertThatThrownBy(() -> client.getBalance(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("余额不足");
    }
}
