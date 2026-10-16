package com.mawai.wiibquant.external.sim;

import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

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
    private static volatile long responseDelayMs;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            lastMethod = exchange.getRequestMethod();
            lastUri = exchange.getRequestURI();
            lastToken = exchange.getRequestHeaders().getFirst("X-Internal-Token");
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (responseDelayMs > 0) {
                try {
                    Thread.sleep(responseDelayMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
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
    void deleteAccount_POST查询参数_业务失败拆壳抛异常() {
        responseJson = "{\"code\":0,\"msg\":\"成功\",\"data\":null}";
        client.deleteAccount("ai_trader_1_r1");
        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastUri.getPath()).isEqualTo("/internal/futures/delete-account");
        assertThat(lastUri.getQuery()).contains("username=ai_trader_1_r1");

        // 护栏拒删（比如误传 quant-FIBO）走 Result.fail，必须抛出来而不是静默当成功
        responseJson = "{\"code\":500,\"msg\":\"仅允许删除 ai_trader 量化子账户\",\"data\":null}";
        assertThatThrownBy(() -> client.deleteAccount("quant-FIBO"))
                .hasMessageContaining("仅允许删除");
    }

    /**
     * 读超时抛什么，AI Trader 的"同键重发确认"整条链路都押在这上面：必须是
     * ResourceAccessException 而不是业务失败那种 SimBizException，
     * 否则 isTransportFailure 认不出来，超时会被当明确失败回给模型，模型重下就是双仓。
     */
    @Test
    void 读超时抛ResourceAccessException_供同键重发识别() {
        responseJson = "{\"code\":0,\"msg\":\"成功\",\"data\":{\"orderId\":1}}";
        responseDelayMs = 6000; // 客户端读超时 5s
        try {
            Throwable thrown = catchThrowable(() -> client.openPosition(42L, new FuturesOpenRequest()));

            assertThat(thrown).isInstanceOf(ResourceAccessException.class);
            assertThat(SimTradeClient.isTransportFailure(thrown)).isTrue();
        } finally {
            responseDelayMs = 0;
        }
    }

    /**
     * 幂等占位的"处理中"是 1106，认不出来就不会去重发确认。
     * 1105 是 sim 抢 Redis 锁失败，语义是"确定没成交"，混进来会让没下出去的单被报成"可能已成交"。
     */
    @Test
    void 处理中按错误码识别_不与抢锁失败和普通业务失败混淆() {
        responseJson = "{\"code\":1106,\"msg\":\"请求处理中，请稍后用同一 clientRequestId 重试\",\"data\":null}";
        Throwable processing = catchThrowable(() -> client.openPosition(42L, new FuturesOpenRequest()));
        assertThat(SimTradeClient.isProcessing(processing)).isTrue();

        responseJson = "{\"code\":1105,\"msg\":\"订单正在处理中，请稍后再试\",\"data\":null}";
        Throwable lockBusy = catchThrowable(() -> client.openPosition(42L, new FuturesOpenRequest()));
        assertThat(SimTradeClient.isProcessing(lockBusy)).isFalse();

        responseJson = "{\"code\":1751,\"msg\":\"余额不足\",\"data\":null}";
        Throwable business = catchThrowable(() -> client.openPosition(42L, new FuturesOpenRequest()));
        assertThat(SimTradeClient.isProcessing(business)).isFalse();
        assertThat(SimTradeClient.isTransportFailure(business)).isFalse();
    }

    @Test
    void 业务失败200加Resultfail_拆壳转异常() {
        responseJson = "{\"code\":500,\"msg\":\"余额不足\",\"data\":null}";

        assertThatThrownBy(() -> client.getBalance(42L))
                .isInstanceOf(SimTradeClient.SimBizException.class)
                .hasMessageContaining("余额不足");
    }

    /**
     * ★ 拒因成文跟<b>调用方</b>那门语言，不跟 sim 渲染时用的那门。★
     * <p>
     * sim 收不到语言（quant 打的是 internal API，没有请求线程也没有 X-Lang），
     * 它只会按自己的回落语言渲染。所以 quant 拿码回来自己查词表 —— 英文 trader
     * 的拒因既要回给模型（跟着混语言模型立刻跟着混）又要进公开时间线。
     */
    @Test
    void 业务码按调用方语言成文_而不是sim渲染的那句() {
        MessageCatalog messages = new MessageCatalog();
        // 1751 = FUTURES_INSUFFICIENT_BALANCE，sim 那边渲染成了中文
        responseJson = "{\"code\":1751,\"msg\":\"余额不足\",\"data\":null}";
        Throwable e = catchThrowable(() -> client.openPosition(42L, new FuturesOpenRequest()));

        assertThat(SimTradeClient.describe(e, messages, AgentLang.EN)).isEqualTo("Not enough balance");
        assertThat(SimTradeClient.describe(e, messages, AgentLang.ZH)).isEqualTo("余额不足");
    }

    /** sim 在码之外另写了细节（步长/名义额/杠杆档位的数字）：词条换不掉它，拼在词条后面 */
    @Test
    void 带细节的业务码把sim原话拼在词条后() {
        MessageCatalog messages = new MessageCatalog();
        // 1603 = TRADE_STEP_INVALID，sim 的原话里有步长
        responseJson = "{\"code\":1603,\"msg\":\"BTCUSDT 数量需为 0.001 的整数倍\",\"data\":null}";
        Throwable e = catchThrowable(() -> client.openPosition(42L, new FuturesOpenRequest()));

        assertThat(SimTradeClient.describe(e, messages, AgentLang.EN))
                .isEqualTo("Quantity does not match the minimum step size (BTCUSDT 数量需为 0.001 的整数倍)");
        assertThat(SimTradeClient.describe(e, messages, AgentLang.ZH))
                .isEqualTo("数量不符合最小交易步长（BTCUSDT 数量需为 0.001 的整数倍）");
    }

    /**
     * 1000 以下的码（sim 侧 Result.fail(自己写的话)）没有词表条目可查，退回原话。
     * 渲染成通用的"系统错误"会把唯一有用的信息弄丢——比如护栏为什么拒绝删号。
     */
    @Test
    void 通用码退回sim原话_不渲染成系统错误() {
        MessageCatalog messages = new MessageCatalog();
        responseJson = "{\"code\":500,\"msg\":\"仅允许删除 ai_trader 量化子账户\",\"data\":null}";
        Throwable e = catchThrowable(() -> client.deleteAccount("quant-FIBO"));

        assertThat(SimTradeClient.describe(e, messages, AgentLang.EN))
                .isEqualTo("仅允许删除 ai_trader 量化子账户");
    }

    /** 非 sim 业务失败（传输失败、代码异常）没有码可查，照原样 —— 那是诊断信息不是给用户的话 */
    @Test
    void 非业务失败照原样不进词表() {
        MessageCatalog messages = new MessageCatalog();
        assertThat(SimTradeClient.describe(new IllegalStateException("boom"), messages, AgentLang.EN))
                .isEqualTo("boom");
        // 无 message 的异常退回类名，绝不能是 null —— 这句要落库展示
        assertThat(SimTradeClient.describe(new NullPointerException(), messages, AgentLang.EN))
                .isEqualTo("NullPointerException");
    }
}
