package com.mawai.wiibagent.replay;

import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.handover.InFlight;
import com.mawai.wiibcommon.handover.Intake;
import com.mawai.wiibcommon.handover.Start;
import com.mawai.wiibagent.chat.ChatModelFactory;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibagent.llm.LlmClient;
import com.mawai.wiibagent.llm.LlmEndpointService;
import com.mawai.wiibagent.llm.LlmErrorMessages;
import com.mawai.wiibagent.llm.SseChannel;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 手动复盘 AI 教练的一次请求：准入 → 交出 emitter → 虚拟线程里跑流式调用。
 * 一次请求 = 一次 BYOK 模型流式调用，SSE 事件协议与研判工作台同款（token/done/error），前端复用同一套解析。
 * <p>
 * 与工作台对话的区别：不进会话历史、不记忆——纯一次性问答，所以不占 ChatConcurrencyGate
 * 的名额；只挡"同一用户上一次还没跑完又点"（{@link ErrorCode#REPLAY_AI_BUSY}）。
 * 断连即停：没有落库诉求，用户切走了就别再烧他的 token。
 * 进活的口是"接新请求"：关着时直接拒，回"服务正在更新"。
 * 在跑的请求是一份手上的活（coach）：提前收尾就是掐断模型调用，给前端发一帧"已中断"再把流收掉。
 */
@Slf4j
@Service
@Order(80)
@RequiredArgsConstructor
public class ReplayCoachService implements Intake, InFlight {

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
    /** 心跳专用：只发注释帧，单线程够所有会话用；虚拟线程不支持定时调度故用平台线程 */
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "replay-coach-heartbeat");
                t.setDaemon(true);
                return t;
            });
    /** 在跑中的用户 → 这次的掐断信号：同一人一次只跑一个 */
    private final Map<Long, CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>();
    /** 接新请求的口 */
    private volatile boolean open;
    /** 提前收尾过了：之后收尾的流都是被掐断的 */
    private volatile boolean cut;

    /** 上下文收尾：停掉心跳与流式线程 */
    @PreDestroy
    public void stop() {
        heartbeatScheduler.shutdownNow();
        streamExecutor.shutdownNow();
    }

    @Override
    public void open(Start how) {
        open = true;
    }

    @Override
    public void close() {
        open = false;
    }

    @Override
    public String name() {
        return "coach";
    }

    @Override
    public int count() {
        return inFlight.size();
    }

    /** 掐断在途的模型调用：流收尾时发"已中断"再完成 */
    @Override
    public void cut() {
        cut = true;
        inFlight.values().forEach(c -> c.complete(null));
    }

    /**
     * 准入 + 起流。
     * 准入检查全部在交出 emitter 之前：一旦返回 event-stream，错误只能推 error 事件，
     * 前端拿不到 2201/2202 去引导配置。
     */
    public SseEmitter start(long userId, ReplayCoachRequest request) {
        AgentLang lang = userLangResolver.of(userId);
        String invalid = coachPrompts.validate(request, lang);
        if (invalid != null) {
            throw new BizException(invalid);
        }
        ChatModel model = modelFor(userId, request);
        // 断开、超时、出错、关停提前收尾：掐断在途的模型调用
        CompletableFuture<Void> cancel = new CompletableFuture<>();
        if (inFlight.putIfAbsent(userId, cancel) != null) {
            throw new BizException(ErrorCode.REPLAY_AI_BUSY);
        }
        // 先登记再看口：关口那边先关口再数在跑的，两边总有一边看得到对方
        if (!open) {
            inFlight.remove(userId);
            throw new BizException(ErrorCode.CHAT_SERVICE_UPDATING);
        }

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        SseChannel channel = new SseChannel(emitter);
        Runnable close = () -> {
            channel.markClosed();
            cancel.complete(null);
        };
        emitter.onCompletion(close);
        emitter.onTimeout(() -> {
            close.run();
            emitter.complete();
        });
        emitter.onError(_ -> close.run());
        try {
            streamExecutor.execute(() -> {
                try {
                    run(channel, cancel, model, request, lang);
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

    /** 端点：复盘配置台点名的那条（endpointId）；没点名/点的已被删 → 用户默认端点；一条都没有 → 2201 */
    private ChatModel modelFor(long userId, ReplayCoachRequest request) {
        UserLlmEndpoint endpoint = request.endpointId() == null ? null : endpointService.get(userId, request.endpointId());
        if (endpoint == null) {
            endpoint = endpointService.defaultOf(userId);
        }
        if (endpoint == null) {
            throw new BizException(ErrorCode.LLM_CONFIG_MISSING);
        }
        try {
            // 复用对话轨的模型缓存（同一条端点对话/复盘共享一个实例），只取深模型那格
            return chatModelFactory.modelsFor(new ChatEndpoints(userId, endpoint, null)).deep();
        } catch (Exception e) {
            log.warn("[ReplayCoach] 建模失败 userId={}", userId, e);
            throw new BizException(ErrorCode.LLM_CONFIG_INVALID);
        }
    }

    /**
     * 一次流式调用的全过程。包私有：单测直接喂 mock 模型看它发出去的事件
     *
     * @param cancel 通道关了就完成，掐断在途的模型调用
     */
    void run(SseChannel channel, CompletableFuture<Void> cancel, ChatModel model, ReplayCoachRequest request,
             AgentLang lang) {
        // 心跳写失败 SseChannel 只标关不通知：写完看到已关，也掐断模型调用
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleWithFixedDelay(() -> {
            channel.heartbeat();
            if (channel.isClosed()) {
                cancel.complete(null);
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        StringBuilder answer = new StringBuilder();
        try {
            // LlmClient 捎系统提示 + 流式重试；不挂工具不进 ReactLoop，落到底就是一次 model.stream
            LlmClient client = LlmClient.builder().model(model)
                    .systemPrompt(coachPrompts.system(request, lang)).build();
            // cancel 完成就掐断上游；写 token 失败只标关，由 takeWhile 在下一帧停下、close 取消订阅
            try (Stream<ChatResponse> frames = client.streamingExecute(
                    List.of(new UserMessage(coachPrompts.user(request, lang))), cancel).toStream()) {
                frames.takeWhile(f -> !channel.isClosed()).forEach(f -> {
                    String chunk = textOf(f);
                    if (chunk == null || chunk.isEmpty()) {
                        return;
                    }
                    answer.append(chunk);
                    channel.send("token", MAPPER.createObjectNode().put("text", chunk));
                });
            }
            if (channel.isClosed()) {
                return;
            }
            if (cut) {
                channel.send("error", MAPPER.createObjectNode().put("message",
                        prompts.get(lang, "coach.error.interrupted")));
            } else if (answer.isEmpty()) {
                channel.send("error", MAPPER.createObjectNode().put("message",
                        prompts.get(lang, "coach.error.emptyAnswer")));
            } else {
                channel.send("done", MAPPER.createObjectNode().put("answer", answer.toString()));
            }
            channel.complete();
        } catch (Exception e) {
            log.error("[ReplayCoach] 调用失败 mode={}", request.mode(), e);
            if (!channel.isClosed()) {
                // 正常收尾而非 completeWithError：原因已随 error 事件发出，抛回 MVC 只会往 event-stream 里塞 JSON 盖掉真因
                channel.send("error", MAPPER.createObjectNode().put("message",
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
