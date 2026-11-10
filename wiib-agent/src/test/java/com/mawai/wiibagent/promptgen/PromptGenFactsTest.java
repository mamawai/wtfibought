package com.mawai.wiibagent.promptgen;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.PromptI18nAssertions;
import com.mawai.wiibagent.learning.ExperienceLedger;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.trader.prompt.TraderPromptAssembler;
import com.mawai.wiibagent.trader.trade.TraderRiskConfig;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.enums.AgentLang;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 风险表与噪音线的取值；必问项随现成指令和是否写出过版本变；英文提示词全文无中文 */
class PromptGenFactsTest {

    private final PromptCatalog prompts = new PromptCatalog();
    private final PromptGenFacts facts = new PromptGenFacts(
            new TraderPromptAssembler(mock(AiTraderMapper.class), prompts, mock(ExperienceLedger.class)), prompts);

    /** 默认规格：杠杆 3~20、保证金上限 20%；1h 噪音线 0.6% */
    @Test
    void riskTableFromFullSizeAndNoiseLine() {
        List<PromptGenFacts.RiskRow> rows = PromptGenFacts.riskTable(TraderRiskConfig.of(new AiTrader()), new BigDecimal("0.6"));

        assertThat(rows).extracting(PromptGenFacts.RiskRow::style)
                .containsExactly("conservative", "balanced", "aggressive");
        // 1% 风险：顶格 20 倍 × 20% → 止损 0.25%；止损放在 0.6% 噪音线、20 倍 → 占比缩到 8.33%
        assertThat(rows.get(1).stopAtFull()).isEqualByComparingTo("0.25");
        assertThat(rows.get(1).shareAtNoise()).isEqualByComparingTo("8.33");
        // 0.125 四舍五入是 0.13，不是银行家舍入的 0.12
        assertThat(rows.get(0).stopAtFull()).isEqualByComparingTo("0.13");
    }

    @Test
    void noiseLineScalesWithSquareRootOfInterval() {
        assertThat(Stream.of("5m", "15m", "1h", "4h").map(PromptGenFacts::noiseStopPct)
                .map(v -> v.stripTrailingZeros().toPlainString()))
                .containsExactly("0.17", "0.3", "0.6", "1.2");
    }

    @Test
    void modeIsRequiredOnlyWithAnInstructionAndBeforeTheFirstVersion() {
        AiTrader trader = new AiTrader();
        assertThat(PromptGenFacts.required(null, false)).containsExactly("goal", "style", "holding", "direction", "limits");
        assertThat(PromptGenFacts.required(trader, false)).containsExactly("goal", "style", "holding", "direction", "limits");

        trader.setCustomPrompt("[Goal]\nGrow the account");
        assertThat(PromptGenFacts.required(trader, false))
                .containsExactly("mode", "goal", "style", "holding", "direction", "limits");
        // 写出过版本后用户把它存进了 My Trader：不因此回头问 mode
        assertThat(PromptGenFacts.required(trader, true)).containsExactly("goal", "style", "holding", "direction", "limits");
    }

    @Test
    void englishPromptsHaveNoCjk() {
        AiTrader trader = new AiTrader();
        trader.setSymbols("BTCUSDT,ETHUSDT");
        trader.setIntervalCode("1h");
        trader.setCustomPrompt("[Goal]\nGrow the account");
        PromptGenFacts.Prompts withTrader = facts.prompts(trader, false, AgentLang.EN, AgentLang.EN);
        PromptGenFacts.Prompts noTrader = facts.prompts(null, false, AgentLang.EN, AgentLang.ZH);

        PromptI18nAssertions.assertNoCjk("英文采访者", withTrader.agent());
        PromptI18nAssertions.assertNoCjk("英文写稿人", withTrader.writer());
        PromptI18nAssertions.assertNoCjk("英文采访者（没 trader）", noTrader.agent());
        PromptI18nAssertions.assertNoCjk("英文写稿人（正文中文）", noTrader.writer());
        for (String key : List.of("kickoff", "skipped", "askShown", "askOnePerTurn", "draftOnce", "draftAfterAsk",
                "writeCancelled", "emptyDraft", "noPlayStats", "noQa", "noPrevious", "userNote")) {
            PromptI18nAssertions.assertNoCjk("英文 " + key, prompts.get(AgentLang.EN, "promptgen." + key));
        }
        Map<String, Object> vars = Map.of("max", 10, "keys", "style", "mark", PromptGenFacts.NOTES_MARK,
                "qa", "-", "brief", "-", "previous", "-");
        for (String key : List.of("askLimit", "requiredMissing", "shrink", "truncatedNote", "writerInput")) {
            PromptI18nAssertions.assertNoCjk("英文 " + key, prompts.get(AgentLang.EN, "promptgen." + key, vars));
        }
    }

    /** 没 trader 时按默认心跳 1h 算：噪音止损距离 = 15m 的 0.3% × √4 = 0.6% */
    @Test
    void writerPromptCarriesComputedNumbers() {
        String writer = facts.prompts(null, false, AgentLang.ZH, AgentLang.EN).writer();

        assertThat(writer).contains(PromptGenFacts.NOTES_MARK).contains("英文").contains("4000")
                .contains("止损距离 0.25%").contains("占比缩到 8.33%").contains("0.6%").contains("超过上限 20%");
    }
}
