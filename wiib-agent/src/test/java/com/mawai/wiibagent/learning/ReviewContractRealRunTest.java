package com.mawai.wiibagent.learning;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.trader.TraderModelFactory;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复盘输出契约的真跑验收：手造五块素材交给真模型，看它是否按【本期复盘】/【经验更新】两段填表、
 * 经验更新里每行能否被 {@link ExperienceOpParser} 认出。两门语言各跑一次。
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
            String user = runner.userPrompt(trader, material(lang), boundary - 86_400_000L, boundary, lang);
            String output = modelFactory.modelFor(trader)
                    .call(new Prompt(List.of(new SystemMessage(prompts.get(lang, "reviewer.system")), new UserMessage(user))))
                    .getResult().getOutput().getText();
            log.info("[ReviewRealRun] {} 输出：\n{}", lang.code(), output);

            assertThat(output).as("%s 正文段标记", lang.code()).contains(lang == AgentLang.ZH ? "【本期复盘】" : "[REVIEW]");
            List<ExperienceLedger.Op> ops = parser.parse(output, lang);
            assertThat(ops).as("%s 经验更新至少解析出一行", lang.code()).isNotEmpty();
            // 现役那条本期有同类单子，必须给结论
            assertThat(ops).as("%s 对 #1 给了结论", lang.code())
                    .anyMatch(op -> op instanceof ExperienceLedger.Verdict v && v.id() == 1L);
        }
    }

    /** 手造素材：一组两笔的 PULLBACK（一赢一亏）+ 行情 + 账本里一条待核对的假设 */
    private ReviewMaterialAssembler.ReviewMaterial material(AgentLang lang) {
        String none = prompts.get(lang, "reviewer.label.none");
        String stats = prompts.get(lang, "reviewer.label.statsHeader") + "\n"
                + prompts.get(lang, "reviewer.label.statsEquity", Map.of("start", "10000.00", "end", "9880.00", "pct", "-1.20")) + "\n"
                + prompts.get(lang, "reviewer.label.statsDrawdown", Map.of("dd", "2.10")) + "\n"
                + prompts.get(lang, "reviewer.label.statsClosed", Map.of("n", 2, "wins", 1, "losses", 1, "winRate", 50, "pnl", "-120.00")) + "\n";
        String cases = prompts.get(lang, "reviewer.label.casesHeader") + "\n"
                + prompts.get(lang, "reviewer.label.caseGroup", Map.of("play", "PULLBACK", "n", 2, "wins", 1, "net", "-120")) + "\n"
                + prompts.get(lang, "reviewer.label.caseTrade", Map.ofEntries(
                        Map.entry("symbol", "BTCUSDT"), Map.entry("side", "LONG"), Map.entry("opened", "10-09 09:00"), Map.entry("closed", "10-09 11:30"),
                        Map.entry("held", prompts.get(lang, "reviewer.label.duration.hours", Map.of("n", 2))),
                        Map.entry("entry", "100000"), Map.entry("stop", "99000"), Map.entry("target", "102000"), Map.entry("exit", "101400"),
                        Map.entry("manner", prompts.get(lang, "reviewer.label.closeManner.manual")), Map.entry("pnl", "+280"), Map.entry("funding", "-2"))) + "\n"
                + prompts.get(lang, "reviewer.label.casePath", Map.ofEntries(
                        Map.entry("mfe", "101900"), Map.entry("mfeAt", "11:05"), Map.entry("mfeFrac", "95%"), Map.entry("mae", "99650"), Map.entry("maeFrac", "35%"),
                        Map.entry("t25", "20"), Map.entry("t50", "55"), Map.entry("firstBack", none), Map.entry("best4", "103100"), Map.entry("best24", "103800"),
                        Map.entry("targetAfter", prompts.get(lang, "reviewer.label.caseTargetHit")))) + "\n"
                + prompts.get(lang, "reviewer.label.caseHourly", Map.of("closes", "100600 101300 101400")) + "\n"
                + prompts.get(lang, "reviewer.label.casePlan", Map.of("signals", "1h EMA20 回踩企稳 + 量比 1.6", "invalidation", "1h 收盘跌破 99000", "revisions", none)) + "\n"
                + prompts.get(lang, "reviewer.label.caseCtx", Map.ofEntries(
                        Map.entry("regime", "TREND_UP"), Map.entry("atr", "420"), Map.entry("stopAtr", "2.4"), Map.entry("stopPct", "1.0"),
                        Map.entry("eventMin", none), Map.entry("eventTitle", ""), Map.entry("hour", "9"), Map.entry("funding", "0.0001"),
                        Map.entry("orderType", "MARKET"), Map.entry("leverage", "10"))) + "\n"
                + prompts.get(lang, "reviewer.label.caseTrade", Map.ofEntries(
                        Map.entry("symbol", "BTCUSDT"), Map.entry("side", "LONG"), Map.entry("opened", "10-09 16:00"), Map.entry("closed", "10-09 17:40"),
                        Map.entry("held", prompts.get(lang, "reviewer.label.duration.minutes", Map.of("n", 100))),
                        Map.entry("entry", "101000"), Map.entry("stop", "100000"), Map.entry("target", "103000"), Map.entry("exit", "99950"),
                        Map.entry("manner", prompts.get(lang, "reviewer.label.closeManner.stopLoss")), Map.entry("pnl", "-400"), Map.entry("funding", "-1"))) + "\n"
                + prompts.get(lang, "reviewer.label.casePath", Map.ofEntries(
                        Map.entry("mfe", "101600"), Map.entry("mfeAt", "16:20"), Map.entry("mfeFrac", "30%"), Map.entry("mae", "99950"), Map.entry("maeFrac", "105%"),
                        Map.entry("t25", "15"), Map.entry("t50", none), Map.entry("firstBack", "16:45"), Map.entry("best4", "100900"), Map.entry("best24", "100900"),
                        Map.entry("targetAfter", prompts.get(lang, "reviewer.label.caseTargetMiss")))) + "\n"
                + prompts.get(lang, "reviewer.label.caseHourly", Map.of("closes", "100800 99950")) + "\n"
                + prompts.get(lang, "reviewer.label.casePlan", Map.of("signals", "1h EMA20 回踩 + 量比 1.1", "invalidation", "1h 收盘跌破 100000", "revisions", none)) + "\n"
                + prompts.get(lang, "reviewer.label.caseCtx", Map.ofEntries(
                        Map.entry("regime", "RANGE"), Map.entry("atr", "380"), Map.entry("stopAtr", "2.6"), Map.entry("stopPct", "1.0"),
                        Map.entry("eventMin", "45"), Map.entry("eventTitle", "CPI"), Map.entry("hour", "16"), Map.entry("funding", "0.0003"),
                        Map.entry("orderType", "MARKET"), Map.entry("leverage", "10"))) + "\n";
        String market = prompts.get(lang, "reviewer.label.marketHeader") + "\n"
                + prompts.get(lang, "reviewer.label.marketRow", Map.of("symbol", "BTCUSDT", "open", "99800", "close", "100300",
                        "low", "99500", "lowAt", "10-09 03:00", "high", "103800", "highAt", "10-09 14:00", "range", "4.32")) + "\n";
        String ledger = prompts.get(lang, "reviewer.label.ledgerHeader", Map.of("n", 1)) + "\n"
                + prompts.get(lang, "reviewer.label.ledgerRow", Map.ofEntries(
                        Map.entry("id", 1), Map.entry("kind", prompts.get(lang, "trader.label.ledgerKind.hypothesis")),
                        Map.entry("slot", prompts.get(lang, "reviewer.op.slot.entry")), Map.entry("dir", prompts.get(lang, "reviewer.op.dir.opportunity")),
                        Map.entry("play", "PULLBACK"), Map.entry("text", lang == AgentLang.ZH ? "量比大于 1.5 的回踩才做" : "take the pullback only when volume ratio is above 1.5"),
                        Map.entry("supports", 2), Map.entry("opposes", 0), Map.entry("untested", 0),
                        Map.entry("evidence", lang == AgentLang.ZH ? "10-07 两笔" : "two trades on 10-07"), Map.entry("flagged", ""))) + "\n";
        return new ReviewMaterialAssembler.ReviewMaterial(stats, "", cases, market, ledger, 2, Map.of());
    }
}
