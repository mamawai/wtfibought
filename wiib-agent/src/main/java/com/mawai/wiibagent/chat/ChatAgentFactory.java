package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.constant.AiProtocols;
import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.gate.ApprovalGate;
import com.mawai.wiibagent.chat.gate.ApprovalRegistry;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.chat.run.ChildRunRegistry;
import com.mawai.wiibagent.chat.run.WorkerKind;
import com.mawai.wiibagent.chat.tools.BehaviorToolkit;
import com.mawai.wiibagent.chat.tools.DeepAnalysisToolkit;
import com.mawai.wiibagent.chat.tools.DelegateToolkit;
import com.mawai.wiibagent.chat.tools.TraderActionToolkit;
import com.mawai.wiibagent.chat.tools.TraderQueryToolkit;
import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibagent.analysis.DeepAnalysisService;
import com.mawai.wiibagent.behavior.BehaviorAnalysisService;
import com.mawai.wiibagent.llm.LlmClient;
import com.mawai.wiibagent.llm.ModelCallLimiter;
import com.mawai.wiibagent.llm.ReactLoop;
import com.mawai.wiibagent.llm.UsageTrackingChatModel;
import com.mawai.wiibagent.toolkit.IndicatorToolkit;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibagent.toolkit.NewsToolkit;
import com.mawai.wiibagent.trader.TraderChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对话链路的叶子工厂：按用户的 BYOK 配置建出主 agent（一个 {@link ReactLoop}，全部对话工具挂在它身上），
 * 以及主 agent 派后台子 agent 时按预设现建的 worker（{@link #newWorker}）。
 * <p>
 * <b>这里只管"造"，不管"怎么用"</b>：一轮怎么跑、历史怎么存，在 {@link ChatTurnRunner}。
 * <p>
 * 模型是建叶子时绑死的（工具方法体里拿不到用户身份），所以叶子按配置指纹缓存，见 {@link #leavesFor}。
 * <p>
 * 语言与模型一样是建叶子时烤死的：系统提示词与工具描述按 {@link AgentLang} 取好烤进叶子里，
 * 所以语言也在缓存键里，见 {@link #leafKey}。
 */
@Slf4j
@Component
public class ChatAgentFactory {

    /**
     * 一份用户配置对应的叶子。
     *
     * @param modelLabel 这份配置的对外名字（端点名 · 模型名），随每条答案落库给前端显示
     * @param deep       深模型（主 agent 作答、整理上下文都走它）。透出来是为了取用量——它本身就是本轮的账本；
     *                   深研判子运行跨轮活着，另记账，见 {@link DeepAnalysisToolkit}
     * @param main       主 agent：深模型 + 全部对话工具的 ReactLoop，流式作答
     * @param mainClient 主 agent 调模型的客户端。整理上下文用它发：系统提示词、工具、思考配置与平时一模一样，
     *                   请求前缀和上一轮相同，整段历史命中提示缓存（见 {@link ContextCompactor}）
     * @param lang       这套叶子按哪门语言建的。它在缓存键里（见 {@link #leafKey}），拿到的叶子
     *                   必与请求语言一致；带出来供编排层（过程文案）直接用
     * @param contextWindow 主模型端点配的上下文窗口（token），也在缓存键里
     */
    public record Leaves(String modelLabel, UsageTrackingChatModel deep, ReactLoop main, LlmClient mainClient,
                         AgentLang lang, int contextWindow) {
    }

    /** 一个现建的 worker：循环 + 它独享的账本（子运行的用量不混进主 agent 那一轮的账） */
    public record Worker(ReactLoop loop, UsageTrackingChatModel usage) {
    }

    private final ChatModelFactory chatModelFactory;
    private final MarketToolkit marketToolkit;
    private final IndicatorToolkit indicatorToolkit;
    private final NewsToolkit newsToolkit;
    private final DeepAnalysisService deepAnalysisService;
    /** 行为分析的准入层（缓存/负缓存/并发闸门都在它那儿），工具只经它跑 */
    private final BehaviorAnalysisService behaviorAnalysisService;
    /** 对话轨读写 trader 的唯一入口；两条 agent 链路只经它与 DB 打交道，从不互相对话 */
    private final TraderChatService traderChatService;
    private final WorkbenchRunRegistry runRegistry;
    /** 后台子运行（深研判、delegate）从这里派，报告经它回灌会话 */
    private final ChildRunRegistry childRuns;
    private final ApprovalRegistry approvalRegistry;
    private final PromptCatalog prompts;
    private final LocalizedToolCallbacks localizedTools;
    /** 主 agent 与每个 worker 各自一轮 ReAct 的模型调用上限：到数即收尾，防死转烧钱 */
    private final int runModelCallLimit;
    /** 补充源在回答里的标签，如 [X]；源名可配，见构造参数 supplementSource 的说明 */
    private final String supplementTag;
    /** 两边都有的事件合并后的标签，如 [BlockBeats+X] */
    private final String mergedTag;

    /**
     * 叶子缓存上限：与 {@link ChatModelFactory#MAX_ENTRIES} 同口径（一个配置指纹一份），
     * 实际就是"能同时缓存几个活跃用户的叶子"。超了 LRU 抖动，被淘汰的人下次发消息重建。
     * 对话已对全体用户开放，32 是拍的数，调它看的是轮流来聊的人数——理由详见 MAX_ENTRIES。
     */
    private static final int MAX_LEAVES = 32;

    /** 按配置指纹缓存的叶子。LRU 与并发口径同 {@link ChatModelFactory#modelsFor}，建叶子不在锁里做 */
    private final Map<String, Leaves> cache =
            Collections.synchronizedMap(
                    new LinkedHashMap<>(16, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, Leaves> eldest) {
                            return size() > MAX_LEAVES;
                        }
                    });

    /**
     * @param supplementSource 补充源名。BlockBeats 之外那一路是主 agent 经服务端 web_search 工具搜的
     *                         （端点勾选开启，见 {@link #build} 的 webSearch），搜到什么平台随上游走。
     *                         所以提示词里一律只说"联网搜索"不点名平台，只有输出标签用这个名字——
     *                         换源改配置一处，提示词不用动；留空标 [Web]
     */
    public ChatAgentFactory(ChatModelFactory chatModelFactory,
                            MarketToolkit marketToolkit,
                            IndicatorToolkit indicatorToolkit,
                            NewsToolkit newsToolkit,
                            DeepAnalysisService deepAnalysisService,
                            BehaviorAnalysisService behaviorAnalysisService,
                            TraderChatService traderChatService,
                            WorkbenchRunRegistry runRegistry,
                            ChildRunRegistry childRuns,
                            ApprovalRegistry approvalRegistry,
                            PromptCatalog prompts,
                            LocalizedToolCallbacks localizedTools,
                            @Value("${agent.workbench.run-model-call-limit:8}") int runModelCallLimit,
                            @Value("${agent.workbench.news-supplement-source:}") String supplementSource) {
        this.chatModelFactory = chatModelFactory;
        this.marketToolkit = marketToolkit;
        this.indicatorToolkit = indicatorToolkit;
        this.newsToolkit = newsToolkit;
        this.deepAnalysisService = deepAnalysisService;
        this.behaviorAnalysisService = behaviorAnalysisService;
        this.traderChatService = traderChatService;
        this.runRegistry = runRegistry;
        this.childRuns = childRuns;
        this.approvalRegistry = approvalRegistry;
        this.prompts = prompts;
        this.localizedTools = localizedTools;
        this.runModelCallLimit = runModelCallLimit;
        // 不配就用中性的 Web：标签总得有个名字，但代码里不该替某个平台站队
        String source = supplementSource == null || supplementSource.isBlank() ? "Web" : supplementSource.trim();
        this.supplementTag = "[" + source + "]";
        this.mergedTag = "[BlockBeats+" + source + "]";
    }

    /**
     * 取这份配置的叶子，按指纹缓存：配置一变指纹就变、自然拿到新叶子，不需要任何显式失效。
     * <p>
     * <b>为什么要缓存</b>：建一套叶子要反射扫工具类、装配 LlmClient，
     * 几十到几百毫秒；这条路在请求线程上，每请求重建等于每句话先卡半秒。
     * <p>
     * <b>先查后建，不用 computeIfAbsent</b>：它会在整个 mapping 函数执行期间攥着互斥锁，
     * 于是任何一个用户首次建叶子期间，<b>其余所有用户的 /chat 请求全堵在这把锁上</b>。
     */
    public Leaves leavesFor(ChatEndpoints eps, AgentLang lang) {
        String fp = leafKey(eps, lang);
        Leaves hit = cache.get(fp);
        if (hit != null) {
            return hit;
        }
        // 锁外建好叶子
        Leaves built = build(eps, lang);
        // 并发下可能有人先放好了，用先到的那份：叶子无会话状态，多建一份只是一次 GC
        Leaves prev = cache.putIfAbsent(fp, built);
        if (prev != null) {
            return prev;
        }
        log.info("对话工作台叶子已构建 model={} lang={} 缓存数={}", eps.deep().getModel(), lang.code(), cache.size());
        return built;
    }

    /**
     * 叶子的缓存键 = 模型配置指纹 + 语言 + 上下文窗口。语言变则整套系统提示词与工具描述变，叶子须重建；
     * 窗口烤在叶子里，改了也得换一套。
     * <p>
     * 这两样只加在这一层，不进 {@link ChatModelFactory#fingerprint}：模型实例与它们无关，
     * 切语言、改窗口都不重建 SDK 客户端与连接池。
     */
    static String leafKey(ChatEndpoints eps, AgentLang lang) {
        // 指纹是十六进制串，用冒号接语言码与窗口不会与它的任何取值相撞
        return ChatModelFactory.fingerprint(eps) + ':' + lang.code() + ':' + eps.deep().getContextWindow();
    }

    /** 端点声明了服务端搜索：协议支持且用户勾了 */
    private static boolean serverSearch(UserLlmEndpoint endpoint) {
        return AiProtocols.supportsServerSearch(endpoint.getApiProtocol()) && Boolean.TRUE.equals(endpoint.getWebSearch());
    }

    private Leaves build(ChatEndpoints eps, AgentLang lang) {
        ChatModelFactory.Models models = chatModelFactory.modelsFor(eps);
        // 用量装饰器进行包装
        UsageTrackingChatModel deep = new UsageTrackingChatModel(models.deep());
        // worker 跑在浅模型上：它的搜索许可看浅模型那条端点
        boolean lightSearch = serverSearch(eps.lightOrDeep());
        LlmClient client = mainClient(deep, models.deep(), eps.userId(), lang, eps.deep(),
                kind -> newWorker(kind, lang, lightSearch, models.light()));
        return new Leaves(modelLabel(eps.deep()), deep, mainLoop(client, lang), client, lang,
                eps.deep().getContextWindow());
    }

    /**
     * 主 agent 系统提示词按端点搜索能力拼装：新闻条款二选一（{@code chat.newsRule.search} 承诺联网补充 /
     * {@code chat.newsRule.noSearch} 如实说没有检索能力），其余原则两版共有。
     * 提示词不许承诺端点给不了的能力——文案跟着能力走，能力跟着端点走。
     */
    // 包私有非 private：ChatAgentFactoryTest 要直接断言两版文案
    String mainInstruction(AgentLang lang, boolean webSearch) {
        String newsRule = prompts.get(lang, webSearch ? "chat.newsRule.search" : "chat.newsRule.noSearch",
                Map.of("supplementTag", supplementTag, "mergedTag", mergedTag));
        return prompts.get(lang, "chat.main", Map.of("newsRule", newsRule));
    }

    /** 端点名 · 模型名：站内展示模型的统一口径（见 LlmEndpointSelect / ReplayPanel）；没起名就只报模型 */
    private static String modelLabel(UserLlmEndpoint endpoint) {
        String name = endpoint.getName();
        return name == null || name.isBlank() ? endpoint.getModel() : name + " · " + endpoint.getModel();
    }

    /**
     * 主 agent 调模型的客户端：深模型 + 全部对话工具，自己取数自己作答，长活派子运行。
     *
     * @param deepBase 裸深模型，给深研判子运行：它每次自己包账本，不走主轮那本
     * @param userId   trader 工具（只读与动作）、行为分析、子运行烤死的归属，理由见 {@link TraderQueryToolkit}
     * @param endpoint 主模型端点：声明了服务端搜索就用承诺联网的那版提示词、每次调用捎搜索许可；端点身份进前缀指纹
     * @param workers  delegate 按预设现建 worker 的办法
     */
    private LlmClient mainClient(ChatModel deep, ChatModel deepBase, long userId, AgentLang lang,
                                 UserLlmEndpoint endpoint, java.util.function.Function<WorkerKind, Worker> workers) {
        boolean webSearch = serverSearch(endpoint);
        // 工具的模型在这一层绑死："当前用的是谁的 key"只有这里知道
        return LlmClient.builder()
                .model(deep)
                .systemPrompt(mainInstruction(lang, webSearch))
                .tools(localizedTools.of(lang,
                        // 行情 ×4 / 新闻 / 深研判 / trader 只读 ×5 / trader 动作表单 ×3 / 行为分析 / 派子 agent
                        marketToolkit,
                        newsToolkit.boundTo(lang),
                        new DeepAnalysisToolkit(deepBase, deepAnalysisService, childRuns, prompts, userId, lang),
                        new TraderQueryToolkit(traderChatService, userId, lang),
                        new TraderActionToolkit(runRegistry, userId, prompts, lang),
                        new BehaviorToolkit(deep, behaviorAnalysisService, runRegistry, userId, lang),
                        new DelegateToolkit(childRuns, workers, prompts, userId, lang)))
                .webSearch(webSearch)
                .origin(String.join("\0", endpoint.getApiProtocol(), endpoint.getBaseUrl(), endpoint.getModel(),
                        endpoint.getApiKeyEnc()))
                .maxAttempts(3).initialDelay(500).maxDelay(4000)
                .build();
    }

    /** 主 agent：流式作答，挂保险丝与 HITL 闸门 */
    private ReactLoop mainLoop(LlmClient client, AgentLang lang) {
        return ReactLoop.builder().client(client)
                .streaming(true)    // 答案要逐字推给前端
                .limiter(new ModelCallLimiter(runModelCallLimit,
                        prompts.get(lang, "llm.callLimit.notExecuted"),
                        prompts.get(lang, "llm.callLimit.lastCall")))
                .gate(new ApprovalGate(approvalRegistry, prompts, lang))
                .build();
    }

    /**
     * 按预设现建一个 worker：浅模型 + 预设的只读工具集，独享账本，没有闸门。
     * 每次派发现建而不是建叶子时缓存：账本要独享，循环又是把模型绑死的，只能连循环一起新建。
     *
     * @param lightSearch researcher 的服务端搜索许可：浅模型端点声明了才给
     */
    // 包私有非 private：ChatAgentFactoryTest 要直接验两个预设的工具表与许可
    Worker newWorker(WorkerKind kind, AgentLang lang, boolean lightSearch, ChatModel lightBase) {
        UsageTrackingChatModel model = new UsageTrackingChatModel(lightBase);
        LlmClient.Builder client = LlmClient.builder()
                .model(model)
                .systemPrompt(prompts.get(lang, kind.promptKey()))
                .maxAttempts(3).initialDelay(500).maxDelay(4000);
        switch (kind) {
            case RESEARCHER -> client.tools(localizedTools.of(lang, newsToolkit.boundTo(lang))).webSearch(lightSearch);
            case ANALYST -> client.tools(localizedTools.of(lang, marketToolkit, indicatorToolkit));
        }
        ReactLoop loop = ReactLoop.builder().client(client.build())
                .limiter(new ModelCallLimiter(runModelCallLimit,
                        prompts.get(lang, "llm.callLimit.notExecuted"),
                        prompts.get(lang, "llm.callLimit.lastCall")))
                .build();
        return new Worker(loop, model);
    }
}
