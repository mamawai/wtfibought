package com.mawai.wiibagent.learning;

import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.trader.DecisionText;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 段标记的双语解析——这批最容易出的 bug 就钉在这里：英文提示词让模型输出英文标记，
 * 解析却还在找中文标记，于是每次都判"格式失守"，memory / learning_notes 永远不更新，
 * 而日志只说一句"缺分隔符"，从产出上完全看不出是解析侧掉队了。
 * <p>
 * 两套判据刻意不同，分别对应两种真实场景：
 * <ul>
 *   <li><b>模型这一轮刚交回的输出</b>（{@link ReviewRunner#parse} / {@link LearningRunner#missingMarks}）
 *       只认当前语言那一套：提示词刚说了用哪套标记，交回另一套就是没照格式走，该判失守。
 *       两套都认的话，"英文提示词却输出中文"这种真失守会被悄悄放过。</li>
 *   <li><b>库里的历史决策</b>（{@link ReviewMaterialAssembler#waitsBySymbol}）两套都认：决策是
 *       写入时那门语言落库的，用户切过语言后只认当前这套，整条时间线会变成"未给等待条件"，
 *       观望对账当场没了原料。两套标记字面不同，多认一套不会误伤。</li>
 * </ul>
 */
class PromptMarkParsingTest {

    private final PromptCatalog prompts = new PromptCatalog();

    private static final String ZH_REVIEW = "【本期复盘】战绩：起始 10000 → 期末 9800\n【记忆更新】已验证纪律：等回踩";
    private static final String EN_REVIEW = "[REVIEW] Scorecard: 10000 -> 9800\n[MEMORY UPDATE] verified: wait for the retest";
    private static final String ZH_LEARN = "【本期学习】看了谁：id=8\n【不学什么】样本 1 笔\n【前车之鉴】爆仓链条";
    private static final String EN_LEARN = "[WHAT I LEARNED] id=8\n[WHAT I AM NOT TAKING] sample of 1\n[CAUTIONARY TALES] blow-up chain";

    // ==================== reviewer 两段分隔符 ====================

    @Test
    void 中文复盘_认中文标记切两段() {
        ReviewRunner.Parsed p = ReviewRunner.parse(ZH_REVIEW, prompts.get(AgentLang.ZH, "reviewer.mark.memory"));

        assertThat(p.review()).isEqualTo("【本期复盘】战绩：起始 10000 → 期末 9800");
        assertThat(p.memory()).isEqualTo("已验证纪律：等回踩");
    }

    @Test
    void 英文复盘_认英文标记切两段() {
        ReviewRunner.Parsed p = ReviewRunner.parse(EN_REVIEW, prompts.get(AgentLang.EN, "reviewer.mark.memory"));

        assertThat(p.review()).isEqualTo("[REVIEW] Scorecard: 10000 -> 9800");
        assertThat(p.memory()).isEqualTo("verified: wait for the retest");
    }

    /** 反例：语言与标记交叉 → 判格式失守，memory 返回 null（REVIEW 行照存、记忆不动） */
    @Test
    void 复盘标记与语言对不上_判格式失守不污染记忆() {
        assertThat(ReviewRunner.parse(EN_REVIEW, prompts.get(AgentLang.ZH, "reviewer.mark.memory")).memory())
                .as("中文用户交回英文标记").isNull();
        assertThat(ReviewRunner.parse(ZH_REVIEW, prompts.get(AgentLang.EN, "reviewer.mark.memory")).memory())
                .as("英文用户交回中文标记——最容易被漏掉的那一种").isNull();
    }

    // ==================== learning 三段必需标记 ====================

    @Test
    void 学习必需段_两门语言各认各的() {
        LearningRunner runner = learningRunner();

        assertThat(runner.missingMarks(ZH_LEARN, AgentLang.ZH)).isEmpty();
        assertThat(runner.missingMarks(EN_LEARN, AgentLang.EN)).isEmpty();
    }

    /** 反例：交叉就是两段都缺，笔记一个字不动；error 里报的是当前语言的段名 */
    @Test
    void 学习标记与语言对不上_两段都判缺() {
        LearningRunner runner = learningRunner();

        assertThat(runner.missingMarks(EN_LEARN, AgentLang.ZH))
                .containsExactly("【本期学习】", "【不学什么】");
        assertThat(runner.missingMarks(ZH_LEARN, AgentLang.EN))
                .containsExactly("[WHAT I LEARNED]", "[WHAT I AM NOT TAKING]");
    }

    /** 缺【不学什么】＝没做否定判断＝在抄：两门语言都得单独认出来 */
    @Test
    void 只缺不学什么那一段_两门语言都指名道姓() {
        LearningRunner runner = learningRunner();

        assertThat(runner.missingMarks("【本期学习】看了谁\n【前车之鉴】坑", AgentLang.ZH))
                .containsExactly("【不学什么】");
        assertThat(runner.missingMarks("[WHAT I LEARNED] who\n[CAUTIONARY TALES] trap", AgentLang.EN))
                .containsExactly("[WHAT I AM NOT TAKING]");
    }

    // ==================== 历史决策的结论块：两套都认 ====================

    private static final String ZH_CONCLUSION =
            "行情铺垫一大段\n[本轮结论]\n判断：突破确认\n动作：HOLD\n等待：回踩 99000 站稳";
    private static final String EN_CONCLUSION =
            "some market context\n[ROUND CONCLUSION]\nJudgement: breakout confirmed\nAction: HOLD\nWaiting: retest 99000 holds";

    /** 错误格式（无分段标记）整块的等待条件从 waitsBySymbol 的 WHOLE 伪键取 */
    private String wholeWait(String reasoning, AgentLang lang) {
        return assembler().waitsBySymbol(reasoning, lang).get(ReviewMaterialAssembler.WHOLE);
    }

    @Test
    void 结论块_同语言取得出等待条件() {
        assertThat(wholeWait(ZH_CONCLUSION, AgentLang.ZH)).isEqualTo("回踩 99000 站稳");
        assertThat(wholeWait(EN_CONCLUSION, AgentLang.EN)).isEqualTo("retest 99000 holds");
    }

    /** 用户切了语言：旧决策还是旧语言写的，reviewer 照样得读得出来，否则观望对账整段空转 */
    @Test
    void 结论块_切语言后旧决策照样读得出() {
        assertThat(wholeWait(ZH_CONCLUSION, AgentLang.EN)).isEqualTo("回踩 99000 站稳");
        assertThat(wholeWait(EN_CONCLUSION, AgentLang.ZH)).isEqualTo("retest 99000 holds");
    }

    /** "等待条件："全称与"等待："都得认（中文侧原有的容忍不许在搬家时丢） */
    @Test
    void 结论块_等待条件全称也认() {
        assertThat(wholeWait("[本轮结论]\n判断：观望\n等待条件：站稳 99000", AgentLang.ZH))
                .isEqualTo("站稳 99000");
        assertThat(wholeWait("[ROUND CONCLUSION]\nAction: HOLD\nWaiting for: 99000 holds", AgentLang.EN))
                .isEqualTo("99000 holds");
    }

    /** 没有结论块就是没有条件：不拿正文冒充，否则对账只能编出假结论 */
    @Test
    void 没有结论块_两门语言都返回空() {
        assertThat(wholeWait("BTC 走强，我先看着。", AgentLang.ZH)).isEmpty();
        assertThat(wholeWait("BTC looks strong, watching for now.", AgentLang.EN)).isEmpty();
    }

    // ==================== 输出语言硬收尾：恒在用户消息最末一行 ====================

    /**
     * 复盘与学习的用户消息都以输出语言硬收尾结尾，两门语言各钉一遍。
     * 注入的素材（历史决策、旧笔记、同侪材料含别人给 trader 起的名字）可能是另一门语言，
     * 而它们排在这行之前。
     */
    @Test
    void 复盘与学习的用户消息都以输出语言收尾() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setRoundNo(1);
        t.setMemory("旧笔记：等回踩");
        t.setLearningNotes("Peer A: BREAKOUT 12 trades 8 wins.");
        ReviewMaterialAssembler.ReviewMaterial material = new ReviewMaterialAssembler.ReviewMaterial(
                "stats", "trades", "timeline", "path", 1);
        ReviewRunner review = new ReviewRunner(assembler(), null, null, null,
                prompts, mock(UserLangResolver.class));
        LearningRunner learn = learningRunner();

        for (AgentLang lang : AgentLang.values()) {
            assertThat(review.userPrompt(t, material, 0, 86_400_000L, null, lang).stripTrailing())
                    .as("%s 复盘用户消息", lang.code())
                    .endsWith(prompts.get(lang, "reviewer.label.outputLanguage"));
            assertThat(learn.userPrompt(t, 86_400_000L, "leaderboard", lang).stripTrailing())
                    .as("%s 学习用户消息", lang.code())
                    .endsWith(prompts.get(lang, "learning.label.outputLanguage"));
        }
    }

    private ReviewMaterialAssembler assembler() {
        return new ReviewMaterialAssembler(mock(AiTraderDecisionMapper.class), mock(AiTraderPlanMapper.class),
                mock(SimTradeClient.class), mock(KlineHistoryStore.class), prompts,
                new DecisionText(prompts));
    }

    private LearningRunner learningRunner() {
        return new LearningRunner(mock(PeerInsightService.class), null, null, null,
                prompts, new LocalizedToolCallbacks(prompts), mock(UserLangResolver.class));
    }
}
