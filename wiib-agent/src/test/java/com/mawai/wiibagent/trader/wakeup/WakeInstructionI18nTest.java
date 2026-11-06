package com.mawai.wiibagent.trader.wakeup;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibagent.i18n.PromptI18nAssertions;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.toolkit.CrossAssetToolkit;
import com.mawai.wiibagent.toolkit.DerivativesToolkit;
import com.mawai.wiibagent.toolkit.IndicatorToolkit;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibagent.toolkit.NewsToolkit;
import com.mawai.wiibagent.toolkit.WhaleToolkit;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.mapper.AiTraderWatchMapper;
import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import com.mawai.wiibagent.learning.ExperienceLedger;
import com.mawai.wiibagent.trader.DecisionText;
import com.mawai.wiibagent.trader.TraderModelFactory;
import com.mawai.wiibagent.trader.prompt.EconCalendarAssembler;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
import com.mawai.wiibagent.trader.prompt.TraderPromptAssembler;
import com.mawai.wiibagent.trader.trade.EntryContext;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import com.mawai.wiibagent.trader.trade.TraderRiskConfig;
import com.mawai.wiibquant.market.service.KlineFetcher;
import com.mawai.wiibquant.market.service.MarketDataService;
import com.mawai.wiibquant.market.service.NewsCache;
import com.mawai.wiibquant.market.service.NewsFlashLocalizer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 唤醒开场白的双语契约：开场白（单段、第一段、第二段）的收尾标记与系统提示词必须同源，否则模型同时收到两条冲突的
 * 格式指令，收尾格式失守、复盘素材切不出"判断/动作/等待"。
 * <p>
 * 判据是"同源"而不是"等于某个字面量"：断言比的是 {@code trader.mark.conclusion}
 * （系统提示词模板用的同一条 key）的实际取值——改词表两边一起变，测试照样绿；
 * 开场白改成硬编码，它立刻红。
 */
class WakeInstructionI18nTest {

    private static final long BOUNDARY = 1785171600000L;
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final PromptCatalog prompts = new PromptCatalog();
    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final BinanceRestClient binance = mock(BinanceRestClient.class);

    /** 观察包成文走真实词表；取数侧（决策行/战绩）给 mock，走空数据路径 */
    private final TraderWatchStore watchStore = new TraderWatchStore(mock(AiTraderWatchMapper.class),
            mock(org.springframework.context.ApplicationEventPublisher.class));
    private final WakeObservation wakeObservation = new WakeObservation(
            mock(AiTraderDecisionMapper.class), mock(DecisionText.class), mock(PlayStatsAssembler.class), prompts,
            mock(PositionPath.class), watchStore);

    private final TraderWakeupRunner runner = new TraderWakeupRunner(
            mock(TraderModelFactory.class), new TraderPromptAssembler(traderMapper, prompts, mock(ExperienceLedger.class)),
            mock(SimTradeClient.class), binance,
            new IndicatorToolkit(new KlineFetcher(binance, 60_000)),
            new MarketToolkit(mock(MarketDataService.class)),
            mock(DerivativesToolkit.class), mock(WhaleToolkit.class),
            new NewsToolkit(mock(NewsCache.class), mock(NewsFlashLocalizer.class)),
            mock(CrossAssetToolkit.class),
            traderMapper, mock(AiTraderDecisionMapper.class),
            new TraderPlanStore(mock(AiTraderPlanMapper.class), prompts), watchStore,
            mock(UserLangResolver.class), prompts,
            new MessageCatalog(),
            new LocalizedToolCallbacks(prompts),
            wakeObservation,
            mock(EconCalendarAssembler.class), new TraderLiveHub(), mock(EntryContext.class),
            mock(DecisionText.class), mock(ExperienceLedger.class));

    {
        runner.nowMs = () -> BOUNDARY + 1_000L;
    }

    /** 留言消费走 Lambda 条件构造器，要查 TableInfo；不预热的话本类单独跑会炸 */
    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTrader.class);
    }

    /** 带一条待读留言的开场白段：注入即消费，每次现造 trader */
    private String noteBlock(AgentLang lang, String note) {
        AiTrader t = trader();
        t.setOwnerNote(note);
        t.setOwnerNoteRounds(2);
        return new TraderPromptAssembler(traderMapper, prompts, mock(ExperienceLedger.class)).ownerNoteBlock(t, lang);
    }

    private static AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setRoundNo(1);
        t.setSymbols("BTCUSDT");
        t.setIntervalCode("1h");
        return t;
    }

    private static AlertTrigger alert() {
        return new AlertTrigger("BTCUSDT", new BigDecimal("5.2"), new BigDecimal("100000"),
                AlertTrigger.UP, BOUNDARY);
    }

    private static TraderPlanStore.Reconcile nothing() {
        return new TraderPlanStore.Reconcile(List.of(), List.of());
    }

    /** 单段、第一段（例行 / 警报）、第二段（两版）：收尾标记都 = 系统提示词模板里的那一条，两门语言各钉一遍 */
    @Test
    void 开场白的收尾标记与系统提示词同源() {
        for (AgentLang lang : AgentLang.values()) {
            String mark = prompts.get(lang, "trader.mark.conclusion");
            String template = new TraderPromptAssembler(traderMapper, prompts, mock(ExperienceLedger.class))
                    .platformTemplate(lang, "1h", "BTCUSDT", TraderRiskConfig.of(new AiTrader()), null);
            assertThat(template).as("%s 系统提示词要带收尾标记", lang.code()).contains(mark);
            List<String> openings = List.of(
                    runner.routineInstruction(trader(), BOUNDARY, "", "", "", "", null, lang),
                    runner.stageOneInstruction(trader(), null, BOUNDARY, null, "", null, "", lang),
                    runner.stageOneInstruction(trader(), List.of(alert()), BOUNDARY, null, "", null, "", lang),
                    runner.stageTwoInstruction(trader(), BOUNDARY, "", "", "", null, lang, false),
                    runner.stageTwoInstruction(trader(), BOUNDARY, "", "", "", null, lang, true));
            for (String opening : openings) {
                assertThat(opening).as("%s 开场白的收尾标记要与系统提示词同源", lang.code()).contains(mark);
                // 另一门语言的标记一个都不许漏进来——那正是"两条指令打架"的形态
                for (AgentLang other : AgentLang.values()) {
                    if (other != lang) {
                        assertThat(opening).as("%s 开场白里混进了 %s 的收尾标记", lang.code(), other.code())
                                .doesNotContain(prompts.get(other, "trader.mark.conclusion"));
                    }
                }
            }
        }
    }

    /** 英文唤醒开场白（单段 / 第一段例行与警报 / 第二段两版 / 休眠提示 / 留言段）全文零中文 */
    @Test
    void 英文唤醒开场白全文无中文() {
        PromptI18nAssertions.assertNoCjk("英文单段开场白",
                runner.routineInstruction(trader(), BOUNDARY, "", "", "", "", null, AgentLang.EN));
        AiTrader windowed = trader();
        windowed.setWakeWindow("21:00-08:00");
        PromptI18nAssertions.assertNoCjk("英文单段开场白（带休眠提示）",
                runner.routineInstruction(windowed, BOUNDARY, "", "", "", "", null, AgentLang.EN));
        PromptI18nAssertions.assertNoCjk("英文第一段（例行）",
                runner.stageOneInstruction(trader(), null, BOUNDARY, null, "", null, "", AgentLang.EN));
        PromptI18nAssertions.assertNoCjk("英文第一段（警报）",
                runner.stageOneInstruction(trader(), List.of(alert()), BOUNDARY, null, "", null, "", AgentLang.EN));
        PromptI18nAssertions.assertNoCjk("英文第二段（带休眠提示）",
                runner.stageTwoInstruction(windowed, BOUNDARY, "", "", "", null, AgentLang.EN, false));
        PromptI18nAssertions.assertNoCjk("英文第二段（预算已尽）",
                runner.stageTwoInstruction(trader(), BOUNDARY, "", "", "", null, AgentLang.EN, true));
        // 留言段的段头/亲笔提示/footer 都是平台文案，英文用户一个中文字都不许见；正文是主人亲笔，拿英文造
        PromptI18nAssertions.assertNoCjk("英文第一段（带留言段）",
                runner.stageOneInstruction(trader(), null, BOUNDARY, null, "", null,
                        noteBlock(AgentLang.EN, "Take profit on ETH now."), AgentLang.EN));
    }

    /**
     * 有待读留言：留言段整段压在第一段最末（user 消息最近因位置——留言在这儿才是"本轮要回答的问题之一"，
     * 放 system 里跟纪律同层必被压过）；反重放跟正文同位置。
     */
    @Test
    void 有留言时留言段压在第一段最末() {
        String block = noteBlock(AgentLang.ZH, "ETH 提前止盈");
        assertThat(block)
                .startsWith("\n")
                .contains("主人的留言").contains("ETH 提前止盈")
                .as("留言段自带反重放").contains("一次性动作做过不要再做");
        assertThat(runner.stageOneInstruction(trader(), null, BOUNDARY, null, "", null, block, AgentLang.ZH)).endsWith(block);

        String en = noteBlock(AgentLang.EN, "Take profit on ETH now.");
        assertThat(runner.stageOneInstruction(trader(), null, BOUNDARY, null, "", null, en, AgentLang.EN))
                .endsWith(en)
                .contains("Do not repeat a one-shot action");
    }

    /** 财经日历块只在事实区：单段在问题之前；警报第一段在"尚未收盘"提醒之前；第二段在它的问题之前。null 时一个字不加 */
    @Test
    void 财经日历块排在问题之前() {
        String calendar = "【财经日历】全球 High 级宏观事件\n- 09-04 20:30 US/USD Non Farm Payrolls 实际:206K 预测:205K 前值:207K\n";
        String single = runner.routineInstruction(trader(), BOUNDARY, "", "", "", "", calendar, AgentLang.ZH);
        String alert = runner.stageOneInstruction(trader(), List.of(alert()), BOUNDARY, null, "", calendar, "", AgentLang.ZH);
        String second = runner.stageTwoInstruction(trader(), BOUNDARY, "", "", "", calendar, AgentLang.ZH, false);

        assertThat(single).contains(calendar);
        assertThat(single.indexOf(calendar)).isLessThan(single.indexOf("本轮：按主人的交易指令"));
        assertThat(alert.indexOf(calendar)).isLessThan(alert.indexOf("注意：本次不是例行唤醒"));
        assertThat(second.indexOf(calendar)).isLessThan(second.indexOf("本段：按主人的交易指令"));
    }

    /** 观察包（账户/上一轮结论）的文案：英文侧零中文（含全角【】），中文侧段头齐全；没承诺、没事件就不算忙 */
    @Test
    void 观察包中英文案无中文() {
        WakeObservation.Pack en = wakeObservation.assemble(trader(), List.of("BTCUSDT"), new BigDecimal("10000"), List.of(), List.of(),
                nothing(), List.of(), BOUNDARY, AgentLang.EN, List.of(), List.of(), Map.of());
        PromptI18nAssertions.assertNoCjk("英文观察包", en.text());
        PromptI18nAssertions.assertNoCjk("英文第一段（带观察包）",
                runner.stageOneInstruction(trader(), null, BOUNDARY, null, en.text(), null, "", AgentLang.EN));
        PromptI18nAssertions.assertNoCjk("英文警报第一段（带观察包）",
                runner.stageOneInstruction(trader(), List.of(alert()), BOUNDARY, null, en.text(), null, "", AgentLang.EN));
        assertThat(en.text()).contains("[Account]").contains("[Last round's conclusion]");
        assertThat(en.busy()).isFalse();

        WakeObservation.Pack zh = wakeObservation.assemble(trader(), List.of("BTCUSDT"), new BigDecimal("10000"), List.of(), List.of(),
                nothing(), List.of(), BOUNDARY, AgentLang.ZH, List.of(), List.of(), Map.of());
        assertThat(zh.text()).contains("【当前账户】").contains("\"equity\":10000.00")
                .contains("【上一轮结论】本局还没有上一轮结论");
    }

    /** 承诺块：在盯的价格条件带"离现价多远"，中英各自成文；布了条件就算忙 */
    @Test
    void 承诺块列出在盯的条件与离现价多远() {
        TraderWatchStore store = mock(TraderWatchStore.class);
        AiTraderWatch w = new AiTraderWatch();
        w.setSymbol("BTCUSDT");
        w.setKind("TOUCH_ABOVE");
        w.setLevel(new BigDecimal("65000"));
        w.setNote("breakout, chase long"); // 备注是模型自己写的，英文 trader 写英文，不算平台文案
        w.setCreatedWakeTime(BOUNDARY - 3_600_000L);
        when(store.armed(7L, 1)).thenReturn(List.of(w));
        WakeObservation obs = new WakeObservation(mock(AiTraderDecisionMapper.class), mock(DecisionText.class),
                mock(PlayStatsAssembler.class), prompts, mock(PositionPath.class), store);
        Map<String, BigDecimal> marks = Map.of("BTCUSDT", new BigDecimal("64000"));

        WakeObservation.Pack zh = obs.assemble(trader(), List.of("BTCUSDT"), new BigDecimal("10000"), List.of(), List.of(),
                nothing(), List.of(), BOUNDARY, AgentLang.ZH, List.of(), List.of(), marks);
        assertThat(zh.text()).contains("【你的承诺】")
                .contains("- BTCUSDT 触及 65000 以上（备注：breakout, chase long）：未命中；现价 64000，在 65000 下方 1.54%");
        assertThat(zh.busy()).isTrue();

        WakeObservation.Pack en = obs.assemble(trader(), List.of("BTCUSDT"), new BigDecimal("10000"), List.of(), List.of(),
                nothing(), List.of(), BOUNDARY, AgentLang.EN, List.of(), List.of(), marks);
        PromptI18nAssertions.assertNoCjk("英文承诺块", en.text());
        assertThat(en.text()).contains("[Your commitments]").contains("1.54% below 65000");
    }

    /** 观察包紧跟头部事实：单段在快照之前；警报第一段在"上次唤醒"之后、"尚未收盘"提醒之前 */
    @Test
    void 观察包排在头部事实之后() {
        String obs = "\n【当前账户】\n{}\n";
        String snapshot = "- BTCUSDT 标记价 100000，资金费率 0.0001\n";
        String routine = runner.routineInstruction(trader(), BOUNDARY, obs, "", "", snapshot, null, AgentLang.ZH);
        assertThat(routine.indexOf(obs)).isGreaterThan(routine.indexOf("新一根 1h K线已收盘"));
        assertThat(routine.indexOf(obs)).isLessThan(routine.indexOf("行情快照"));

        String alert = runner.stageOneInstruction(trader(), List.of(alert()), BOUNDARY, null, obs, null, "", AgentLang.ZH);
        assertThat(alert.indexOf(obs)).isGreaterThan(alert.indexOf("你上次唤醒"));
        assertThat(alert.indexOf(obs)).isLessThan(alert.indexOf("注意：本次不是例行唤醒，当前 1h K线尚未收盘"));
    }

    /** 中文单段开场白逐字钉死 */
    @Test
    void 中文单段开场白逐字不变() {
        assertThat(runner.routineInstruction(trader(), BOUNDARY, "", "", "", "", null, AgentLang.ZH))
                .isEqualTo("新一根 1h K线已收盘（" + TIME_FMT.format(Instant.ofEpochMilli(BOUNDARY)) + "）。"
                        + "本轮：按主人的交易指令看每个可交易币有没有进场条件到位；每个币下一步等什么，用 set_watch 布下去，没什么可等的写明为什么。"
                        + "先读上面的事实，判断用工具求证。"
                        + "最后用[本轮结论]固定格式收尾（每个可交易币各一段）。");
    }

    /** 中文第一段（例行）与第二段两版逐字钉死 */
    @Test
    void 中文两段开场白逐字不变() {
        assertThat(runner.stageOneInstruction(trader(), null, BOUNDARY, null, "", null, "", AgentLang.ZH))
                .isEqualTo("新一根 1h K线已收盘（" + TIME_FMT.format(Instant.ofEpochMilli(BOUNDARY)) + "）。"
                        + "本段只处置你自己留下的事：这根收盘命中的盯盘条件按布它时的备注办；【自上次唤醒以来】的结局先对账；"
                        + "【你的承诺】逐条回答还成立吗，该动的动（移止损、平仓、撤单、按备注进场）；主人的留言按它段内的说明办。动作按主人的交易指令定。"
                        + "写了等待但没布条件的，说明是不需要叫醒还是忘了。本段不布盯盘条件、不写[本轮结论]块，那是第二段的事。");
        assertThat(runner.stageTwoInstruction(trader(), BOUNDARY, "", "", "", null, AgentLang.ZH, false))
                .isEqualTo("第二段。第一段里你做的动作已经生效，下面是整盘事实。\n"
                        + "本段：按主人的交易指令看整盘——有没有新的进场条件到位；每个可交易币下一步等什么，用 set_watch 布下去"
                        + "（一次整组替换该币全部条件，要留的旧条件一并带上），没什么可等的写明为什么。"
                        + "最后用[本轮结论]固定格式收尾（每个可交易币各一段）。");
        assertThat(runner.stageTwoInstruction(trader(), BOUNDARY, "", "", "", null, AgentLang.ZH, true))
                .isEqualTo("第二段。第一段里你做的动作已经生效，下面是整盘事实。\n"
                        + "本段：模型调用预算已用完，不能再调用任何工具，set_watch 也不行。"
                        + "直接用[本轮结论]固定格式收尾（每个可交易币各一段），要等的条件写进\"等待\"行，下一轮再布。");
    }

    /** 警报里"距下一次例行唤醒"要看时段：时段末尾的警报，下次醒是时段再开门那一刻，不是下一根 */
    @Test
    void 警报开场白的下次例行唤醒按时段算() {
        AiTrader t = trader();
        // BOUNDARY 是北京时间 01:00，时段内最后一根；下一边界 02:00 已出时段，再醒是 08:00
        t.setWakeWindow("08:00-01:00");

        assertThat(runner.stageOneInstruction(t, List.of(alert()), BOUNDARY, null, "", null, "", AgentLang.ZH))
                .contains("距下一次例行唤醒还有约 420 分钟");
    }

    /** 中文警报第一段逐字不变 */
    @Test
    void 中文警报开场白逐字不变() {
        String time = TIME_FMT.format(Instant.ofEpochMilli(BOUNDARY));
        assertThat(runner.stageOneInstruction(trader(), List.of(alert()), BOUNDARY, null, "", null, "", AgentLang.ZH))
                .isEqualTo("行情波动警报（" + time + "）：BTCUSDT 5分钟内波动 5.2%（方向：上涨，现价 100000）\n"
                        + "你上次唤醒本局还没有过唤醒，距下一次例行唤醒还有约 60 分钟。\n"
                        + "注意：本次不是例行唤醒，当前 1h K线尚未收盘——按 1h 收盘定义的条件此刻不作数，求证请用已收盘的K线。\n"
                        + "本次是非例行唤醒，上面列的就是把你叫醒的事。先用工具看清它们前后的行情，再回答它意味着什么："
                        + "盯盘命中的，这条是真到位还是插针，布它时你写的备注现在还该照做吗；"
                        + "持仓事件的，这个结局改变了你对这个币的看法吗，下一步重进、观望还是撤掉剩下的挂单；"
                        + "波动警报的，持仓的计划被它动摇了吗。然后处置【你的承诺】里的每一条，动作按主人的交易指令定。"
                        + "被叫醒本身不是动作的理由，也不是不动的理由。本段不布盯盘条件、不写[本轮结论]块，那是第二段的事。");
    }
}
