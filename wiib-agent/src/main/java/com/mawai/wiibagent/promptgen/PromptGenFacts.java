package com.mawai.wiibagent.promptgen;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.trader.TraderService;
import com.mawai.wiibagent.trader.prompt.TraderPromptAssembler;
import com.mawai.wiibagent.trader.trade.TradeGuard;
import com.mawai.wiibagent.trader.trade.TraderRiskConfig;
import com.mawai.wiibagent.trader.wakeup.TraderScheduler;
import com.mawai.wiibagent.trader.wakeup.TraderWakeupRunner;
import com.mawai.wiibagent.trader.wakeup.WakeWindow;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.enums.AgentLang;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 生成器每轮现拼的平台事实（平台模板、默认指令、harness 硬限制、风险档位、现有指令），
 * 采访的 agent 和写稿人共用同一份。数字都由代码算好，模型只引用。
 */
@Component
@RequiredArgsConstructor
public class PromptGenFacts {

    /** 正文与 notes 的分界：写稿人单独起一行写它 */
    static final String NOTES_MARK = "=====NOTES=====";

    /** 15m 上比这还小的止损距离基本落在噪音里；别的周期按时间的平方根缩放 */
    static final double NOISE_STOP_PCT_15M = 0.3;

    /** 风格 → 单笔风险（占权益 %） */
    private static final List<Map.Entry<String, BigDecimal>> STYLES = List.of(
            Map.entry("conservative", new BigDecimal("0.5")),
            Map.entry("balanced", BigDecimal.ONE),
            Map.entry("aggressive", new BigDecimal("2")));

    /** 必问项：方向 / 持仓时长 / 风格 */
    private static final List<String> BASE_REQUIRED = List.of("direction", "holding", "style");

    /** 有现成指令、本对话又还没写出过版本时多问一项：在它上面改还是从零写 */
    static final String MODE_KEY = "mode";

    private final TraderPromptAssembler assembler;
    private final PromptCatalog prompts;

    /** 采访者与写稿人的系统提示词 */
    public record Prompts(String agent, String writer) {
    }

    /** 一档风险：顶格仓位（杠杆上限 × 占比上限）时的止损距离（%），和止损放在噪音线上时占比要缩到多少（%） */
    record RiskRow(String style, BigDecimal riskPct, BigDecimal stopAtFull, BigDecimal shareAtNoise) {
    }

    /**
     * @param drafted 本对话已经写出过版本：之后都是在上一版上改，mode 不用再问
     *                （用户把版本存进 My Trader 后现成指令就有了，不能因此回头问）
     */
    static Set<String> required(AiTrader trader, boolean drafted) {
        Set<String> keys = new LinkedHashSet<>();
        if (!drafted && trader != null && trader.getCustomPrompt() != null && !trader.getCustomPrompt().isBlank()) {
            keys.add(MODE_KEY);
        }
        keys.addAll(BASE_REQUIRED);
        return keys;
    }

    /** @param trader 可空：还没建 trader 就按平台默认规格 */
    public Prompts prompts(AiTrader trader, boolean drafted, AgentLang lang, AgentLang outputLang) {
        String facts = facts(trader, lang);
        String agent = prompts.get(lang, "promptgen.agent", Map.of(
                "facts", facts,
                "required", String.join(", ", required(trader, drafted)),
                "maxAsks", PromptGenToolkit.MAX_ASKS));
        String writer = prompts.get(lang, "promptgen.writer", Map.of(
                "facts", facts,
                "bodyLang", prompts.get(lang, "promptgen.langName." + outputLang.code()),
                "notesLang", prompts.get(lang, "promptgen.langName." + lang.code()),
                "mark", NOTES_MARK,
                "maxChars", TraderService.MAX_CUSTOM_PROMPT));
        return new Prompts(agent, writer);
    }

    private String facts(AiTrader trader, AgentLang lang) {
        // 没建 trader 时的默认值与 /prompt-template 预览接口同一套
        String symbols = trader == null ? "BTCUSDT" : trader.getSymbols();
        String interval = trader == null ? TraderScheduler.DEFAULT_INTERVAL : trader.getIntervalCode();
        WakeWindow window = trader == null ? null : WakeWindow.of(trader);
        TraderRiskConfig risk = TraderRiskConfig.of(trader == null ? new AiTrader() : trader);
        String existing = trader == null ? null : trader.getCustomPrompt();
        return prompts.get(lang, "promptgen.facts", Map.of(
                "trader", prompts.get(lang, trader == null ? "promptgen.noTrader" : "promptgen.hasTrader"),
                "template", assembler.templatePreview(lang, interval, symbols, risk,
                        window == null ? null : window.text()),
                "defaultInstructions", assembler.defaultInstructions(lang),
                "harness", prompts.get(lang, "promptgen.harness", Map.of(
                        "maxSymbols", TraderService.MAX_SYMBOLS,
                        "maxCalls", TraderWakeupRunner.MAX_MODEL_CALLS,
                        "maxChars", TraderService.MAX_CUSTOM_PROMPT,
                        // Set.of 每次启动迭代顺序不同，排一下让提示词文本稳定
                        "playTypes", TradeGuard.PLAY_TYPES.stream().sorted().collect(Collectors.joining(", ")))),
                "risk", riskBlock(risk, interval, lang),
                "existing", existing == null || existing.isBlank()
                        ? prompts.get(lang, "promptgen.noExisting")
                        : prompts.get(lang, "promptgen.existing", Map.of("text", existing))));
    }

    private String riskBlock(TraderRiskConfig risk, String interval, AgentLang lang) {
        BigDecimal noise = noiseStopPct(interval);
        String rows = riskTable(risk, noise).stream()
                .map(row -> prompts.get(lang, "promptgen.riskRow", Map.of(
                        "style", prompts.get(lang, "promptgen.style." + row.style()),
                        "risk", plain(row.riskPct()),
                        "levMax", risk.leverageMax(),
                        "marginMax", plain(risk.marginPctMax()),
                        "stopAtFull", plain(row.stopAtFull()),
                        "noise", plain(noise),
                        "shareAtNoise", plain(row.shareAtNoise()))))
                .collect(Collectors.joining("\n"));
        return prompts.get(lang, "promptgen.risk", Map.of(
                "rows", rows, "interval", interval, "noise", plain(noise), "marginMax", plain(risk.marginPctMax())));
    }

    /**
     * 风险 = 止损距离% × 杠杆 × 保证金占比。顶格仓位（杠杆上限 × 占比上限）反算止损距离；
     * 止损放在噪音线上、杠杆取上限，反算占比。占比没有下限，止损再远都能靠缩仓位把风险压回这一档；
     * 反算出的占比超过上限，就是止损放在噪音线上这档风险做不满（提示词里让生成器点明）。
     */
    static List<RiskRow> riskTable(TraderRiskConfig risk, BigDecimal noiseStopPct) {
        return STYLES.stream()
                .map(style -> new RiskRow(style.getKey(), style.getValue(),
                        solve(style.getValue(), risk.leverageMax(), risk.marginPctMax()),
                        solve(style.getValue(), risk.leverageMax(), noiseStopPct)))
                .toList();
    }

    /** 风险 = 止损距离% × 杠杆 × 占比%：止损距离与占比里已知一个（百分数），反算另一个（百分数） */
    private static BigDecimal solve(BigDecimal riskPct, int leverage, BigDecimal knownPct) {
        return riskPct.multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(leverage).multiply(knownPct), 2, RoundingMode.HALF_UP);
    }

    /** 这个周期上止损距离的噪音线（%）：interval 形如 5m / 1h / 4h */
    static BigDecimal noiseStopPct(String interval) {
        int unit = switch (interval.charAt(interval.length() - 1)) {
            case 'h' -> 60;
            case 'd' -> 1440;
            default -> 1;
        };
        int minutes = Integer.parseInt(interval.substring(0, interval.length() - 1)) * unit;
        return BigDecimal.valueOf(NOISE_STOP_PCT_15M * Math.sqrt(minutes / 15.0)).setScale(2, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
