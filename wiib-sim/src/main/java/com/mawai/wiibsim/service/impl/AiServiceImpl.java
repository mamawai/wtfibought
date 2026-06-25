package com.mawai.wiibsim.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.mawai.wiibsim.config.AiModelConfig;
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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiServiceImpl implements AiService {

    private final AiModelConfig aiModelConfig;
    private final ConcurrentHashMap<String, WebClient> webClientCache = new ConcurrentHashMap<>();

    @Override
    public String chat(String prompt) {
        List<AiModelConfig.ModelProvider> enabledProviders = aiModelConfig.getProviders().stream()
                .filter(AiModelConfig.ModelProvider::getEnabled)
                .sorted(Comparator.comparing(AiModelConfig.ModelProvider::getPriority))
                .toList();

        if (enabledProviders.isEmpty()) {
            log.error("没有启用的AI提供商");
            throw new RuntimeException("没有启用的AI提供商");
        }

        Exception lastException = null;
        for (AiModelConfig.ModelProvider provider : enabledProviders) {
            try {
                log.info("尝试调用AI: {} (priority={})", provider.getName(), provider.getPriority());
                String result = callAiWithRetry(provider, prompt);
                log.info("AI调用成功: {}", provider.getName());
                return result;
            } catch (Exception e) {
                log.warn("AI调用失败: {} - {}", provider.getName(), e.getMessage());
                lastException = e;
            }
        }

        log.error("所有AI提供商调用失败");
        throw new RuntimeException("所有AI提供商调用失败", lastException);
    }

    @Override
    public String chatWithProvider(String providerName, String prompt) {
        AiModelConfig.ModelProvider provider = aiModelConfig.getProviders().stream()
                .filter(p -> p.getName().equals(providerName) && p.getEnabled())
                .findFirst()
                .orElseThrow(() -> new RuntimeException("提供商不存在或未启用: " + providerName));

        return callAiWithRetry(provider, prompt);
    }

    private String callAiWithRetry(AiModelConfig.ModelProvider provider, String prompt) {
        int maxRetries = provider.getMaxRetries();
        Exception lastException = null;

        for (int i = 0; i <= maxRetries; i++) {
            try {
                return callAi(provider, prompt);
            } catch (Exception e) {
                lastException = e;
                if (i < maxRetries) {
                    log.warn("AI调用失败，重试 {}/{}: {}", i + 1, maxRetries, e.getMessage());
                    try {
                        Thread.sleep(1000L * (i + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        throw new RuntimeException("AI调用失败，已重试" + maxRetries + "次", lastException);
    }

    private String callAi(AiModelConfig.ModelProvider provider, String prompt) {
        JSONObject requestBody = getRequestBody(provider, prompt);

        WebClient webClient = getOrCreateWebClient(provider);
        int timeoutMs = resolveTimeoutMs(provider);
        String response = webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> applyAuth(headers, provider))
                .bodyValue(requestBody.toString())
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofMillis(timeoutMs));

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

    private static JSONObject getRequestBody(AiModelConfig.ModelProvider provider, String prompt) {
        JSONObject requestBody = new JSONObject();
        requestBody.set("model", provider.getModel());

        JSONArray messages = new JSONArray();
        JSONObject message = new JSONObject();
        message.set("role", "user");
        message.set("content", prompt);
        messages.add(message);
        requestBody.set("messages", messages);
        requestBody.set("stream", false);

        if (provider.getTemperature() != null) {
            requestBody.set("temperature", provider.getTemperature());
        }
        return requestBody;
    }

    private WebClient getOrCreateWebClient(AiModelConfig.ModelProvider provider) {
        return webClientCache.computeIfAbsent(provider.getName(), name -> {
            int timeoutMs = resolveTimeoutMs(provider);

            ConnectionProvider connectionProvider = ConnectionProvider.builder(name)
                    .maxConnections(2)
                    .maxIdleTime(Duration.ofSeconds(60))
                    .build();

            HttpClient httpClient = HttpClient.create(connectionProvider)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs)
                    .responseTimeout(Duration.ofMillis(timeoutMs));

            return WebClient.builder()
                    .baseUrl(provider.getBaseUrl())
                    .clientConnector(new ReactorClientHttpConnector(httpClient))
                    .build();
        });
    }

    private int resolveTimeoutMs(AiModelConfig.ModelProvider provider) {
        Integer timeout = provider.getTimeout();
        if (timeout == null || timeout <= 0) {
            return 30000;
        }
        return timeout;
    }

    private void applyAuth(HttpHeaders headers, AiModelConfig.ModelProvider provider) {
        String authType = provider.getAuthType();
        String apiKey = provider.getApiKey();
        if (authType == null || authType.isBlank() || "authorization".equalsIgnoreCase(authType)) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
            return;
        }
        if ("x-api-key".equalsIgnoreCase(authType)) {
            headers.set("x-api-key", apiKey);
            return;
        }
        throw new RuntimeException("不支持的鉴权方式: " + authType);
    }
}
