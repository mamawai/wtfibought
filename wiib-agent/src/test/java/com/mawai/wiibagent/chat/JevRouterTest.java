package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.entity.UserJevConfig;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.llm.ApiKeyCrypto;
import com.mawai.wiibagent.llm.jev.JevClient;
import com.mawai.wiibagent.llm.jev.JevConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.mawai.wiibagent.chat.ChatTestEndpoints.PROMPTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 未配置/失败/缺题都回落；过线的按固定顺序进名单、都不过线是 FINISH；state 只带提问与用户/助手原文 */
class JevRouterTest {

    private final JevConfigService configService = mock(JevConfigService.class);
    private final JevClient client = mock(JevClient.class);
    private final ApiKeyCrypto crypto = mock(ApiKeyCrypto.class);
    private final JevRouter router = new JevRouter(configService, client, crypto, PROMPTS);

    private static UserJevConfig cfg() {
        UserJevConfig c = new UserJevConfig();
        c.setUserId(1L);
        c.setBaseUrl("https://8.8.8.8");
        c.setModel("jev-latest");
        c.setApiKeyEnc("enc");
        return c;
    }

    private static JevClient.Answer noul(double p) {
        return new JevClient.Answer("noul", p, null, null, null, null);
    }

    private static JevClient.Response answers(double market, double news, double trader) {
        Map<String, JevClient.Answer> a = new LinkedHashMap<>();
        a.put("need_market", noul(market));
        a.put("need_news", noul(news));
        a.put("need_trader", noul(trader));
        return new JevClient.Response("jev-1.13.0", a, 100);
    }

    /** 本轮提问的真实拼法（ChatTurnStreamer.Turn.run）：时间行 + 问题前缀 */
    private static String enriched(String question) {
        return PROMPTS.get(AgentLang.ZH, "chat.turn.timeMark", Map.of("time", "2026-09-21 10:00")) + "\n"
                + PROMPTS.get(AgentLang.ZH, "chat.turn.questionPrefix") + question;
    }

    private void configured() {
        when(configService.of(1L)).thenReturn(cfg());
        when(crypto.decrypt("enc")).thenReturn("sk-plain");
    }

    @Test
    void 未配置不调Jev() {
        when(configService.of(1L)).thenReturn(null);

        assertThat(router.route(1L, List.of(new UserMessage(enriched("看看行情"))))).isEmpty();
        verify(client, never()).ask(any(), any(), any(), any(), any());
    }

    @Test
    void 过线的按固定顺序进名单() {
        configured();
        when(client.ask(eq("https://8.8.8.8"), eq("sk-plain"), eq("jev-latest"), any(), eq(JevRouter.QUESTIONS)))
                .thenReturn(answers(0.8, 0.3, 0.6));

        Optional<List<String>> next = router.route(1L, List.of(new UserMessage(enriched("我的 trader 拿了什么仓，现在行情如何"))));

        assertThat(next).contains(List.of("market_agent", "trader_agent"));
        // 发出去的 state 就是剥掉标记的提问
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> state = ArgumentCaptor.forClass(Map.class);
        verify(client).ask(any(), any(), any(), state.capture(), any());
        assertThat(state.getValue()).containsEntry("latest_question", "我的 trader 拿了什么仓，现在行情如何");
    }

    @Test
    void 三题都不过线是FINISH() {
        configured();
        when(client.ask(any(), any(), any(), any(), any())).thenReturn(answers(0.1, 0.2, 0.3));

        assertThat(router.route(1L, List.of(new UserMessage(enriched("分析我的行为")))))
                .contains(List.of(ChatTurnRunner.FINISH));
    }

    @Test
    void 调用失败回落() {
        configured();
        when(client.ask(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("timeout"));

        assertThat(router.route(1L, List.of(new UserMessage(enriched("看看行情"))))).isEmpty();
    }

    @Test
    void 回包缺题回落() {
        configured();
        Map<String, JevClient.Answer> partial = new LinkedHashMap<>();
        partial.put("need_market", noul(0.9));
        partial.put("need_news", noul(0.1));
        when(client.ask(any(), any(), any(), any(), any())).thenReturn(new JevClient.Response("jev-1.13.0", partial, 100));

        assertThat(router.route(1L, List.of(new UserMessage(enriched("看看行情"))))).isEmpty();
    }

    @Test
    void state只带提问与用户助手原文() {
        List<Message> working = List.of(
                new SystemMessage("【对话摘要】早先聊过 SOL"),
                new UserMessage(enriched("BTC 现在怎么样")),
                ChatTurnRunner.expertMessage(PROMPTS, AgentLang.ZH, "market_agent", "chat.expertStatus.data", "{大段行情数据}"),
                new UserMessage(PROMPTS.get(AgentLang.ZH, "chat.expertHandoff") + "\n" + PROMPTS.get(AgentLang.ZH, "chat.outputLanguage")),
                new AssistantMessage("BTC 在 6 万附近震荡"),
                new UserMessage(enriched("那 ETH 呢")));

        Map<String, Object> state = router.state(working);

        assertThat(state).containsEntry("latest_question", "那 ETH 呢");
        assertThat(state.get("recent_conversation")).isEqualTo(List.of(
                Map.of("role", "user", "text", "BTC 现在怎么样"),
                Map.of("role", "assistant", "text", "BTC 在 6 万附近震荡")));
    }

    @Test
    void 最近原文条数封顶取最近的() {
        List<Message> working = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            working.add(new UserMessage(enriched("问" + i)));
            working.add(new AssistantMessage("答" + i));
        }
        working.add(new UserMessage(enriched("最新")));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> recent = (List<Map<String, String>>) router.state(working).get("recent_conversation");

        assertThat(recent).hasSize(JevRouter.RECENT_MESSAGES);
        assertThat(recent.getFirst()).containsEntry("text", "问4");
        assertThat(recent.getLast()).containsEntry("text", "答5");
    }
}
