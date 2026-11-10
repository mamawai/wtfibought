package com.mawai.wiibagent.promptgen;

import com.mawai.wiibagent.llm.ChatEndpoints;
import com.mawai.wiibagent.llm.LlmEndpointService;
import com.mawai.wiibagent.llm.SseChannel;
import com.mawai.wiibcommon.entity.PromptGenSession;
import com.mawai.wiibcommon.enums.AgentLang;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真跑验收（非单测）：真模型从开始一路选推荐项，直到交出一版。看的是真模型肯不肯好好用 ask_user、
 * 必问项问没问全、写稿人守不守格式——单测的 ChatModel 是 mock，对这些完全无感。
 * <p>
 * 会真烧 LLM token，并覆盖 1 号用户的生成器对话与版本，默认跳过。跑法（项目根）：
 * <pre>
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-agent -am -DskipTests=false \
 *   -Dtest=PromptGenRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@SpringBootTest(properties = {
        // 只验生成器链路：策略信号/实盘执行/AI 分析轨全关，测试期间不许背景任务下单写库
        "strategy.runtime.enabled=false",
        "strategy.execution.enabled=false",
        "agent.analysis.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class PromptGenRealRunTest {

    private static final Logger log = LoggerFactory.getLogger(PromptGenRealRunTest.class);

    /** 真跑要烧的那份 BYOK 配在 1 号名下 */
    private static final long ADMIN_USER_ID = 1L;

    /** 必问 5~6 题加几道追问，留足余量；超了说明 agent 不肯写稿 */
    private static final int MAX_ROUNDS = 16;

    @Autowired
    private PromptGenService service;

    @Autowired
    private LlmEndpointService endpointService;

    @Test
    void 一路选推荐项直到交出第一版() {
        ChatEndpoints eps = endpointService.chatEndpoints(ADMIN_USER_ID);
        assertThat(eps).as("先用管理员账号在 AI 页「模型配置」加一条 BYOK 端点再跑").isNotNull();
        long endpointId = eps.deep().getId();
        ChatModel model = service.model(ADMIN_USER_ID, endpointId);

        service.start(channel(), ADMIN_USER_ID, model, endpointId, AgentLang.ZH);
        PromptGenView view = service.view(ADMIN_USER_ID);
        for (int round = 0; round < MAX_ROUNDS && view.currentNo() == null; round++) {
            assertThat(view.question()).as("agent 这一轮既没出题也没写稿，看日志里它说了什么").isNotNull();
            log.info("[RealRun] 第 {} 题 [{}] {} → 选推荐：{}（{}）", round + 1, view.question().key(),
                    view.question().question(), view.question().recommended(), view.question().reason());
            PromptGenSession session = service.session(ADMIN_USER_ID);
            service.reply(channel(), ADMIN_USER_ID, model, session,
                    service.replyHistory(ADMIN_USER_ID, session, view.question().recommended(), false));
            view = service.view(ADMIN_USER_ID);
        }

        assertThat(view.currentNo()).as("问了 %d 轮还没写出一版", MAX_ROUNDS).isNotNull();
        assertThat(view.items()).filteredOn(item -> "question".equals(item.type()))
                .extracting(item -> item.question().key())
                .contains("goal", "style", "holding", "direction", "limits");
        PromptGenView.Version version = view.versions().getFirst();
        log.info("[RealRun] v{} 正文（{}字）：\n{}\nnotes：\n{}", version.no(), version.body().length(),
                version.body(), version.notes());
        assertThat(version.body()).isNotBlank().doesNotContain(PromptGenFacts.NOTES_MARK);
        assertThat(version.body().length()).isLessThanOrEqualTo(4000);
        assertThat(version.notes()).isNotBlank();
    }

    /** 没挂到真请求上的通道：帧攒在 emitter 里没人读，这里只看落库后的状态 */
    private static SseChannel channel() {
        return new SseChannel(new SseEmitter());
    }
}
