package com.mawai.wiibquant.external.sim;

import com.mawai.wiibcommon.dto.PredictionBetResponse;
import com.mawai.wiibcommon.dto.PredictionRoundResponse;
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
 * SimPredictionApi 的真实 HTTP 层验证（JDK 内置 HttpServer 假扮 sim）：路径展开、可选参数、
 * 请求体、鉴权头、Result 泛型拆壳（含 data 为 null）与业务失败转异常。
 */
class SimPredictionClientTest {

    private static HttpServer server;
    private static SimPredictionClient client;

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
        client = new SimPredictionClient("http://localhost:" + server.getAddress().getPort(), "test-token");
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @Test
    void 买入_路径_请求体_鉴权头_拆壳() {
        responseJson = "{\"code\":0,\"msg\":\"成功\",\"data\":{\"id\":9,\"side\":\"UP\",\"contracts\":20.0,\"cost\":10.0,\"status\":\"ACTIVE\"}}";

        PredictionBetResponse resp = client.buy(42L, "UP", new BigDecimal("10"));

        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastUri.getPath()).isEqualTo("/internal/prediction/42/buy");
        assertThat(lastToken).isEqualTo("test-token");
        assertThat(lastBody).contains("\"side\":\"UP\"").contains("\"amount\":10");
        assertThat(resp.getId()).isEqualTo(9L);
        assertThat(resp.getContracts()).isEqualByComparingTo("20");
    }

    @Test
    void 全卖不带contracts参数() {
        responseJson = "{\"code\":0,\"msg\":\"成功\",\"data\":{\"id\":9,\"status\":\"SOLD\",\"payout\":11.664}}";

        PredictionBetResponse resp = client.sell(42L, 9L, null);

        assertThat(lastUri.getPath()).isEqualTo("/internal/prediction/42/sell/9");
        assertThat(lastUri.getQuery()).isNull();
        assertThat(resp.getPayout()).isEqualByComparingTo("11.664");
    }

    @Test
    void 回合不存在拆出null() {
        responseJson = "{\"code\":0,\"msg\":\"成功\",\"data\":null}";

        PredictionRoundResponse round = client.round(1_700_000_000L);

        assertThat(lastMethod).isEqualTo("GET");
        assertThat(lastUri.getPath()).isEqualTo("/internal/prediction/round/1700000000");
        assertThat(round).isNull();
    }

    @Test
    void 注单列表与游戏余额() {
        responseJson = "{\"code\":0,\"msg\":\"成功\",\"data\":[{\"id\":1,\"side\":\"UP\"},{\"id\":2,\"side\":\"DOWN\"}]}";
        List<PredictionBetResponse> bets = client.recentBets(42L, 5);
        assertThat(lastUri.getPath()).isEqualTo("/internal/prediction/42/bets");
        assertThat(lastUri.getQuery()).isEqualTo("limit=5");
        assertThat(bets).extracting(PredictionBetResponse::getSide).containsExactly("UP", "DOWN");

        responseJson = "{\"code\":0,\"msg\":\"成功\",\"data\":87.65}";
        assertThat(client.gameBalance(42L)).isEqualByComparingTo("87.65");
        assertThat(lastUri.getPath()).isEqualTo("/internal/prediction/42/game-balance");
    }

    @Test
    void 建号返回userId() {
        responseJson = "{\"code\":0,\"msg\":\"成功\",\"data\":{\"userId\":77,\"gameBalance\":100}}";

        Long userId = client.ensureAccount("jev-prediction", new BigDecimal("100"));

        assertThat(lastUri.getPath()).isEqualTo("/internal/prediction/ensure-account");
        assertThat(lastUri.getQuery()).contains("username=jev-prediction").contains("initialGameBalance=100");
        assertThat(userId).isEqualTo(77L);
    }

    @Test
    void 业务失败转SimBizException() {
        responseJson = "{\"code\":4201,\"msg\":\"游戏钱包余额不足\",\"data\":null}";

        assertThatThrownBy(() -> client.buy(42L, "DOWN", new BigDecimal("999")))
                .isInstanceOf(SimTradeClient.SimBizException.class)
                .hasMessageContaining("游戏钱包余额不足");
    }
}
