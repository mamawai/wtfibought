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
import com.mawai.wiibcommon.entity.PromptGenSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 一轮的起跑历史：存着的前缀指纹和这一轮的 agent 对不上就按文本重建，对得上照样原样回放 */
class PromptGenServiceTest {

    private final PromptGenStore store = mock(PromptGenStore.class);
    private final PromptGenFacts facts = mock(PromptGenFacts.class);
    private final ChatModel model = mock(ChatModel.class);
    private final PromptGenService service = new PromptGenService(store, facts, mock(LlmEndpointService.class),
            mock(ChatModelFactory.class), mock(TraderService.class), mock(TraderChatService.class),
            mock(PlayStatsAssembler.class), ChatTestEndpoints.TOOLS, ChatTestEndpoints.PROMPTS,
            ChatTestEndpoints.zhLang());
    /** 发给模型的请求 */
    private final List<Prompt> sent = new ArrayList<>();

    @AfterEach
    void stop() {
        service.stop();
    }

    /** 跑一轮 reply：起跑历史里那条 agent 回复带原始内容块；返回这一轮存下的前缀指纹 */
    private String reply(String storedPrefix) {
        List<Message> history = List.of(new UserMessage("开始"),
                AssistantMessage.builder().content("上一轮的话")
                        .properties(Map.of(AnthropicChatModel.BLOCKS_KEY, "[{\"type\":\"thinking\"}]")).build(),
                new UserMessage("回答"));
        PromptGenSession session = new PromptGenSession();
        session.setUserId(1L);
        session.setOutputLang("zh");
        session.setState(ChatContextCodec.write(new ChatContextStore.State(history, null, storedPrefix)));
        SseChannel channel = new SseChannel(new SseEmitter());
        channel.markClosed();

        service.reply(channel, 1L, model, session, history);

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
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.stream(any(Prompt.class))).thenAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("好的")))));
        });
        when(facts.prompts(any(), anyBoolean(), any(), any()))
                .thenReturn(new PromptGenFacts.Prompts("你是指令生成助手", "写稿"));

        String prefix = reply("旧指纹");
        assertThat(replayed().getMetadata()).doesNotContainKey(AnthropicChatModel.BLOCKS_KEY);

        reply(prefix);
        assertThat(replayed().getMetadata()).containsKey(AnthropicChatModel.BLOCKS_KEY);
    }
}
