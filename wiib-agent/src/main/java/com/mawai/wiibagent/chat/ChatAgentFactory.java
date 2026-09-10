package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.constant.AiProtocols;
import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibagent.analysis.DeepAnalysisService;
import com.mawai.wiibagent.behavior.BehaviorAnalysisService;
import com.mawai.wiibagent.llm.ConversationSummarizer;
import com.mawai.wiibagent.llm.ModelCallLimiter;
import com.mawai.wiibagent.llm.ReactLoop;
import com.mawai.wiibagent.llm.ResilientChatService;
import com.mawai.wiibagent.llm.UsageTrackingChatModel;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibagent.toolkit.NewsToolkit;
import com.mawai.wiibagent.trader.TraderChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Supplier;

/**
 * 对话链路的叶子 agent 工厂：按用户的 BYOK 配置建出三个专家（market/news/trader）和一个汇总 agent，
 * 每个都是一个 {@link ReactLoop}。
 * <p>
 * <b>这里只管"造"，不管"怎么用"</b>：派谁、派几轮、结果怎么拼、历史怎么存，全在
 * {@link ChatTurnRunner} 的平铺 Java 循环里。
 * <p>
 * 模型是建叶子时绑死的（工具方法体里拿不到用户身份），所以叶子按配置指纹缓存，见 {@link #leavesFor}。
 * <p>
 * 语言与模型一样是建叶子时烤死的：系统提示词与工具描述按 {@link AgentLang} 取好烤进叶子里，
 * 所以语言也在缓存键里，见 {@link #leafKey}。
 */
@Slf4j
@Component
public class ChatAgentFactory {

    public static final String MARKET_AGENT = "market_agent";
    public static final String NEWS_AGENT = "news_agent";
    public static final String TRADER_AGENT = "trader_agent";
    public static final Set<String> EXPERT_AGENTS = Set.of(MARKET_AGENT, NEWS_AGENT, TRADER_AGENT);

    /**
     * 一个专家叶子。
     *
     * @param preload 可空。非空则每次执行前先把数据取好、随消息喂进去——无参工具（news_search）
     *                挂成 function tool 的话模型未必调，预取才 100% 保证数据到位
     */
    public record Expert(ReactLoop loop, Supplier<String> preload) {
    }

    /**
     * 一份用户配置对应的全套叶子。
     *
     * @param modelLabel 这份配置的对外名字（端点名 · 模型名），随每条答案落库给前端显示
     * @param deep       深模型（summarizer 作答与深研判走它）。透出来是为了取用量——它本身就是本轮的账本
     * @param light      浅模型本体：{@link ChatTurnRunner} 的路由问答是一次性的结构化调用，
     *                   没有 ReAct 循环也没有工具执行，用不上包一层 agent
     * @param lang       这套叶子按哪门语言建的。它在缓存键里（见 {@link #leafKey}），拿到的叶子
     *                   必与请求语言一致；带出来供编排层（过程文案、路由工具描述）直接用
     */
    public record Leaves(String modelLabel, UsageTrackingChatModel deep, UsageTrackingChatModel light,
                         Map<String, Expert> experts, ReactLoop summarizer, AgentLang lang) {

        /** 轮开始清零，划出本轮账本的起点 */
        public void resetUsage() {
            deep.reset();
            light.reset();      // 没单独绑轻模型时与 deep 是同一个实例，重复清零无害
        }

        /** 本轮累计：深浅两条端点合账。同一个实例时只能取一遍，否则每笔都算两次 */
        public UsageTrackingChatModel.UsageSnapshot usageSnapshot() {
            return light == deep ? deep.snapshot() : deep.snapshot().merge(light.snapshot());
        }

        /** 账本被中断丢下的在途流写脏了，这一轮的数不能报 */
        public boolean usageUntrusted() {
            return deep.untrusted() || light.untrusted();
        }
    }

    private final ChatModelFactory chatModelFactory;
    private final MarketToolkit marketToolkit;
    private final NewsToolkit newsToolkit;
    private final DeepAnalysisService deepAnalysisService;
    /** 行为分析的准入层（缓存/负缓存/并发闸门都在它那儿），工具只经它跑 */
    private final BehaviorAnalysisService behaviorAnalysisService;
    /** 对话轨读写 trader 的唯一入口；两条 agent 链路只经它与 DB 打交道，从不互相对话 */
    private final TraderChatService traderChatService;
    private final WorkbenchRunRegistry runRegistry;
    private final ApprovalRegistry approvalRegistry;
    private final PromptCatalog prompts;
    private final LocalizedToolCallbacks localizedTools;
    /**
     * 这一个配置项管的是<b>每个 agent 各自的上限</b>而不是整轮总量：summarizer 与每个带工具的专家
     * 各跑各的循环，计数互不相通。所以一轮对话的模型调用是各家相加（summarizer ≤8、
     * 每个带工具的专家各 ≤8，再加上路由每轮一次），不是 8 次封顶。
     * 想收总量得另立机制，不是把这个数调小。
     */
    private final int runModelCallLimit;
    private final int summarizeThresholdTokens;
    private final int summarizeKeepMessages;
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
     * @param supplementSource 补充源名。BlockBeats 之外那一路是 summarizer 经服务端 web_search 工具搜的
     *                         （端点勾选开启，见 {@link #build} 的 webSearch），搜到什么平台随上游走。
     *                         所以提示词里一律只说"联网搜索"不点名平台，只有输出标签用这个名字——
     *                         换源改配置一处，提示词不用动；留空标 [Web]
     */
    public ChatAgentFactory(ChatModelFactory chatModelFactory,
                            MarketToolkit marketToolkit,
                            NewsToolkit newsToolkit,
                            DeepAnalysisService deepAnalysisService,
                            BehaviorAnalysisService behaviorAnalysisService,
                            TraderChatService traderChatService,
                            WorkbenchRunRegistry runRegistry,
                            ApprovalRegistry approvalRegistry,
                            PromptCatalog prompts,
                            LocalizedToolCallbacks localizedTools,
                            @Value("${agent.workbench.run-model-call-limit:8}") int runModelCallLimit,
                            @Value("${agent.workbench.summarize-threshold-tokens:32000}") int summarizeThresholdTokens,
                            @Value("${agent.workbench.summarize-keep-messages:6}") int summarizeKeepMessages,
                            @Value("${agent.workbench.news-supplement-source:}") String supplementSource) {
        this.chatModelFactory = chatModelFactory;
        this.marketToolkit = marketToolkit;
        this.newsToolkit = newsToolkit;
        this.deepAnalysisService = deepAnalysisService;
        this.behaviorAnalysisService = behaviorAnalysisService;
        this.traderChatService = traderChatService;
        this.runRegistry = runRegistry;
        this.approvalRegistry = approvalRegistry;
        this.prompts = prompts;
        this.localizedTools = localizedTools;
        this.runModelCallLimit = runModelCallLimit;
        this.summarizeThresholdTokens = summarizeThresholdTokens;
        this.summarizeKeepMessages = summarizeKeepMessages;
        // 不配就用中性的 Web：标签总得有个名字，但代码里不该替某个平台站队
        String source = supplementSource == null || supplementSource.isBlank() ? "Web" : supplementSource.trim();
        this.supplementTag = "[" + source + "]";
        this.mergedTag = "[BlockBeats+" + source + "]";
    }

    /**
     * 取这份配置的叶子，按指纹缓存：配置一变指纹就变、自然拿到新叶子，不需要任何显式失效。
     * <p>
     * <b>为什么要缓存</b>：建一套叶子要反射扫工具类、装配 ChatService，
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
     * 叶子的缓存键 = 模型配置指纹 + 语言。语言变则整套系统提示词与工具描述变，叶子须重建。
     * <p>
     * 语言只加在这一层，不进 {@link ChatModelFactory#fingerprint}：模型实例与语言无关，
     * 切语言不重建 SDK 客户端与连接池。
     */
    static String leafKey(ChatEndpoints eps, AgentLang lang) {
        // 指纹是十六进制串，用冒号接语言码不会与它的任何取值相撞
        return ChatModelFactory.fingerprint(eps) + ':' + lang.code();
    }

    private Leaves build(ChatEndpoints eps, AgentLang lang) {
        ChatModelFactory.Models models = chatModelFactory.modelsFor(eps);
        // 是否允许网络搜索
        boolean webSearch = AiProtocols.supportsServerSearch(eps.deep().getApiProtocol())
                && Boolean.TRUE.equals(eps.deep().getWebSearch());
        // 用量装饰器进行包装
        UsageTrackingChatModel deep = new UsageTrackingChatModel(models.deep());
        // 没单独绑轻模型时工厂给的是同一个实例，装饰器也得共用一个，否则同一次调用记两遍账
        UsageTrackingChatModel light = models.light() == models.deep() ? deep : new UsageTrackingChatModel(models.light());
        // LinkedHashMap 保序：派发顺序、结论拼进历史的顺序都跟着它，market 在前 news 在后
        Map<String, Expert> experts = new LinkedHashMap<>();
        // market 的工具要按问题选 symbol，只能交给模型现取，所以没有 preload
        experts.put(MARKET_AGENT, new Expert(expertLoop(lang, light, marketToolkit, "required",
                prompts.get(lang, "chat.expert.market")), null));
        // 新闻只预取 BlockBeats（news_search 入参语言，不挂 tool），这里不进行联网搜索
        experts.put(NEWS_AGENT, new Expert(expertLoop(lang, light, null, null,
                prompts.get(lang, "chat.expert.news")), () -> newsToolkit.newsSearch(lang)));
        // trader 专家只读这个用户自己的 trader
        experts.put(TRADER_AGENT, new Expert(expertLoop(lang, light,
                new TraderQueryToolkit(traderChatService, eps.userId(), lang), "required",
                prompts.get(lang, "chat.expert.trader")), null));

        return new Leaves(modelLabel(eps.deep()), deep, light, experts,
                summarizerLeaf(deep, light, eps.userId(), lang, webSearch), lang);
    }

    /**
     * summarizer 系统提示词按端点搜索能力拼装：新闻条款二选一（{@code chat.newsRule.search} 承诺联网补充 /
     * {@code chat.newsRule.noSearch} 如实说没有检索能力），其余原则两版共有。
     * 提示词不许承诺端点给不了的能力——文案跟着能力走，能力跟着端点走。
     */
    // 包私有非 private：ChatAgentFactoryTest 要直接断言两版文案
    String summarizerInstruction(AgentLang lang, boolean webSearch) {
        String newsRule = prompts.get(lang, webSearch ? "chat.newsRule.search" : "chat.newsRule.noSearch",
                Map.of("supplementTag", supplementTag, "mergedTag", mergedTag));
        return prompts.get(lang, "chat.summarizer", Map.of("newsRule", newsRule));
    }

    /** 端点名 · 模型名：站内展示模型的统一口径（见 LlmEndpointSelect / ReplayPanel）；没起名就只报模型 */
    private static String modelLabel(UserLlmEndpoint endpoint) {
        String name = endpoint.getName();
        return name == null || name.isBlank() ? endpoint.getModel() : name + " · " + endpoint.getModel();
    }

    /**
     * 专家 agent：浅模型 + 自己那套工具的 ReAct 循环。
     * <p>
     * 包私有而非 private：ExpertCallLimitTest 要直接调它真跑一遍，才验得到"生产代码里保险丝挂没挂"。
     *
     * @param toolkit              可空。null=纯预取/纯模型能力的专家（如 news），不挂任何 function tool
     * @param forceFirstToolChoice "required"=首轮强制调工具（工具带参数、数据必须模型现取的专家）；
     *                             null=不强制
     */
    ReactLoop expertLoop(AgentLang lang, ChatModel model, Object toolkit,
                         String forceFirstToolChoice, String instruction) {
        ResilientChatService chat = ResilientChatService.builder()
                .model(model)
                .systemPrompt(instruction)
                // 按照 lang 设置 tool 的 description 语言
                .tools(toolkit == null ? List.of() : localizedTools.of(lang, toolkit))
                .forceFirstToolChoice(forceFirstToolChoice)
                .build();
        // 没工具的纯模型 agent 也挂保险丝：一次就收尾，触发不到
        return ReactLoop.builder().chat(chat)
                .limiter(new ModelCallLimiter(runModelCallLimit,
                        prompts.get(lang, "llm.callLimit.notExecuted"),
                        prompts.get(lang, "llm.callLimit.lastCall")))
                .build();
    }

    /**
     * 汇总 agent：深模型 + 深研判工具，只管把专家数据写成最终回答。
     * <p>
     * 派谁、还要不要再派，全归 {@link ChatTurnRunner} 的显式循环管，这里一个字都不提——
     * 角色单一，模型不会再纠结"该作答还是该派发"（那正是之前无限循环的病根）。
     *
     * @param light  压缩用浅模型：摘要是简单活，用深模型纯烧钱
     * @param userId 动作类工具烤死的归属；查询归专家，动手归汇总者，理由见 {@link TraderActionToolkit}
     * @param webSearch 端点声明了服务端搜索：提示词用承诺联网的那版，且每次调用捎搜索许可。
     *                  许可只在这一个叶子发——专家与 trader 链路的数据源必须可控，物理拿不到搜索
     */
    private ReactLoop summarizerLeaf(ChatModel deep, ChatModel light, long userId, AgentLang lang,
                                     boolean webSearch) {
        // 工具的模型在这一层绑死："当前用的是谁的 key"只有这里知道
        ResilientChatService chat = ResilientChatService.builder()
                .model(deep)
                .systemPrompt(summarizerInstruction(lang, webSearch))
                .tools(localizedTools.of(lang,
                        // 三套工具 研判/trader/行为分析
                        new DeepAnalysisToolkit(deep, deepAnalysisService, runRegistry, prompts, lang),
                        new TraderActionToolkit(runRegistry, userId, prompts, lang),
                        new BehaviorToolkit(deep, behaviorAnalysisService, runRegistry, userId, lang)))
                .webSearch(webSearch)
                .maxAttempts(3).initialDelay(500).maxDelay(4000)
                .build();
        return ReactLoop.builder().chat(chat)
                .streaming(true)    // 答案要逐字推给前端
                .limiter(new ModelCallLimiter(runModelCallLimit,
                        prompts.get(lang, "llm.callLimit.notExecuted"),
                        prompts.get(lang, "llm.callLimit.lastCall")))
                .gate(new ApprovalGate(approvalRegistry, prompts, lang))
                .summarizer(new ConversationSummarizer(light, summarizeThresholdTokens,
                        summarizeKeepMessages, prompts, lang))
                .build();
    }

}
