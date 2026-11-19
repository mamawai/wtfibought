package com.mawai.wiibagent.chat.tools;

import com.mawai.wiibagent.chat.gate.TraderPickRegistry;
import com.mawai.wiibagent.chat.tools.TraderLocator.Located;
import com.mawai.wiibagent.trader.TraderChatService;
import com.mawai.wiibagent.trader.TraderService;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.enums.AgentLang;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 对话里查询工具定位 trader 的规则，按顺序：
 * <ol start="0">
 *   <li>名下一个都没有：回"还没有创建"；</li>
 *   <li>只有一个：直接用；</li>
 *   <li>点了名：按名字找（去掉首尾空格、不分大小写），找到就用并记成本会话的当前 trader，找不到把名单回给模型；</li>
 *   <li>本会话记着一个、它还在：直接用；</li>
 *   <li>都不满足：弹选择卡原地等用户选（{@link TraderPickRegistry#pick}）。</li>
 * </ol>
 * 名字只在这个用户名下找，userId 是建叶子时烤进来的（理由见 {@link TraderQueryToolkit}）。
 */
@Component
@RequiredArgsConstructor
public class SessionTraderLocator {

    private final TraderService traderService;
    private final TraderChatService traderChatService;
    private final TraderPickRegistry picks;

    /** 这个用户、这门语言的定位办法，建叶子时烤进查询工具 */
    public TraderLocator of(long userId, AgentLang lang) {
        return (name, context) -> locate(userId, lang, name, ToolRunContext.sessionId(context));
    }

    private Located locate(long userId, AgentLang lang, String name, String sessionId) {
        List<AiTrader> mine = traderService.listMine(userId);
        if (mine.isEmpty()) {
            return Located.miss(traderChatService.noTrader(lang));
        }
        if (mine.size() == 1) {
            return Located.of(mine.getFirst());
        }
        if (name != null && !name.isBlank()) {
            Optional<AiTrader> named = mine.stream().filter(t -> TraderLocator.sameName(t, name)).findFirst();
            if (named.isEmpty()) {
                return Located.miss(traderChatService.unknownName(name.trim(), mine, lang));
            }
            picks.remember(sessionId, named.get().getId());
            return Located.of(named.get());
        }
        Long current = picks.current(sessionId);
        Optional<AiTrader> remembered = mine.stream().filter(t -> t.getId().equals(current)).findFirst();
        if (remembered.isPresent()) {
            return Located.of(remembered.get());
        }
        AiTrader chosen = picks.pick(sessionId, mine.stream()
                .map(t -> new TraderPickRegistry.Candidate(t.getId(), t.getName(), t.getStatus(),
                        TraderService.pnlPct(traderService.latestEquity(t))))
                .toList());
        return chosen == null ? Located.miss(traderChatService.notPicked(lang)) : Located.of(chosen);
    }
}
