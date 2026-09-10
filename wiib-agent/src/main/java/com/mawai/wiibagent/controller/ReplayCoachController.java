package com.mawai.wiibagent.controller;

import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibagent.analysis.ReplayCoachPrompts;
import com.mawai.wiibagent.analysis.ReplayCoachRequest;
import com.mawai.wiibagent.chat.ChatModelFactory;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibagent.llm.LlmEndpointService;
import com.mawai.wiibagent.llm.LlmErrorMessages;
import com.mawai.wiibagent.llm.ResilientChatService;
import com.mawai.wiibagent.llm.SseChannel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 手动复盘的 AI 教练：局中盘面提示 / 结算后评估用户看法。一次请求 = 一次 BYOK 模型流式调用，
 * SSE 事件协议与研判工作台同款（token/done/error），前端复用同一套解析。
 * <p>
 * 与工作台对话的区别：不进会话历史、不记忆、不排队——纯一次性问答，所以没有 ChatConcurrencyGate
 * 那套名额与让位；只挡"同一用户上一次还没跑完又点"（{@link ErrorCode#REPLAY_AI_BUSY}）。
 * 断连即停：没有落库诉求，用户切走了就别再烧他的 token。
 */
@Slf4j
@Tag(name = "复盘 AI 教练")
@RestController
@RequestMapping("/api/ai/backtest/replay")
@RequiredArgsConstructor
public class ReplayCoachController {

    /** 深模型带思考时几十秒不出字很正常；20s 一帧心跳喂饱 nginx 默认 60s 空闲计时器 */
    private static final long HEARTBEAT_SECONDS = 20;
    /** 单次上限 5 分钟：REVIEW 几百根 K 线 + 深模型思考，够；再长多半是端点挂了 */
    private static final long EMITTER_TIMEOUT_MS = 300_000L;

    private final LlmEndpointService endpointService;
    private final ChatModelFactory chatModelFactory;
    private final ReplayCoachPrompts coachPrompts;
    private final PromptCatalog prompts;
    /** 教练是实时请求：语言走 @CurrentUserId -> user.lang，与 chat/trader 同一条路 */
    private final UserLangResolver userLangResolver;
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "replay-coach-heartbeat");
                t.setDaemon(true);
                return t;
            });
    /** 在跑中的用户：同一人一次只跑一个 */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    @PostMapping(value = "/coach", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "复盘 AI 教练（SSE：HINT 盘面提示 / REVIEW 评估用户看法）")
    public SseEmitter coach(@CurrentUserId long userId, @RequestBody ReplayCoachRequest request,
                            HttpServletResponse response) {
        SseChannel.noProxyBuffering(response);
        AgentLang lang = userLangResolver.of(userId);
        String invalid = coachPrompts.validate(request, lang);
        if (invalid != null) {
            throw new BizException(invalid);
        }
        // 准入检查全部在交出 emitter 之前：一旦返回 event-stream，错误只能推 error 事件，前端拿不到 2201/2202 去引导配置。
        // 端点：复盘配置台点名的那条（endpointId）；没点名/点的已被删 → 用户默认端点；一条都没有 → 2201
        UserLlmEndpoint endpoint = request.endpointId() == null ? null : endpointService.get(userId, request.endpointId());
        if (endpoint == null) {
            endpoint = endpointService.defaultOf(userId);
        }
        if (endpoint == null) {
            throw new BizException(ErrorCode.LLM_CONFIG_MISSING);
        }
        ChatModel model;
        try {
            // 复用对话轨的模型缓存（同一条端点对话/复盘共享一个实例），只取深模型那格
            model = chatModelFactory.modelsFor(new ChatEndpoints(userId, endpoint, null)).deep();
        } catch (Exception e) {
            log.warn("[ReplayCoach] 建模失败 userId={}", userId, e);
            throw new BizException(ErrorCode.LLM_CONFIG_INVALID);
        }
        if (!inFlight.add(userId)) {
            throw new BizException(ErrorCode.REPLAY_AI_BUSY);
        }

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        SseChannel channel = new SseChannel(emitter);
        emitter.onCompletion(channel::markClosed);
        emitter.onTimeout(() -> {
            channel.markClosed();
            emitter.complete();
        });
        emitter.onError(ex -> channel.markClosed());
        try {
            streamExecutor.execute(() -> {
                try {
                    run(channel, model, request, lang);
                } catch (Throwable e) {
                    log.error("[ReplayCoach] 任务异常退出 userId={}", userId, e);
                } finally {
                    inFlight.remove(userId);   // 在跑标记只能在这一层摘，否则一次异常就把这个用户永久锁死
                }
            });
        } catch (Throwable e) {
            inFlight.remove(userId);
            throw e;
        }
        return emitter;
    }

    /** 一次流式调用的全过程。包私有：单测直接喂 mock 模型看它发出去的事件 */
    void run(SseChannel channel, ChatModel model, ReplayCoachRequest request, AgentLang lang) {
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleWithFixedDelay(
                channel::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        StringBuilder answer = new StringBuilder();
        try {
            // ResilientChatService 捎系统提示 + 流式重试；不挂工具不进 ReactLoop，落到底就是一次 model.stream
            ResilientChatService service = ResilientChatService.builder().model(model)
                    .systemPrompt(coachPrompts.system(request, lang)).build();
            // toStream + try-with-resources：用户切走（通道关闭）后 takeWhile 停止迭代、close 取消订阅，不再烧 token
            try (Stream<ChatResponse> frames = service.streamingExecute(
                    List.of(new UserMessage(coachPrompts.user(request, lang))), null).toStream()) {
                frames.takeWhile(f -> !channel.isClosed()).forEach(f -> {
                    String chunk = textOf(f);
                    if (chunk == null || chunk.isEmpty()) {
                        return;
                    }
                    answer.append(chunk);
                    channel.send("token", new JSONObject().fluentPut("text", chunk));
                });
            }
            if (channel.isClosed()) {
                return;
            }
            if (answer.isEmpty()) {
                channel.send("error", new JSONObject().fluentPut("message",
                        prompts.get(lang, "coach.error.emptyAnswer")));
            } else {
                channel.send("done", new JSONObject().fluentPut("answer", answer.toString()));
            }
            channel.complete();
        } catch (Exception e) {
            log.error("[ReplayCoach] 调用失败 mode={}", request.mode(), e);
            if (!channel.isClosed()) {
                // 正常收尾而非 completeWithError：原因已随 error 事件发出，抛回 MVC 只会往 event-stream 里塞 JSON 盖掉真因
                channel.send("error", new JSONObject().fluentPut("message",
                        LlmErrorMessages.classify(e, prompts, lang)));
                channel.complete();
            }
        } finally {
            heartbeat.cancel(false);
        }
    }

    private static String textOf(ChatResponse frame) {
        if (frame == null || frame.getResult() == null) {
            return null;
        }
        return frame.getResult().getOutput().getText();
    }
}
