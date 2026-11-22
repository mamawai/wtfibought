package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibagent.trader.prompt.EconCalendarAssembler;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.enums.AgentLang;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.HashSet;
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
 * 整盘轮三种走法：单段（一段问完）、两段（先处置自己留下的事，再布防收尾）、联网三段（核查事实 → 要不要搜 → 交易与收尾）。
 * 权益回落警报叫醒的也是整盘轮，走法和例行轮一样，只是开头报的是权益回落、第一段前面多问一句。
 * 单币轮一种走法：先处理把它叫醒的事（动作当场做）→ 开了联网的回头查外部信息 → 给这个币收尾。
 * 一轮开头用 {@link #choose} 选定一种，之后循环和交易工具都只问那一个 {@link Flow}。
 * 加一种走法就是加一个 {@link Flow} 的实现，再在 {@link #choose} 里挑它。
 */
@Component
@RequiredArgsConstructor
class WakeFlows {

    private final PromptCatalog prompts;
    private final WakeObservation wakeObservation;
    private final TriggerText triggerText;

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

        /** 现在能不能对这个币开仓、加仓、反手 */
        default boolean openingAllowed(String symbol) {
            return true;
        }

        /** 决策正文是只取最后一段，还是各段都留 */
        default boolean finalStageOnly() {
            return false;
        }

        /** 模型每答一条知会一声 */
        default void replied(AssistantMessage reply) {
        }

        /** 这一轮进行中，这个币的触发被插了进来：之前不许对它开仓的走法，从现在起放开 */
        default void unlock(String symbol) {
        }
    }

    /**
     * 一轮开头备好的素材，走法和成文都从这里取。
     *
     * @param scope     这一轮管哪些币
     * @param triggers  把它叫醒的触发，空＝例行或手动轮
     * @param pack      观察包
     * @param calendar  财经日历块，null＝整块缺席；单币轮不带
     * @param ownerNote 主人留言段，空串＝没有；只有按点的例行轮和手动轮带
     * @param quotes    开场那一刻拉的各币标记价和资金费率
     * @param overview  单币轮的账户概况；整盘轮是空串
     * @param busyNote  整盘轮里的一句：此刻另有单币轮在处理哪几个币；没有是空串
     */
    record Brief(AiTrader trader, WakeScope scope, AgentLang lang, long wakeTime, List<WakeTrigger> triggers,
                 List<WakeObservation.RecentWake> recent, WakeObservation.Pack pack, String calendar, String ownerNote,
                 Map<String, JsonNode> quotes, String overview, String busyNote) {

        boolean triggered() {
            return !triggers.isEmpty();
        }

        /** 整盘轮开场白里的观察包：后面跟上别的轮正在处理哪几个币 */
        String bookObservation() {
            return pack.text() + busyNote;
        }
    }

    /**
     * 选走法。单币轮只有一种；整盘轮开了联网走三段，没开的，被触发叫醒、有留言或承诺要处置就分两段，其余一段问完
     */
    Flow choose(Brief brief, boolean webSearch) {
        if (brief.scope().single()) {
            return new SingleSymbol(brief, webSearch);
        }
        if (webSearch) {
            return new Search(brief);
        }
        return brief.triggered() || !brief.ownerNote().isEmpty() || brief.pack().busy()
                ? new TwoStage(brief) : new Single(brief);
    }

    // ========== 整盘轮的三种走法 ==========

    /** 单段：一段问完 */
    @RequiredArgsConstructor
    private final class Single implements Flow {
        private final Brief b;

        @Override
        public String opening() {
            return routineInstruction(b.trader(), b.triggers(), b.wakeTime(), lastRealWake(b.recent()), b.bookObservation(),
                    symbolsSummary(b), history(b), openingSnapshot(b), b.calendar(), b.lang());
        }

        @Override
        public Optional<UserMessage> next(boolean budgetExhausted, Supplier<String> account) {
            return Optional.empty();
        }
    }

    /** 两段：第一段处置自己留下的事，第二段布防与收尾 */
    @RequiredArgsConstructor
    private final class TwoStage implements Flow {
        private final Brief b;
        private boolean secondSent;

        @Override
        public String opening() {
            return stageOneInstruction(b.trader(), b.triggers(), b.wakeTime(), lastRealWake(b.recent()), b.bookObservation(),
                    b.ownerNote(), b.lang(), false);
        }

        /** 第二段只发一次。整盘事实到追加那一刻才算：第一段可能跑了几分钟，标记价重拉一次，不拿开场时那份 */
        @Override
        public Optional<UserMessage> next(boolean budgetExhausted, Supplier<String> account) {
            if (secondSent) {
                return Optional.empty();
            }
            secondSent = true;
            return Optional.of(new UserMessage(stageTwoInstruction(b.trader(), b.wakeTime(), symbolsSummary(b), history(b),
                    freshSnapshot(b), b.calendar(), b.lang(), budgetExhausted)));
        }
    }

    private enum SearchStage { FACTS, SEARCH, DECISION, FINISH }

    /** 联网三段：先给齐事实核查，再问要不要搜，最后交易与收尾。到交易段才许开仓；决策正文只留最后一段，搜索过程留在现场 */
    @RequiredArgsConstructor
    private final class Search implements Flow {
        private final Brief b;
        private SearchStage stage = SearchStage.FACTS;
        private boolean lastReplyUsedTools;
        /** 这一轮进行中来过触发的币 */
        private final Set<String> unlocked = new HashSet<>();

        /** 判断要不要搜所需的事实开场就给齐，日历与历史只放这一次 */
        @Override
        public String opening() {
            long intervalMs = WakeIntervals.MS.get(b.trader().getIntervalCode());
            String openingFacts = facts(b.trader(), WakeIntervals.align(b.wakeTime(), intervalMs), intervalMs,
                    symbolsSummary(b), history(b), openingSnapshot(b), b.calendar(), b.lang());
            return stageOneInstruction(b.trader(), b.triggers(), b.wakeTime(), lastRealWake(b.recent()),
                    b.bookObservation() + openingFacts, b.ownerNote(), b.lang(), true);
        }

        /** 一轮进行中这个币来了触发：它的开仓、加仓、反手不必再等到交易段 */
        @Override
        public void unlock(String symbol) {
            unlocked.add(symbol);
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
        public boolean openingAllowed(String symbol) {
            return stage == SearchStage.DECISION || unlocked.contains(symbol);
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

    // ========== 单币轮的走法 ==========

    private enum SingleStage { ACT, SEARCH, CLOSE, DONE }

    /**
     * 单币轮：先处理把它叫醒的事，平仓、开仓、反手第一段就能做；开了联网的再回头查外部信息，查到的改变判断可以接着调整；
     * 最后给这个币布盯盘、写它自己的结论段。开了联网的决策正文只留最后一段，搜索过程留在现场
     */
    @RequiredArgsConstructor
    private final class SingleSymbol implements Flow {
        private final Brief b;
        private final boolean webSearch;
        private SingleStage stage = SingleStage.ACT;
        private boolean lastReplyUsedTools;

        @Override
        public String opening() {
            String symbol = b.scope().symbol();
            return singleInstruction(b.trader(), symbol, b.triggers(), b.wakeTime(), lastRealWake(b.recent()), b.pack().text(),
                    wakeObservation.coinFacts(b.trader(), symbol, b.pack().lastSeen(), b.wakeTime(), b.quotes(),
                            nowMs.getAsLong(), b.lang()),
                    b.overview(), b.lang(), webSearch);
        }

        @Override
        public Optional<UserMessage> next(boolean budgetExhausted, Supplier<String> account) {
            if (stage == SingleStage.DONE || (stage == SingleStage.CLOSE && !lastReplyUsedTools)) {
                return Optional.empty();
            }
            Map<String, Object> vars = Map.of("symbol", b.scope().symbol(),
                    "mark", prompts.get(b.lang(), "trader.mark.conclusion"));
            if (budgetExhausted) {
                stage = SingleStage.DONE;
                return Optional.of(new UserMessage(prompts.get(b.lang(), "trader.wake.single.exhausted", vars)));
            }
            if (stage == SingleStage.ACT && webSearch) {
                stage = SingleStage.SEARCH;
                return Optional.of(new UserMessage(prompts.get(b.lang(), "trader.wake.single.search", vars)));
            }
            stage = SingleStage.CLOSE;
            return Optional.of(new UserMessage(prompts.get(b.lang(), "trader.wake.single.closing", vars)));
        }

        @Override
        public boolean finalStageOnly() {
            return webSearch;
        }

        @Override
        public void replied(AssistantMessage reply) {
            lastReplyUsedTools = reply.hasToolCalls();
        }
    }

    // ========== 走法取素材 ==========

    private String symbolsSummary(Brief b) {
        return wakeObservation.symbolsSummary(b.trader(), WakeObservation.board(b.scope().tradable()), b.scope().tradable(),
                b.pack().lastSeen(), b.wakeTime(), b.lang());
    }

    private String history(Brief b) {
        return wakeObservation.history(b.trader(), b.recent(), b.lang());
    }

    /** 开场那一刻拉的报价出的快照 */
    private String openingSnapshot(Brief b) {
        return wakeObservation.snapshot(b.quotes(), b.scope().tradable(), nowMs.getAsLong(), b.lang());
    }

    /** 重拉一次标记价出的快照 */
    private String freshSnapshot(Brief b) {
        return wakeObservation.snapshot(wakeObservation.quotes(WakeObservation.board(b.scope().tradable())),
                b.scope().tradable(), nowMs.getAsLong(), b.lang());
    }

    /** 上次真跑过的唤醒时刻：SKIPPED 行（触发过晚）没跑模型，不算醒过 */
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
     * 整盘轮单段开场白（没有承诺要处置、没留言、也不是被触发叫醒）：头 + 观察包 + 整盘事实 + 本轮任务。
     * <p>
     * 收尾标记取 {@code trader.mark.conclusion}，与系统提示词同一条 key——两处必须同源。
     * <p>
     * 包私有非 private：标记同源那条钉子（{@code WakeInstructionI18nTest}）要拿它的成文比对。
     *
     * @param triggers    把它叫醒的触发，空＝例行或手动轮
     * @param observation 观察包（{@link WakeObservation#assemble}），空串=不带。紧跟头部事实
     * @param symbols     各币摘要块（{@link WakeObservation#symbolsSummary}），空串=不带
     * @param history     最近轨迹 + 论点战绩（{@link WakeObservation#history}），空串=不带
     * @param calendar    财经日历块（{@link EconCalendarAssembler}），null=整块缺席。
     *                    与快照同属事实区，排在快照之后、休眠提示与本轮任务之前
     */
    String routineInstruction(AiTrader trader, List<WakeTrigger> triggers, long wakeTime, Long lastWakeTime,
                              String observation, String symbols, String history,
                              String snapshot, String calendar, AgentLang lang) {
        long intervalMs = WakeIntervals.MS.get(trader.getIntervalCode());
        // 手动唤醒的 wakeTime 是按下按钮那一刻，"已收盘的那根"和休眠提示要按对齐边界说；例行轮本来就对齐
        long boundaryTime = WakeIntervals.align(wakeTime, intervalMs);
        return bookHead(trader, triggers, wakeTime, lastWakeTime, lang)
                + observation
                + facts(trader, boundaryTime, intervalMs, symbols, history, snapshot, calendar, lang)
                + prompts.get(lang, "trader.wake.routineQuestion",
                        Map.of("mark", prompts.get(lang, "trader.mark.conclusion")));
    }

    /**
     * 整盘轮分段走时的第一段：头、观察包和本段任务。联网轮核查事实与必要减险，未联网轮处置已有承诺。
     * 被权益回落警报叫醒的，本段任务前面多问一句。留言放在本段末尾。
     *
     * @param triggers  把它叫醒的触发，空＝例行或手动轮
     * @param webSearch 联网轮第一段只核查事实和必要减险，开头交代这一轮分三段
     */
    String stageOneInstruction(AiTrader trader, List<WakeTrigger> triggers, long wakeTime, Long lastWakeTime,
                               String observation, String ownerNote, AgentLang lang, boolean webSearch) {
        Map<String, Object> mark = Map.of("mark", prompts.get(lang, "trader.mark.conclusion"));
        return bookHead(trader, triggers, wakeTime, lastWakeTime, lang)
                + observation
                + (webSearch ? prompts.get(lang, "trader.wake.routineScope") + '\n'
                        + prompts.get(lang, "trader.wake.routineFlow") + '\n' : "")
                + triggerText.bookQuestions(triggers, lang)
                + prompts.get(lang, webSearch ? "trader.wake.factsQuestion" : "trader.wake.stageQuestion", mark)
                + ownerNote;
    }

    /**
     * 整盘轮第二段「布防与收尾」开场白：头一句 + 整盘事实 + 第二段的问题。
     * 预算在第一段用尽的（exhausted）换成只许收尾的问题：工具一个都调不了，set_watch 也不行
     */
    String stageTwoInstruction(AiTrader trader, long wakeTime, String symbols, String history, String snapshot,
                               String calendar, AgentLang lang, boolean exhausted) {
        long intervalMs = WakeIntervals.MS.get(trader.getIntervalCode());
        return prompts.get(lang, "trader.wake.stageTwoHeader") + '\n'
                + facts(trader, WakeIntervals.align(wakeTime, intervalMs), intervalMs, symbols, history, snapshot, calendar, lang)
                + prompts.get(lang, exhausted ? "trader.wake.stageTwoExhausted" : "trader.wake.stageTwoQuestion",
                        Map.of("mark", prompts.get(lang, "trader.mark.conclusion")));
    }

    /**
     * 单币轮开场白：把它叫醒的事 → 当前 K 线未收盘 → 这一轮只处理哪个币 → 这个币的观察包和行情 → 账户概况
     * → 这一轮分几段 → 每种触发各自的问题 → 四种共用的那一句。
     *
     * @param coinFacts 这个币的走势和现价（{@link WakeObservation#coinFacts}），空串=不带
     * @param overview  账户概况（{@link WakeObservation#accountOverview}），空串=不带
     */
    String singleInstruction(AiTrader trader, String symbol, List<WakeTrigger> triggers, long wakeTime, Long lastWakeTime,
                             String observation, String coinFacts, String overview, AgentLang lang, boolean webSearch) {
        return triggerText.head(trader, triggers, wakeTime, lastWakeTime, lang)
                + prompts.get(lang, "trader.wake.triggerNotice", Map.of("interval", trader.getIntervalCode())) + '\n'
                + prompts.get(lang, "trader.wake.single.scope", Map.of("symbol", symbol)) + '\n'
                + observation
                + coinFacts
                + overview
                + '\n' + prompts.get(lang, webSearch ? "trader.wake.single.flowSearch" : "trader.wake.single.flow") + '\n'
                + triggerText.questions(triggers, lang)
                + prompts.get(lang, "trader.wake.single.actFirst", Map.of("mark", prompts.get(lang, "trader.mark.conclusion")));
    }

    /** 联网轮的交易段：只刷新账户和价格，计划对账仍只在唤醒开头做 */
    private String tradingInstruction(Brief b, String account) {
        AgentLang lang = b.lang();
        String snapshot = freshSnapshot(b);
        return prompts.get(lang, "trader.wake.tradingHeader",
                Map.of("time", BeijingTime.shortFormat(nowMs.getAsLong()))) + '\n'
                + account + '\n'
                + (snapshot.isEmpty() ? "" : prompts.get(lang, "trader.wake.snapshotHeader") + '\n' + snapshot)
                + prompts.get(lang, "trader.wake.routineScope") + '\n'
                + prompts.get(lang, "trader.wake.tradingQuestion",
                        Map.of("mark", prompts.get(lang, "trader.mark.conclusion")));
    }

    /**
     * 整盘轮开场白的头：例行和手动轮是"新一根 K 线已收盘"；被触发叫醒的是那几件事各一句、上次唤醒那行，加一句当前 K 线未收盘
     */
    private String bookHead(AiTrader trader, List<WakeTrigger> triggers, long wakeTime, Long lastWakeTime, AgentLang lang) {
        if (triggers.isEmpty()) {
            return prompts.get(lang, "trader.wake.routineHeader", Map.of(
                    "interval", trader.getIntervalCode(),
                    "time", BeijingTime.shortFormat(WakeIntervals.align(wakeTime, WakeIntervals.MS.get(trader.getIntervalCode())))));
        }
        return triggerText.head(trader, triggers, wakeTime, lastWakeTime, lang)
                + prompts.get(lang, "trader.wake.triggerNotice", Map.of("interval", trader.getIntervalCode())) + '\n';
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
}
