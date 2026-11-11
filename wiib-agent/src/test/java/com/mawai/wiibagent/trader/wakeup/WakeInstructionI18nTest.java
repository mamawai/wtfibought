package com.mawai.wiibagent.trader.wakeup;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibagent.i18n.PromptI18nAssertions;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.learning.CaseFileBuilder;
import com.mawai.wiibagent.toolkit.BeijingTime;
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
import com.mawai.wiibquant.market.service.CrossAssetService;
import com.mawai.wiibquant.market.service.KlineFetcher;
import com.mawai.wiibquant.market.service.MarketDataService;
import com.mawai.wiibquant.market.service.NewsCache;
import com.mawai.wiibquant.market.service.NewsFlashLocalizer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
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
    private final PositionPath positionPath = mock(PositionPath.class);
    private final CrossAssetService crossAssets = mock(CrossAssetService.class);
    private final WakeObservation wakeObservation = new WakeObservation(
            mock(AiTraderDecisionMapper.class), mock(DecisionText.class), mock(PlayStatsAssembler.class), prompts,
            positionPath, crossAssets);

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
            mock(DecisionText.class), mock(ExperienceLedger.class), mock(BinanceProperties.class),
            mock(PresetExecutor.class));

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
                    runner.stageTwoInstruction(trader(), BOUNDARY, "", "", "", null, lang, false, false),
                    runner.stageTwoInstruction(trader(), BOUNDARY, "", "", "", null, lang, true, false),
                    runner.stageTwoInstruction(trader(), BOUNDARY, "", "", "", null, lang, false, true));
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
                runner.stageTwoInstruction(windowed, BOUNDARY, "", "", "", null, AgentLang.EN, false, false));
        PromptI18nAssertions.assertNoCjk("英文第二段（非例行轮）",
                runner.stageTwoInstruction(trader(), BOUNDARY, "", "", "", null, AgentLang.EN, true, false));
        PromptI18nAssertions.assertNoCjk("英文第二段（预算已尽）",
                runner.stageTwoInstruction(trader(), BOUNDARY, "", "", "", null, AgentLang.EN, false, true));
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
        String calendar = "【财经日历】美国 High 级宏观事件\n- 09-04 20:30 Non Farm Payrolls 实际:206K 预测:205K 前值:207K\n";
        String single = runner.routineInstruction(trader(), BOUNDARY, "", "", "", "", calendar, AgentLang.ZH);
        String alert = runner.stageOneInstruction(trader(), List.of(alert()), BOUNDARY, null, "", calendar, "", AgentLang.ZH);
        String second = runner.stageTwoInstruction(trader(), BOUNDARY, "", "", "", calendar, AgentLang.ZH, false, false);

        assertThat(single).contains(calendar);
        assertThat(single.indexOf(calendar)).isLessThan(single.indexOf("本轮：看整盘"));
        assertThat(alert.indexOf(calendar)).isLessThan(alert.indexOf("注意：本次不是例行唤醒"));
        assertThat(second.indexOf(calendar)).isLessThan(second.indexOf("本段：看整盘"));
    }

    /** 观察包（账户/上一轮结论）的文案：英文侧零中文（含全角【】），中文侧段头齐全；没承诺、没事件就不算忙 */
    @Test
    void 观察包中英文案无中文() {
        WakeObservation.Pack en = wakeObservation.assemble(trader(), List.of("BTCUSDT"), new BigDecimal("10000"), List.of(), List.of(),
                nothing(), List.of(), BOUNDARY, AgentLang.EN, List.of(), List.of(), List.of(), Map.of());
        PromptI18nAssertions.assertNoCjk("英文观察包", en.text());
        PromptI18nAssertions.assertNoCjk("英文第一段（带观察包）",
                runner.stageOneInstruction(trader(), null, BOUNDARY, null, en.text(), null, "", AgentLang.EN));
        PromptI18nAssertions.assertNoCjk("英文警报第一段（带观察包）",
                runner.stageOneInstruction(trader(), List.of(alert()), BOUNDARY, null, en.text(), null, "", AgentLang.EN));
        assertThat(en.text()).contains("[Account]").contains("[Last round's conclusion]");
        assertThat(en.busy()).isFalse();

        WakeObservation.Pack zh = wakeObservation.assemble(trader(), List.of("BTCUSDT"), new BigDecimal("10000"), List.of(), List.of(),
                nothing(), List.of(), BOUNDARY, AgentLang.ZH, List.of(), List.of(), List.of(), Map.of());
        assertThat(zh.text()).contains("【当前账户】").contains("\"equity\":10000.00")
                .contains("【上一轮结论】本局还没有上一轮结论");
    }

    /** 承诺块：在盯的价格条件带"离现价多远"，中英各自成文；布了条件就算忙 */
    @Test
    void 承诺块列出在盯的条件与离现价多远() {
        AiTraderWatch w = new AiTraderWatch();
        w.setSymbol("BTCUSDT");
        w.setKind("TOUCH_ABOVE");
        w.setLevel(new BigDecimal("65000"));
        w.setNote("breakout, chase long"); // 备注是模型自己写的，英文 trader 写英文，不算平台文案
        w.setCreatedWakeTime(BOUNDARY - 3_600_000L);
        Map<String, BigDecimal> marks = Map.of("BTCUSDT", new BigDecimal("64000"));

        WakeObservation.Pack zh = wakeObservation.assemble(trader(), List.of("BTCUSDT"), new BigDecimal("10000"), List.of(), List.of(),
                nothing(), List.of(), BOUNDARY, AgentLang.ZH, List.of(), List.of(), List.of(w), marks);
        assertThat(zh.text()).contains("【你的承诺】")
                .contains("- BTCUSDT 触及 65000 以上（备注：breakout, chase long）：未命中；现价 64000，在 65000 下方 1.54%");
        assertThat(zh.busy()).isTrue();

        WakeObservation.Pack en = wakeObservation.assemble(trader(), List.of("BTCUSDT"), new BigDecimal("10000"), List.of(), List.of(),
                nothing(), List.of(), BOUNDARY, AgentLang.EN, List.of(), List.of(), List.of(w), marks);
        PromptI18nAssertions.assertNoCjk("英文承诺块", en.text());
        assertThat(en.text()).contains("[Your commitments]").contains("1.54% below 65000");
    }

    /** 带预案的条件：承诺块里那条后面写明命中后平台会做什么、叫不叫醒；执行过的，补报时备注后面跟着结果 */
    @Test
    void 预案在承诺块写明动作命中后带上执行结果() {
        AiTraderWatch armed = new AiTraderWatch();
        armed.setSymbol("BTCUSDT");
        armed.setKind("TOUCH_BELOW");
        armed.setLevel(new BigDecimal("63000"));
        armed.setNote("market breaks, cut the alt");
        armed.setActionJson("{\"type\":\"CLOSE\",\"positionId\":12,\"closePercent\":50,\"symbol\":\"ETHUSDT\",\"side\":\"LONG\"}");
        AiTraderWatch fired = new AiTraderWatch();
        fired.setSymbol("ETHUSDT");
        fired.setKind("TOUCH_ABOVE");
        fired.setLevel(new BigDecimal("3100"));
        fired.setFiredAt(BOUNDARY - 600_000L);
        fired.setFiredPrice(new BigDecimal("3101"));
        fired.setNote("lock breakeven");
        fired.setActionJson("{\"type\":\"MOVE_STOP\",\"positionId\":12,\"stopLossPrice\":3050,\"symbol\":\"ETHUSDT\",\"side\":\"LONG\"}");
        fired.setActionOk(true);
        fired.setActionResult("ETHUSDT LONG 止损 3000→3050");

        String zh = wakeObservation.assemble(trader(), List.of("BTCUSDT"), new BigDecimal("10000"), List.of(), List.of(),
                nothing(), List.of(), BOUNDARY, AgentLang.ZH, List.of(), List.of(fired), List.of(armed), Map.of()).text();
        assertThat(zh)
                .contains("- BTCUSDT 触及 63000 以下（备注：market breaks, cut the alt）：未命中；预案：命中后平台市价平掉 ETHUSDT LONG（positionId 12）的 50%，然后叫醒你")
                .contains("你的备注：lock breakeven｜预案已由平台执行：ETHUSDT LONG 止损 3000→3050");

        fired.setActionResult("ETHUSDT LONG stop 3000→3050");
        String en = wakeObservation.assemble(trader(), List.of("BTCUSDT"), new BigDecimal("10000"), List.of(), List.of(),
                nothing(), List.of(), BOUNDARY, AgentLang.EN, List.of(), List.of(fired), List.of(armed), Map.of()).text();
        PromptI18nAssertions.assertNoCjk("英文预案", en);
        assertThat(en).contains("standing order: when it fires the platform closes 50% of ETHUSDT LONG (positionId 12) at market, then wakes you")
                .contains("standing order carried out by the platform: ETHUSDT LONG stop 3000→3050");
    }

    /**
     * 行情快照：大盘参考不在可交易币里也排第一行并标出来，涨跌缺的那格写"—"；
     * 美股参考只留纳指期货、标普期货、VIX；英文侧零中文
     */
    @Test
    void 行情快照带大盘参考和美股参考() {
        when(positionPath.changes("BTCUSDT", BOUNDARY))
                .thenReturn(Arrays.asList(new BigDecimal("0.42"), new BigDecimal("-1.30"), null));
        when(positionPath.changes("ZECUSDT", BOUNDARY))
                .thenReturn(List.of(new BigDecimal("-2.10"), new BigDecimal("3.05"), new BigDecimal("12.80")));
        when(crossAssets.snapshot()).thenReturn(List.of(
                new CrossAssetService.Quote("NQ=F", "Nasdaq 100 futures", new BigDecimal("25000.50"),
                        new BigDecimal("-0.85"), new BigDecimal("1.20"), BOUNDARY),
                new CrossAssetService.Quote("GC=F", "Gold futures (USD/oz)", new BigDecimal("4100.00"),
                        new BigDecimal("0.30"), new BigDecimal("2.00"), BOUNDARY)));
        Map<String, JsonNode> quotes = Map.of(
                "BTCUSDT", MAPPER.readTree("{\"markPrice\":\"100000.00\",\"lastFundingRate\":\"0.0001\"}"),
                "ZECUSDT", MAPPER.readTree("{\"markPrice\":\"512.30\",\"lastFundingRate\":\"-0.0002\"}"));

        assertThat(wakeObservation.snapshot(quotes, List.of("ZECUSDT"), BOUNDARY, AgentLang.ZH)).isEqualTo(
                "- BTCUSDT（大盘参考，不可交易） 标记价 100000，1h / 4h / 24h 涨跌 +0.42% / -1.30% / —，资金费率 0.0001\n"
                        + "- ZECUSDT 标记价 512.3，1h / 4h / 24h 涨跌 -2.10% / +3.05% / +12.80%，资金费率 -0.0002\n"
                        + prompts.get(AgentLang.ZH, "trader.wake.equitiesHeader") + "\n"
                        + "- 纳指期货 25000.5，较昨收 -0.85%（" + BeijingTime.format(BOUNDARY) + "）\n");
        PromptI18nAssertions.assertNoCjk("英文行情快照",
                wakeObservation.snapshot(quotes, List.of("ZECUSDT"), BOUNDARY, AgentLang.EN));
    }

    /** 各币摘要：大盘参考不在可交易币里也给一行并标出来 */
    @Test
    void 各币摘要带大盘参考() {
        when(positionPath.symbol("BTCUSDT", null, BOUNDARY, 3_600_000L)).thenReturn(new CaseFileBuilder.MarketWindow(
                "BTCUSDT", BOUNDARY - 3_600_000L, BOUNDARY, new BigDecimal("100000"), new BigDecimal("100900"),
                BOUNDARY - 1_800_000L, new BigDecimal("99800"), BOUNDARY - 600_000L, new BigDecimal("100500")));
        AiTrader t = trader();
        t.setSymbols("ZECUSDT");

        assertThat(wakeObservation.symbolsSummary(t, List.of("ZECUSDT"), null, BOUNDARY, AgentLang.ZH))
                .contains("- BTCUSDT（大盘参考，不可交易） ").doesNotContain("ZECUSDT");
        PromptI18nAssertions.assertNoCjk("英文各币摘要",
                wakeObservation.symbolsSummary(t, List.of("ZECUSDT"), null, BOUNDARY, AgentLang.EN));
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
                        + "本轮：看整盘——每个可交易币现在有没有值得做的交易。先读上面的事实，判断用工具求证。"
                        + "想让系统在某个价位、某根收盘或某个时刻叫醒你，用 set_watch 布下去"
                        + "（每个方向只留得下一条，布最先要处理的，更远的写进等待行）；不需要叫醒的币不用布。"
                        + "最后用[本轮结论]固定格式收尾（只写有变化的币）。");
    }

    /** 中文第一段（例行）与第二段三版（例行 / 非例行 / 预算已尽）逐字钉死 */
    @Test
    void 中文两段开场白逐字不变() {
        assertThat(runner.stageOneInstruction(trader(), null, BOUNDARY, null, "", null, "", AgentLang.ZH))
                .isEqualTo("新一根 1h K线已收盘（" + TIME_FMT.format(Instant.ofEpochMilli(BOUNDARY)) + "）。"
                        + "本段只处置你自己留下的事：这根收盘命中的盯盘条件按布它时的备注办；【自上次唤醒以来】的结局先对账；"
                        + "【你的承诺】只处置有变化的——失效条件被触发或快到的、挂单该撤的、等的条件到了的，"
                        + "该动的动（移止损、平仓、撤单、按备注进场），没变化的不用逐条复述；主人的留言按它段内的说明办。"
                        + "写了等待但没布条件的，说明是不需要叫醒还是忘了。本段不布盯盘条件、不写[本轮结论]块，那是第二段的事。");
        assertThat(runner.stageTwoInstruction(trader(), BOUNDARY, "", "", "", null, AgentLang.ZH, false, false))
                .isEqualTo("第二段。第一段里你做的动作已经生效，下面是整盘事实。\n"
                        + "本段：看整盘——有没有值得做的新交易。想让系统在某个价位、某根收盘或某个时刻叫醒你，用 set_watch 布下去"
                        + "（一次整组替换该币全部条件，要留的旧条件一并带上；每个方向只留得下一条，布最先要处理的，更远的写进等待行）；"
                        + "还在盯的条件不用重布，不需要叫醒的币不用布。"
                        + "最后用[本轮结论]固定格式收尾（只写有变化的币）。");
        // 非例行轮：只给被叫醒的那件事涉及的币布防和收尾，整盘留给例行轮
        assertThat(runner.stageTwoInstruction(trader(), BOUNDARY, "", "", "", null, AgentLang.ZH, true, false))
                .isEqualTo("第二段。第一段里你做的动作已经生效，下面是整盘事实。\n"
                        + "本段：只给本轮涉及的币布防和收尾——把你叫醒的那件事涉及的币，加上你在第一段动过的币。"
                        + "它们的盯盘条件要变就用 set_watch 重布"
                        + "（一次整组替换该币全部条件，要留的旧条件一并带上；每个方向只留得下一条，布最先要处理的，更远的写进等待行）。"
                        + "别的币的结论和盯盘条件原样留着，不用重写也不用重布，整盘留给例行轮看。"
                        + "最后用[本轮结论]固定格式收尾，只写这几个币的段。");
        assertThat(runner.stageTwoInstruction(trader(), BOUNDARY, "", "", "", null, AgentLang.ZH, true, true))
                .isEqualTo("第二段。第一段里你做的动作已经生效，下面是整盘事实。\n"
                        + "本段：模型调用预算已用完，不能再调用任何工具，set_watch 也不行。"
                        + "直接用[本轮结论]固定格式收尾（只写有变化的币），要等的条件写进\"等待\"行，下一轮再布。");
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
                        + "本次是非例行唤醒，上面列的就是把你叫醒的事。先回答它意味着什么，要不要先用工具求证、求证到哪一步由你定："
                        + "盯盘命中的，这条是真到位还是插针，布它时你写的备注现在还该照做吗；"
                        + "持仓事件的，这个结局改变了你对这个币的看法吗，下一步重进、观望还是撤掉剩下的挂单；"
                        + "波动警报的，持仓的计划被它动摇了吗。然后只处置【你的承诺】里和这件事有关的、或者已经有变化的那几条，别的币留给例行轮。"
                        + "被叫醒本身不是动作的理由，也不是不动的理由。本段不布盯盘条件、不写[本轮结论]块，那是第二段的事。");
    }
}
