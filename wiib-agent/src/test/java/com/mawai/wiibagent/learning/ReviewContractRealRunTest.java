package com.mawai.wiibagent.learning;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.trader.TraderModelFactory;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.enums.AgentLang;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一复盘输出契约的真跑验收：手造五块素材（单子带单笔复盘正文）交给真模型，看它是否按【本期复盘】/【经验更新】两段填表、
 * 经验更新里的行能否被 {@link ExperienceOpParser} 认出、有没有照规矩不写编号归类行。两门语言各跑一次。
 * <p>
 * 会烧真 token（2 次调用），默认跳过。跑法（项目根）：
 * <pre>
 * WIIB_REAL_RUN=1 mvn test -pl wiib-agent -am -DskipTests=false \
 *   -Dtest=ReviewContractRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@SpringBootTest(properties = {
        "strategy.runtime.enabled=false",
        "strategy.execution.enabled=false",
        "agent.analysis.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class ReviewContractRealRunTest {

    private static final Logger log = LoggerFactory.getLogger(ReviewContractRealRunTest.class);

    /** 挑 1 号：真跑要烧的那份 BYOK 配在它名下 */
    private static final long ADMIN_USER_ID = 1L;

    @Autowired
    private TraderModelFactory modelFactory;
    @Autowired
    private ReviewRunner runner;
    @Autowired
    private ExperienceOpParser parser;
    @Autowired
    private PromptCatalog prompts;
    /** 重放行、本期重放汇总用真素材的写法 */
    @Autowired
    private CaseFileRenderer caseRenderer;
    @Autowired
    private PlayStatsAssembler playStats;

    @Test
    void 复盘两段契约两门语言真模型各服从一轮() {
        AiTrader trader = new AiTrader();
        trader.setId(999_999L);
        trader.setUserId(ADMIN_USER_ID);
        trader.setRoundNo(1);
        trader.setSymbols("BTCUSDT");
        trader.setIntervalCode("1h");
        trader.setCustomPrompt("只做趋势突破与回踩，不抄底不摸顶；单笔风险不超过权益 2%。");
        long boundary = System.currentTimeMillis() / 86_400_000L * 86_400_000L;

        for (AgentLang lang : AgentLang.values()) {
            String user = runner.userPrompt(trader, material(lang), boundary - 2 * 86_400_000L, boundary - 86_400_000L,
                    boundary, lang);
            String output = modelFactory.modelFor(trader)
                    .call(new Prompt(List.of(new SystemMessage(prompts.get(lang, "reviewer.system")), new UserMessage(user))))
                    .getResult().getOutput().getText();
            log.info("[ReviewRealRun] {} 输出：\n{}", lang.code(), output);

            assertThat(output).as("%s 正文段标记", lang.code()).contains(lang == AgentLang.ZH ? "【本期复盘】" : "[REVIEW]");
            ExperienceOpParser.ReviewParsed parsed = parser.parse(output, lang);
            // 有可认的新增、发现、删除，或者老实写了「本期无更新」
            assertThat(parsed.noUpdates() || !parsed.ops().isEmpty())
                    .as("%s 经验更新能解析", lang.code()).isTrue();
            // 归类已经由单笔复盘做完，这里不该再写编号归类行
            assertThat(parsed.ops()).as("%s 没有编号归类行", lang.code())
                    .noneMatch(ExperienceLedger.Classify.class::isInstance);
        }
    }

    /** 手造素材：一组两笔的 PULLBACK（一赢一亏，带重放和单笔复盘正文）+ 行情 + 账本里一条假设，两笔都已归到它名下 */
    private ReviewMaterialAssembler.ReviewMaterial material(AgentLang lang) {
        String none = prompts.get(lang, "reviewer.label.none");
        // 赢的那笔各档止损宽度都到目标；亏的那笔原样被止损，放宽以后都没了结
        CaseFileBuilder.Replay winner = new CaseFileBuilder.Replay(new BigDecimal("1.40"), new BigDecimal("3.80"),
                new BigDecimal("-0.35"), new BigDecimal("3.50"), List.of(
                        width("1", CaseFileBuilder.Outcome.TARGET, "1.98"), width("1.5", CaseFileBuilder.Outcome.TARGET, "1.31"),
                        width("2", CaseFileBuilder.Outcome.TARGET, "0.98"), width("3", CaseFileBuilder.Outcome.TARGET, "0.65")), false);
        CaseFileBuilder.Replay loser = new CaseFileBuilder.Replay(new BigDecimal("-1.07"), new BigDecimal("0.60"),
                new BigDecimal("-1.40"), new BigDecimal("-0.10"), List.of(
                        width("1", CaseFileBuilder.Outcome.STOP, "-1.02"), width("1.5", CaseFileBuilder.Outcome.OPEN, null),
                        width("2", CaseFileBuilder.Outcome.OPEN, null), width("3", CaseFileBuilder.Outcome.OPEN, null)), false);
        String stats = prompts.get(lang, "reviewer.label.statsHeader") + "\n"
                + prompts.get(lang, "reviewer.label.statsEquity", Map.of("start", "10000.00", "end", "9880.00", "pct", "-1.20")) + "\n"
                + prompts.get(lang, "reviewer.label.statsDrawdown", Map.of("dd", "2.10")) + "\n"
                + prompts.get(lang, "reviewer.label.statsClosed", Map.of("n", 2, "wins", 1, "losses", 1, "winRate", 50, "pnl", "-120.00")) + "\n";
        boolean zh = lang == AgentLang.ZH;
        String debrief = prompts.get(lang, "reviewer.label.caseDebrief") + "\n";
        String cases = prompts.get(lang, "reviewer.label.casesHeader", Map.of("guide", caseRenderer.guide(lang))) + "\n"
                + prompts.get(lang, "reviewer.label.caseGroup", Map.of("play", "PULLBACK", "n", 2, "wins", 1, "net", "-120")) + "\n"
                + prompts.get(lang, "reviewer.label.caseTrade", Map.ofEntries(Map.entry("id", 2238),
                        Map.entry("symbol", "BTCUSDT"), Map.entry("side", "LONG"), Map.entry("opened", "10-09 09:00"), Map.entry("closed", "10-09 11:30"),
                        Map.entry("held", prompts.get(lang, "reviewer.label.duration.hours", Map.of("n", 2))),
                        Map.entry("entry", "100000"), Map.entry("stop", "99000"), Map.entry("target", "102000"), Map.entry("exit", "101400"),
                        Map.entry("manner", prompts.get(lang, "reviewer.label.closeManner.manual")), Map.entry("pnl", "+280"), Map.entry("funding", "-2"))) + "\n"
                + prompts.get(lang, "reviewer.label.casePath", Map.ofEntries(
                        Map.entry("mfe", "101900"), Map.entry("mfeAt", "11:05"), Map.entry("mfeR", "+1.90R"), Map.entry("mae", "99650"), Map.entry("maeR", "-0.35R"),
                        Map.entry("tHalf", "20"), Map.entry("t1", "55"), Map.entry("firstBack", none), Map.entry("best4", "103100"), Map.entry("best24", "103800"),
                        Map.entry("targetsAfter", prompts.get(lang, "reviewer.label.caseTargetsAfter", Map.of("hit", 1, "total", 1))))) + "\n"
                + caseRenderer.replayLine(winner, lang) + "\n"
                + prompts.get(lang, "reviewer.label.caseHourly", Map.of("closes", "100600 101300 101400")) + "\n"
                + prompts.get(lang, "reviewer.label.casePlan", Map.of("signals", "1h EMA20 回踩企稳 + 量比 1.6", "invalidation", "1h 收盘跌破 99000", "revisions", none)) + "\n"
                + prompts.get(lang, "reviewer.label.caseCtx", Map.ofEntries(
                        Map.entry("adx", "31.2"), Map.entry("bollBw", "2.8"), Map.entry("atr", "420"), Map.entry("stopAtr", "2.4"), Map.entry("stopPct", "1.0"),
                        Map.entry("eventMin", none), Map.entry("eventTitle", ""), Map.entry("hour", "9"), Map.entry("funding", "0.0001"),
                        Map.entry("orderType", "MARKET"), Map.entry("leverage", "10"), Map.entry("pending", ""))) + "\n"
                + debrief + (zh
                ? "    路径：09:00 市价开多，11:05 到过 101900，11:30 主动平在 101400。\n    对在哪：1h EMA20 回踩企稳、量比 1.6，之后一路走高。\n    少赚了多少：出场后 24 小时到过 103800。\n    管理动作：提前平仓，比原计划不动少了 0.58R。\n    有没有写下要做却没照做的：无\n"
                : "    Path: market long at 09:00, reached 101900 at 11:05, closed by hand at 101400 at 11:30.\n    What was right: the 1h EMA20 pullback held with volume ratio 1.6 and price kept rising.\n    Left on the table: price reached 103800 within 24h of the exit.\n    Managing the position: closed early, 0.58R below leaving it untouched.\n    Anything written down and not done: none\n")
                + prompts.get(lang, "reviewer.label.caseTrade", Map.ofEntries(Map.entry("id", 2241),
                        Map.entry("symbol", "BTCUSDT"), Map.entry("side", "LONG"), Map.entry("opened", "10-09 16:00"), Map.entry("closed", "10-09 17:40"),
                        Map.entry("held", prompts.get(lang, "reviewer.label.duration.minutes", Map.of("n", 100))),
                        Map.entry("entry", "101000"), Map.entry("stop", "100000"), Map.entry("target", "103000"), Map.entry("exit", "99950"),
                        Map.entry("manner", prompts.get(lang, "reviewer.label.closeManner.stopLoss")), Map.entry("pnl", "-400"), Map.entry("funding", "-1"))) + "\n"
                + prompts.get(lang, "reviewer.label.casePath", Map.ofEntries(
                        Map.entry("mfe", "101600"), Map.entry("mfeAt", "16:20"), Map.entry("mfeR", "+0.60R"), Map.entry("mae", "99950"), Map.entry("maeR", "-1.05R"),
                        Map.entry("tHalf", "15"), Map.entry("t1", none), Map.entry("firstBack", "16:45"), Map.entry("best4", "100900"), Map.entry("best24", "100900"),
                        Map.entry("targetsAfter", prompts.get(lang, "reviewer.label.caseTargetsAfter", Map.of("hit", 0, "total", 1))))) + "\n"
                + caseRenderer.replayLine(loser, lang) + "\n"
                + prompts.get(lang, "reviewer.label.caseHourly", Map.of("closes", "100800 99950")) + "\n"
                + prompts.get(lang, "reviewer.label.casePlan", Map.of("signals", "1h EMA20 回踩 + 量比 1.1", "invalidation", "1h 收盘跌破 100000", "revisions", none)) + "\n"
                + prompts.get(lang, "reviewer.label.caseCtx", Map.ofEntries(
                        Map.entry("adx", "17.5"), Map.entry("bollBw", "1.2"), Map.entry("atr", "380"), Map.entry("stopAtr", "2.6"), Map.entry("stopPct", "1.0"),
                        Map.entry("eventMin", "45"), Map.entry("eventTitle", "CPI"), Map.entry("hour", "16"), Map.entry("funding", "0.0003"),
                        Map.entry("orderType", "MARKET"), Map.entry("leverage", "10"), Map.entry("pending", ""))) + "\n"
                + debrief + (zh
                ? "    路径：16:00 市价开多，16:20 到过 101600，16:45 收回入场价，17:40 止损带走。\n    方向错了还是被洗掉：方向错了，出场后 24 小时最高只到 100900。\n    问题出在哪：进场方式，量比只有 1.1，离 CPI 公布 45 分钟。\n    管理动作：没动过。\n    有没有写下要做却没照做的：无\n"
                : "    Path: market long at 16:00, reached 101600 at 16:20, closed back through entry at 16:45, stopped at 17:40.\n    Wrong direction or shaken out: wrong direction, the best price within 24h of the exit was only 100900.\n    Where the problem lay: entry method, volume ratio was only 1.1, 45 minutes before CPI.\n    Managing the position: nothing was changed.\n    Anything written down and not done: none\n")
                + prompts.get(lang, "reviewer.label.caseReplaySummary") + "\n"
                + playStats.replayLines(List.of(winner, loser), lang);
        String market = prompts.get(lang, "reviewer.label.marketHeader") + "\n"
                + prompts.get(lang, "reviewer.label.marketRow", Map.of("symbol", "BTCUSDT", "open", "99800", "close", "100300",
                        "low", "99500", "lowAt", "10-09 03:00", "high", "103800", "highAt", "10-09 14:00", "range", "4.32")) + "\n";
        String ledger = prompts.get(lang, "reviewer.label.ledgerHeader", Map.of("n", 1)) + "\n"
                + prompts.get(lang, "reviewer.label.ledgerQuota", Map.of("n", 1, "max", ExperienceLedger.MAX_ACTIVE,
                        "perPlay", ExperienceLedger.MAX_PER_PLAY, "fullPlays", "",
                        "r", 0, "rMax", ExperienceLedger.MAX_RESTRICTION, "rIds", "")) + "\n"
                + prompts.get(lang, "reviewer.label.ledgerRow", Map.ofEntries(
                        Map.entry("id", 1), Map.entry("kind", prompts.get(lang, "trader.label.ledgerKind.hypothesis")),
                        Map.entry("slot", prompts.get(lang, "reviewer.op.slot.entry")), Map.entry("dir", prompts.get(lang, "reviewer.op.dir.opportunity")),
                        Map.entry("play", "PULLBACK"), Map.entry("text", lang == AgentLang.ZH ? "量比大于 1.5 的回踩才做" : "take the pullback only when volume ratio is above 1.5"),
                        Map.entry("tally", prompts.get(lang, "reviewer.label.ledgerTally", Map.of("n", 2, "wins", 2, "losses", 0,
                                "net", "+310.00", "days", 2, "segN", 2, "segNet", "+310.00", "segDays", 2, "miscited", ""))),
                        Map.entry("evidence", lang == AgentLang.ZH ? "10-07 两笔" : "two trades on 10-07"))) + "\n"
                + prompts.get(lang, "reviewer.label.ledgerSorted", Map.of("marks", prompts.get(lang, "reviewer.op.under") + " "
                        + prompts.get(lang, "reviewer.label.ledgerTrade", Map.of("id", 2238, "symbol", "BTCUSDT", "side", "LONG",
                                "play", "PULLBACK", "opened", "10-09 09:00", "net", "+280")))) + "\n";
        return new ReviewMaterialAssembler.ReviewMaterial(stats, "", cases, market, "", ledger, Map.of(
                2238L, new ExperienceLedger.PeriodTrade(new BigDecimal("280.00"), LocalDate.of(2026, 10, 9)),
                2241L, new ExperienceLedger.PeriodTrade(new BigDecimal("-400.00"), LocalDate.of(2026, 10, 9))),
                List.of(), List.of());
    }

    /** 一档止损宽度的重放结局；未决没有 r */
    private static CaseFileBuilder.WidthOutcome width(String width, CaseFileBuilder.Outcome outcome, String r) {
        return new CaseFileBuilder.WidthOutcome(new BigDecimal(width), outcome, r == null ? null : new BigDecimal(r));
    }
}
