package com.mawai.wiibagent.chat.store;

import com.mawai.wiibagent.chat.ChatTestEndpoints;

import com.mawai.wiibcommon.entity.WorkbenchChatMessage;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibagent.llm.SearchEvent;
import com.mawai.wiibagent.mapper.WorkbenchChatMessageMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 读数在展示表上的一进一出。
 * <p>
 * 要钉的是"<b>没有读数</b>"这件事怎么表达：user 行、以及加列之前就存在的老数据，读出来必须是 null
 * 而不是一个字段全空的壳——前端拿到壳就得多判一层"有对象但没数"，那层判断迟早会漏。
 */
class ChatHistoryMetaTest {

    private static final String SESSION = "wb-1-hist";

    private static WorkbenchChatMessage row(long id, String role, String content) {
        WorkbenchChatMessage r = new WorkbenchChatMessage();
        r.setId(id);
        r.setSessionId(SESSION);
        r.setUserId(1L);
        r.setRole(role);
        r.setContent(content);
        r.setCreatedAt(LocalDateTime.of(2026, 8, 18, 14, 32));
        return r;
    }

    @Test
    void 带读数的追加把六列都写下去() {
        WorkbenchChatMessageMapper mapper = mock(WorkbenchChatMessageMapper.class);
        ChatHistoryService service = new ChatHistoryService(mapper, ChatTestEndpoints.PROMPTS, new MessageCatalog());

        service.append(SESSION, 1L, "assistant", "答案",
                new ChatHistoryService.TurnMeta("我的端点 · gpt-5", 3, 160L, 30L, 190L, 4200),
                List.of(new SearchEvent.Source("https://a.com/1", "A1")));

        ArgumentCaptor<WorkbenchChatMessage> captor = ArgumentCaptor.forClass(WorkbenchChatMessage.class);
        verify(mapper).insert(captor.capture());
        WorkbenchChatMessage saved = captor.getValue();
        assertThat(saved.getModelLabel()).isEqualTo("我的端点 · gpt-5");
        assertThat(saved.getModelCalls()).isEqualTo(3);
        assertThat(saved.getPromptTokens()).isEqualTo(160L);
        assertThat(saved.getCompletionTokens()).isEqualTo(30L);
        assertThat(saved.getTotalTokens()).isEqualTo(190L);
        assertThat(saved.getLatencyMs()).isEqualTo(4200);
        // 来源单独一列，JSON 数组 [{url,title}]
        assertThat(saved.getSources()).contains("https://a.com/1").contains("A1");
    }

    @Test
    void 回读时没有读数的行给的是null而不是空壳() {
        WorkbenchChatMessage user = row(1L, "user", "BTC 怎么样");
        WorkbenchChatMessage legacy = row(2L, "assistant", "加列之前落的老答案");
        WorkbenchChatMessage fresh = row(3L, "assistant", "带读数的新答案");
        fresh.setModelLabel("我的端点 · gpt-5");
        fresh.setModelCalls(3);
        fresh.setTotalTokens(190L);
        fresh.setLatencyMs(4200);
        fresh.setSources("[{\"url\":\"https://a.com/1\",\"title\":\"A1\"}]");

        WorkbenchChatMessageMapper mapper = mock(WorkbenchChatMessageMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(user, legacy, fresh));

        List<ChatHistoryService.ChatMessage> messages = new ChatHistoryService(mapper, ChatTestEndpoints.PROMPTS, new MessageCatalog()).messages(SESSION);

        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).id()).isEqualTo(1L);
        assertThat(messages.get(0).meta()).isNull();
        assertThat(messages.get(1).meta()).isNull();
        assertThat(messages.get(2).meta()).isNotNull();
        assertThat(messages.get(2).meta().totalTokens()).isEqualTo(190L);
        // 上游没报 usage 的那两项照样是 null，不能被补成 0
        assertThat(messages.get(2).meta().promptTokens()).isNull();
        // 来源同理：没搜过的行是 null 不是空列表
        assertThat(messages.get(1).sources()).isNull();
        assertThat(messages.get(2).sources()).extracting(SearchEvent.Source::url).containsExactly("https://a.com/1");
    }
}
