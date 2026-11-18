package com.mawai.wiibagent.learning;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderExperience;
import com.mawai.wiibcommon.enums.AgentLang;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.entity.AiTraderExperienceTrade.MARK_BROKEN;
import static com.mawai.wiibcommon.entity.AiTraderExperienceTrade.MARK_KEPT;
import static com.mawai.wiibcommon.entity.AiTraderExperienceTrade.MARK_MISCITED;
import static com.mawai.wiibcommon.entity.AiTraderExperienceTrade.MARK_UNDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 段标记、行首固定词与标记词的双语解析。模型这一轮刚交回的输出只认当前语言那一套：
 * 提示词刚说了用哪套，交回另一套就是没照格式走，该判失守（账本不动）。两套都认的话，
 * "英文提示词却输出中文"这种真失守会被悄悄放过。
 */
class PromptMarkParsingTest {

    private final PromptCatalog prompts = new PromptCatalog();
    private final ExperienceOpParser parser = new ExperienceOpParser(prompts);

    private static final String ZH_REVIEW = """
            【本期复盘】
            战绩：起始权益 10000 → 期末 9800
            案例一：PULLBACK（2 笔，净 -300）
            【经验更新】
            #75 名下 2238、2241 ｜ 两笔都保留了原主阻力挂单
            #86 无
            #61 守 2201，2203 ｜ 破 2207 ｜ 2207 的止损只有 0.62 个 ATR
            #62 名下 2210 ｜ 引用不符 2225 ｜ 2225 触发的是 15m 收盘
            新增 BREAKOUT 入场条件 限制型 ｜ 5m 或 15m 跌破颈线后 → 不市价追，只在颈线挂限价 ｜ HYPE 4 笔市价追空净亏 -783 ｜ 名下 2199、2201、2207
            新增 通用 执行缺口 执行型 ｜ 开仓设止损时 → 不小于 0.75 个 1h ATR ｜ 2207 只有 0.62 ｜ 破 2207 ｜ 来源 #72
            发现 ｜ 指令第六条少赚 4800 ｜ 5 笔
            删除 #74 ｜ 只剩一天的结果，腾名额
            随口一句不是表里的行""";
    private static final String EN_REVIEW = """
            [REVIEW]
            Scorecard: 10000 -> 9800
            [LEDGER UPDATES]
            #75 UNDER 2238, 2241 | both kept the original resistance limit
            #86 NONE
            #61 KEPT 2201 2203 | BROKEN [2207] | 2207's stop was only 0.62 ATR
            ADD pullback RISK EXECUTION | when setting the stop -> at least 1 ATR on 1h | 5 trades | UNDER 2238
            FINDING | rule six left 4800 on the table | 5 trades
            DELETE #74 | duplicates instruction five""";
    /** 既没有操作行也没有「本期无更新」 */
    private static final ExperienceOpParser.ReviewParsed NOTHING = new ExperienceOpParser.ReviewParsed(List.of(), false);

    // ==================== reviewer 经验更新 ====================

    @Test
    void 中文复盘_认中文标记与固定词() {
        List<ExperienceLedger.Op> ops = parser.parse(ZH_REVIEW, AgentLang.ZH).ops();

        assertThat(ops).containsExactly(
                new ExperienceLedger.Classify(75L, List.of(mark(MARK_UNDER, 2238L), mark(MARK_UNDER, 2241L))),
                new ExperienceLedger.Classify(86L, List.of()),
                new ExperienceLedger.Classify(61L, List.of(mark(MARK_KEPT, 2201L), mark(MARK_KEPT, 2203L), mark(MARK_BROKEN, 2207L))),
                new ExperienceLedger.Classify(62L, List.of(mark(MARK_UNDER, 2210L), mark(MARK_MISCITED, 2225L))),
                new ExperienceLedger.Add("BREAKOUT", AiTraderExperience.SLOT_ENTRY, AiTraderExperience.DIR_RESTRICTION,
                        "5m 或 15m 跌破颈线后 → 不市价追，只在颈线挂限价", "HYPE 4 笔市价追空净亏 -783",
                        List.of(mark(MARK_UNDER, 2199L), mark(MARK_UNDER, 2201L), mark(MARK_UNDER, 2207L)), null),
                new ExperienceLedger.Add(null, AiTraderExperience.SLOT_EXEC, AiTraderExperience.DIR_EXECUTION,
                        "开仓设止损时 → 不小于 0.75 个 1h ATR", "2207 只有 0.62", List.of(mark(MARK_BROKEN, 2207L)), 72L),
                new ExperienceLedger.Finding("指令第六条少赚 4800", "5 笔"),
                new ExperienceLedger.Delete(74L, "只剩一天的结果，腾名额"));
    }

    /** 英文：编号之间用逗号或空格，编号带方括号也认 */
    @Test
    void 英文复盘_认英文标记与固定词() {
        List<ExperienceLedger.Op> ops = parser.parse(EN_REVIEW, AgentLang.EN).ops();

        assertThat(ops).hasSize(6);
        assertThat(ops.get(1)).isEqualTo(new ExperienceLedger.Classify(86L, List.of()));
        assertThat(ops.get(2)).isEqualTo(new ExperienceLedger.Classify(61L,
                List.of(mark(MARK_KEPT, 2201L), mark(MARK_KEPT, 2203L), mark(MARK_BROKEN, 2207L))));
        assertThat(ops.get(3)).isEqualTo(new ExperienceLedger.Add("PULLBACK", AiTraderExperience.SLOT_RISK,
                AiTraderExperience.DIR_EXECUTION, "when setting the stop -> at least 1 ATR on 1h", "5 trades",
                List.of(mark(MARK_UNDER, 2238L)), null));
    }

    /** 反例：语言与标记交叉 → 一行都解析不出，账本不动 */
    @Test
    void 复盘标记与语言对不上_判格式失守不污染账本() {
        assertThat(parser.parse(EN_REVIEW, AgentLang.ZH)).as("中文用户交回英文").isEqualTo(NOTHING);
        assertThat(parser.parse(ZH_REVIEW, AgentLang.EN)).as("英文用户交回中文——最容易被漏掉的那一种").isEqualTo(NOTHING);
    }

    /**
     * 编号行从严，结构不对整行丢弃：第一段就是说明、说明后面又有标记段、「无」和别的段混写、
     * 同一笔出现在两段里、编号不是数字或带 #、超长的经验编号。说明里带「｜」切成几段的照常认，同一段里重复的编号去重
     */
    @Test
    void 编号行结构不对整行丢弃() {
        List<ExperienceLedger.Op> ops = parser.parseOps("""
                #75 本期两笔都属于它
                #75 名下 2238 ｜ 说明 ｜ 破 2207
                #75 无 ｜ 名下 2238
                #75 名下 2238 ｜ 引用不符 2238
                #75 名下 2238x
                #75 名下 #2238
                #12345678901234567890 名下 1
                #76 名下 2238、2238 ｜ 说明一 ｜ 说明二""", AgentLang.ZH);

        assertThat(ops).containsExactly(new ExperienceLedger.Classify(76L, List.of(mark(MARK_UNDER, 2238L))));
    }

    /**
     * 标记段写成「守 无」「名下 无」：这一段一笔都没有，不影响别的段；
     * 标记段全是这种的，按写了「无」算；和单独的「无」写在一起也不算混写
     */
    @Test
    void 标记段写无算这一段一笔都没有() {
        List<ExperienceLedger.Op> ops = parser.parseOps("""
                #61 守 无 ｜ 破 2207 ｜ 2207 的止损只有 0.62 个 ATR
                #62 名下 无 ｜ 引用不符 2225
                #63 名下 无 ｜ 引用不符 无 ｜ 本期没碰到
                #64 名下无
                #65 无 ｜ 名下 无""", AgentLang.ZH);

        assertThat(ops).containsExactly(
                new ExperienceLedger.Classify(61L, List.of(mark(MARK_BROKEN, 2207L))),
                new ExperienceLedger.Classify(62L, List.of(mark(MARK_MISCITED, 2225L))),
                new ExperienceLedger.Classify(63L, List.of()),
                new ExperienceLedger.Classify(64L, List.of()),
                new ExperienceLedger.Classify(65L, List.of()));
        assertThat(parser.parseOps("#61 KEPT NONE | BROKEN 2207\n#62 UNDER NONE", AgentLang.EN)).containsExactly(
                new ExperienceLedger.Classify(61L, List.of(mark(MARK_BROKEN, 2207L))),
                new ExperienceLedger.Classify(62L, List.of()));
    }

    /** 新增行认不出的尾段只丢那一段，这条新增照常；同一笔出现在两段里整行丢弃 */
    @Test
    void 新增行认不出的尾段只丢那一段() {
        List<ExperienceLedger.Op> ops = parser.parseOps("""
                新增 PULLBACK 入场条件 机会型 ｜ 回踩到位再挂 ｜ 6 笔 5 胜 ｜ 名下：2199 ｜ 来源 #3
                新增 通用 执行缺口 执行型 ｜ x ｜ y ｜ 守 2201 ｜ 破 2201""", AgentLang.ZH);

        assertThat(ops).containsExactly(new ExperienceLedger.Add("PULLBACK", AiTraderExperience.SLOT_ENTRY,
                AiTraderExperience.DIR_OPPORTUNITY, "回踩到位再挂", "6 笔 5 胜", List.of(), 3L));
    }

    /** 【经验更新】下单独一行「本期无更新」：没有操作行也算写了；和操作行一起出现时操作行照常解析 */
    @Test
    void 本期无更新单独一行被认出() {
        assertThat(parser.parse("【本期复盘】\n战绩：……\n下次改什么 / 可复制条件：无\n【经验更新】\n本期无更新", AgentLang.ZH))
                .isEqualTo(new ExperienceOpParser.ReviewParsed(List.of(), true));
        assertThat(parser.parse("[REVIEW]\nScorecard: …\n[LEDGER UPDATES]\nNO_UPDATES", AgentLang.EN))
                .isEqualTo(new ExperienceOpParser.ReviewParsed(List.of(), true));
        assertThat(parser.parse("【经验更新】\n本期无更新\n#3 名下 42", AgentLang.ZH).ops())
                .containsExactly(new ExperienceLedger.Classify(3L, List.of(mark(MARK_UNDER, 42L))));
        // 提示词骨架里写的就是词表里这个词
        for (AgentLang lang : AgentLang.values()) {
            assertThat(prompts.get(lang, "reviewer.system")).contains(prompts.get(lang, "reviewer.op.noUpdates"));
        }
    }

    /** 不算「本期无更新」：写在更新段外面、跟着别的字、另一门语言的词 */
    @Test
    void 本期无更新不在段里或不是单独一行都不算() {
        assertThat(parser.parse("【本期复盘】\n本期无更新", AgentLang.ZH)).as("缺更新段").isEqualTo(NOTHING);
        assertThat(parser.parse("【经验更新】\n本期无更新，两笔都不属于现役条目", AgentLang.ZH)).as("跟着别的字").isEqualTo(NOTHING);
        assertThat(parser.parse("[LEDGER UPDATES]\n本期无更新", AgentLang.EN)).as("英文用户交回中文词").isEqualTo(NOTHING);
    }

    /** 行首的列表符号剥掉再认；编号、标记词、仓位编号之间不留空格也认 */
    @Test
    void 列表符号与紧贴编号的写法照常解析() {
        List<ExperienceLedger.Op> ops = parser.parseOps("""
                - #3 名下 42
                * 新增 PULLBACK 入场条件 机会型 ｜ x ｜ y
                2. 删除 #4 ｜ 重复
                #12名下42｜b""", AgentLang.ZH);

        assertThat(ops).containsExactly(
                new ExperienceLedger.Classify(3L, List.of(mark(MARK_UNDER, 42L))),
                new ExperienceLedger.Add("PULLBACK", AiTraderExperience.SLOT_ENTRY, AiTraderExperience.DIR_OPPORTUNITY,
                        "x", "y", List.of(), null),
                new ExperienceLedger.Delete(4L, "重复"),
                new ExperienceLedger.Classify(12L, List.of(mark(MARK_UNDER, 42L))));
    }

    /** 全角空格当普通空格 */
    @Test
    void 全角空格分隔的行照常解析() {
        List<ExperienceLedger.Op> ops = parser.parseOps(
                "新增　PULLBACK　风险限制　执行型 ｜ 止损不小于 1 个 1h ATR ｜ 样本 5 ｜ 来源　#5\n#3　名下　42", AgentLang.ZH);

        assertThat(ops).containsExactly(
                new ExperienceLedger.Add("PULLBACK", AiTraderExperience.SLOT_RISK, AiTraderExperience.DIR_EXECUTION,
                        "止损不小于 1 个 1h ATR", "样本 5", List.of(), 5L),
                new ExperienceLedger.Classify(3L, List.of(mark(MARK_UNDER, 42L))));
    }

    private static ExperienceLedger.Mark mark(String mark, long positionId) {
        return new ExperienceLedger.Mark(mark, positionId);
    }

    // ==================== 输出语言硬收尾：恒在用户消息最末一行 ====================

    /**
     * 复盘的用户消息以输出语言硬收尾结尾，两门语言各钉一遍。
     * 注入的素材（账本、主人指令）可能是另一门语言，而它们排在这行之前。
     */
    @Test
    void 复盘用户消息以输出语言收尾() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setRoundNo(1);
        t.setMemory("【经验账本】\n#1 规律");
        ReviewMaterialAssembler.ReviewMaterial material = new ReviewMaterialAssembler.ReviewMaterial(
                "stats", "plays", "cases", "market", "watches", "ledger", Map.of());
        ReviewRunner review = new ReviewRunner(mock(ReviewMaterialAssembler.class), null, null,
                prompts, mock(UserLangResolver.class), mock(ExperienceLedger.class), parser);

        for (AgentLang lang : AgentLang.values()) {
            assertThat(review.userPrompt(t, material, 0, 86_400_000L, lang).stripTrailing())
                    .as("%s 复盘用户消息", lang.code())
                    .endsWith(prompts.get(lang, "reviewer.label.outputLanguage"));
        }
    }
}
