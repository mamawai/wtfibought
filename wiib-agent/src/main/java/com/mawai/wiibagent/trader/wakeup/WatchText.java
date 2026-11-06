package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibcommon.enums.AgentLang;

import java.math.BigDecimal;
import java.util.Map;

/** 盯盘条件的成文：命中的一句话（币、类型、价位 / 周期 / 时刻、命中价）和还在盯的条件本身。命中轮首行、【自上次唤醒以来】、承诺块、复盘的盯盘布防块共用 */
public final class WatchText {

    private WatchText() {
    }

    /** 一条命中：币、类型、价位 / 周期 / 时刻、命中价 */
    public static String detail(AiTraderWatch w, BigDecimal price, PromptCatalog prompts, AgentLang lang) {
        return prompts.get(lang, "trader.wake.watch." + key(w.getKind()), Map.of(
                "symbol", w.getSymbol(),
                "level", num(w.getLevel()),
                "period", String.valueOf(w.getPeriod()),
                "at", w.getAtTime() == null ? "" : BeijingTime.format(w.getAtTime()),
                "price", num(price)));
    }

    /** 一条还在盯的价格类条件本身：触及 X 以上 / 1h 收盘站上 X，承诺块用；AT_TIME 另有写法不走这儿 */
    public static String armed(AiTraderWatch w, PromptCatalog prompts, AgentLang lang) {
        return prompts.get(lang, "trader.wake.armed." + key(w.getKind()), Map.of(
                "level", num(w.getLevel()),
                "period", String.valueOf(w.getPeriod())));
    }

    /** 条件类型对应的词表 key 后半截，两种成文共用一份 */
    private static String key(String kind) {
        return switch (kind) {
            case AiTraderWatch.TOUCH_ABOVE -> "touchAbove";
            case AiTraderWatch.TOUCH_BELOW -> "touchBelow";
            case AiTraderWatch.CLOSE_ABOVE -> "closeAbove";
            case AiTraderWatch.CLOSE_BELOW -> "closeBelow";
            default -> "atTime";
        };
    }

    /** 价、量的写法：去掉尾零，没有就问号 */
    static String num(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }
}
