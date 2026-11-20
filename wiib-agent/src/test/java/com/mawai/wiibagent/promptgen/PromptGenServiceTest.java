package com.mawai.wiibagent.promptgen;

import com.mawai.wiibagent.chat.ChatModelFactory;
import com.mawai.wiibagent.chat.ChatTestEndpoints;
import com.mawai.wiibagent.chat.store.ChatContextCodec;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import com.mawai.wiibagent.llm.AnthropicChatModel;
import com.mawai.wiibagent.llm.LlmEndpointService;
import com.mawai.wiibagent.llm.SseChannel;
import com.mawai.wiibagent.trader.TraderChatService;
import com.mawai.wiibagent.trader.TraderService;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.PromptGenSession;
import com.mawai.wiibcommon.enums.AgentLang;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生成器的一轮：起跑历史的前缀指纹对不上就按文本重建；规格事实、必问项都按会话记的那个 trader 取。
 */
class PromptGenServiceTest {

    private final PromptGenStore store = mock(PromptGenStore.class);
    private final PromptGenFacts facts = mock(PromptGenFacts.class);
    private final TraderService traderService = mock(TraderService.class);
    private final LlmEndpointService endpointService = mock(LlmEndpointService.class);
    private final ChatModel model = mock(ChatModel.class);
    private final PromptGenService service = new PromptGenService(store, facts, endpointService,
            mock(ChatModelFactory.class), traderService, mock(TraderChatService.class),
            mock(PlayStatsAssembler.class), ChatTestEndpoints.TOOLS, ChatTestEndpoints.PROMPTS,
            ChatTestEndpoints.zhLang());
    /** 发给模型的请求 */
    private final List<Prompt> sent = new ArrayList<>();

    @AfterEach
    void stop() {
        service.stop();
    }

    /** 模型每次都只回一句话，系统提示词随便给一份，端点固定一条 */
    private void modelSaysOk() {
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.stream(any(Prompt.class))).thenAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("好的")))));
        });
        when(facts.prompts(any(), anyBoolean(), any(), any()))
                .thenReturn(new PromptGenFacts.Prompts("你是指令生成助手", "写稿"));
        when(endpointService.get(eq(1L), anyLong())).thenReturn(ChatTestEndpoints.endpoint("gpt-5"));
    }

    /** 没挂到页面上的通道：只看落库和调用 */
    private static SseChannel channel() {
        SseChannel channel = new SseChannel(new SseEmitter());
        channel.markClosed();
        return channel;
    }

    private static PromptGenSession session(Long traderId, List<Message> history, String prefix) {
        PromptGenSession session = new PromptGenSession();
        session.setUserId(1L);
        session.setEndpointId(3L);
        session.setTraderId(traderId);
        session.setOutputLang("zh");
        session.setState(ChatContextCodec.write(new ChatContextStore.State(history, null, prefix)));
        return session;
    }

    /** 有现成指令的 trader：还没写过版本时 mode 是必问项 */
    private static AiTrader alpha() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setName("Alpha");
        t.setCustomPrompt("只做突破");
        return t;
    }

    /** 开场 + 一道挂着等回答的 mode 题 */
    private static List<Message> askingMode() {
        return List.of(new UserMessage("开始"),
                AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("a", "function",
                        "ask_user", "{\"key\":\"mode\",\"question\":\"在现有指令上改还是从零写？\"}"))).build(),
                ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("a", "ask_user",
                        MAPPER.writeValueAsString("{\"shown\":true}")))).build());
    }

    /** 跑一轮 reply：起跑历史里那条 agent 回复带原始内容块；返回这一轮存下的前缀指纹 */
    private String reply(String storedPrefix) {
        List<Message> history = List.of(new UserMessage("开始"),
                AssistantMessage.builder().content("上一轮的话")
                        .properties(Map.of(AnthropicChatModel.BLOCKS_KEY, "[{\"type\":\"thinking\"}]")).build(),
                new UserMessage("回答"));

        service.reply(channel(), 1L, model, session(null, history, storedPrefix), history);

        ArgumentCaptor<String> prefix = ArgumentCaptor.captor();
        verify(store, atLeastOnce()).saveState(eq(1L), any(), prefix.capture());
        return prefix.getValue();
    }

    /** 这一轮发出去的请求里那条上一轮回复：系统提示词、开始之后 */
    private Message replayed() {
        return sent.getLast().getInstructions().get(2);
    }

    @Test
    void 前缀变了起跑历史按文本重建_没变照样原样回放() {
        modelSaysOk();

        String prefix = reply("旧指纹");
        assertThat(replayed().getMetadata()).doesNotContainKey(AnthropicChatModel.BLOCKS_KEY);

        reply(prefix);
        assertThat(replayed().getMetadata()).containsKey(AnthropicChatModel.BLOCKS_KEY);
    }

    /** 会话长期留着，续答用的是会话里记的端点：端点改了 key、地址或模型，上一轮的原始内容块不再原样回放 */
    @Test
    void 同会话换端点后原始内容块被剥掉() {
        modelSaysOk();
        String prefix = reply("旧指纹");
        when(endpointService.get(1L, 3L)).thenReturn(ChatTestEndpoints.endpoint("gpt-5", "另一把key"));

        reply(prefix);

        assertThat(replayed().getMetadata()).doesNotContainKey(AnthropicChatModel.BLOCKS_KEY);
    }

    /** 页面、必问项、这一轮的规格事实都按会话记的 trader 取 */
    @Test
    void 按会话记的trader取事实与必问项() {
        modelSaysOk();
        AiTrader alpha = alpha();
        when(traderService.owned(1L, 7L)).thenReturn(alpha);
        PromptGenSession session = session(7L, askingMode(), null);
        when(store.session(1L)).thenReturn(session);

        PromptGenView view = service.view(1L);
        service.reply(channel(), 1L, model, session, service.replyHistory(1L, session, "在现有指令上改", false));

        assertThat(view.traderId()).isEqualTo(7L);
        assertThat(view.traderName()).isEqualTo("Alpha");
        assertThat(view.question().required()).isTrue();
        verify(facts).prompts(eq(alpha), anyBoolean(), any(), any());
    }

    /** 会话记的 trader 已经删了：页面不再显示它，mode 不再必问，事实按还没有 trader 拼 */
    @Test
    void trader删了按还没有trader处理() {
        modelSaysOk();
        PromptGenSession session = session(7L, askingMode(), null);
        when(store.session(1L)).thenReturn(session);

        PromptGenView view = service.view(1L);
        service.reply(channel(), 1L, model, session, service.replyHistory(1L, session, null, true));

        assertThat(view.traderId()).isNull();
        assertThat(view.question().required()).isFalse();
        verify(facts).prompts(isNull(), anyBoolean(), any(), any());
    }

    /** 开新对话带来的 trader 要归这个人，别人的当没带 */
    @Test
    void 开新对话只认自己名下的trader() {
        modelSaysOk();
        when(traderService.owned(1L, 7L)).thenReturn(alpha());

        service.start(channel(), 1L, model, 3L, 7L, AgentLang.ZH);
        service.start(channel(), 1L, model, 3L, 8L, AgentLang.ZH);

        verify(store).open(eq(1L), eq(3L), eq(7L), eq(AgentLang.ZH), any());
        verify(store).open(eq(1L), eq(3L), isNull(), eq(AgentLang.ZH), any());
    }
}
