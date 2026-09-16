package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibagent.llm.LlmEndpointService;
import com.mawai.wiibagent.trader.TraderChatService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真跑验收（非单测）：起完整 Spring 上下文，真连 DB 配置的 LLM 代理与本地 PG/Redis，
 * 按 Controller 同款方式驱动一轮完整对话。单测把上游 mock 掉了（mock ChatModel），
 * 绿了不代表链路通——框架契约边界的验证空白由本类补。
 * <p>
 * 会真烧 LLM token，默认跳过，显式开启才跑。跑法（项目根）：
 * <pre>
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-agent -am -DskipTests=false \
 *   -Dtest=ChatWorkbenchRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * 日志看点：[Responses] 请求 tool_choice=… / toolCalls=…、[NewsTool] 预取、[Workbench] 派发、[TurnMetrics]。
 */
@SpringBootTest(properties = {
        // 只验对话链路：策略信号/实盘执行/AI 分析轨全关，测试期间不许背景任务下单写库
        "strategy.runtime.enabled=false",
        "strategy.execution.enabled=false",
        "agent.analysis.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class ChatWorkbenchRealRunTest {

    private static final Logger log = LoggerFactory.getLogger(ChatWorkbenchRealRunTest.class);

    /** 挑 1 号：真跑要烧的那份 BYOK 配在它名下 */
    private static final long ADMIN_USER_ID = 1L;

    @Autowired
    private ChatAgentFactory chatAgentFactory;

    @Autowired
    private ChatTurnRunner chatTurnRunner;

    /** 真跑就得烧真配置：这一跑的全部价值就在于走用户自己那份 BYOK，绝不在这里造一份假的 */
    @Autowired
    private LlmEndpointService endpointService;

    /** 断言的期望值从这儿取：库里那份真 trader 数据，不在测试里另造 */
    @Autowired
    private TraderChatService traderChatService;

    @Test
    void 一轮新闻加行情提问全链路真跑() {
        ChatEndpoints llmConfig = endpointService.chatEndpoints(ADMIN_USER_ID);
        assertThat(llmConfig).as("先用管理员账号在 AI 页「模型配置」加一条 BYOK 端点再跑").isNotNull();

        ChatAgentFactory.Leaves leaves = chatAgentFactory.leavesFor(llmConfig, AgentLang.ZH);
        String sessionId = "wb-1-realrun-" + UUID.randomUUID();
        List<ChatTurnRunner.ExpertProgress> events = new CopyOnWriteArrayList<>();
        StringBuilder answer = new StringBuilder();

        // 纯新闻问题复刻实测暴露过的病：summarizer 拿到专家清单后用自己的搜索重写一遍
        chatTurnRunner.run(leaves, ADMIN_USER_ID, sessionId,
                "最近有什么重要的加密货币新闻？", null, answer::append, events::add, s -> { },
                ChatTurnRunner.TurnYield.NONE, null);

        for (ChatTurnRunner.ExpertProgress e : events) {
            log.info("[RealRun] 专家事件 agent={} phase={} text={}", e.agent(), e.phase(),
                    e.text() == null ? null : e.text().substring(0, Math.min(2000, e.text().length())));
        }
        log.info("[RealRun] 最终回答（{}字）：{}", answer.length(), answer);

        // 链路底线：汇总有产出；新闻问题至少派出过一个专家
        assertThat(answer.toString()).isNotBlank();
        Map<String, Long> starts = events.stream()
                .filter(e -> ChatTurnRunner.ExpertProgress.START.equals(e.phase()))
                .collect(Collectors.groupingBy(ChatTurnRunner.ExpertProgress::agent, Collectors.counting()));
        assertThat(starts).isNotEmpty();
        // 同一专家最多 start 一次：去重生效，循环必然收敛
        assertThat(starts).allSatisfy((agent, count) -> assertThat(count).isLessThanOrEqualTo(1L));
        // 输出契约：news_agent 的 BlockBeats 条目必须存活在最终回答里（标可因联网佐证升级为
        // 合并标）——只剩补充源的标即 summarizer 丢弃专家清单自己重写了，正是要防的回归
        // 合并标是 [BlockBeats+源名]（源名可配），按前缀认不依赖具体源名
        assertThat(answer.toString()).containsAnyOf("[BlockBeats]", "[BlockBeats+");
    }

    /**
     * 专家把 trader 数据取回来了，summarizer 却答"没有 trader 的最新表现数据"。
     * <p>
     * <b>只有真跑验得到</b>：病根在喂进去的上下文形态，而单测的 ChatModel 是 mock、照剧本吐字，
     * 不会像真模型那样把上一条助手消息当成"我已经答过了"，对形态完全无感。
     * <p>
     * trader 链路纯读库不打 Binance，本机可验（行情链路仍要上服务器，见 tutorial 附录 A）。
     */
    @Test
    void trader表现提问真跑不再答没有数据() {
        ChatEndpoints llmConfig = endpointService.chatEndpoints(ADMIN_USER_ID);
        assertThat(llmConfig).as("先用管理员账号在 AI 页「模型配置」加一条 BYOK 端点再跑").isNotNull();
        // 期望值取自库里那份真数据，不在测试里另造一份：造了就变成"自己写的自己验"
        String overview = traderChatService.overview(ADMIN_USER_ID, AgentLang.ZH);
        assertThat(overview).as("这一跑要有一个真 trader 才有意义").contains("\"hasTrader\":true");
        String equityBefore = equityDigits(overview);

        ChatAgentFactory.Leaves leaves = chatAgentFactory.leavesFor(llmConfig, AgentLang.ZH);
        String sessionId = "wb-1-realrun-" + UUID.randomUUID();
        List<ChatTurnRunner.ExpertProgress> events = new CopyOnWriteArrayList<>();
        StringBuilder answer = new StringBuilder();

        chatTurnRunner.run(leaves, ADMIN_USER_ID, sessionId,
                "我的 AI 交易员最近表现如何？", null, answer::append, events::add, s -> { },
                ChatTurnRunner.TurnYield.NONE, null);

        for (ChatTurnRunner.ExpertProgress e : events) {
            log.info("[RealRun] 专家事件 agent={} phase={} text={}", e.agent(), e.phase(),
                    e.text() == null ? null : e.text().substring(0, Math.min(2000, e.text().length())));
        }
        log.info("[RealRun] 最终回答（{}字）：{}", answer.length(), answer);

        // 收尾事件（done/error）分开断言：合成一条 orElseThrow 会把上游取数失败误报成
        // "路由没派专家"，排查方向带偏
        ChatTurnRunner.ExpertProgress settled = events.stream()
                .filter(e -> ChatAgentFactory.TRADER_AGENT.equals(e.agent())
                        && !ChatTurnRunner.ExpertProgress.START.equals(e.phase()))
                .findFirst().orElseThrow(() -> new AssertionError("路由没把 trader_agent 派出去"));
        assertThat(settled.phase())
                .as("trader_agent 取数失败：%s（上游瞬时故障就重跑）", settled.text())
                .isEqualTo(ChatTurnRunner.ExpertProgress.DONE);
        assertThat(settled.text()).isNotBlank();
        // 库里那个权益数字出现在回答里，才算数据真穿过了汇总这一跳。
        // 不锚 status：库里存 RUNNING、模型多半写"运行中"，锚它是在考措辞不是考链路。
        // 前后各读一次取并集：权益随唤醒（5m 一次）落库刷新，单值会偶发红
        String equityAfter = equityDigits(traderChatService.overview(ADMIN_USER_ID, AgentLang.ZH));
        assertThat(answer.toString().replace(",", ""))
                .as("汇总否认了专家数据")
                .containsAnyOf(equityBefore, equityAfter);
    }

    /** 权益的整数段：模型可能写 10,515.31 也可能写 10515.31，去掉千分位后比整数段最稳 */
    private static String equityDigits(String overviewJson) {
        return MAPPER.readTree(overviewJson).path("equity").asDecimal(null)
                .setScale(0, RoundingMode.DOWN).toPlainString();
    }
}
