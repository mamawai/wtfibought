package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibcommon.enums.AgentLang;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 盯盘条件的成文：命中的一句话（币、类型、价位 / 周期 / 时刻、命中价）、还在盯的条件本身、带的预案动作和它的执行结果。
 * 命中轮首行、【自上次唤醒以来】、承诺块、复盘的盯盘布防块共用
 */
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

    /** 条件带的预案动作一句话（命中后平台会做什么、做完叫不叫醒）；没带动作返回空串。承诺块用 */
    public static String action(AiTraderWatch w, PromptCatalog prompts, AgentLang lang) {
        if (w.getActionJson() == null) {
            return "";
        }
        JsonNode a = MAPPER.readTree(w.getActionJson());
        String key = switch (a.path("type").asString()) {
            case AiTraderWatch.ACTION_MOVE_STOP -> "moveStop";
            case AiTraderWatch.ACTION_CLOSE -> "close";
            default -> "cancelOrder";
        };
        return prompts.get(lang, "trader.wake.preset." + key, Map.of(
                "symbol", a.path("symbol").asString(),
                "side", a.path("side").asString(),
                "positionId", a.path("positionId").asString(""),
                "orderId", a.path("orderId").asString(""),
                "stop", num(a.path("stopLossPrice").asDecimal(null)),
                "percent", num(a.path("closePercent").asDecimal(null))));
    }

    /** 命中后念给模型的备注；预案执行过的，后面跟上做成了什么或者为什么没做成 */
    public static String note(AiTraderWatch w, PromptCatalog prompts, AgentLang lang) {
        if (w.getActionOk() == null) {
            return w.getNote();
        }
        return prompts.get(lang, w.getActionOk() ? "trader.wake.preset.done" : "trader.wake.preset.failed",
                Map.of("note", w.getNote(), "result", w.getActionResult()));
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
