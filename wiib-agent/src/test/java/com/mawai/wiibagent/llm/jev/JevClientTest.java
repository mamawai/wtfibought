package com.mawai.wiibagent.llm.jev;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 请求体形状、鉴权头、三种答案的解析、非 2xx 抛出。上游用本地 HttpServer 回放 */
class JevClientTest {

    private static HttpServer server;
    private static volatile int httpStatus = 200;
    private static volatile String httpBody = "{}";
    private static volatile String lastRequestBody = "";
    private static volatile String lastAuth = "";
    private static volatile String lastPath = "";

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath = exchange.getRequestURI().getPath();
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] bytes = httpBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(httpStatus, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static final String OK_BODY = """
            {"model":"jev-1.13.0",
             "answers":{
               "urgent":{"type":"noul","noul":0.93},
               "dept":{"type":"choice","choice":"billing","probabilities":{"billing":0.84,"technical":0.16},"confidence":0.6},
               "sev":{"type":"score","score":1.3,"probabilities":{"0":0.0,"1":0.7,"2":0.3},"confidence":0.54,
                      "legend":{"0":"a","1":"b","2":"c"}}
             },
             "usage":{"input_tokens":210,"output_tokens":31}}
            """;

    @Test
    void 请求体带model_state_questions且走Bearer鉴权() {
        httpStatus = 200;
        httpBody = OK_BODY;
        new JevClient().ask(baseUrl() + "/v1/", "sk-jev", "jev-latest",
                Map.of("message", "charged twice"),
                Map.of("urgent", JevClient.Question.noul("Does this convey urgency?"),
                        "dept", JevClient.Question.choice("Which team?", Map.of("billing", "Charges", "technical", "Bugs")),
                        "sev", JevClient.Question.score("How severe?", List.of("Cosmetic", "Degraded", "Blocking"))));

        assertThat(lastPath).isEqualTo("/v1/systemone");   // 用户填的地址带 /v1 也不会拼成 /v1/v1
        assertThat(lastAuth).isEqualTo("Bearer sk-jev");
        JsonNode body = MAPPER.readTree(lastRequestBody);
        assertThat(body.path("model").asString(null)).isEqualTo("jev-latest");
        assertThat(body.path("state").path("message").asString(null)).isEqualTo("charged twice");
        JsonNode qs = body.path("questions");
        assertThat(qs.path("urgent").path("type").asString(null)).isEqualTo("noul");
        assertThat(qs.path("urgent").has("criteria")).isFalse();
        assertThat(qs.path("dept").path("criteria").path("billing").asString(null)).isEqualTo("Charges");
        assertThat(qs.path("sev").path("criteria").isArray()).isTrue();
        assertThat(qs.path("sev").path("criteria").size()).isEqualTo(3);
    }

    @Test
    void 三种答案各取各的字段() {
        httpStatus = 200;
        httpBody = OK_BODY;
        JevClient.Response r = new JevClient().ask(baseUrl(), "sk-jev", "jev-latest", "x",
                Map.of("urgent", JevClient.Question.noul("q")));

        assertThat(r.model()).isEqualTo("jev-1.13.0");
        assertThat(r.inputTokens()).isEqualTo(210);
        JevClient.Answer urgent = r.answers().get("urgent");
        assertThat(urgent.noul()).isEqualTo(0.93);
        assertThat(urgent.choice()).isNull();
        JevClient.Answer dept = r.answers().get("dept");
        assertThat(dept.choice()).isEqualTo("billing");
        assertThat(dept.confidence()).isEqualTo(0.6);
        assertThat(dept.probabilities()).containsEntry("billing", 0.84);
        JevClient.Answer sev = r.answers().get("sev");
        assertThat(sev.score()).isEqualTo(1.3);
        assertThat(sev.probabilities()).containsEntry("1", 0.7);
    }

    @Test
    void 非2xx抛出() {
        httpStatus = 401;
        httpBody = "{\"error\":\"invalid key\"}";
        assertThatThrownBy(() -> new JevClient().ask(baseUrl(), "bad", "jev-latest", "x",
                Map.of("q", JevClient.Question.noul("q"))))
                .hasMessageContaining("401");
    }

    @Test
    void 回包没有answers抛出() {
        httpStatus = 200;
        httpBody = "{\"model\":\"jev-1.13.0\"}";
        assertThatThrownBy(() -> new JevClient().ask(baseUrl(), "sk", "jev-latest", "x",
                Map.of("q", JevClient.Question.noul("q"))))
                .hasMessageContaining("answers");
    }
}
