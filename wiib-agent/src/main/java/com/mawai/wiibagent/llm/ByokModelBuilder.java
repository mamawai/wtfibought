package com.mawai.wiibagent.llm;

import com.mawai.wiibcommon.constant.AiProtocols;
import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibagent.trader.ApiKeyCrypto;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.models.models.Model;
import io.micrometer.observation.ObservationRegistry;
import okhttp3.Response;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * 建模的唯一实现：协议 + baseUrl + key + 模型 + 档位 + 搜索声明 → 一个 ChatModel，按协议分叉。
 * BYOK 入口（{@link #build(UserLlmEndpoint)}）解密后调核心方法；平台轨
 * （{@link com.mawai.wiibagent.runtime.AiAgentRuntimeManager}）拿明文 key 直接调核心方法，两条路同一份建法。
 * 对话（ChatModelFactory）与交易员（TraderModelFactory）两个工厂只管各自的缓存策略。
 */
@Component
public class ByokModelBuilder {

    /**
     * 落点主机必须还是用户填的那个：baseUrl 的地址校验只在它上面做过，跨主机跳走的落点没校验。
     * 只比主机不比端口——http→https 的 301 会把端口从 80 带到 443，比端口就把这种正常重定向误杀了。
     * Spring AI 只暴露 application 级 interceptor（在 OkHttp 重定向处理之上，看不到中间跳），
     * 所以判据取最终响应落在哪台主机。自研协议走 WebClient，压根不跟随重定向。
     */
    private static final List<OpenAiHttpClientBuilderCustomizer> REJECT_CROSS_HOST_REDIRECT = List.of(
            builder -> builder.interceptor(chain -> {
                Response response = chain.proceed(chain.request());
                String asked = chain.request().url().host();
                String landed = response.request().url().host();
                if (!asked.equalsIgnoreCase(landed)) {
                    response.close();
                    throw new IOException("上游把请求重定向到了 " + landed + "，已拒绝");
                }
                return response;
            }));

    private final ApiKeyCrypto apiKeyCrypto;
    private final ToolCallingManager toolCallingManager;
    private final ObservationRegistry observationRegistry;

    public ByokModelBuilder(ApiKeyCrypto apiKeyCrypto,
                            ToolCallingManager toolCallingManager,
                            ObjectProvider<ObservationRegistry> observationRegistry) {
        this.apiKeyCrypto = apiKeyCrypto;
        this.toolCallingManager = toolCallingManager;
        this.observationRegistry = observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP);
    }

    /** BYOK 建模。纯本地构造不发网络请求；key 现解现用，明文不留存字段 */
    public ChatModel build(UserLlmEndpoint e) {
        // webSearch 只是端点能力声明：真发不发搜索工具还要看调用方授权（summarizer 独有），见 SseChatModel.WEB_SEARCH_KEY
        return build(e.getApiProtocol(), e.getBaseUrl(), apiKeyCrypto.decrypt(e.getApiKeyEnc()), e.getModel(),
                e.getReasoningEffort(), Boolean.TRUE.equals(e.getWebSearch()));
    }

    /**
     * 协议中性的建模核心。协议空/未知按 openai 兜底；档位留空=不传走模型默认。
     * 新协议 = 一个 SseChatModel 子类 + 这里一行。
     */
    public ChatModel build(String protocol, String baseUrl, String apiKey, String model, String reasoningEffort,
                           boolean webSearch) {
        String effort = reasoningEffort == null || reasoningEffort.isBlank() ? null : reasoningEffort;
        return switch (AiProtocols.normalize(protocol)) {
            case AiProtocols.RESPONSES ->
                    new ResponsesChatModel(apiKey, baseUrl, model, null, effort, toolCallingManager, webSearch);
            case AiProtocols.ANTHROPIC ->
                    new AnthropicChatModel(apiKey, baseUrl, model, null, effort, toolCallingManager, webSearch);
            case AiProtocols.GEMINI ->
                    new GeminiChatModel(apiKey, baseUrl, model, null, effort, toolCallingManager, webSearch);
            default -> openAiModel(baseUrl, apiKey, model, effort);
        };
    }

    /** openai 协议：Spring AI 2.0 底层是官方 OpenAI SDK，连接参数经 OpenAiSetup 建 client（照抄官方自动配置的建法） */
    private ChatModel openAiModel(String baseUrl, String apiKey, String model, String effort) {
        // timeout 非空是硬约束（SDK 是 Kotlin，null 运行时 NPE）；超时/maxRetries 与自研协议同值，阻塞路径的重试归模型层
        OpenAIClient client = OpenAiSetup.setupSyncClient(
                OpenAiBaseUrl.forSdk(baseUrl), apiKey, null, null, null, null,
                false, false, model, SseChatModel.CALL_TIMEOUT, 3, null, null,
                observationRegistry, null, REJECT_CROSS_HOST_REDIRECT);
        // async 也必须显式给：builder 见 openAiClientAsync 为空就拿 options 自建，而 options 里没 key，
        // SDK 当场抛 "At least one credential source must be specified"（哪怕根本不走流式）
        OpenAIClientAsync clientAsync = OpenAiSetup.setupAsyncClient(
                OpenAiBaseUrl.forSdk(baseUrl), apiKey, null, null, null, null,
                false, false, model, SseChatModel.CALL_TIMEOUT, 3, null, null,
                observationRegistry, null, REJECT_CROSS_HOST_REDIRECT);
        // 不设 temperature：走各模型默认值，思考模型（多数拒收或忽略温度）也安全。
        // timeout 必须显式给：2.0.1 起每请求都把它传给 SDK，缺省 60s 会盖掉上面 client 的 10 分钟
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(model)
                .timeout(SseChatModel.CALL_TIMEOUT);
        if (effort != null) {
            options.reasoningEffort(effort);
        }
        // 不传 toolCallingManager：模型层不跑工具循环（循环在 ReactLoop 里）
        return OpenAiChatModel.builder()
                .openAiClient(client)
                .openAiClientAsync(clientAsync)
                .options(options.build())
                .observationRegistry(observationRegistry)
                .build();
    }

    /** 拉取端点可用模型清单，按协议走各自的 /models；失败原样抛给调用方 */
    public List<String> listModels(String protocol, String baseUrl, String apiKeyEnc) {
        String apiKey = apiKeyCrypto.decrypt(apiKeyEnc);
        return switch (AiProtocols.normalize(protocol)) {
            case AiProtocols.ANTHROPIC -> AnthropicChatModel.listModels(baseUrl, apiKey);
            case AiProtocols.GEMINI -> GeminiChatModel.listModels(baseUrl, apiKey);
            // openai 与 responses 的 /v1/models 同一形状，走 SDK
            default -> {
                // model 参数只在 Azure/GitHub 分支参与 URL 计算，探针还没选模型，占位即可
                OpenAIClient client = OpenAiSetup.setupSyncClient(
                        OpenAiBaseUrl.forSdk(baseUrl), apiKey, null, null, null, null,
                        false, false, "list-models", SseChatModel.CALL_TIMEOUT, 3, null, null,
                        observationRegistry, null, REJECT_CROSS_HOST_REDIRECT);
                yield client.models().list().data().stream().map(Model::id).sorted().toList();
            }
        };
    }
}
