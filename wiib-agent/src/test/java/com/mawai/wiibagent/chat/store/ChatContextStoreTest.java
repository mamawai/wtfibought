package com.mawai.wiibagent.chat.store;

import com.mawai.wiibagent.mapper.WorkbenchChatContextMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.io.UncheckedIOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话上下文的存取往返。编解码用真的（{@link ChatContextCodec}，生产同款）——
 * 这张表的全部价值就是"Message 多态与 tool_call 配对往返无损"，mock 序列化器等于什么都没测。
 */
class ChatContextStoreTest {

    private static final String SESSION = "wb-1-ctx";

    private final WorkbenchChatContextMapper mapper = mock(WorkbenchChatContextMapper.class);
    private final ChatContextStore store = new ChatContextStore(mapper);

    /** 库里会有的四种消息形态一个不少：用户原文、带 tool_call 的助手消息、配对回执、老会话的摘要 */
    private static List<Message> fullShapedHistory() {
        return List.of(
                new UserMessage("BTC 现在怎么样"),
                new SystemMessage("## 早前对话摘要：\n── 第1段 ──\n用户长期关注 BTC"),
                AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1", "function", "market_snapshot", "{\"symbol\":\"BTCUSDT\"}")))
                        .build(),
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("call-1", "market_snapshot",
                                "{\"available\":true,\"price\":97000}")))
                        .build(),
                new AssistantMessage("[market_agent] 当前价格 97000"));
    }

    @Test
    void 存进去的历史读出来配对与内容无损() {
        List<Message> history = fullShapedHistory();
        store.save(SESSION, 1L, new ChatContextStore.State(history, 123_456L, "fp"));

        // 截住落库字节，喂回读取路：验的是"序列化往返"这一整条，不是 mapper 的搬运
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(mapper).upsert(eq(SESSION), eq(1L), bytes.capture());
        when(mapper.selectState(SESSION)).thenReturn(List.of(bytes.getValue()));

        ChatContextStore.State state = store.load(SESSION);
        List<Message> loaded = state.messages();

        // 用量读数和前缀指纹跟着消息一起回来
        assertThat(state.contextTokens()).isEqualTo(123_456L);
        assertThat(state.prefix()).isEqualTo("fp");
        assertThat(loaded).hasSize(history.size());
        assertThat(loaded.getFirst().getText()).isEqualTo("BTC 现在怎么样");
        // tool_call 与回执的 id 配对必须原样活着：断一半上游 API 直接 400
        AssistantMessage toolCall = (AssistantMessage) loaded.get(2);
        assertThat(toolCall.getToolCalls()).hasSize(1)
                .first().satisfies(c -> {
                    assertThat(c.id()).isEqualTo("call-1");
                    assertThat(c.arguments()).contains("BTCUSDT");
                });
        ToolResponseMessage response = (ToolResponseMessage) loaded.get(3);
        assertThat(response.getResponses()).hasSize(1)
                .first().satisfies(r -> {
                    assertThat(r.id()).isEqualTo("call-1");
                    assertThat(r.responseData()).contains("97000");
                });
        // 老会话的摘要是 SystemMessage：类型和正文都得原样回来，ChatTurnRewinder 按类型认老形状
        assertThat(loaded.get(1)).isInstanceOf(SystemMessage.class);
        assertThat(loaded.get(1).getText()).startsWith("## 早前对话摘要：");
    }

    @Test
    void 新会话无行返回空上下文() {
        when(mapper.selectState(SESSION)).thenReturn(List.of());

        assertThat(store.load(SESSION)).isEqualTo(ChatContextStore.State.EMPTY);
    }

    /** 读不出来就往上抛：当成空历史跑完一轮，会拿这一轮覆盖掉整段上下文 */
    @Test
    void 读库失败往上抛() {
        when(mapper.selectState(SESSION)).thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> store.load(SESSION)).hasMessage("db down");
    }

    /** 老格式的对象流壳读到一半断了 */
    @Test
    void 解不开往上抛() {
        when(mapper.selectState(SESSION)).thenReturn(List.of(new byte[]{(byte) 0xAC, (byte) 0xED, 0, 5}));

        assertThatThrownBy(() -> store.load(SESSION)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void 落库失败不往上抛() {
        when(mapper.upsert(anyString(), anyLong(), any()))
                .thenThrow(new RuntimeException("db down"));

        // 调用点在答案已流给用户之后，抛上去只会把成功的回答标成失败
        assertThatCode(() -> store.save(SESSION, 1L, new ChatContextStore.State(fullShapedHistory(), null, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void 删除委托到位且失败不炸删会话() {
        store.purge(SESSION);
        verify(mapper).deleteBySessionId(SESSION);

        when(mapper.deleteBySessionId(SESSION)).thenThrow(new RuntimeException("db down"));
        assertThatCode(() -> store.purge(SESSION)).doesNotThrowAnyException();
    }
}
