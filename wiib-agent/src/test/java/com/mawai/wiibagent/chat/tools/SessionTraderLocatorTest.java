package com.mawai.wiibagent.chat.tools;

import com.mawai.wiibagent.chat.gate.TraderPickRegistry;
import com.mawai.wiibagent.chat.tools.TraderLocator.Located;
import com.mawai.wiibagent.trader.TraderChatService;
import com.mawai.wiibagent.trader.TraderService;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.enums.AgentLang;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话里查询工具定位 trader 的规则：0 个、1 个、点名命中、点名不中、记住当前、弹卡。
 * 弹卡本身怎么等、怎么收场归 {@code TraderPickRegistryTest}。
 */
class SessionTraderLocatorTest {

    private static final long ME = 1L;
    private static final String SESSION = "wb-1-x";
    private static final ToolContext CTX = new ToolContext(Map.of(ToolRunContext.SESSION_KEY, SESSION));

    private final TraderService traderService = mock(TraderService.class);
    private final TraderChatService chat = mock(TraderChatService.class);
    private final TraderPickRegistry picks = mock(TraderPickRegistry.class);
    private final TraderLocator locator = new SessionTraderLocator(traderService, chat, picks).of(ME, AgentLang.ZH);

    private final AiTrader alpha = trader(7L, "Alpha");
    private final AiTrader beta = trader(8L, "Beta");

    private static AiTrader trader(long id, String name) {
        AiTrader t = new AiTrader();
        t.setId(id);
        t.setUserId(ME);
        t.setName(name);
        t.setStatus(AiTrader.STATUS_RUNNING);
        return t;
    }

    /** 一个都没有：回"还没有创建"，不弹卡 */
    @Test
    void 一个都没有() {
        when(traderService.listMine(ME)).thenReturn(List.of());
        when(chat.noTrader(AgentLang.ZH)).thenReturn("还没有");

        Located at = locator.locate(null, CTX);

        assertThat(at.trader()).isNull();
        assertThat(at.reply()).isEqualTo("还没有");
        verify(picks, never()).pick(anyString(), any());
    }

    /** 只有一个：直接用，不看名字、不记、不弹卡——和多开之前一样 */
    @Test
    void 只有一个直接用() {
        when(traderService.listMine(ME)).thenReturn(List.of(alpha));

        assertThat(locator.locate(null, CTX).trader()).isSameAs(alpha);
        verify(picks, never()).remember(anyString(), anyLong());
        verify(picks, never()).pick(anyString(), any());
    }

    /** 名字去掉首尾空格、不分大小写；名单只取这个用户名下的；点中了记成本会话的当前 trader */
    @Test
    void 点名命中记成当前() {
        when(traderService.listMine(ME)).thenReturn(List.of(alpha, beta));

        assertThat(locator.locate("  beta ", CTX).trader()).isSameAs(beta);
        verify(picks).remember(SESSION, 8L);
        verify(picks, never()).pick(anyString(), any());
    }

    /** 点名不中：把名下名单交回去让模型改口，不记、不弹卡 */
    @Test
    void 点名不中回名单() {
        List<AiTrader> mine = List.of(alpha, beta);
        when(traderService.listMine(ME)).thenReturn(mine);
        when(chat.unknownName("Gamma", mine, AgentLang.ZH)).thenReturn("没有 Gamma");

        Located at = locator.locate("Gamma", CTX);

        assertThat(at.reply()).isEqualTo("没有 Gamma");
        verify(picks, never()).remember(anyString(), anyLong());
        verify(picks, never()).pick(anyString(), any());
    }

    /** 没点名、本会话记着一个且它还在：直接用，不弹卡 */
    @Test
    void 记住的当前trader直接用() {
        when(traderService.listMine(ME)).thenReturn(List.of(alpha, beta));
        when(picks.current(SESSION)).thenReturn(8L);

        assertThat(locator.locate(null, CTX).trader()).isSameAs(beta);
        verify(picks, never()).pick(anyString(), any());
    }

    /** 记着的已经不在了（删了）：弹卡，候选是名下每一个的 id/名字/状态/本局收益率；用户没选就回没选那句 */
    @Test
    void 定不下来就弹卡() {
        when(traderService.listMine(ME)).thenReturn(List.of(alpha, beta));
        when(picks.current(SESSION)).thenReturn(99L);
        when(traderService.latestEquity(any())).thenReturn(new BigDecimal("10150"));
        List<TraderPickRegistry.Candidate> candidates = List.of(
                new TraderPickRegistry.Candidate(7L, "Alpha", AiTrader.STATUS_RUNNING, new BigDecimal("1.50")),
                new TraderPickRegistry.Candidate(8L, "Beta", AiTrader.STATUS_RUNNING, new BigDecimal("1.50")));
        when(picks.pick(SESSION, candidates)).thenReturn(alpha, (AiTrader) null);
        when(chat.notPicked(AgentLang.ZH)).thenReturn("没选");

        assertThat(locator.locate(null, CTX).trader()).isSameAs(alpha);
        assertThat(locator.locate(null, CTX).reply()).isEqualTo("没选");
    }
}
