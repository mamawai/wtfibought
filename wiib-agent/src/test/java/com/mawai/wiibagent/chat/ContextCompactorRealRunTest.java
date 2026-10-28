package com.mawai.wiibagent.chat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.run.ChildRunRegistry;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import com.mawai.wiibagent.chat.store.ChatHistoryService;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibagent.llm.LlmEndpointService;
import com.mawai.wiibagent.llm.SseChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真跑验收（非单测）：同一会话聊三轮，整理线压得很低，第 2、3 轮开头各整理一次。
 * <p>
 * 单测替代不了的三件事：
 * <ol>
 *   <li>整理在生产装配下真的通电：轮编排读库里上一轮的用量判线，走主 agent 的 client 发整理请求</li>
 *   <li>整理请求带着全部工具和思考配置，真模型交回的是摘要文本，不是去调工具</li>
 *   <li>整理后的历史落库再读出来上游照收；第一轮的数字只活在摘要里，第三轮还答得出来</li>
 * </ol>
 * 按 Controller 同款方式走 {@link ChatTurnStreamer}（通道标成已断开，帧不写、照样落库），
 * 上一轮用量就是从展示表里读的那份。
 * <p>
 * 会真烧 token（三轮外加两次整理），默认跳过。跑法（项目根）：
 * <pre>
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-agent -am -DskipTests=false \
 *   -Dtest=ContextCompactorRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * 日志看点：[Compact] 整理完成、整理请求的缓存读取量（Anthropic 看 cache_read_input_tokens）、[TurnMetrics]。
 */
@SpringBootTest(properties = {
        // 只验对话链路：策略信号/实盘执行/AI 分析轨全关，测试期间不许背景任务下单写库
        "strategy.runtime.enabled=false",
        "strategy.execution.enabled=false",
        "agent.analysis.enabled=false",
        // 窗口最小 128K，真聊到九成太贵：比例压到千分之一，第一轮之后每轮开头都整理
        "agent.workbench.compact-trigger-ratio=0.001"
})
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class ContextCompactorRealRunTest {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(ContextCompactorRealRunTest.class);

    /** 挑 1 号：真跑要烧的那份 BYOK 配在它名下 */
    private static final long ADMIN_USER_ID = 1L;

    /**
     * 数字是用户自己给的，不靠行情工具（本机连 Binance 会 451）。第三轮开头整理完，
     * BTC 那组数只剩摘要里有，ETH 那组还在原样留下的上一问答里
     */
    private static final List<String> SCRIPT = List.of(
            "我在 96300 开了 BTC 多单，止损设在 94200，帮我记一下，简单说说这个止损距离合不合适",
            "ETH 我在 3410 也开了多单，止损 3330，同样评价一下",
            "我 BTC 的止损设在多少来着？ETH 是在多少开的仓？");

    @Autowired
    private ChatAgentFactory chatAgentFactory;

    @Autowired
    private ChatTurnStreamer streamer;

    @Autowired
    private ChatContextStore chatContextStore;

    @Autowired
    private ChatHistoryService chatHistoryService;

    @Autowired
    private ChildRunRegistry childRuns;

    /** 真跑就得烧真配置：走用户自己那份 BYOK，不在这里造一份假的 */
    @Autowired
    private LlmEndpointService endpointService;

    @Autowired
    private PromptCatalog prompts;

    private final ListAppender<ILoggingEvent> compactLogs = new ListAppender<>();

    @AfterEach
    void detachAppender() {
        ((Logger) LoggerFactory.getLogger(ContextCompactor.class)).detachAppender(compactLogs);
    }

    @Test
    void 多轮真跑开轮整理且追问接得上() {
        compactLogs.start();
        ((Logger) LoggerFactory.getLogger(ContextCompactor.class)).addAppender(compactLogs);

        ChatEndpoints llmConfig = endpointService.chatEndpoints(ADMIN_USER_ID);
        assertThat(llmConfig).as("先用管理员账号在 AI 页「模型配置」加一条 BYOK 端点再跑").isNotNull();
        ChatAgentFactory.Leaves leaves = chatAgentFactory.leavesFor(llmConfig, AgentLang.ZH);
        String sessionId = "wb-1-compact-realrun-" + UUID.randomUUID();

        List<String> answers;
        List<Message> finalHistory;
        try {
            for (String question : SCRIPT) {
                SseChannel channel = new SseChannel(new SseEmitter());
                channel.markClosed();
                streamer.run(channel, ADMIN_USER_ID, sessionId, question, leaves, null, null, false);
            }
            answers = chatHistoryService.messages(sessionId).stream()
                    .filter(row -> "assistant".equals(row.role()))
                    .map(ChatHistoryService.ChatMessage::content)
                    .toList();
            finalHistory = chatContextStore.load(sessionId).messages();
        } finally {
            // 真跑不留脏行；模型若派了子运行也一并停掉，免得报告晚到又写回上下文
            childRuns.purge(sessionId);
            chatContextStore.purge(sessionId);
            chatHistoryService.deleteSession(sessionId);
        }
        answers.forEach(answer -> log.info("[CompactRealRun] 回答：{}", answer));
        log.info("[CompactRealRun] 最终历史队首：{}", finalHistory.getFirst().getText());

        List<String> logs = compactLogs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(logs).filteredOn(line -> line.contains("整理完成")).as("第 2、3 轮开头各整理一次").hasSize(2);
        assertThat(logs).filteredOn(line -> line.contains("沿用原历史")).as("整理静默失败了，绿是假绿").isEmpty();
        assertThat(answers).hasSize(3).allSatisfy(answer -> assertThat(answer).isNotBlank());
        assertThat(finalHistory.getFirst().getText())
                .startsWith(prompts.get(AgentLang.ZH, "chat.compact.preamble"));
        // BTC 的止损只在摘要里，ETH 的开仓价在原样留下的上一问答里
        assertThat(answers.getLast().replace(",", ""))
                .contains("94200")
                .contains("3410");
    }
}
