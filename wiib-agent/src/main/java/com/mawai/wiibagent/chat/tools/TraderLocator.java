package com.mawai.wiibagent.chat.tools;

import com.mawai.wiibagent.trader.TraderChatService;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.enums.AgentLang;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 查询工具怎么定位 trader：对话里按会话规则走（{@link SessionTraderLocator}），指令生成器里固定一个（{@link #fixed}）。
 */
@FunctionalInterface
public interface TraderLocator {

    /** 定到了 trader 非空；定不到时 reply 是原样回给模型的那句（还没有 trader、点名不中、没选、只能查别的） */
    record Located(AiTrader trader, String reply) {

        public static Located of(AiTrader trader) {
            return new Located(trader, null);
        }

        public static Located miss(String reply) {
            return new Located(null, reply);
        }
    }

    /** @param name 模型点的名字，没点给 null */
    Located locate(String name, ToolContext context);

    /** 名字对不对得上：去掉首尾空格、不分大小写（名下不许重名，按的也是这个口径） */
    static boolean sameName(AiTrader trader, String name) {
        return trader.getName().equalsIgnoreCase(name.trim());
    }

    /**
     * 固定用这一个：没点名、或点的就是它，照常查；点了别的名字回"这里只能查它"。
     * trader 为空（还没有、或已经删了）一律回还没有 trader
     */
    static TraderLocator fixed(AiTrader trader, TraderChatService chat, AgentLang lang) {
        return (name, context) -> {
            if (trader == null) {
                return Located.miss(chat.noTrader(lang));
            }
            return name == null || name.isBlank() || sameName(trader, name)
                    ? Located.of(trader) : Located.miss(chat.onlyThis(trader, lang));
        };
    }
}
