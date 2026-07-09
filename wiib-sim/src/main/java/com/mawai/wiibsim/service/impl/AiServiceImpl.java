package com.mawai.wiibsim.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.mawai.wiibcommon.constant.AiFunctions;
import com.mawai.wiibcommon.entity.AiModelAssignment;
import com.mawai.wiibcommon.entity.AiRuntimeConfig;
import com.mawai.wiibcommon.mapper.AiModelAssignmentMapper;
import com.mawai.wiibcommon.mapper.AiRuntimeConfigMapper;
import com.mawai.wiibsim.service.AiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import io.netty.channel.ChannelOption;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 调用实现：配置全部来自 DB（ai_model_assignment 的 'sim' 功能位 → ai_runtime_config 的 key），
 * 与 quant 同表同管理入口（Admin 页），改配置即时生效。
 * 每次调用现查 DB——sim 的 AI 只用于每日行情/新闻生成，低频，免缓存即天然热切换。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiServiceImpl implements AiService {

    /** ai_model_assignment 里 sim 的功能位名（行由 quant 启动种子/Admin 页维护） */
    private static final String FUNCTION_NAME = AiFunctions.SIM;
    private static final int TIMEOUT_MS = 60_000;
    private static final int MAX_RETRIES = 3;
    /** 行情参数/虚构新闻要多样性，沿用原配置的偏高温 */
    private static final double TEMPERATURE = 0.85;

    private final AiRuntimeConfigMapper configMapper;
    private final AiModelAssignmentMapper assignmentMapper;
    /** 按 baseUrl 缓存：Admin 换 key 导致 baseUrl 变化时自动建新 client，旧条目残留无害（个位数） */
    private final ConcurrentHashMap<String, WebClient> webClientCache = new ConcurrentHashMap<>();

    @Override
    public String chat(String prompt) {
        Provider provider = loadProvider();
        Exception lastException = null;

        for (int i = 0; i <= MAX_RETRIES; i++) {
            try {
                return callAi(provider, prompt);
            } catch (Exception e) {
                lastException = e;
                if (i < MAX_RETRIES) {
                    log.warn("AI调用失败，重试 {}/{}: {}", i + 1, MAX_RETRIES, e.getMessage());
                    try {
                        Thread.sleep(1000L * (i + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        throw new RuntimeException("AI调用失败，已重试" + MAX_RETRIES + "次", lastException);
    }

    /** 从 DB 解析当前生效的 key/baseUrl/model（模型名归属配置本身）；缺配置直接抛，由调用方降级（GBM 默认参数/跳过新闻） */
    private Provider loadProvider() {
        AiModelAssignment assignment = assignmentMapper.selectByFunction(FUNCTION_NAME);
        if (assignment == null) {
            throw new RuntimeException("未找到" + FUNCTION_NAME + "的功能位分配，请在Admin页配置");
        }
        AiRuntimeConfig config = configMapper.selectById(assignment.getConfigId());
        if (config == null) {
            throw new RuntimeException(FUNCTION_NAME + "引用的LLM配置不存在(id=" + assignment.getConfigId() + ")");
        }
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            throw new RuntimeException(FUNCTION_NAME + "引用的LLM配置已停用: " + config.getConfigName());
        }
        if (config.getModel() == null || config.getModel().isBlank()) {
            throw new RuntimeException(FUNCTION_NAME + "所选LLM配置'" + config.getConfigName() + "'缺模型名，请在Admin页完善");
        }
        return new Provider(config.getApiKey(), config.getBaseUrl(), config.getModel());
    }

    private String callAi(Provider provider, String prompt) {
        JSONObject requestBody = getRequestBody(provider, prompt);

        WebClient webClient = getOrCreateWebClient(provider.baseUrl());
        // 与 quant(Spring AI 默认 completionsPath)同约定：base_url 不含 /v1，客户端自拼 /v1/chat/completions
        String response = webClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey())
                .bodyValue(requestBody.toString())
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofMillis(TIMEOUT_MS));

        if (response == null) {
            throw new RuntimeException("AI返回内容为空");
        }

        JSONObject json = JSONUtil.parseObj(response);

        if (json.containsKey("error")) {
            String errorMsg = json.getByPath("error.message", String.class);
            throw new RuntimeException("AI返回错误: " + errorMsg);
        }

        String content = json.getByPath("choices[0].message.content", String.class);
        if (content == null || content.trim().isEmpty()) {
            throw new RuntimeException("AI返回内容为空");
        }

        return content;
    }

    private static JSONObject getRequestBody(Provider provider, String prompt) {
        JSONObject requestBody = new JSONObject();
        requestBody.set("model", provider.model());

        JSONArray messages = new JSONArray();
        JSONObject message = new JSONObject();
        message.set("role", "user");
        message.set("content", prompt);
        messages.add(message);
        requestBody.set("messages", messages);
        requestBody.set("stream", false);
        requestBody.set("temperature", TEMPERATURE);
        return requestBody;
    }

    private WebClient getOrCreateWebClient(String baseUrl) {
        return webClientCache.computeIfAbsent(baseUrl, url -> {
            ConnectionProvider connectionProvider = ConnectionProvider.builder("sim-ai")
                    .maxConnections(2)
                    .maxIdleTime(Duration.ofSeconds(60))
                    .build();

            HttpClient httpClient = HttpClient.create(connectionProvider)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, TIMEOUT_MS)
                    .responseTimeout(Duration.ofMillis(TIMEOUT_MS));

            return WebClient.builder()
                    .baseUrl(url)
                    .clientConnector(new ReactorClientHttpConnector(httpClient))
                    .build();
        });
    }

    /** DB 当前生效的一组调用参数（1 功能位 = 1 key+model，与 quant 同口径） */
    private record Provider(String apiKey, String baseUrl, String model) {
    }
}
