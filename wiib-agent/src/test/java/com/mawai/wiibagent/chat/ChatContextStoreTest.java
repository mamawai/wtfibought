package com.mawai.wiibagent.chat;

import com.mawai.wiibagent.mapper.WorkbenchChatContextMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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

    /** 生产会存的四种消息形态一个不少：用户原文、带 tool_call 的助手消息、配对回执、压缩摘要 */
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
        store.save(SESSION, 1L, history);

        // 截住落库字节，喂回读取路：验的是"序列化往返"这一整条，不是 mapper 的搬运
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(mapper).upsert(eq(SESSION), eq(1L), bytes.capture());
        when(mapper.selectState(SESSION)).thenReturn(List.of(bytes.getValue()));

        List<Message> loaded = store.load(SESSION);

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
        // 摘要是 SystemMessage 且前缀完整：ConversationSummarizer 靠这个前缀认出老摘要不重压
        assertThat(loaded.get(1)).isInstanceOf(SystemMessage.class);
        assertThat(loaded.get(1).getText()).startsWith("## 早前对话摘要：");
    }

    @Test
    void 新会话无行返回空历史() {
        when(mapper.selectState(SESSION)).thenReturn(List.of());

        assertThat(store.load(SESSION)).isEmpty();
    }

    @Test
    void 读库失败降级为空历史不炸对话() {
        when(mapper.selectState(SESSION)).thenThrow(new RuntimeException("db down"));

        assertThat(store.load(SESSION)).isEmpty();
    }

    @Test
    void 坏字节降级为空历史不炸对话() {
        when(mapper.selectState(SESSION)).thenReturn(List.of(new byte[]{1, 2, 3}));

        assertThat(store.load(SESSION)).isEmpty();
    }

    @Test
    void 落库失败不往上抛() {
        when(mapper.upsert(anyString(), anyLong(), any()))
                .thenThrow(new RuntimeException("db down"));

        // 调用点在答案已流给用户之后，抛上去只会把成功的回答标成失败
        assertThatCode(() -> store.save(SESSION, 1L, fullShapedHistory()))
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
