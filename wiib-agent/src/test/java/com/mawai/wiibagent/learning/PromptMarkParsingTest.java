package com.mawai.wiibagent.learning;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderExperience;
import com.mawai.wiibcommon.entity.AiTraderExperienceVerdict;
import com.mawai.wiibcommon.enums.AgentLang;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 段标记与行首固定词的双语解析。模型这一轮刚交回的输出只认当前语言那一套：
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
            #3 支持 ｜ 09-21 BTC +3073
            #1 破 ｜ SOL 止损 0.87% 小于 1.0%
            新增 PULLBACK 盈利处理 执行型 ｜ 出场只认目标位或已收盘 1h 跌破最近 1h 低点 ｜ 样本 5
            新增 通用 执行缺口 执行型 ｜ 止损不小于缓冲 ｜ SOL 23:33
            发现 ｜ 指令第六条少赚 4800 ｜ 5 笔
            删除 #2 ｜ 与指令第五节重复
            随口一句不是表里的行""";
    private static final String EN_REVIEW = """
            [REVIEW]
            Scorecard: 10000 -> 9800
            [LEDGER UPDATES]
            #3 SUPPORT | 09-21 BTC +3073
            #1 BROKEN | SOL stop 0.87% under 1.0%
            ADD pullback PROFIT_HANDLE EXECUTION | exit only at target or a closed 1h below the last 1h low | 5 trades
            FINDING | rule six left 4800 on the table | 5 trades
            DELETE #2 | duplicates instruction five""";

    // ==================== reviewer 经验更新 ====================

    @Test
    void 中文复盘_认中文标记与固定词() {
        List<ExperienceLedger.Op> ops = parser.parse(ZH_REVIEW, AgentLang.ZH);

        assertThat(ops).hasSize(6);
        assertThat(ops.get(0)).isEqualTo(new ExperienceLedger.Verdict(3L, AiTraderExperienceVerdict.SUPPORT, "09-21 BTC +3073"));
        assertThat(ops.get(1)).isEqualTo(new ExperienceLedger.Verdict(1L, AiTraderExperienceVerdict.OPPOSE, "SOL 止损 0.87% 小于 1.0%"));
        assertThat(ops.get(2)).isEqualTo(new ExperienceLedger.Add("PULLBACK", AiTraderExperience.SLOT_PROFIT_HANDLE,
                AiTraderExperience.DIR_EXECUTION, "出场只认目标位或已收盘 1h 跌破最近 1h 低点", "样本 5", null));
        assertThat(ops.get(3)).isEqualTo(new ExperienceLedger.Add(null, AiTraderExperience.SLOT_EXEC,
                AiTraderExperience.DIR_EXECUTION, "止损不小于缓冲", "SOL 23:33", null));
        assertThat(ops.get(4)).isEqualTo(new ExperienceLedger.Finding("指令第六条少赚 4800", "5 笔"));
        assertThat(ops.get(5)).isEqualTo(new ExperienceLedger.Delete(2L, "与指令第五节重复"));
    }

    @Test
    void 英文复盘_认英文标记与固定词() {
        List<ExperienceLedger.Op> ops = parser.parse(EN_REVIEW, AgentLang.EN);

        assertThat(ops).hasSize(5);
        assertThat(ops.get(1)).isEqualTo(new ExperienceLedger.Verdict(1L, AiTraderExperienceVerdict.OPPOSE, "SOL stop 0.87% under 1.0%"));
        assertThat(ops.get(2)).isEqualTo(new ExperienceLedger.Add("PULLBACK", AiTraderExperience.SLOT_PROFIT_HANDLE,
                AiTraderExperience.DIR_EXECUTION, "exit only at target or a closed 1h below the last 1h low", "5 trades", null));
    }

    /** 反例：语言与标记交叉 → 一行都解析不出，账本不动 */
    @Test
    void 复盘标记与语言对不上_判格式失守不污染账本() {
        assertThat(parser.parse(EN_REVIEW, AgentLang.ZH)).as("中文用户交回英文").isEmpty();
        assertThat(parser.parse(ZH_REVIEW, AgentLang.EN)).as("英文用户交回中文——最容易被漏掉的那一种").isEmpty();
    }

    /** 行首的列表符号剥掉再认；编号后紧贴固定词也认 */
    @Test
    void 列表符号与紧贴编号的写法照常解析() {
        List<ExperienceLedger.Op> ops = parser.parseOps("""
                - #3 支持 ｜ a
                * 新增 PULLBACK 入场条件 机会型 ｜ x ｜ y
                2. 删除 #4 ｜ 重复
                #12反对｜b""", AgentLang.ZH);

        assertThat(ops).containsExactly(
                new ExperienceLedger.Verdict(3L, AiTraderExperienceVerdict.SUPPORT, "a"),
                new ExperienceLedger.Add("PULLBACK", AiTraderExperience.SLOT_ENTRY, AiTraderExperience.DIR_OPPORTUNITY, "x", "y", null),
                new ExperienceLedger.Delete(4L, "重复"),
                new ExperienceLedger.Verdict(12L, AiTraderExperienceVerdict.OPPOSE, "b"));
        assertThat(parser.parseAudit("1. #5保留 ｜ 理由", AgentLang.ZH).audits())
                .containsExactly(new ExperienceLedger.Audit(5L, ExperienceLedger.AUDIT_KEEP, null, "理由"));
    }

    /** 编号最多 18 位：超长的编号整行不认，来源也不认 */
    @Test
    void 超长编号的行被忽略() {
        assertThat(parser.parseOps("#12345678901234567890 支持 ｜ x\n删除 #12345678901234567890 ｜ y", AgentLang.ZH)).isEmpty();

        ExperienceOpParser.AuditParsed audit = parser.parseAudit(
                "#12345678901234567890 保留 ｜ 理由\n新增 PULLBACK 入场条件 机会型 ｜ a ｜ b ｜ 来源 #12345678901234567890", AgentLang.ZH);
        assertThat(audit.audits()).isEmpty();
        assertThat(audit.adds()).isEmpty();
    }

    @Test
    void 复审输出_四种结论与拆分新增() {
        String zh = """
                #5 保留 ｜ 支持率 85%，历史最长连败 4 次
                #6 修订 ｜ regime 非 SHOCK 时才成立 ｜ 3 次反对全在 SHOCK
                #7 拆分 ｜ ADX 大于 40 与 30 以下结局相反
                新增 PULLBACK 入场条件 机会型 ｜ ADX>40 时回踩做多 ｜ 6 笔 5 胜 ｜ 来源 #7
                新增 PULLBACK 入场条件 限制型 ｜ ADX<30 不做回踩 ｜ 4 笔 0 胜 ｜ 来源 #7
                #8 退役 ｜ 近 20 次支持 6 反对 14""";

        ExperienceOpParser.AuditParsed p = parser.parseAudit(zh, AgentLang.ZH);

        assertThat(p.audits()).containsExactly(
                new ExperienceLedger.Audit(5L, ExperienceLedger.AUDIT_KEEP, null, "支持率 85%，历史最长连败 4 次"),
                new ExperienceLedger.Audit(6L, ExperienceLedger.AUDIT_REVISE, "regime 非 SHOCK 时才成立", "3 次反对全在 SHOCK"),
                new ExperienceLedger.Audit(7L, ExperienceLedger.AUDIT_SPLIT, null, "ADX 大于 40 与 30 以下结局相反"),
                new ExperienceLedger.Audit(8L, ExperienceLedger.AUDIT_RETIRE, null, "近 20 次支持 6 反对 14"));
        assertThat(p.adds()).hasSize(2);
        assertThat(p.adds().get(0).sourceId()).isEqualTo(7L);
        assertThat(p.adds().get(1).direction()).isEqualTo(AiTraderExperience.DIR_RESTRICTION);

        ExperienceOpParser.AuditParsed en = parser.parseAudit(
                "#5 KEEP | within variance\nADD GENERAL RISK RESTRICTION | x | y | SOURCE #5", AgentLang.EN);
        assertThat(en.audits()).containsExactly(new ExperienceLedger.Audit(5L, ExperienceLedger.AUDIT_KEEP, null, "within variance"));
        assertThat(en.adds().getFirst().sourceId()).isEqualTo(5L);
    }

    /** 复盘路径的新增不认来源：写了「来源 #3」也是普通新增，来源只属于复审 */
    @Test
    void 复盘路径的新增不继承来源() {
        List<ExperienceLedger.Op> ops = parser.parseOps(
                "新增 PULLBACK 入场条件 机会型 ｜ ADX>40 时回踩做多 ｜ 6 笔 5 胜 ｜ 来源 #3", AgentLang.ZH);

        assertThat(ops).containsExactly(new ExperienceLedger.Add("PULLBACK", AiTraderExperience.SLOT_ENTRY,
                AiTraderExperience.DIR_OPPORTUNITY, "ADX>40 时回踩做多", "6 笔 5 胜", null));
    }

    /** 全角空格当普通空格：复盘与复审两条路都认 */
    @Test
    void 全角空格分隔的行照常解析() {
        List<ExperienceLedger.Op> ops = parser.parseOps(
                "新增　PULLBACK　盈利处理　执行型 ｜ 出场只认目标位 ｜ 样本 5\n#3　支持 ｜ 证据", AgentLang.ZH);
        assertThat(ops).containsExactly(
                new ExperienceLedger.Add("PULLBACK", AiTraderExperience.SLOT_PROFIT_HANDLE, AiTraderExperience.DIR_EXECUTION,
                        "出场只认目标位", "样本 5", null),
                new ExperienceLedger.Verdict(3L, AiTraderExperienceVerdict.SUPPORT, "证据"));

        ExperienceOpParser.AuditParsed audit = parser.parseAudit(
                "#5　保留 ｜ 理由\n新增　通用　风险限制　限制型 ｜ x ｜ y ｜ 来源　#5", AgentLang.ZH);
        assertThat(audit.audits()).containsExactly(new ExperienceLedger.Audit(5L, ExperienceLedger.AUDIT_KEEP, null, "理由"));
        assertThat(audit.adds().getFirst().sourceId()).isEqualTo(5L);
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
                "stats", "plays", "cases", "market", "ledger", 1, Map.of());
        ReviewRunner review = new ReviewRunner(mock(ReviewMaterialAssembler.class), null, null,
                prompts, mock(UserLangResolver.class), mock(ExperienceLedger.class), parser, null);

        for (AgentLang lang : AgentLang.values()) {
            assertThat(review.userPrompt(t, material, 0, 86_400_000L, lang).stripTrailing())
                    .as("%s 复盘用户消息", lang.code())
                    .endsWith(prompts.get(lang, "reviewer.label.outputLanguage"));
        }
    }
}
