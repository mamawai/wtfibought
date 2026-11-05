package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibcommon.enums.AgentLang;

import java.math.BigDecimal;
import java.util.Map;

/** 一条盯盘条件命中的一句话：币、类型、价位 / 周期 / 时刻、命中价。命中轮首行、【自上次唤醒以来】、复盘的盯盘布防块共用 */
public final class WatchText {

    private WatchText() {
    }

    public static String detail(AiTraderWatch w, BigDecimal price, PromptCatalog prompts, AgentLang lang) {
        String key = switch (w.getKind()) {
            case AiTraderWatch.TOUCH_ABOVE -> "touchAbove";
            case AiTraderWatch.TOUCH_BELOW -> "touchBelow";
            case AiTraderWatch.CLOSE_ABOVE -> "closeAbove";
            case AiTraderWatch.CLOSE_BELOW -> "closeBelow";
            default -> "atTime";
        };
        return prompts.get(lang, "trader.wake.watch." + key, Map.of(
                "symbol", w.getSymbol(),
                "level", num(w.getLevel()),
                "period", String.valueOf(w.getPeriod()),
                "at", w.getAtTime() == null ? "" : BeijingTime.format(w.getAtTime()),
                "price", num(price)));
    }

    /** 价、量的写法：去掉尾零，没有就问号 */
    static String num(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }
}
