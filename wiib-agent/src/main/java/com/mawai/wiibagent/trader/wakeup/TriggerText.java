package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.market.TradeEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 触发的成文：把它叫醒的事一条一句、"上次唤醒 / 距下次例行"那一行、每种触发在单币轮里问什么。
 * 事实全由代码注入。了结的净盈亏和当时的计划在观察包的【自上次唤醒以来】里，这里不再算一遍。
 */
@Component
@RequiredArgsConstructor
class TriggerText {

    private final PromptCatalog prompts;

    /**
     * 开场白的头：逐条报把它叫醒的事，几条一起来的按先后各带自己的时刻；再是"上次唤醒 / 距下次例行"那行
     *
     * @param wakeTime     真正醒来的时刻(ms)，"距下一次例行"按它算
     * @param lastWakeTime 本局上一次交易唤醒的时刻(ms)，null=本局还没醒过
     */
    String head(AiTrader trader, List<WakeTrigger> triggers, long wakeTime, Long lastWakeTime, AgentLang lang) {
        StringBuilder sb = new StringBuilder();
        boolean batch = triggers.size() > 1;
        if (batch) {
            sb.append(prompts.get(lang, "trader.wake.triggerBatch", Map.of("n", triggers.size()))).append('\n');
        }
        for (WakeTrigger t : triggers) {
            sb.append(batch ? "- " : "").append(line(t, lang)).append('\n');
        }
        return sb.append(lastWakeLine(trader, wakeTime, lastWakeTime, lang)).append('\n').toString();
    }

    /** 一条触发的一句话：警报报振幅/方向/现价，事件报币/方向/量/价，盯盘命中报条件与命中价和备注，权益回落报现在多少、最高到过多少、回落百分之几，都带自己的时刻 */
    String line(WakeTrigger t, AgentLang lang) {
        String time = BeijingTime.shortFormat(t.triggeredAt());
        return switch (t) {
            case AlertTrigger a -> prompts.get(lang, "trader.wake.alertHeader", Map.of(
                    "time", time,
                    "symbol", a.symbol(),
                    "amplitude", a.amplitudePct().stripTrailingZeros().toPlainString(),
                    "direction", prompts.get(lang, "trader.wake.direction." + a.direction().toLowerCase(Locale.ROOT)),
                    "price", a.price().stripTrailingZeros().toPlainString()));
            case EventTrigger e -> prompts.get(lang, "trader.wake.eventHeader", Map.of(
                    "time", time,
                    "detail", eventDetail(e.event(), lang)));
            // 盯盘条件的时刻带年份，和它条件里的时刻、账户块的 nowSingapore 同一种写法
            case WatchTrigger w -> prompts.get(lang, "trader.wake.watchHeader", Map.of(
                    "time", BeijingTime.format(w.triggeredAt()),
                    "detail", WatchText.detail(w.watch(), w.price(), prompts, lang),
                    "note", WatchText.note(w.watch(), prompts, lang)));
            case EquityDropTrigger d -> prompts.get(lang, "trader.wake.equityDropHeader", equityDropFacts(d));
        };
    }

    /** 权益回落警报叫醒的那一轮没跑成：留给下一次整盘轮在【自上次唤醒以来】里补报的那一行 */
    String missedEquityDrop(EquityDropTrigger d, AgentLang lang) {
        return prompts.get(lang, "trader.wake.eventEquityDropMissed", equityDropFacts(d)) + '\n';
    }

    private static Map<String, Object> equityDropFacts(EquityDropTrigger d) {
        return Map.of(
                "time", BeijingTime.shortFormat(d.triggeredAt()),
                "equity", WatchText.num(d.equity().setScale(2, RoundingMode.HALF_UP)),
                "peak", WatchText.num(d.peak().setScale(2, RoundingMode.HALF_UP)),
                "peakTime", BeijingTime.shortFormat(d.peakAt()),
                "drop", d.dropPct().stripTrailingZeros().toPlainString(),
                "threshold", d.thresholdPct().stripTrailingZeros().toPlainString());
    }

    /** 整盘轮被触发叫醒时，排在第一段的问题前面多问的话：权益回落警报问一句，别的触发没有，空串 */
    String bookQuestions(List<WakeTrigger> triggers, AgentLang lang) {
        return triggers.stream().anyMatch(EquityDropTrigger.class::isInstance)
                ? prompts.get(lang, "trader.wake.equityDropQuestion") + '\n' : "";
    }

    /** 单币轮的问题：把它叫醒的每种事各问一句，同一种只问一次，按触发的先后 */
    String questions(List<WakeTrigger> triggers, AgentLang lang) {
        Set<String> kinds = new LinkedHashSet<>();
        triggers.forEach(t -> kinds.add(questionKind(t)));
        StringBuilder sb = new StringBuilder();
        for (String kind : kinds) {
            sb.append(prompts.get(lang, "trader.wake.single.question." + kind)).append('\n');
        }
        return sb.toString();
    }

    /**
     * 一轮进行中到来的触发，插进对话的那段：发生了什么、接着要它回答什么、这个币现在的样子；
     * 原本要等到交易段才能对这个币开仓的，说明现在就可以。
     *
     * @param coinState 这个币现在的持仓、挂单和盯盘条件；不归某一个币的触发没有，传 null
     * @param unlocked  这条触发放开了这个币的开仓、加仓、反手
     */
    String interjection(WakeTrigger t, String coinState, boolean unlocked, AgentLang lang) {
        boolean wholeAccount = t instanceof EventTrigger e && TradeEvent.CROSS_LIQUIDATION.equals(e.event().type());
        StringBuilder sb = new StringBuilder(prompts.get(lang, "trader.wake.interject.head")).append(line(t, lang)).append('\n')
                .append(prompts.get(lang, "trader.wake.interject." + (wholeAccount ? "crossLiquidation" : questionKind(t))));
        if (coinState != null) {
            sb.append('\n').append(prompts.get(lang, "trader.wake.interject.state", Map.of("symbol", t.symbol(), "state", coinState)));
        }
        if (unlocked) {
            sb.append('\n').append(prompts.get(lang, "trader.wake.interject.unlock", Map.of("symbol", t.symbol())));
        }
        return sb.toString();
    }

    /** 整盘轮进行中例行到点了，插进对话的那段 */
    String routineDue(AiTrader trader, long boundary, AgentLang lang) {
        return prompts.get(lang, "trader.wake.interject.routine", Map.of(
                "interval", trader.getIntervalCode(), "time", BeijingTime.shortFormat(boundary)));
    }

    /** 这条触发问哪一种问题：盯盘命中、波动警报、限价单成交、仓位没了（止损和强平）、权益回落 */
    private static String questionKind(WakeTrigger t) {
        return switch (t) {
            case AlertTrigger _ -> "alert";
            case WatchTrigger _ -> "watch";
            case EquityDropTrigger _ -> "equityDrop";
            case EventTrigger e -> TradeEvent.LIMIT_FILLED.equals(e.event().type()) ? "limitFilled" : "stopped";
        };
    }

    /** 事件一句话：按类型选文案，币/方向/量/价填进去（全仓爆仓没有币和价，qty 是爆掉的仓位数） */
    private String eventDetail(TradeEvent e, AgentLang lang) {
        String key = switch (e.type()) {
            case TradeEvent.STOP_LOSS -> "stopLoss";
            case TradeEvent.LIQUIDATION -> "liquidation";
            case TradeEvent.CROSS_LIQUIDATION -> "crossLiquidation";
            case TradeEvent.LIMIT_FILLED -> "limitFilled";
            default -> "other";
        };
        return prompts.get(lang, "trader.wake.event." + key, Map.of(
                "type", e.type(),
                "symbol", String.valueOf(e.symbol()),
                "side", String.valueOf(e.side()),
                "qty", WatchText.num(e.quantity()),
                "price", WatchText.num(e.price())));
    }

    /**
     * "你上次唤醒…，距下一次例行唤醒还有约 N 分钟"：非例行开场白都带这一行。
     * 下次例行要看时段：下一边界落在时段外就是更晚的那个（时段末尾被叫醒，下次醒可能是第二天）。
     */
    private String lastWakeLine(AiTrader trader, long triggeredAt, Long lastWakeTime, AgentLang lang) {
        long intervalMs = WakeIntervals.MS.get(trader.getIntervalCode());
        long next = WakeIntervals.align(triggeredAt, intervalMs) + intervalMs;
        WakeWindow window = WakeWindow.of(trader);
        if (window != null) {
            long inWindow = window.nextBoundaryFrom(next, intervalMs);
            if (inWindow > 0) {
                next = inWindow;
            }
        }
        long toNextMin = Math.max(1, (next - triggeredAt) / 60_000);
        String lastWake = lastWakeTime == null ? prompts.get(lang, "trader.wake.alertNoWake")
                : prompts.get(lang, "trader.wake.alertWakeAt", Map.of(
                        "time", BeijingTime.shortFormat(lastWakeTime),
                        "minutes", Math.max(1, (triggeredAt - lastWakeTime) / 60_000)));
        return prompts.get(lang, "trader.wake.alertLastWake", Map.of("lastWake", lastWake, "minutes", toNextMin));
    }
}
