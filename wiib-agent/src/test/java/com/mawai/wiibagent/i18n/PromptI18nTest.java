package com.mawai.wiibagent.i18n;

import java.util.Map;
import com.mawai.wiibagent.replay.ReplayCoachRequest;
import com.mawai.wiibagent.replay.ReplayCoachPrompts;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibagent.learning.PeerInsightService;
import com.mawai.wiibagent.learning.ReviewMaterialAssembler;
import com.mawai.wiibagent.trader.DecisionText;
import com.mawai.wiibagent.trader.prompt.TraderPromptAssembler;
import com.mawai.wiibagent.trader.trade.TraderRiskConfig;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 交易链三处提示词的双语契约。三条底线，每条对应一类"上线才发现"的事故：
 * <ul>
 *   <li><b>英文用户不许收到一个中文字</b>——逐码点扫 CJK。提示词里混一段中文，模型多半会跟着
 *       用中文回答，产出直接串语言；靠肉眼看是看不全的（段名藏在代码拼接里）。</li>
 *   <li><b>中文侧的关键约束一条都不许在搬家时丢</b>——这批是"外置 + 加英文版"，不是重写中文提示词。
 *       钉住的是行为约束句，不是文案措辞。</li>
 *   <li><b>分隔符按语言认对应那一套</b>——见 {@code PromptMarkParsingTest}（要包内可见的
 *       parse/missingMarks/waitSection，放在 learning 包里）。</li>
 * </ul>
 */
class PromptI18nTest {

    private static final long FROM = 1785110400000L;
    private static final long TO = FROM + 86_400_000L;

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTrader.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderDecision.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderPlan.class);
    }

    private final PromptCatalog prompts = new PromptCatalog();
    private final ReplayCoachPrompts coach = new ReplayCoachPrompts(prompts);

    /** 笔记上限按语言给（见 NoteBudget）；提示词里那句"≤N"就是这个数注进去的 */
    private static final Map<String, Object> NOTE_CAP_ZH = Map.of("maxChars", 2000);
    private static final Map<String, Object> NOTE_CAP_EN = Map.of("maxChars", 4000);

    /** 逐码点扫 CJK 的实现在 {@link PromptI18nAssertions}：唤醒开场白那条钉子在 trader 包，两边共用一份 */
    private static void assertNoCjk(String label, String text) {
        PromptI18nAssertions.assertNoCjk(label, text);
    }

    // ==================== ① 英文用户拿到的提示词零中文 ====================

    @Test
    void 英文trader提示词全文无中文() {
        TraderPromptAssembler assembler =
                new TraderPromptAssembler(mock(AiTraderMapper.class), prompts);
        AiTrader t = enTrader();
        t.setMemory("突破回踩不守住颈线就别追。");            // 旧笔记是中文：原样注入不算违规
        t.setLearningNotes("同侪A的BREAKOUT 12笔8胜。");
        t.setCustomPrompt("Breakouts only.");
        t.setOwnerNote("Watch CPI tonight.");
        t.setOwnerNoteRounds(3);
        t.setWakeWindow("21:00-08:30");

        String full = assembler.assemble(t, AgentLang.EN);
        // 用户自己写的字与旧笔记原样注入，扫描前剥掉——它们本来就不该被翻译
        String platform = full.replace(t.getMemory(), "").replace(t.getLearningNotes(), "");
        assertNoCjk("英文 trader 提示词", platform);
        // 留言段走开场白不走 system：段头/亲笔提示/footer 同样零中文
        assertNoCjk("英文留言段", assembler.ownerNoteBlock(t, AgentLang.EN));

        // 各条件分支的片段也得干净：单仓 / 禁双开
        AiTrader strict = enTrader();
        strict.setAllowMultiPosition(false);
        strict.setAllowHedge(false);
        assertNoCjk("英文 trader 模板（严格档）", assembler.platformTemplate(
                AgentLang.EN, "4h", "BTCUSDT", TraderRiskConfig.of(strict), "21:00-08:30"));
    }

    @Test
    void 英文reviewer提示词与素材块全文无中文() {
        assertNoCjk("英文 reviewer 系统提示词", prompts.get(AgentLang.EN, "reviewer.system", NOTE_CAP_EN));
        assertNoCjk("英文 reviewer 收尾指令", prompts.get(AgentLang.EN, "reviewer.label.closing"));
        assertNoCjk("英文 reviewer 输出语言硬收尾",
                prompts.get(AgentLang.EN, "reviewer.label.outputLanguage"));

        ReviewMaterialAssembler.ReviewMaterial m = enMaterial();
        assertNoCjk("英文战绩表", m.statsBlock());
        assertNoCjk("英文配对表", m.tradesBlock());
        assertNoCjk("英文时间线", m.timelineBlock());
        assertNoCjk("英文价格路径", m.pricePathBlock());
    }

    @Test
    void 英文learning提示词与同侪块全文无中文() {
        assertNoCjk("英文 learning 系统提示词", prompts.get(AgentLang.EN, "learning.system", NOTE_CAP_EN));
        assertNoCjk("英文 peer_insights 工具描述", prompts.get(AgentLang.EN, "tool.peer_insights"));
        assertNoCjk("英文 learning 收尾指令", prompts.get(AgentLang.EN, "learning.label.closing"));
        assertNoCjk("英文 learning 输出语言硬收尾",
                prompts.get(AgentLang.EN, "learning.label.outputLanguage"));

        PeerInsightService peers = enPeers();
        assertNoCjk("英文同侪排行榜", peers.leaderboard(7L, AgentLang.EN));
        assertNoCjk("英文同侪详情", peers.detail(8L, AgentLang.EN));
        assertNoCjk("英文查无此人", peers.detail(404L, AgentLang.EN));
    }

    @Test
    void 英文chat全链路提示词与过程文案全文无中文() {
        for (String key : List.of("chat.expert.market", "chat.expert.news", "chat.expert.trader",
                "chat.router", "chat.expertHandoff", "chat.yieldPlaceholder", "chat.cancelledNote",
                "chat.cancelledEmpty", "chat.yieldDoneAnswer", "chat.preloadHeader",
                "chat.expertStatus.data", "chat.expertStatus.failed", "chat.expertStatus.noContent",
                "chat.expertNoContentBody", "chat.expertNoContentReason",
                "chat.deferred.prefix", "chat.hitl.label", "chat.hitl.reason", "chat.hitl.notExecuted",
                "chat.hitl.resumeMessage", "chat.form.wake", "chat.form.review", "chat.form.note",
                "chat.compress.summaryPrefix", "chat.compress.role.user", "chat.compress.role.assistant",
                "chat.compress.role.system", "chat.compress.role.tool", "chat.compress.role.other",
                "chat.compress.clipped", "chat.deepAnalysis.noNews", "chat.deepAnalysis.noIv",
                "chat.deepAnalysis.bullStance",
                "chat.deepAnalysis.bearStance", "chat.deepAnalysis.judgeFailed",
                "chat.deepAnalysis.progress.bullDone", "chat.deepAnalysis.progress.judging",
                "chat.deepAnalysis.progress.judged", "chat.outputLanguage",
                "chat.turn.timePrefix", "chat.turn.questionPrefix",
                "chat.traderQuery.wakeWindowAllDay", "chat.traderQuery.memoryNote",
                "chat.traderQuery.learningNotesNote", "chat.traderQuery.kindNote",
                "chat.traderQuery.planNote", "chat.traderQuery.noTrader",
                "llm.callLimit.notExecuted",
                "tool.route", "tool.run_deep_analysis",
                // 已搬进词表的工具描述：英文侧不许混中文
                "tool.market_snapshot", "tool.option_iv", "tool.funding_history", "tool.orderbook_depth",
                "tool.klines", "tool.indicators", "tool.kline_structure",
                "tool.trader_overview", "tool.trader_positions", "tool.trader_decisions", "tool.trader_plans",
                "tool.wake_trader", "tool.review_trader_now", "tool.leave_note_to_trader",
                "tool.get_account", "tool.open_position", "tool.close_position",
                "tool.set_stop_loss", "tool.set_take_profit", "tool.write_plan", "tool.cancel_order")) {
            assertNoCjk("英文 " + key, prompts.get(AgentLang.EN, key));
        }
        // 带占位符的那些：真填一遍再扫，模板里的中文标点藏在占位符两边。
        // summarizer 两版新闻条款（有无联网搜索）都要扫，哪版被建出来取决于用户端点配置
        assertNoCjk("英文 summarizer(search)", summarizer(AgentLang.EN, "chat.newsRule.search"));
        assertNoCjk("英文 summarizer(noSearch)", summarizer(AgentLang.EN, "chat.newsRule.noSearch"));
        assertNoCjk("英文历史压缩提示词",
                prompts.get(AgentLang.EN, "chat.compress.prompt", Map.of("history", "u: hi")));
        assertNoCjk("英文专家出处标注", prompts.get(AgentLang.EN, "chat.expertTag",
                Map.of("agent", "market_agent", "status", prompts.get(AgentLang.EN, "chat.expertStatus.data"))));
        assertNoCjk("英文补答标头",
                prompts.get(AgentLang.EN, "chat.deferred.header", Map.of("question", "btc?")));
        assertNoCjk("英文补答指令",
                prompts.get(AgentLang.EN, "chat.deferred.instruction", Map.of("question", "btc?")));
        assertNoCjk("英文专家失败进度", prompts.get(AgentLang.EN, "chat.progress.expertFailed",
                Map.of("agent", "market_agent", "reason", "timeout")));
        assertNoCjk("英文 HITL 待确认", prompts.get(AgentLang.EN, "chat.hitl.pendingMessage",
                Map.of("label", "deep analysis", "reason", "3 calls")));
        assertNoCjk("英文 HITL 已拒绝", prompts.get(AgentLang.EN, "chat.hitl.rejectedReply",
                Map.of("label", "deep analysis")));
        assertNoCjk("英文表单回执", prompts.get(AgentLang.EN, "chat.form.opened", Map.of("label", "wake-up")));
        assertNoCjk("英文表单回执（失败）",
                prompts.get(AgentLang.EN, "chat.form.failed", Map.of("label", "wake-up")));
        assertNoCjk("英文深研判辩论提示词", prompts.get(AgentLang.EN, "chat.deepAnalysis.arguePrompt",
                Map.of("stance", "bull", "data", "d")));
        assertNoCjk("英文深研判裁决提示词", prompts.get(AgentLang.EN, "chat.deepAnalysis.judgePrompt",
                Map.of("data", "d", "bull", "b", "bear", "b", "format", "{}")));
        assertNoCjk("英文深研判数据块", prompts.get(AgentLang.EN, "chat.deepAnalysis.dataContext",
                Map.of("symbol", "BTCUSDT", "price", "1", "micro", "m", "iv", "i", "news", "n")));
        assertNoCjk("英文轮起始标记", prompts.get(AgentLang.EN, "chat.turn.timeMark",
                Map.of("time", "2026-08-18 14:32")));
        assertNoCjk("英文唤醒时段说明", prompts.get(AgentLang.EN, "chat.traderQuery.wakeWindowNote",
                Map.of("window", "21:00-08:30")));
        assertNoCjk("英文杠杆区间", prompts.get(AgentLang.EN, "chat.traderQuery.leverage",
                Map.of("min", 3, "max", 20)));
        assertNoCjk("英文加仓覆盖留痕", prompts.get(AgentLang.EN, "trader.revise.addOnNote",
                Map.of("playType", "BREAKOUT", "invalidation", "loses 99000")));
    }

    /** 翻译是平台后台任务（没有"当前用户"）：提示词整篇取英文，混一个中文字都可能把译文带回中文 */
    @Test
    void 英文翻译提示词与快讯行模板全文无中文() {
        assertNoCjk("英文翻译提示词", prompts.get(AgentLang.EN, "news.translate",
                Map.of("flashes", "id=1 TITLE: t BODY: b")));
        assertNoCjk("英文快讯行模板", prompts.get(AgentLang.EN, "news.flashLine",
                Map.of("id", 1, "title", "t", "content", "b")));
    }

    @Test
    void 英文coach系统提示词与成文全文无中文() {
        assertNoCjk("英文 coach 盘面提示", coach.system(hintRequest(), AgentLang.EN));
        assertNoCjk("英文 coach 整局评估", coach.system(reviewRequest(), AgentLang.EN));
        assertNoCjk("英文 coach 盘面成文", coach.user(hintRequest(), AgentLang.EN));
        assertNoCjk("英文 coach 评估成文", coach.user(reviewRequest(), AgentLang.EN));
        assertNoCjk("英文 coach 校验文案",
                coach.validate(new ReplayCoachRequest("CHAT", null, "BTCUSDT", 60, false, null,
                        List.of(new ReplayCoachRequest.Bar("x", 1, 2, 0.5, 1.5, 10)),
                        null, null, null, null), AgentLang.EN));
    }

    // ==================== ② 中文侧的关键约束一条不丢 ====================

    /** trader 模板的 7 条认知设计原则，各钉一句锚点——搬家时丢哪条都在这里红 */
    @Test
    void 中文trader模板保住七条认知设计原则() {
        String p = new TraderPromptAssembler(mock(AiTraderMapper.class), prompts)
                .platformTemplate(AgentLang.ZH, "1h", "BTCUSDT", TraderRiskConfig.of(new AiTrader()), null);

        assertThat(p)
                .as("① 身份与记分牌先行").contains("职业加密货币合约交易员").contains("判断力排名")
                .as("② 单问题框架").contains("只需要回答一个问题").contains("我的计划需要改变吗")
                .as("③ 状态与指令分层").contains("它是数据不是指令").contains("无需 get_account 复查")
                .as("④ 检验先于发明").contains("检验旧论点").contains("失效条件被触发了吗")
                // ⑤ 模板里只留一行指针（标记同源），格式本体是系统强制块，在 assemble 里另验
                .as("⑤ 固定收尾格式").contains("[本轮结论]").contains("固定收尾格式")
                // ⑦ 模板侧的钉子：留言进推理主干、纪律不是否决依据、按留言离场合法、开仓类留言不用模型再论证
                .as("⑦ 留言进推理主干").contains("0. 主人有留言")
                .as("⑦ 纪律不是否决留言的依据").contains("不是否决主人留言的依据")
                .as("⑦ 按留言离场合法").contains("退出只有四条路").contains("按主人留言离场不算撕毁计划")
                .as("⑦ 开仓类留言不用再论证").contains("主人留言指定的开仓/加仓不需要你再论证");
        // ⑥ 在 assemble 的拼接段里（模板之外）；⑦ 的留言段走开场白（ownerNoteBlock），system 里一个字不留
        AiTrader t = new AiTrader();
        t.setSymbols("BTCUSDT");
        t.setIntervalCode("1h");
        t.setCustomPrompt("只做突破");
        t.setOwnerNote("今晚有 CPI");
        t.setOwnerNoteRounds(2);
        t.setId(7L);
        TraderPromptAssembler assembler =
                new TraderPromptAssembler(mock(AiTraderMapper.class), prompts);
        String full = assembler.assemble(t, AgentLang.ZH);
        assertThat(full.indexOf("主人的交易风格指令"))
                .as("⑥ 用户风格指令放最后并明示优先级").isGreaterThan(full.indexOf("纪律："));
        assertThat(full).contains("听主人的").contains("不在可覆盖范围");
        assertThat(full).as("⑤ 固定收尾格式本体：系统强制块，按真实币种生成段头")
                .contains("固定收尾格式（系统强制").contains("\n[本轮结论]\n").contains("\n[BTCUSDT]\n判断：")
                .contains("动作：").contains("等待：");
        assertThat(full).as("⑦ 留言不进 system（模板正文提到「主人的留言」这几个字是纪律引言，段头才是注入痕迹）").doesNotContain("————— 主人的留言").doesNotContain("今晚有 CPI");
        assertThat(assembler.ownerNoteBlock(t, AgentLang.ZH))
                .contains("今晚有 CPI").contains("本次之后还会出现 1 次")
                .as("⑦ 举证责任倒置：执行不需要理由，否决只认数字，结论里单起一行对账（旧「尽量考虑履行/观点不成立」是万能借口）")
                .contains("执行不需要理由").contains("不引数字的反对视为没有反对").contains("主人留言：已执行")
                .doesNotContain("尽量考虑履行").doesNotContain("观点不成立").doesNotContain("不是常驻规则");
    }

    /** reviewer 的防自夸三件套 */
    @Test
    void 中文reviewer保住防自夸三条() {
        String p = prompts.get(AgentLang.ZH, "reviewer.system", NOTE_CAP_ZH);
        assertThat(p)
                .as("① 战绩数字只许复述").contains("只许原样复述，禁止自行计算或美化")
                .as("② 先找错误再找亮点").contains("先找错误再找亮点")
                .as("③ 教训条数上限").contains("逐笔教训：≤5 条").contains("下期纪律：≤3 条")
                .contains("【决策错】").contains("【运气差】")
                .contains("同样条件下次照做");
    }

    /** learning 的反照抄三件套 */
    @Test
    void 中文learning保住反照抄三条() {
        String p = prompts.get(AgentLang.ZH, "learning.system", NOTE_CAP_ZH);
        assertThat(p)
                .as("①【不学什么】必填").contains("【不学什么】是必填段").contains("你就只是在抄")
                .as("② 每条学习带证据与差距数字").contains("每条学习必须落在证据与差距上")
                .as("③ 引用战绩带笔数").contains("引用同侪战绩必须带笔数")
                .contains("【本期学习】").contains("【前车之鉴】");
    }

    /** 任务 4：三处都要显式交代"笔记可能是另一门语言，照读照用，输出用当前语言" */
    @Test
    void 三处提示词都交代了跨语言笔记() {
        assertThat(new TraderPromptAssembler(mock(AiTraderMapper.class), prompts)
                .platformTemplate(AgentLang.ZH, "1h", "BTCUSDT", TraderRiskConfig.of(new AiTrader()), null))
                .contains("可能是另一门语言写的").contains("本轮输出一律用中文");
        assertThat(prompts.get(AgentLang.ZH, "reviewer.system", NOTE_CAP_ZH)).contains("可能是另一门语言写的");
        assertThat(prompts.get(AgentLang.ZH, "learning.system", NOTE_CAP_ZH)).contains("可能是另一门语言写的");
        for (String key : List.of("reviewer.system", "learning.system")) {
            assertThat(prompts.get(AgentLang.EN, key, NOTE_CAP_EN)).contains("another language");
        }
    }

    /** 英文侧不是漏翻回落：两门语言的同一 key 一字不差就说明英文没写 */
    @Test
    void 三个域的英文词表都真有自己的文案() {
        for (String key : List.of("trader.template", "trader.label.memory", "trader.mark.conclusion",
                "reviewer.system", "reviewer.mark.memory", "reviewer.label.statsHeader",
                "learning.system", "learning.mark.skip", "learning.label.peer.leaderboardHeader",
                "tool.peer_insights", "tool.route", "tool.run_deep_analysis", "tool.news_search",
                "chat.summarizer", "chat.router", "chat.expert.market", "chat.expert.news",
                "chat.expert.trader", "chat.compress.prompt", "chat.deepAnalysis.judgePrompt",
                "chat.hitl.reason", "chat.deferred.prefix",
                "coach.hint.system", "coach.review.system", "coach.label.barsHeader",
                "trader.wake.routineQuestion", "trader.wake.sleepNotice", "trader.error.wakeTimeout",
                // 落库即公开展示的暂停原因：四种来源必须都有英文，漏一种就是面板上一行中文
                "trader.pause.manual", "trader.error.keyInvalid", "trader.error.liquidatedReason",
                // 护栏拒因既回给模型也公开在时间线上；修订标签同理
                "trader.guard.leverageRange", "trader.guard.stopLossRequired", "trader.guard.marginOutOfRange",
                "trader.reject.stopOnlyTighter", "trader.reject.expired", "trader.reject.planAlreadyExists",
                "trader.revise.moveStop",
                "trader.revise.addOnNote",
                "news.translate", "news.flashLine",
                // 轮起始标记与 trader 查询说明字段：全是喂模型的，回落成中文就混语
                "chat.turn.timeMark", "chat.traderQuery.kindNote", "llm.callLimit.notExecuted",
                // 已搬进词表的工具描述：中文侧真有译文而不是英文原样两份
                "tool.market_snapshot", "tool.option_iv", "tool.funding_history", "tool.orderbook_depth",
                "tool.klines", "tool.indicators", "tool.kline_structure",
                "tool.trader_overview", "tool.trader_positions", "tool.trader_decisions", "tool.trader_plans",
                "tool.wake_trader", "tool.review_trader_now", "tool.leave_note_to_trader",
                "tool.get_account", "tool.open_position", "tool.close_position",
                "tool.set_stop_loss", "tool.set_take_profit", "tool.write_plan", "tool.cancel_order",
                // 任务 5 的两条缓解：回落成中文＝英文用户被一行中文指令要求"输出中文"，正好反了
                "trader.label.ownerWritten", "trader.label.outputLanguage",
                "reviewer.label.outputLanguage", "learning.label.outputLanguage",
                "chat.outputLanguage", "coach.label.outputLanguage",
                // 上游异常归类后的那一句：chat 与 coach 共用，既上屏也喂回模型
                "llm.error.unauthorized", "llm.error.quota", "llm.error.modelNotFound",
                "llm.error.unreachable", "llm.error.fallback")) {
            assertThat(prompts.find(AgentLang.EN, key))
                    .as("英文词表缺 %s，回落成中文了", key)
                    .isNotEqualTo(prompts.find(AgentLang.ZH, key));
        }
    }

    /** 生产同款两步渲染：先按变体键出新闻条款，再填进 summarizer 的 {{newsRule}} 槽位 */
    private String summarizer(AgentLang lang, String newsRuleKey) {
        String newsRule = prompts.get(lang, newsRuleKey,
                Map.of("supplementTag", "[X]", "mergedTag", "[BlockBeats+X]"));
        return prompts.get(lang, "chat.summarizer", Map.of("newsRule", newsRule));
    }

    /** chat：汇总者的六条回答原则 + news/summarizer 的分工红线，搬家时丢哪条都在这里红 */
    @Test
    void 中文chat保住汇总者六原则与新闻分工() {
        String p = summarizer(AgentLang.ZH, "chat.newsRule.search");
        assertThat(p)
                .as("① 结论可追溯").contains("结论必须可追溯到专家给的数据，不编造")
                .as("② 新闻分工").contains("news_agent 只管 BlockBeats，联网补充归你")
                .as("③ 鼓励表态").contains("鼓励表态")
                .as("④ 看不清是例外").contains("不是回避表态的出口")
                .as("⑤ 深研判要明说才调").contains("仅当用户明确说出").contains("run_deep_analysis")
                .as("⑥ 动手三工具只弹表单").contains("绝不能说已经唤醒了／已经复盘了／留言已记下");
        // 另一半分工写在 news 专家那边，两处必须同时在
        assertThat(prompts.get(AgentLang.ZH, "chat.expert.news"))
                .contains("严禁把你联网搜索到的任何内容写进回答");
        assertThat(prompts.get(AgentLang.ZH, "chat.router")).contains("只调用 route 工具，不要输出任何文字");
    }

    /**
     * 快讯译文口径是<b>配对</b>的：news 专家那句"材料就这么多、是原文还是译文"与 summarizer 那句
     * "[BlockBeats] 那批来自中文源，按事件去重"必须同时在。只改一处，另一处的模型就会出事——
     * news 专家以为材料不全自己去搜（它明令禁搜），summarizer 把同一件事当成两件事。
     */
    @Test
    void 快讯译文口径在news专家与summarizer两处同时钉住() {
        for (AgentLang lang : AgentLang.values()) {
            String anchor = lang == AgentLang.ZH ? "中文源" : "machine translation";
            assertThat(prompts.get(lang, "chat.expert.news"))
                    .as("%s 的 news 专家没交代快讯是原文还是译文", lang.code()).contains(anchor);
            // 译文口径只在承诺联网的那版新闻条款里有意义（noSearch 版没有第二个来源要去重）
            assertThat(summarizer(lang, "chat.newsRule.search"))
                    .as("%s 的 summarizer 没交代 [BlockBeats] 那批是原文还是译文", lang.code()).contains(anchor);
        }
    }

    /** coach：只依据给定数据 + 中性不下单 */
    @Test
    void 中文coach保住盲测与中性两条() {
        assertThat(coach.system(hintRequest(), AgentLang.ZH))
                .as("① 盲测不许猜日期").contains("绝不要猜测这是哪一天").contains("不引用外部信息")
                .as("② 中性不下单").contains("不给买卖指令，不做确定性预测");
        assertThat(coach.system(reviewRequest(), AgentLang.ZH))
                .contains("只依据给定数据，不引用外部行情记忆");
    }

    /**
     * run_deep_analysis 的描述<b>两门语言只差触发词</b>：描述是给模型读的，触发词要匹配用户
     * 实际会说的话，只有后者跟语言走。中文侧保持注解原文不译——误触发一次烧 3 次深模型调用，
     * "仅在用户明说时才调"这条约束不拿译文去换。
     */
    @Test
    void 深研判工具描述两门语言只差触发词() {
        String zhTrigger = "\"深度研判\"/\"全面分析\"";
        String enTrigger = "\"deep dive\"/\"full analysis\"";
        String zh = prompts.get(AgentLang.ZH, "tool.run_deep_analysis");
        String en = prompts.get(AgentLang.EN, "tool.run_deep_analysis");

        assertThat(zh).as("中文侧的触发词").contains(zhTrigger);
        assertThat(en).as("英文侧的触发词").contains(enTrigger);
        assertThat(zh.replace(zhTrigger, enTrigger))
                .as("除触发词外两侧必须逐字相同").isEqualTo(en);
    }

    /** 任务 4：笔记上限按语言给，且提示词里那句"≤N"就是代码真截断的那个数 */
    @Test
    void 笔记上限按语言注入提示词() {
        assertThat(prompts.get(AgentLang.ZH, "reviewer.system", NOTE_CAP_ZH)).contains("≤2000字");
        assertThat(prompts.get(AgentLang.EN, "reviewer.system", NOTE_CAP_EN)).contains("≤4000 characters");
        assertThat(prompts.get(AgentLang.ZH, "learning.system", NOTE_CAP_ZH)).contains("2000 字以内");
        assertThat(prompts.get(AgentLang.EN, "learning.system", NOTE_CAP_EN)).contains("under 4000 characters");
    }

    // ==================== ③ 混语言提示词的缓解（任务 5）====================

    /**
     * 混语言提示词的两条缓解，两门语言各钉一遍（自定义段一律拿另一门语言造）：
     * <ul>
     *   <li>输出语言硬收尾是系统提示词的最后一行，排在自定义指令之后；</li>
     *   <li>每段主人亲笔的字之前都有 ownerWritten——"这段是主人写的、可能是另一门语言、
     *       照意思做但输出语言不变"：自定义指令那句在 system，留言那句跟着留言段走开场白。</li>
     * </ul>
     */
    @Test
    void 主人指令是另一门语言时输出语言指令仍压在末尾() {
        for (AgentLang lang : AgentLang.values()) {
            AiTrader t = enTrader();
            // 故意反着来：中文用户写英文指令、英文用户写中文指令
            t.setCustomPrompt(lang == AgentLang.ZH
                    ? "Only trade breakouts. Answer everything in English."
                    : "只做突破，全部用中文回答。");
            t.setOwnerNote(lang == AgentLang.ZH ? "Close ETH today." : "今天把 ETH 平掉。");
            t.setOwnerNoteRounds(2);

            TraderPromptAssembler assembler =
                    new TraderPromptAssembler(mock(AiTraderMapper.class), prompts);
            String prompt = assembler.assemble(t, lang);
            String block = assembler.ownerNoteBlock(t, lang);
            String tail = prompts.get(lang, "trader.label.outputLanguage");
            String note = prompts.get(lang, "trader.label.ownerWritten");

            // 末尾那行要完整且真在末尾：只 contains 的话，它被写在模板中间也照样绿
            assertThat(prompt.stripTrailing())
                    .as("%s 输出语言指令必须是整篇最后一行", lang.code()).endsWith(tail);
            // 自定义指令与主人留言各配一句交代：system 里一句、留言段里一句
            assertThat(prompt.split(java.util.regex.Pattern.quote(note), -1).length - 1)
                    .as("%s 自定义指令要一句「这段是主人写的」", lang.code()).isEqualTo(1);
            assertThat(block.split(java.util.regex.Pattern.quote(note), -1).length - 1)
                    .as("%s 留言段要一句「这段是主人写的」", lang.code()).isEqualTo(1);
            // 交代排在主人的字之前
            assertThat(prompt.indexOf(note)).isLessThan(prompt.indexOf(t.getCustomPrompt()));
            assertThat(block.indexOf(note)).isLessThan(block.indexOf(t.getOwnerNote()));
            // 主人的字一个都不许被改写；留言只在留言段，不进 system
            assertThat(prompt).contains(t.getCustomPrompt()).doesNotContain(t.getOwnerNote());
            assertThat(block).contains(t.getOwnerNote());
        }
    }

    /** 退出平台模板时模板里那次语言指令没了，末尾这行是唯一还站着的一条 */
    @Test
    void 退出平台模板后输出语言指令仍在() {
        for (AgentLang lang : AgentLang.values()) {
            AiTrader t = enTrader();
            t.setUseDefaultPrompt(false);
            t.setCustomPrompt("Do whatever you want.");
            assertThat(new TraderPromptAssembler(mock(AiTraderMapper.class), prompts)
                    .assemble(t, lang).stripTrailing())
                    .as("%s 退出平台模板后仍要有输出语言硬收尾", lang.code())
                    .endsWith(prompts.get(lang, "trader.label.outputLanguage"));
        }
    }

    /** 教练的用户消息（局中提示与整局评估两条路）同样以输出语言指令收尾 */
    @Test
    void 教练用户消息以输出语言指令收尾() {
        for (AgentLang lang : AgentLang.values()) {
            String tail = prompts.get(lang, "coach.label.outputLanguage");
            assertThat(coach.user(hintRequest(), lang).stripTrailing())
                    .as("%s 教练盘面提示", lang.code()).endsWith(tail);
            assertThat(coach.user(reviewRequest(), lang).stripTrailing())
                    .as("%s 教练整局评估", lang.code()).endsWith(tail);
        }
    }

    // ==================== 造数 ====================

    private static ReplayCoachRequest hintRequest() {
        return new ReplayCoachRequest(ReplayCoachRequest.MODE_HINT, null, "ETHUSDT", 15, true, "D1 00:00",
                List.of(new ReplayCoachRequest.Bar("D1 09:00", 1, 2, 0.5, 1.5, 10)), 10000.0,
                List.of(new ReplayCoachRequest.Position("LONG", 1.5, 3400.5, 7.5, 123.456)), null, null);
    }

    private static ReplayCoachRequest reviewRequest() {
        return new ReplayCoachRequest(ReplayCoachRequest.MODE_REVIEW, 7L, "BTCUSDT", 60, false, null,
                List.of(new ReplayCoachRequest.Bar("D1 09:00", 1, 2, 0.5, 1.5, 10)), null, null,
                List.of(new ReplayCoachRequest.Trade("SHORT", 0.5, 10, 60100, 59800, 148.2,
                        "D1 08:00", "D1 12:00", "LIQUIDATION", false)),
                new ReplayCoachRequest.Stats(3, 2, 1, 520.4, 0.0052, 0.031, 12.3, 100000, 100520.4));
    }

    private static AiTrader enTrader() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setRoundNo(1);
        t.setSimUserId(99L);
        t.setName("Alpha");
        t.setSymbols("BTCUSDT,ETHUSDT");
        t.setIntervalCode("1h");
        return t;
    }

    private static LocalDateTime at(long ms) {
        return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    private static FuturesPositionDTO closedPos(String pnl, long openMs, long closeMs) {
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setSymbol("BTCUSDT");
        p.setSide("LONG");
        p.setStatus("CLOSED");
        p.setEntryPrice(new BigDecimal("100000"));
        p.setClosedPrice(new BigDecimal("103000"));
        p.setClosedPnl(new BigDecimal(pnl));
        p.setQuantity(new BigDecimal("0.01"));
        p.setCreatedAt(at(openMs));
        p.setUpdatedAt(at(closeMs));
        return p;
    }

    private static AiTraderDecision equityRow(long wakeTime, String equity) {
        AiTraderDecision d = new AiTraderDecision();
        d.setWakeTime(wakeTime);
        d.setEquity(new BigDecimal(equity));
        return d;
    }

    private static AiTraderPlan plan(String status) {
        AiTraderPlan p = new AiTraderPlan();
        p.setTraderId(7L);
        p.setRoundNo(1);
        p.setSymbol("BTCUSDT");
        p.setSide("LONG");
        p.setStatus(status);
        p.setPlayType("BREAKOUT");
        p.setOpenedWakeTime(FROM + 3600_000);
        p.setSignalsUsed("daily above MA20");
        p.setInvalidationCondition("loses 99000");
        return p;
    }

    /** 四块素材全走英文：动作行/观望行/警报行/ERROR 行/价格路径都覆盖到 */
    private ReviewMaterialAssembler.ReviewMaterial enMaterial() {
        AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);
        AiTraderPlanMapper planMapper = mock(AiTraderPlanMapper.class);
        SimTradeClient sim = mock(SimTradeClient.class);
        KlineHistoryStore store = mock(KlineHistoryStore.class);

        when(decisionMapper.selectOne(any())).thenReturn(equityRow(FROM - 3600_000, "10000"));
        AiTraderDecision act = new AiTraderDecision();
        act.setWakeTime(FROM + 3600_000);
        act.setKind(AiTraderDecision.KIND_TRADE);
        act.setStatus(AiTraderDecision.STATUS_OK);
        act.setReasoning("[ROUND CONCLUSION]\nJudgement: breakout\nAction: long\nWaiting: none");
        act.setActionsJson("[{\"tool\":\"open_position\",\"args\":{\"symbol\":\"BTCUSDT\",\"side\":\"LONG\"},"
                + "\"status\":\"pending\"}]");
        AiTraderDecision hold = new AiTraderDecision();
        hold.setWakeTime(FROM + 7200_000);
        hold.setKind(AiTraderDecision.KIND_ALERT);
        hold.setStatus(AiTraderDecision.STATUS_OK);
        hold.setReasoning("[ROUND CONCLUSION]\nJudgement: noise\nAction: HOLD\nWaiting: retest 99000");
        AiTraderDecision noWait = new AiTraderDecision();
        noWait.setWakeTime(FROM + 10800_000);
        noWait.setKind(AiTraderDecision.KIND_TRADE);
        noWait.setStatus(AiTraderDecision.STATUS_OK);
        AiTraderDecision err = new AiTraderDecision();
        err.setWakeTime(FROM + 14400_000);
        err.setKind(AiTraderDecision.KIND_TRADE);
        err.setStatus(AiTraderDecision.STATUS_ERROR);
        when(decisionMapper.selectList(any())).thenReturn(
                List.of(equityRow(FROM + 3600_000, "10500")),
                List.of(act, hold, noWait, err));
        when(sim.getClosedPositions(eq(99L), anyInt())).thenReturn(List.of(
                closedPos("300", FROM + 3600_000, FROM + 7200_000)));
        when(planMapper.selectList(any())).thenReturn(List.of(plan(AiTraderPlan.STATUS_CLOSED)));
        when(store.load(anyString(), anyString(), anyLong(), anyLong())).thenReturn(List.of(
                new KlineBar(FROM, FROM + 300_000, new BigDecimal("100000"), new BigDecimal("101000"),
                        new BigDecimal("99500"), new BigDecimal("100500"), BigDecimal.ONE)));

        AiTrader t = enTrader();
        t.setSymbols("BTCUSDT");
        return new ReviewMaterialAssembler(decisionMapper, planMapper, sim, store, prompts,
                new DecisionText(prompts))
                .assemble(t, FROM, TO, AgentLang.EN);
    }

    private PeerInsightService enPeers() {
        AiTraderMapper traderMapper = mock(AiTraderMapper.class);
        AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);
        AiTraderPlanMapper planMapper = mock(AiTraderPlanMapper.class);
        SimTradeClient sim = mock(SimTradeClient.class);
        ReviewMaterialAssembler assembler = mock(ReviewMaterialAssembler.class);

        AiTrader me = enTrader();
        me.setStatus(AiTrader.STATUS_RUNNING);
        AiTrader other = enTrader();
        other.setId(8L);
        other.setName("Beta");
        other.setSimUserId(80L);
        other.setStatus(AiTrader.STATUS_LIQUIDATED);
        other.setLearningNotes("what I took from peers");

        when(traderMapper.selectList(any())).thenReturn(List.of(me, other));
        when(traderMapper.selectById(8L)).thenReturn(other);
        when(traderMapper.selectById(404L)).thenReturn(null);
        AiTraderDecision review = new AiTraderDecision();
        review.setWakeTime(TO);
        review.setReasoning("[REVIEW]\nScorecard: 10000 -> 11500\nRules: wait for the retest");
        when(assembler.lastReview(anyLong(), anyInt())).thenReturn(review);
        when(decisionMapper.selectOne(any())).thenReturn(equityRow(TO, "11500"));
        when(sim.getClosedPositions(anyLong(), anyInt())).thenReturn(List.of(
                closedPos("300", FROM + 3600_000, FROM + 7200_000)));
        when(planMapper.selectList(any())).thenReturn(List.of(
                plan(AiTraderPlan.STATUS_LIVE), plan(AiTraderPlan.STATUS_CLOSED)));
        // 两人都挂着仓 → 都在同侪池里（在场判据不依赖墙钟）
        when(sim.getAllPositions(anyLong())).thenReturn(List.of(new FuturesPositionDTO()));
        return new PeerInsightService(traderMapper, decisionMapper, planMapper, sim, assembler, prompts);
    }

}
