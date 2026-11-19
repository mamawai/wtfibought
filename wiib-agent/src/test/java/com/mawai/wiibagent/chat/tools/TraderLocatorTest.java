package com.mawai.wiibagent.chat.tools;

import com.mawai.wiibagent.trader.TraderChatService;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.enums.AgentLang;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 指令生成器里的固定定位：只认会话记的那一个 trader */
class TraderLocatorTest {

    private static final ToolContext CTX = new ToolContext(Map.of());

    private final TraderChatService chat = mock(TraderChatService.class);

    /** 没点名、或点的就是它（首尾空格、大小写不论）照常查；点了别的名字回"只能查它"；它不在了回还没有 trader */
    @Test
    void 固定定位只认这一个() {
        AiTrader alpha = new AiTrader();
        alpha.setId(7L);
        alpha.setName("Alpha");
        when(chat.onlyThis(alpha, AgentLang.ZH)).thenReturn("只能查 Alpha");
        when(chat.noTrader(AgentLang.ZH)).thenReturn("还没有");
        TraderLocator fixed = TraderLocator.fixed(alpha, chat, AgentLang.ZH);

        assertThat(fixed.locate(null, CTX).trader()).isSameAs(alpha);
        assertThat(fixed.locate(" alpha ", CTX).trader()).isSameAs(alpha);
        assertThat(fixed.locate("Beta", CTX).reply()).isEqualTo("只能查 Alpha");
        assertThat(TraderLocator.fixed(null, chat, AgentLang.ZH).locate("Beta", CTX).reply()).isEqualTo("还没有");
    }
}
