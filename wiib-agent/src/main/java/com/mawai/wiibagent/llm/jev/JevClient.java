package com.mawai.wiibagent.llm.jev;

import com.mawai.wiibagent.llm.OpenAiBaseUrl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * TypeSafe Jev 的 HTTP 客户端：POST {baseUrl}/v1/systemone，一份 state 配若干题，一次拿回全部答案。
 * 官方没有 Java SDK，接口就是一个 JSON POST。这里只管收发与解析；key 由调用方解密后传入，明文不留字段。
 * <p>
 * 题目类型三种：noul 是非题回概率；choice 从给定选项挑一个回选项+各项概率+confidence；
 * score 在有序档位上定位回分值+各档概率+confidence。题目与 criteria 一律写英文。
 * <p>
 * 任何失败（连不上、非 2xx、超时、回包不是预期形状）都抛异常，由调用方决定降级还是报错。
 */
@Slf4j
@Component
public class JevClient {

    public static final String DEFAULT_BASE_URL = "https://api.typesafe.ai";
    public static final String DEFAULT_MODEL = "jev-latest";
    /** 官方典型 70~500ms；Jev 只做前置判断，等不起太久，超时就该走降级 */
    static final Duration TIMEOUT = Duration.ofSeconds(5);

    /**
     * 一道题。criteria 按类型：noul 可空或 {true: 说明, false: 说明}；choice 是 {选项键: 说明}；score 是有序的档位说明列表。
     */
    public record Question(String type, String instructions, Object criteria) {

        public static Question noul(String instructions) {
            return new Question("noul", instructions, null);
        }

        public static Question noul(String instructions, String yes, String no) {
            return new Question("noul", instructions, Map.of("true", yes, "false", no));
        }

        public static Question choice(String instructions, Map<String, String> options) {
            return new Question("choice", instructions, options);
        }

        public static Question score(String instructions, List<String> levels) {
            return new Question("score", instructions, levels);
        }
    }

    /**
     * 一道题的答案。noul 题只有 {@code noul}；choice 题有 choice/probabilities/confidence；
     * score 题有 score/probabilities/confidence。没有的字段为 null。
     */
    public record Answer(String type, Double noul, String choice, Double score, Double confidence,
                         Map<String, Double> probabilities) {
    }

    /** model 是实际作答的版本号（别名 jev-latest 会被解析成具体版本），调阈值时要按它钉版本 */
    public record Response(String model, Map<String, Answer> answers, long inputTokens) {
    }

    /**
     * 问一次。state 是字符串、Map 或 List，原样序列化进请求；questions 的键就是回包里 answers 的键。
     *
     * @param baseUrl 用户填的地址，带不带 /v1 都行
     */
    public Response ask(String baseUrl, String apiKey, String model, Object state, Map<String, Question> questions) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.set("state", MAPPER.valueToTree(state));
        ObjectNode qs = body.putObject("questions");
        questions.forEach((key, q) -> {
            ObjectNode node = qs.putObject(key);
            node.put("type", q.type());
            node.put("instructions", q.instructions());
            if (q.criteria() != null) {
                node.set("criteria", MAPPER.valueToTree(q.criteria()));
            }
        });
        long startedAt = System.currentTimeMillis();
        String raw = WebClient.create(OpenAiBaseUrl.strip(baseUrl, "/v1")).post()
                .uri("/v1/systemone")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(MAPPER.writeValueAsString(body))
                .retrieve()
                .bodyToMono(String.class)
                .block(TIMEOUT);
        Response response = parse(raw);
        log.info("[Jev] model={} questions={} inputTokens={} 耗时={}ms",
                response.model(), questions.keySet(), response.inputTokens(), System.currentTimeMillis() - startedAt);
        return response;
    }

    /** 回包 → Response；answers 缺失按空回包算，调用方拿不到题就当失败处理 */
    static Response parse(String raw) {
        JsonNode root = MAPPER.readTree(raw);
        JsonNode answers = root.path("answers");
        if (!answers.isObject()) {
            throw new IllegalStateException("Jev 回包没有 answers: " + head(raw));
        }
        Map<String, Answer> out = new LinkedHashMap<>();
        answers.properties().forEach(e -> {
            JsonNode a = e.getValue();
            Map<String, Double> probs = null;
            if (a.path("probabilities").isObject()) {
                probs = new LinkedHashMap<>();
                for (Map.Entry<String, JsonNode> p : a.path("probabilities").properties()) {
                    probs.put(p.getKey(), p.getValue().asDouble());
                }
            }
            out.put(e.getKey(), new Answer(a.path("type").asString(null), num(a.path("noul")),
                    a.path("choice").asString(null), num(a.path("score")), num(a.path("confidence")), probs));
        });
        return new Response(root.path("model").asString(null), out, root.path("usage").path("input_tokens").asLong(0));
    }

    private static Double num(JsonNode n) {
        return n.isNumber() ? n.asDouble() : null;
    }

    private static String head(String raw) {
        return raw == null ? "null" : raw.substring(0, Math.min(200, raw.length()));
    }
}
