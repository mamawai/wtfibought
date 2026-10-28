package com.mawai.wiibagent.promptgen;

import com.mawai.wiibagent.chat.ChatModelFactory;
import com.mawai.wiibagent.chat.tools.TraderQueryToolkit;
import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibagent.llm.LlmClient;
import com.mawai.wiibagent.llm.LlmEndpointService;
import com.mawai.wiibagent.llm.LlmErrorMessages;
import com.mawai.wiibagent.llm.ModelCallLimiter;
import com.mawai.wiibagent.llm.ReactLoop;
import com.mawai.wiibagent.llm.SseChannel;
import com.mawai.wiibagent.trader.TraderChatService;
import com.mawai.wiibagent.trader.TraderService;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.PromptGenSession;
import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.mawai.wiibagent.promptgen.PromptGenToolkit.ASK_USER;
import static com.mawai.wiibagent.promptgen.PromptGenToolkit.WRITE_DRAFT;
import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 交易指令生成器：agent 读 trader 数据、一题一题问用户、信息够了让写稿人出一版，用户提修改再接着问。
 * <pre>
 * /start、/reply 各是一轮：读对话 → 加一条用户消息 → 现建 agent 流式跑 → 对话整份覆盖落库 → done
 *   ask_user   推题卡，这一轮就收工（ReactLoop.stopWhen）
 *   write_draft 写稿人流式写正文，回执进历史时连快照存成一版（只留最近 3 版）
 * </pre>
 * 不开对话压缩：对话本来不长，压了版本快照就对不上。
 * <p>
 * 对话连同 agent 系统提示词与工具的指纹一起存：每轮开跑前比一下，变了就把起跑历史里的原始内容按文本重建——
 * Anthropic 的思考块和这段前缀绑死，照旧回放强制校验的组织会 400。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptGenService {

    /** 单轮模型调用保险丝：正常一轮 2~4 次 */
    static final int RUN_CALL_LIMIT = 12;

    /** 模型思考期间通道可能静默较久，与工作台同值 */
    private static final long HEARTBEAT_SECONDS = 20;

    private final PromptGenStore store;
    private final PromptGenFacts facts;
    private final LlmEndpointService endpointService;
    private final ChatModelFactory chatModelFactory;
    private final TraderService traderService;
    private final TraderChatService traderChatService;
    private final PlayStatsAssembler playStats;
    private final LocalizedToolCallbacks localizedTools;
    private final PromptCatalog prompts;
    private final UserLangResolver userLangResolver;
    /** 心跳专用：只发注释帧，单线程够用；虚拟线程不支持定时调度故用平台线程 */
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "prompt-gen-heartbeat");
        t.setDaemon(true);
        return t;
    });
    /** 有一轮还没跑完落库的用户 → 这一轮的停止信号 */
    private final Map<Long, CompletableFuture<Void>> running = new ConcurrentHashMap<>();
    /** 上一轮失败的原因，/session 带回去；下一轮开跑或回到某一版时清掉 */
    private final Map<Long, String> failures = new ConcurrentHashMap<>();

    @PreDestroy
    public void stop() {
        heartbeatScheduler.shutdownNow();
    }

    public PromptGenSession session(long userId) {
        return store.session(userId);
    }

    /** 建模放在开流之前：端点不对要回 JSON 错误，不能等进了 SSE 再说 */
    public ChatModel model(long userId, long endpointId) {
        UserLlmEndpoint endpoint = endpointService.get(userId, endpointId);
        if (endpoint == null) {
            throw new BizException(ErrorCode.LLM_CONFIG_MISSING);
        }
        try {
            return chatModelFactory.modelsFor(new ChatEndpoints(userId, endpoint, null)).deep();
        } catch (Exception e) {
            log.warn("[PromptGen] 建模失败 userId={}", userId, e);
            throw new BizException(ErrorCode.LLM_CONFIG_INVALID);
        }
    }

    public PromptGenView view(long userId) {
        // running 最先读：读到 false 时这一轮的版本、对话、失败原因都已经落定
        boolean busy = running.containsKey(userId);
        String failure = failures.get(userId);
        List<PromptGenView.Version> versions = store.versions(userId).stream().map(PromptGenView.Version::of).toList();
        PromptGenSession session = store.session(userId);
        if (session == null) {
            return PromptGenView.empty(busy, failure, versions);
        }
        List<PromptGenHistory.Step> steps = PromptGenHistory.steps(PromptGenStore.messages(session.getState()));
        return PromptGenView.of(session, steps, required(userId, steps), busy, failure, versions);
    }

    private Set<String> required(long userId, List<PromptGenHistory.Step> steps) {
        return PromptGenFacts.required(traderService.mine(userId), PromptGenHistory.lastDraft(steps) != null);
    }

    /** 叫停这个用户在跑的那一轮；没在跑返回 false */
    public boolean cancel(long userId) {
        CompletableFuture<Void> cancel = running.get(userId);
        if (cancel == null) {
            return false;
        }
        cancel.complete(null);
        return true;
    }

    /** 用第 no 版的快照替换当前对话；版本列表不动 */
    public PromptGenView rollback(long userId, int no) {
        if (store.session(userId) == null) {
            throw new BizException(ErrorCode.PROMPT_GEN_NO_SESSION);
        }
        if (!store.restore(userId, no)) {
            throw new BizException(ErrorCode.PROMPT_GEN_VERSION_NOT_FOUND);
        }
        failures.remove(userId);
        return view(userId);
    }

    /** 开新对话（旧对话整个覆盖，版本保留）并跑第一轮 */
    public void start(SseChannel channel, long userId, ChatModel model, long endpointId, AgentLang outputLang) {
        List<Message> history = List.of(new UserMessage(prompts.get(userLangResolver.of(userId), "promptgen.kickoff")));
        round(channel, userId, model, outputLang, history, null,
                () -> store.open(userId, endpointId, outputLang, history));
    }

    /** 这一轮的起跑历史：当前对话 + 用户这句（回答、跳过、或直接说要改哪里）；还没答的必问项不让跳 */
    public List<Message> replyHistory(long userId, PromptGenSession session, String text, boolean skip) {
        List<Message> history = new ArrayList<>(PromptGenStore.messages(session.getState()));
        if (skip) {
            List<PromptGenHistory.Step> steps = PromptGenHistory.steps(history);
            PromptGenHistory.Ask pending = PromptGenHistory.pending(steps);
            if (pending != null && PromptGenHistory.missing(steps, required(userId, steps)).contains(pending.key())) {
                throw new BizException(ErrorCode.PARAM_ERROR);
            }
        }
        history.add(new UserMessage(skip ? prompts.get(userLangResolver.of(userId), "promptgen.skipped") : text));
        return history;
    }

    public void reply(SseChannel channel, long userId, ChatModel model, PromptGenSession session,
                      List<Message> history) {
        String prefix = PromptGenStore.prefix(session.getState());
        // 用户这句先落库：这一轮在后台跑时刷新进来也看得到
        round(channel, userId, model, AgentLang.of(session.getOutputLang()), history, prefix,
                () -> store.saveState(userId, history, prefix));
    }

    /**
     * @param prefix 起跑历史是在哪套系统提示词与工具下跑出来的，新对话为 null
     * @param open   起跑历史落库；在标上 running 之后做，/session 读到新对话时一定也读到 running
     */
    private void round(SseChannel channel, long userId, ChatModel model, AgentLang outputLang,
                       List<Message> history, String prefix, Runnable open) {
        AgentLang lang = userLangResolver.of(userId);
        PromptGenRun run = null;
        String failure = null;
        CompletableFuture<Void> cancel = new CompletableFuture<>();
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleWithFixedDelay(
                channel::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        running.put(userId, cancel);
        failures.remove(userId);
        try {
            open.run();
            AiTrader trader = traderService.mine(userId);
            boolean drafted = PromptGenHistory.lastDraft(PromptGenHistory.steps(history)) != null;
            PromptGenFacts.Prompts system = facts.prompts(trader, drafted, lang, outputLang);
            run = new PromptGenRun(userId, lang, outputLang, trader, history, channel::send, cancel, store);
            LlmClient writer = LlmClient.builder()
                    .model(model)
                    .systemPrompt(system.writer())
                    .maxAttempts(3).initialDelay(500).maxDelay(4000)
                    .build();
            LlmClient agent = LlmClient.builder()
                    .model(model)
                    .systemPrompt(system.agent())
                    .tools(localizedTools.of(lang,
                            new TraderQueryToolkit(traderChatService, userId, lang),
                            new PromptGenToolkit(run, writer, playStats, prompts)))
                    .maxAttempts(3).initialDelay(500).maxDelay(4000)
                    .build();
            ReactLoop loop = ReactLoop.builder().client(agent)
                    .streaming(true)
                    .limiter(new ModelCallLimiter(RUN_CALL_LIMIT,
                            prompts.get(lang, "llm.callLimit.notExecuted"),
                            prompts.get(lang, "llm.callLimit.lastCall")))
                    // 同一批里推题卡和写稿别同时跑
                    .serialTools(Set.of(ASK_USER, WRITE_DRAFT))
                    // 真推出了题卡才收工；被拒的提问让 agent 接着按回执办
                    .stopWhen(run::asked)
                    .build();
            // 系统提示词或工具跟上一轮不一样（写出了第一稿、改了 trader、改了提示词）：以前的思考块对不上了，按文本重建
            if (!agent.fingerprint().equals(prefix)) {
                run.plainHistory();
                prefix = agent.fingerprint();
            }

            run.status("thinking");
            loop.run(List.copyOf(run.messages()), null, cancel, run);
            store.saveState(userId, PromptGenHistory.trimDangling(run.messages()), prefix);
        } catch (Exception e) {
            log.error("[PromptGen] 这一轮失败 userId={}", userId, e);
            failure = LlmErrorMessages.classify(e, prompts, lang);
            failures.put(userId, failure);
            // 跑起来之后说的、读的照样落库；没跑起来就只有 open 落的起跑历史（没落成的话旧对话原样留着）
            if (run != null) {
                store.saveState(userId, PromptGenHistory.trimDangling(run.messages()), prefix);
            }
        } finally {
            running.remove(userId);
            heartbeat.cancel(false);
        }
        // 落库、摘掉 running 之后再通知：页面收到就去拉 /session
        if (channel.isClosed()) {
            return;
        }
        if (failure == null) {
            channel.send("done", MAPPER.createObjectNode());
        } else {
            channel.send("error", MAPPER.createObjectNode().put("message", failure));
        }
        channel.complete();
    }
}
