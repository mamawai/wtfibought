package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibagent.trader.prompt.EconCalendarAssembler;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.market.TradeEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 一轮唤醒怎么走，和每一段的开场白怎么写。
 * <p>
 * 三种走法：单段（一段问完）、两段（先处置自己留下的事，再布防收尾）、联网三段（核查事实 → 要不要搜 → 交易与收尾）。
 * 一轮开头用 {@link #choose} 选定一种，之后循环和交易工具都只问那一个 {@link Flow}。
 * 加一种走法就是加一个 {@link Flow} 的实现，再在 {@link #choose} 里挑它。
 */
@Component
@RequiredArgsConstructor
class WakeFlows {

    private final PromptCatalog prompts;
    private final WakeObservation wakeObservation;

    /** 墙钟注入点：休眠提示的小时数、后面几段的时刻和快照按它算 */
    LongSupplier nowMs = System::currentTimeMillis;

    /** 一轮的走法 */
    interface Flow {

        /** 开场白 */
        String opening();

        /**
         * 模型纯文本答完一段时问一次：返回下一段的开场白，空就结束这一轮。
         *
         * @param budgetExhausted 模型调用只剩最后一次或已到上限：下一段只能收尾，再要工具只会拿到未执行占位
         * @param account         现取一份账户快照，交易段要用
         */
        Optional<UserMessage> next(boolean budgetExhausted, Supplier<String> account);

        /** 现在能不能开仓、加仓、反手 */
        default boolean openingAllowed() {
            return true;
        }

        /** 决策正文是只取最后一段，还是各段都留 */
        default boolean finalStageOnly() {
            return false;
        }

        /** 模型每答一条知会一声 */
        default void replied(AssistantMessage reply) {
        }
    }

    /**
     * 一轮开头备好的素材，走法和成文都从这里取。
     *
     * @param triggers  把它叫醒的触发，空＝例行或手动轮
     * @param pack      第一段的观察包
     * @param calendar  财经日历块，null＝整块缺席
     * @param ownerNote 主人留言段，空串＝没有
     * @param quotes    开场那一刻拉的各币标记价和资金费率
     */
    record Brief(AiTrader trader, Set<String> whitelist, AgentLang lang, long wakeTime, List<WakeTrigger> triggers,
                 List<WakeObservation.RecentWake> recent, WakeObservation.Pack pack, String calendar, String ownerNote,
                 Map<String, JsonNode> quotes) {

        boolean triggered() {
            return !triggers.isEmpty();
        }
    }

    /** 选走法：开了联网走三段；没开的，有触发、留言或承诺要处置就分两段，其余一段问完 */
    Flow choose(Brief brief, boolean webSearch) {
        if (webSearch) {
            return new Search(brief);
        }
        return brief.triggered() || !brief.ownerNote().isEmpty() || brief.pack().busy()
                ? new TwoStage(brief) : new Single(brief);
    }

    // ========== 三种走法 ==========

    /** 单段：一段问完 */
    @RequiredArgsConstructor
    private final class Single implements Flow {
        private final Brief b;

        @Override
        public String opening() {
            return routineInstruction(b.trader(), b.wakeTime(), b.pack().text(), symbolsSummary(b), history(b),
                    openingSnapshot(b), b.calendar(), b.lang());
        }

        @Override
        public Optional<UserMessage> next(boolean budgetExhausted, Supplier<String> account) {
            return Optional.empty();
        }
    }

    /** 两段：第一段处置自己留下的事，第二段布防与收尾。非例行轮的日历放第一段，例行轮的放第二段 */
    @RequiredArgsConstructor
    private final class TwoStage implements Flow {
        private final Brief b;
        private boolean secondSent;

        @Override
        public String opening() {
            return stageOneInstruction(b.trader(), b.triggers(), b.wakeTime(), lastRealWake(b.recent()), b.pack().text(),
                    b.triggered() ? b.calendar() : null, b.ownerNote(), b.lang(), false);
        }

        /** 第二段只发一次。整盘事实到追加那一刻才算：第一段可能跑了几分钟，标记价重拉一次，不拿开场时那份 */
        @Override
        public Optional<UserMessage> next(boolean budgetExhausted, Supplier<String> account) {
            if (secondSent) {
                return Optional.empty();
            }
            secondSent = true;
            return Optional.of(new UserMessage(stageTwoInstruction(b.trader(), b.wakeTime(), symbolsSummary(b), history(b),
                    freshSnapshot(b), b.triggered() ? null : b.calendar(), b.lang(), b.triggered(), budgetExhausted)));
        }
    }

    private enum SearchStage { FACTS, SEARCH, DECISION, FINISH }

    /** 联网三段：先给齐事实核查，再问要不要搜，最后交易与收尾。到交易段才许开仓；决策正文只留最后一段，搜索过程留在现场 */
    @RequiredArgsConstructor
    private final class Search implements Flow {
        private final Brief b;
        private SearchStage stage = SearchStage.FACTS;
        private boolean lastReplyUsedTools;

        /** 判断要不要搜所需的事实开场就给齐，日历与历史只放这一次 */
        @Override
        public String opening() {
            long intervalMs = WakeIntervals.MS.get(b.trader().getIntervalCode());
            String openingFacts = facts(b.trader(), WakeIntervals.align(b.wakeTime(), intervalMs), intervalMs,
                    symbolsSummary(b), history(b), openingSnapshot(b), b.calendar(), b.lang());
            return stageOneInstruction(b.trader(), b.triggers(), b.wakeTime(), lastRealWake(b.recent()),
                    b.pack().text() + openingFacts, null, b.ownerNote(), b.lang(), true);
        }

        @Override
        public Optional<UserMessage> next(boolean budgetExhausted, Supplier<String> account) {
            if (stage == SearchStage.FINISH || (stage == SearchStage.DECISION && !lastReplyUsedTools)) {
                return Optional.empty();
            }
            if (budgetExhausted) {
                String skipped = stage == SearchStage.FACTS
                        ? prompts.get(b.lang(), "trader.wake.searchSkipped") + '\n' : "";
                stage = SearchStage.FINISH;
                return Optional.of(new UserMessage(skipped + prompts.get(b.lang(), "trader.wake.searchClosing",
                        Map.of("mark", prompts.get(b.lang(), "trader.mark.conclusion")))));
            }
            if (stage == SearchStage.FACTS) {
                stage = SearchStage.SEARCH;
                return Optional.of(new UserMessage(prompts.get(b.lang(), "trader.wake.searchQuestion")));
            }
            String instruction = tradingInstruction(b, account.get());
            stage = SearchStage.DECISION;
            return Optional.of(new UserMessage(instruction));
        }

        @Override
        public boolean openingAllowed() {
            return stage == SearchStage.DECISION;
        }

        @Override
        public boolean finalStageOnly() {
            return true;
        }

        @Override
        public void replied(AssistantMessage reply) {
            lastReplyUsedTools = reply.hasToolCalls();
        }
    }

    // ========== 走法取素材 ==========

    private String symbolsSummary(Brief b) {
        return wakeObservation.symbolsSummary(b.trader(), b.whitelist(), b.pack().lastOkWakeMs(), b.wakeTime(), b.lang());
    }

    private String history(Brief b) {
        return wakeObservation.history(b.trader(), b.recent(), b.lang());
    }

    /** 开场那一刻拉的报价出的快照 */
    private String openingSnapshot(Brief b) {
        return wakeObservation.snapshot(b.quotes(), b.whitelist(), nowMs.getAsLong(), b.lang());
    }

    /** 重拉一次标记价出的快照 */
    private String freshSnapshot(Brief b) {
        return wakeObservation.snapshot(wakeObservation.quotes(WakeObservation.board(b.whitelist())), b.whitelist(),
                nowMs.getAsLong(), b.lang());
    }

    /** 上次真跑过的唤醒时刻：SKIPPED 行（上轮未完跳过、触发过晚）没跑模型，不算醒过 */
    private static Long lastRealWake(List<WakeObservation.RecentWake> recent) {
        for (WakeObservation.RecentWake w : recent) {
            if (!AiTraderDecision.STATUS_SKIPPED.equals(w.status())) {
                return w.wakeTime();
            }
        }
        return null;
    }

    // ========== 成文 ==========

    /**
     * 单段开场白（没有承诺要处置、没留言、也不是非例行唤醒）：头 + 观察包 + 整盘事实 + 本轮任务。
     * <p>
     * 收尾标记取 {@code trader.mark.conclusion}，与系统提示词同一条 key——两处必须同源。
     * <p>
     * 包私有非 private：标记同源那条钉子（{@code WakeInstructionI18nTest}）要拿它的成文比对。
     *
     * @param observation 第一段观察包（{@link WakeObservation#assemble}），空串=不带。紧跟头部事实
     * @param symbols     各币摘要块（{@link WakeObservation#symbolsSummary}），空串=不带
     * @param history     最近轨迹 + 论点战绩（{@link WakeObservation#history}），空串=不带
     * @param calendar    财经日历块（{@link EconCalendarAssembler}），null=整块缺席。
     *                    与快照同属事实区，排在快照之后、休眠提示与本轮任务之前
     */
    String routineInstruction(AiTrader trader, long wakeTime, String observation, String symbols, String history,
                              String snapshot, String calendar, AgentLang lang) {
        long intervalMs = WakeIntervals.MS.get(trader.getIntervalCode());
        // 手动唤醒的 wakeTime 是按下按钮那一刻，"已收盘的那根"和休眠提示要按对齐边界说；例行轮本来就对齐
        long boundaryTime = WakeIntervals.align(wakeTime, intervalMs);
        return routineHeader(trader, boundaryTime, lang)
                + observation
                + facts(trader, boundaryTime, intervalMs, symbols, history, snapshot, calendar, lang)
                + prompts.get(lang, "trader.wake.routineQuestion",
                        Map.of("mark", prompts.get(lang, "trader.mark.conclusion")));
    }

    /**
     * 第一段：唤醒原因、观察包和本段任务。联网轮核查事实与必要减险，未联网轮处置已有承诺。
     * 留言只给例行或手动轮，放在本段末尾；非例行唤醒带触发记录和当前 K 线未收盘提示。
     *
     * @param triggers  把它叫醒的触发，空＝例行或手动轮
     * @param calendar  未联网的非例行轮在此追加日历；联网轮日历已在 observation 中
     * @param webSearch 联网轮第一段只核查事实和必要减险
     */
    String stageOneInstruction(AiTrader trader, List<WakeTrigger> triggers, long wakeTime, Long lastWakeTime,
                               String observation, String calendar, String ownerNote, AgentLang lang, boolean webSearch) {
        String mark = prompts.get(lang, "trader.mark.conclusion");
        if (triggers.isEmpty()) {
            long intervalMs = WakeIntervals.MS.get(trader.getIntervalCode());
            return routineHeader(trader, WakeIntervals.align(wakeTime, intervalMs), lang)
                    + observation
                    + (webSearch ? prompts.get(lang, "trader.wake.routineScope") + '\n' : "")
                    + prompts.get(lang, webSearch ? "trader.wake.factsQuestion" : "trader.wake.stageQuestion",
                            Map.of("mark", mark))
                    + ownerNote;
        }
        return triggerHead(trader, triggers, wakeTime, lastWakeTime, lang)
                + observation
                + (calendar == null ? "" : calendar)
                + prompts.get(lang, "trader.wake.triggerNotice", Map.of("interval", trader.getIntervalCode())) + '\n'
                + (webSearch ? prompts.get(lang, "trader.wake.triggeredScope") + '\n' : "")
                + prompts.get(lang, webSearch ? "trader.wake.factsQuestion" : "trader.wake.triggerQuestion",
                        Map.of("mark", mark));
    }

    /**
     * 第二段「布防与收尾」开场白：头一句 + 整盘事实 + 第二段的问题。
     * 例行轮问整盘；非例行轮（triggered）只给被叫醒的那件事涉及的币收尾，整盘留给例行轮。
     * 预算在第一段用尽的（exhausted）换成只许收尾的问题：工具一个都调不了，set_watch 也不行
     */
    String stageTwoInstruction(AiTrader trader, long wakeTime, String symbols, String history, String snapshot,
                               String calendar, AgentLang lang, boolean triggered, boolean exhausted) {
        long intervalMs = WakeIntervals.MS.get(trader.getIntervalCode());
        String question = exhausted ? "trader.wake.stageTwoExhausted"
                : triggered ? "trader.wake.stageTwoTriggered" : "trader.wake.stageTwoQuestion";
        return prompts.get(lang, "trader.wake.stageTwoHeader") + '\n'
                + facts(trader, WakeIntervals.align(wakeTime, intervalMs), intervalMs, symbols, history, snapshot, calendar, lang)
                + prompts.get(lang, question, Map.of("mark", prompts.get(lang, "trader.mark.conclusion")));
    }

    /** 联网轮的交易段：只刷新账户和价格，计划对账仍只在唤醒开头做 */
    private String tradingInstruction(Brief b, String account) {
        AgentLang lang = b.lang();
        String snapshot = freshSnapshot(b);
        return prompts.get(lang, "trader.wake.tradingHeader",
                Map.of("time", BeijingTime.shortFormat(nowMs.getAsLong()))) + '\n'
                + account + '\n'
                + (snapshot.isEmpty() ? "" : prompts.get(lang, "trader.wake.snapshotHeader") + '\n' + snapshot)
                + prompts.get(lang, b.triggered() ? "trader.wake.triggeredScope" : "trader.wake.routineScope") + '\n'
                + prompts.get(lang, "trader.wake.tradingQuestion",
                        Map.of("mark", prompts.get(lang, "trader.mark.conclusion")));
    }

    /** 整盘事实区：各币摘要 → 历史 → 快照 → 日历 → 休眠提示 */
    private String facts(AiTrader trader, long boundaryTime, long intervalMs, String symbols, String history,
                         String snapshot, String calendar, AgentLang lang) {
        return symbols
                + history
                + (snapshot.isEmpty() ? "" : "\n" + prompts.get(lang, "trader.wake.snapshotHeader") + "\n" + snapshot)
                + (calendar == null ? "" : "\n" + calendar)
                // 休眠提示放快照之后（事实区）、问题之前：不让"要睡了"成为模型读到的第一件事
                + sleepNotice(prompts, lang, WakeWindow.of(trader), boundaryTime, intervalMs, nowMs.getAsLong());
    }

    private String routineHeader(AiTrader trader, long boundaryTime, AgentLang lang) {
        return prompts.get(lang, "trader.wake.routineHeader", Map.of(
                "interval", trader.getIntervalCode(),
                "time", BeijingTime.shortFormat(boundaryTime)));
    }

    /**
     * 时段内末次唤醒的休眠提示：只给事实（例行唤醒到哪根为止、下次何时醒、期间止损止盈照常），并明说休眠本身
     * 不是任何方向动作的理由——"13 小时看不见"既诱导睡前减仓/收紧止损，也诱导"赶在休眠前多开一笔"，
     * 与警报开场白治的是同一种病（被事件驱动的非计划动作），措辞红线同一条。
     * 措辞不说"本轮是末次"：手动唤醒也走这条开场白（boundary 是当前边界），"到 X 那根为止"对两种轮次都成立；
     * 小时数从 now 算，手动轮晚于边界几十分钟也不会说错。不是末次或全天 → 空串。
     */
    static String sleepNotice(PromptCatalog prompts, AgentLang lang, WakeWindow window,
                              long boundaryTime, long intervalMs, long now) {
        if (window == null || !window.isLastBoundary(boundaryTime, intervalMs)) {
            return "";
        }
        // 末次唤醒⇒下一天同一时刻仍在时段内，一天之内必有下一根，不会 -1
        long next = window.nextBoundaryFrom(boundaryTime + intervalMs, intervalMs);
        double hours = (next - now) / 3_600_000.0;
        return "\n" + prompts.get(lang, "trader.wake.sleepNotice", Map.of(
                "window", window.text(),
                "lastBar", BeijingTime.shortFormat(boundaryTime),
                "next", BeijingTime.shortFormat(next),
                "hours", String.format(Locale.ROOT, "%.1f", hours))) + "\n";
    }

    /**
     * 非例行唤醒的头（警报 / 持仓事件 / 盯盘命中共用）：先逐条报把它叫醒的事，排队期间攒下的几条按先后一起报、各带自己的时刻；
     * 再是"上次唤醒/距例行"那行。事实全代码注入。了结的净盈亏和当时的计划由观察包的【自上次唤醒以来】对账块承载，不在这里再算一遍。
     *
     * @param wakeTime     真正醒来的时刻(ms)，"距下一次例行"按它算
     * @param lastWakeTime 本局上一次交易唤醒的时刻(ms)，null=本局还没醒过
     */
    private String triggerHead(AiTrader trader, List<WakeTrigger> triggers, long wakeTime, Long lastWakeTime, AgentLang lang) {
        StringBuilder sb = new StringBuilder();
        boolean batch = triggers.size() > 1;
        if (batch) {
            sb.append(prompts.get(lang, "trader.wake.triggerBatch", Map.of("n", triggers.size()))).append('\n');
        }
        for (WakeTrigger t : triggers) {
            sb.append(batch ? "- " : "").append(triggerLine(t, lang)).append('\n');
        }
        return sb.append(lastWakeLine(trader, wakeTime, lastWakeTime, lang)).append('\n').toString();
    }

    /** 一条触发的一句话：警报报振幅/方向/现价，事件报币/方向/量/价，盯盘命中报条件与命中价和备注，都带自己的时刻 */
    private String triggerLine(WakeTrigger t, AgentLang lang) {
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
            // 盯盘条件的时刻带年份，和它条件里的时刻、账户块的 nowBeijing 同一种写法
            case WatchTrigger w -> prompts.get(lang, "trader.wake.watchHeader", Map.of(
                    "time", BeijingTime.format(w.triggeredAt()),
                    "detail", WatchText.detail(w.watch(), w.price(), prompts, lang),
                    "note", WatchText.note(w.watch(), prompts, lang)));
        };
    }

    /** 事件一句话：按类型选文案，币/方向/量/价填进去（全仓爆仓没有币和价，qty 是爆掉的仓位数） */
    private String eventDetail(TradeEvent e, AgentLang lang) {
        String key = switch (e.type()) {
            case TradeEvent.STOP_LOSS -> "stopLoss";
            case TradeEvent.TAKE_PROFIT -> "takeProfit";
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
