package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mawai.wiibagent.chat.gate.ApprovalGate;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibagent.llm.LlmEndpointService;
import com.mawai.wiibagent.llm.ConversationSummarizer;
import com.mawai.wiibagent.llm.ResilientChatService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真跑验收（非单测）：多轮同会话对话，把历史撑过阈值触发 {@link ConversationSummarizer}。
 * <p>
 * <b>本类独有、单测替代不了的三件事</b>（切点配对那条纯逻辑已由
 * {@code ConversationSummarizerTest} 的真实形状参数化用例必现覆盖，别再把它当本类的卖点）：
 * <ol>
 *   <li><b>压缩在生产装配下真的通电</b>。ConversationSummarizer 从上线起就长期是死代码——
 *       挂上去的那一份压根没被调到，而全绿的单测（mock 掉 ChatModel）当年整个上线周期都没发现这件事</li>
 *   <li><b>压缩结果经 {@link ChatContextStore} 序列化往返后仍被上游接受</b>。压缩换掉的是
 *       整份历史，要落库、下一轮再读出来重放给模型，这条链上任何一环出错单测都看不见</li>
 *   <li><b>多轮同会话累积</b>下压缩的实际节奏：什么时候触发、摘要怎么分段、
 *       后续轮次会不会被压没上下文</li>
 * </ol>
 * 配对断言（{@link #recordPairingViolation}）留着当现场取证——它记录的是真实形状下切点落在哪，
 * 不是回归网：触发条件看形状（回执后恰好还剩 {@code keep-1} 条才切得断），
 * 2026-08-09 实测 4 次完整剧本里至少有 1 次整跑没踩中。
 * <p>
 * <b>"这一跑到底踩没踩中"怎么判，两种情形不一样，别混</b>：
 * <ul>
 *   <li><b>正确版的跑：看压缩日志的条数，这是完备且精确的判据</b>。压缩后条数
 *       {@code M = 2 + (size - cutoff)}，理想切点给 {@code keep+2}，所以
 *       <b>回退步数 = M - keep - 2</b>。M 恒等于 {@code keep+2} = 整跑没踩中（理想切点本来就安全）；
 *       出现 {@code M > keep+2} = 守卫被逼出来过</li>
 *   <li><b>变异版的跑：条数判不了，只能看 {@code pairingViolations}</b>。变异的定义就是永不回退，
 *       M 恒为 {@code keep+2}，条数与形状脱钩</li>
 * </ul>
 * 反过来，{@code pairingViolations} 为空<b>不能</b>用来判踩没踩中——"守卫正确回退了"和
 * "理想切点本来就安全"两种情况下它都是空的。它判的是"有没有违规"，不是"有没有被考到"。
 * <p>
 * <b>为什么不拿"上游报 400"当信号</b>：把 findSafeCutoff 改成直接返回理想切点（跳过配对检查）
 * 真跑过一遍，切点确实切断了配对、落单回执确实被 {@code ResponsesChatModel.buildInput}
 * 转成 function_call_output 送进请求，而<b>上游照收不误</b>，一次 4xx 都没有。
 * 有效边界要记牢：2026-08-09 实测，深模型位全程 {@code grok-4.5}（走 Responses 协议）；
 * OpenAI 自家 Responses 对孤儿 function_call_output 是会 400 的，换模型位这条结论未必成立。
 * <p>
 * BYOK 之后 summarizer 的 fallback 传 null（只有一个端点，切到同端点没意义），所以真报 400
 * 这一跑会当场红，不再像从前那样被兜底无声接盘。但"重试之后勉强成功"仍然只在日志里留一行，
 * 见 {@link #resilienceLogs}。
 * <p>
 * 会话历史里的工具配对只可能来自 summarizer 的 {@code run_deep_analysis}：专家叶子独立跑，
 * 只把 lastMessage 交回主流程，它们自己的配对不进会话历史。所以剧本靠"深度研判"制造配对，
 * 且<b>不授权</b>——{@link ApprovalGate} 未授权时直接短路回 PENDING_APPROVAL，配对照样完整，
 * 却不烧那 3 次深模型。
 * <p>
 * 会真烧 LLM token（5 轮，约十分钟），默认跳过，显式开启才跑。跑法（项目根）：
 * <pre>
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-agent -am -DskipTests=false \
 *   -Dtest=ConversationSummarizerRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@SpringBootTest(properties = {
        // 只验对话链路：策略信号/实盘执行/AI 分析轨全关，测试期间不许背景任务下单写库
        "strategy.runtime.enabled=false",
        "strategy.execution.enabled=false",
        "agent.analysis.enabled=false",
        // 压缩阈值与保留条数用 properties 覆写，不动 application.yml（改 yml 跑完再改回来=污染工作区，
        // 忘了改回来就带上线）。取值理由见 THRESHOLD_TOKENS / KEEP_MESSAGES 的注释
        "agent.workbench.summarize-threshold-tokens=" + ConversationSummarizerRealRunTest.THRESHOLD_TOKENS,
        "agent.workbench.summarize-keep-messages=" + ConversationSummarizerRealRunTest.KEEP_MESSAGES
})
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class ConversationSummarizerRealRunTest {

    private static final org.slf4j.Logger log =
            LoggerFactory.getLogger(ConversationSummarizerRealRunTest.class);

    /**
     * 阈值 2000。生产默认 32000 要聊几十轮才够得着，真跑烧不起；而第一轮新闻问答的回答本身就是
     * 几 KB 中文（估算按 CJK 1 字≈1 token），2000 保证从第二轮起每次模型调用都够得到判定线，
     * 压缩触发与否不再看运气。再往下调没有收益：压缩能不能发生只取决于"消息条数是否多于保留数"，
     * 阈值只是门票。
     */
    static final int THRESHOLD_TOKENS = 2000;
    /**
     * 保留最近 4 条（生产默认 6）。两个原因，第一个是硬的：
     * <ol>
     *   <li><b>验收点 1 要求 M&lt;N，而压缩后条数恒为 {@code 首条用户消息 + 摘要 + keep = keep+2}</b>，
     *       所以只有 {@code N > keep+2} 才压得短。五次真跑触发压缩时的历史长度实录为
     *       {@code 7、8、9、10、13}：keep=6 时压缩后恒为 8 条，7、8 两档压不短，
     *       {@code anySatisfy(M<N)} 有整跑落空的实际风险；keep=4 时 7 条起就压得短。
     *       换 keep 会连带改变压缩节奏、历史长度分布也会跟着变，所以这是量级判断不是精确外推。
     *       （{@code N == keep+2} 那一档具体走哪条路要看 index 1 是不是摘要：首次压缩时不是，
     *       会压出"N 条 → N 条"的空转；之后老摘要被挑出来不再压，{@code toSummarize} 为空，
     *       走的是 {@code 无新原文可压，跳过}）</li>
     *   <li>经验观察，不是定律，而且<b>只对特定形状成立</b>：run1 那次 9 条历史里回执正在 index 5，
     *       理想切点 {@code 9-4=5} 恰好切断配对，日志留下 {@code 压缩 9 条 → 7 条}（挪到 4 才安全）；
     *       <b>同一个形状</b>换 keep=6 则 cutoff=3 直接安全、守卫压根不会被逼出来。
     *       别把它读成"整批形状在 keep=6 下都逼不出守卫"——那是错的，
     *       {@code ConversationSummarizerTest} 的 {@code run1-R3-次压} 形状在 keep=6 下就会切断配对</li>
     * </ol>
     * 两项都不影响结论的可靠性：{@code findSafeCutoff} 的回退循环与 keep 取值无关，
     * 不存在"测试绿但生产会切断"这种情况。
     */
    static final int KEEP_MESSAGES = 4;

    /** 压缩日志：{@code [Summarize] 压缩 9 条 → 7 条（原 ~5231 tokens）} */
    private static final Pattern COMPRESS_LOG = Pattern.compile("\\[Summarize] 压缩 (\\d+) 条 → (\\d+) 条");
    /** 跳过日志：{@code [Summarize] 找不到安全切点，跳过压缩 tokens=3352 messages=5}，取 messages 数 */
    private static final Pattern SKIP_LOG = Pattern.compile("找不到安全切点.*messages=(\\d+)");

    /** 摘要消息的固定前缀，与 ConversationSummarizer.SUMMARY_PREFIX 一致（那边是 private） */
    private static final String SUMMARY_PREFIX = "## 早前对话摘要：";
    /** 第二段摘要的分段标记：出现它=老摘要没被重压，是原样留着接新段 */
    private static final String SECOND_SEGMENT = "── 第2段 ──";

    /**
     * 剧本。前三轮负责把上下文撑起来并往会话历史里塞两组工具配对，后两轮是"压缩之后还能不能说话"的正主。
     * 第 2、3 轮必须出现"深度研判"字样——summarizer 的 instruction 明令只有这类字眼才准调
     * run_deep_analysis；带上"先看行情"是为了让路由顺手派一个 market 专家，
     * 专家消息会把工具配对顶到理想切点附近，切点安全性才验得到。
     */
    private static final List<String> SCRIPT = List.of(
            "最近有什么重要的加密货币新闻？",
            "帮我对 BTCUSDT 做一次深度研判，先看看它现在的行情",
            "再对 ETHUSDT 做一次深度研判，同样先看行情",
            "刚才我们都聊了什么？简单回顾一下",
            "结合前面聊过的，BTC 现在最需要注意的风险是什么？");

    /** 挑 1 号：真跑要烧的那份 BYOK 配在它名下 */
    private static final long ADMIN_USER_ID = 1L;

    @Autowired
    private ChatAgentFactory chatAgentFactory;

    @Autowired
    private ChatTurnRunner chatTurnRunner;

    /** 续聊上下文的真存储：每轮跑完从这里读，读到的就是下一轮原样重放给上游的那份 */
    @Autowired
    private ChatContextStore chatContextStore;

    /** 真跑就得烧真配置：这一跑的全部价值就在于走用户自己那份 BYOK，绝不在这里造一份假的 */
    @Autowired
    private LlmEndpointService endpointService;

    private final ListAppender<ILoggingEvent> summarizeLogs = new ListAppender<>();
    /**
     * {@link ResilientChatService} 的日志。硬失败现在会直接把这一跑弄红（这条链上已经没有兜底模型），
     * 但<b>重试之后成功</b>那类只在日志里留一行、答案照出、测试照绿——这一层是那类问题唯一的观测点。
     */
    private final ListAppender<ILoggingEvent> resilienceLogs = new ListAppender<>();
    /** 全程扫到的"落单工具回执"，同形状只留一条 */
    private final Set<String> pairingViolations = new LinkedHashSet<>();

    @AfterEach
    void detachAppenders() {
        ((Logger) LoggerFactory.getLogger(ConversationSummarizer.class)).detachAppender(summarizeLogs);
        ((Logger) LoggerFactory.getLogger(ResilientChatService.class)).detachAppender(resilienceLogs);
    }

    @Test
    void 多轮真跑触发历史压缩且切点不切断工具配对() {
        summarizeLogs.start();
        ((Logger) LoggerFactory.getLogger(ConversationSummarizer.class)).addAppender(summarizeLogs);
        resilienceLogs.start();
        ((Logger) LoggerFactory.getLogger(ResilientChatService.class)).addAppender(resilienceLogs);

        ChatEndpoints llmConfig = endpointService.chatEndpoints(ADMIN_USER_ID);
        assertThat(llmConfig).as("先用管理员账号在 AI 页「模型配置」加一条 BYOK 端点再跑").isNotNull();

        ChatAgentFactory.Leaves leaves = chatAgentFactory.leavesFor(llmConfig, AgentLang.ZH);
        // sessionId 跨轮不变：历史靠 ChatContextStore 累积，这才是生产形态
        String sessionId = "wb-1-summarize-realrun-" + UUID.randomUUID();

        List<String> answers = new ArrayList<>();
        List<Integer> compressionsBeforeRound = new ArrayList<>();
        List<Message> finalMessages = List.of();
        try {
            for (String question : SCRIPT) {
                compressionsBeforeRound.add(compressions().size());
                String answer = ask(leaves, sessionId, question);
                answers.add(answer);
                // 一轮跑完落库的这份，就是下一轮原样重放给上游的输入——落单回执在这儿最抓得住现行
                List<Message> history = chatContextStore.load(sessionId);
                recordPairingViolation(answers.size(), history);
                log.info("[SummarizeRealRun] 第 {} 轮问：{}", answers.size(), question);
                log.info("[SummarizeRealRun] 第 {} 轮答（{} 字）：{}", answers.size(), answer.length(), answer);
                log.info("[SummarizeRealRun] 第 {} 轮后历史 {} 条：{}",
                        answers.size(), history.size(), shapeOf(history));
                finalMessages = history;
            }
        } finally {
            chatContextStore.purge(sessionId);   // 真跑不留脏行
        }

        List<String> compressions = compressions();
        compressions.forEach(line -> log.info("[SummarizeRealRun] {}", line));
        pairingViolations.forEach(line -> log.error("[SummarizeRealRun] 配对被切断：{}", line));

        // 验收点 1：压缩真的发生过，且确实压短了（不是"N 条 → N 条"的空转）
        assertThat(compressions).isNotEmpty();
        assertThat(compressions).anySatisfy(line -> {
            Matcher matcher = COMPRESS_LOG.matcher(line);
            assertThat(matcher.find()).isTrue();
            assertThat(Integer.parseInt(matcher.group(2))).isLessThan(Integer.parseInt(matcher.group(1)));
        });

        // 验收点 2：压缩之后上游还能正常说话。
        // 非平凡的是这条——必须真有轮次是「带着已压缩的历史从存储起跑」的，
        // 否则"压缩结果落没落盘、落盘的那份能不能被上游接受"根本没被验到
        assertThat(firstRoundStartingCompressed(compressionsBeforeRound))
                .as("没有任何一轮是带着已压缩历史起跑的，验收点 2 无从谈起")
                .isGreaterThanOrEqualTo(0);
        assertThat(answers).allSatisfy(answer -> assertThat(answer).isNotBlank());
        // 答案非空远远不够：400 发生在首帧之前，兜底模型会无缝接盘，答案照出、测试照绿（见类头）。
        // 上游到底有没有拒过这份压缩历史，只有 ResilientChatService 的日志说得清
        assertThat(upstreamTroubles())
                .as("压缩后的历史让上游出错了——兜底接了盘所以答案还在，但这条链已经废了")
                .isEmpty();
        // 压缩自身的静默降级：两条都是 WARN 之后带着未压缩历史继续，测试绿着而压缩等于没做
        assertThat(silentDegradations())
                .as("压缩发生了静默降级，绿是假绿")
                .isEmpty();

        // 同一件事的结构断言，也是本类真正的守门员（理由见类头"上游宽容"那段）：
        // 每轮落库的历史里，工具回执都必须能找到配对的调用——落单的那条会被原样送进下一轮请求
        assertThat(pairingViolations)
                .as("压缩切点把工具调用与回执切成了两半，落单的回执会被原样送进上游请求")
                .isEmpty();

        // 验收点 3：摘要以固定前缀存在于最终历史；压缩发生两次以上则老摘要原样留着接新段
        List<String> summaries = finalMessages.stream()
                .filter(SystemMessage.class::isInstance)
                .map(Message::getText)
                .filter(text -> text != null && text.startsWith(SUMMARY_PREFIX))
                .toList();
        assertThat(summaries).hasSize(1);
        log.info("[SummarizeRealRun] 最终摘要：{}", summaries.getFirst());
        if (compressions.size() >= 2) {
            assertThat(summaries.getFirst()).contains(SECOND_SEGMENT);
        }
    }

    /** 一轮对话，走的就是 Controller 那条路（{@link ChatTurnRunner#run}） */
    private String ask(ChatAgentFactory.Leaves leaves, String sessionId, String question) {
        StringBuilder answer = new StringBuilder();
        chatTurnRunner.run(leaves, ADMIN_USER_ID, sessionId, question, null, answer::append,
                event -> log.info("[SummarizeRealRun] 专家 {} {}", event.agent(), event.phase()),
                s -> { }, ChatTurnRunner.TurnYield.NONE, null);
        return answer.toString();
    }

    /**
     * 扫每轮落库历史里的"落单工具回执"。这是唯一抓得住现行的地方：这份历史就是下一轮
     * 上游请求的 input（{@code ResponsesChatModel.buildInput} 把 ToolResponseMessage 无条件转成
     * function_call_output），而它在库里只活到下一次压缩——下次压缩把它卷进摘要就"自愈"了。
     * <p>
     * <b>只查"回执找不到调用"这一个方向，不是图省事，是反方向不可达</b>：{@code compress} 做的是
     * 严格的前缀切（{@code messages.subList(cutoff, size)} 原样保留尾部），被提到头部的只有首条
     * UserMessage 和摘要 SystemMessage，这两种都带不了 toolCalls；而 ToolResponseMessage 恒在它的
     * AssistantMessage 之后。所以"调用存活、回执被压走"这种孤儿构造不出来。
     * 反过来查还会误报：模型刚吐出 tool_call、工具还没跑的那一拍，本来就是"调用还没有回执"。
     * <p>
     * <b>这条推理依赖 compress 保持前缀切语义</b>——哪天改成挖中间窗口（比如只压中段、保留头尾），
     * 反向孤儿立刻变得可达，而这条断言会静默瞎掉，届时必须补上反方向。
     */
    private void recordPairingViolation(int round, List<Message> messages) {
        Set<String> orphans = new LinkedHashSet<>(toolResponseIds(messages));
        orphans.removeAll(toolCallIds(messages));
        if (!orphans.isEmpty()) {
            pairingViolations.add("第 " + round + " 轮落单回执 " + orphans + " 形状：" + shapeOf(messages));
        }
    }

    /**
     * 上游出错的痕迹。三串关键词覆盖 {@link ResilientChatService} 的<b>全部四个</b>出错日志点：
     * 流式退避重试、流式重试耗尽切兜底、阻塞路径切兜底（后两个共用"切换兜底"这句）、
     * 阻塞路径读响应中断。任何一条出现都说明这轮请求被上游拒过或断过。
     *（"切换兜底"这两句今天已经打不出来了——BYOK 后全链路都不带兜底模型；
     * 关键词留着不碍事，将来真加回兜底也不用改这里。）
     * <p>
     * <b>这个探针至今没通过电，用它的时候心里要有数</b>：那四个日志点全是 {@code log.warn}、
     * 只在出错时打，所以历次真跑它一条都没捕到过。后果是——appender 接错 logger、或者哪天日志
     * 文案被改，与"一切正常"在观测上完全不可区分，它会静默地永远为空。对比之下
     * {@link #summarizeLogs} 是被证明活着的（每跑都捕到 {@code 无新原文可压} 与压缩 INFO）。
     * <p>
     * 即便如此仍然值得留：硬失败现在会自己红，但"退避重试之后成功"这类照样答案非空、测试全绿，
     * 没有它就完全看不见。
     * <p>
     * 已知代价：{@code 退避重试} 会被无关的瞬时网络抖动触发成假红（历次真跑 0 次出现）。留着——
     * 漏掉一次真的上游拒绝，比偶尔多红一次贵得多。
     */
    private List<String> upstreamTroubles() {
        return resilienceLogs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("切换兜底")
                        || message.contains("退避重试")
                        || message.contains("响应读取中断"))
                .toList();
    }

    /**
     * 压缩的静默降级。{@code 无新原文可压} 不算——切点前只剩首条用户消息与老摘要时跳过是正确降级，
     * 实测每跑都出现一次。
     * <p>
     * {@code 找不到安全切点} 这一句<b>身兼两义</b>（生产代码里两条路径共用它），靠日志里的
     * {@code messages=} 数区分：
     * <ul>
     *   <li>{@code messages <= keep}：{@code findSafeCutoff} 的早退，"历史还没保留数长"，良性</li>
     *   <li>{@code messages > keep}：回退循环从理想切点一路退到了 0。<b>这句日志的措辞与实情有出入</b>——
     *       cutoff=0 处 {@code isSafeCutoff} 恒为真（配对两端都落在切点同侧，
     *       {@code (assistantIndex<0) != (i<0)} 永远是 false），所以它并不是"没找到安全点"，
     *       而是只剩 0 这个没有压缩价值的切点可用。真出事的正是这一支：理想切点到 1 之间<b>全部</b>
     *       不安全，说明配对已密集到无法安全切分</li>
     * </ul>
     * {@code 压缩失败，沿用原始对话} 无条件算违规，但要知道它<b>同样是环境敏感的</b>：那是
     * {@code ConversationSummarizer} 的 catch-all，浅模型任何一次网络抖动都会落进来，不只是逻辑 bug。
     * 风险低于 {@code 退避重试}（SDK 层重试会吸收大部分），保留。
     */
    private List<String> silentDegradations() {
        return summarizeLogs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> {
                    if (message.contains("压缩失败，沿用原始对话")) {
                        return true;
                    }
                    Matcher matcher = SKIP_LOG.matcher(message);
                    return matcher.find() && Integer.parseInt(matcher.group(1)) > KEEP_MESSAGES;
                })
                .toList();
    }

    private List<String> compressions() {
        return summarizeLogs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> COMPRESS_LOG.matcher(message).find())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /** 第一个"起跑时历史已被压过"的轮次下标；没有返回 -1 */
    private static int firstRoundStartingCompressed(List<Integer> compressionsBeforeRound) {
        for (int i = 0; i < compressionsBeforeRound.size(); i++) {
            if (compressionsBeforeRound.get(i) > 0) {
                return i;
            }
        }
        return -1;
    }

    private static Set<String> toolCallIds(List<Message> messages) {
        return messages.stream()
                .filter(AssistantMessage.class::isInstance).map(AssistantMessage.class::cast)
                .flatMap(assistant -> assistant.getToolCalls().stream())
                .map(AssistantMessage.ToolCall::id)
                .collect(Collectors.toSet());
    }

    private static Set<String> toolResponseIds(List<Message> messages) {
        return messages.stream()
                .filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                .flatMap(toolResponse -> toolResponse.getResponses().stream())
                .map(ToolResponseMessage.ToolResponse::id)
                .collect(Collectors.toSet());
    }

    /** 历史形状速览：切点落在哪、配对被不被拆，靠这行日志才看得出来 */
    private static String shapeOf(List<Message> messages) {
        return messages.stream().map(message -> switch (message) {
            case AssistantMessage assistant when !assistant.getToolCalls().isEmpty() -> "调用";
            case AssistantMessage ignored -> "助手";
            case ToolResponseMessage ignored -> "回执";
            case UserMessage ignored -> "用户";
            case SystemMessage system when system.getText() != null && system.getText().startsWith(SUMMARY_PREFIX)
                    -> "摘要";
            default -> "其他";
        }).collect(Collectors.joining("·"));
    }
}
