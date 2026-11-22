package com.mawai.wiibagent.trader.wakeup;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibagent.i18n.PromptI18nAssertions;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.learning.CaseFileBuilder;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.learning.ExperienceLedger;
import com.mawai.wiibagent.trader.DecisionText;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
import com.mawai.wiibagent.trader.prompt.TraderPromptAssembler;
import com.mawai.wiibagent.trader.trade.PlanEntry;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import com.mawai.wiibagent.trader.trade.TraderRiskConfig;
import com.mawai.wiibquant.market.service.CrossAssetService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.mawai.wiibcommon.market.TradeEvent;

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
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(BeijingTime.ZONE);

    private final PromptCatalog prompts = new PromptCatalog();
    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final BinanceRestClient binance = mock(BinanceRestClient.class);

    /** 观察包成文走真实词表；取数侧（决策行/战绩）给 mock，走空数据路径 */
    private final PositionPath positionPath = mock(PositionPath.class);
    private final CrossAssetService crossAssets = mock(CrossAssetService.class);
    private final WakeObservation wakeObservation = new WakeObservation(
            mock(AiTraderDecisionMapper.class), mock(DecisionText.class), mock(PlayStatsAssembler.class), prompts,
            positionPath, crossAssets, binance);
    private final WakeFlows flows = new WakeFlows(prompts, wakeObservation, new TriggerText(prompts));

    {
        flows.nowMs = () -> BOUNDARY + 1_000L;
    }

    /** 整盘轮单段开场白，事实块都不带 */
    private String routine(AiTrader t, AgentLang lang) {
        return flows.routineInstruction(t, List.of(), BOUNDARY, null, "", "", "", "", null, lang);
    }

    /** 整盘轮第一段：没开联网是处置承诺，开了联网是核查事实 */
    private String stageOne(String observation, String ownerNote, AgentLang lang, boolean webSearch) {
        return flows.stageOneInstruction(trader(), List.of(), BOUNDARY, null, observation, ownerNote, lang, webSearch);
    }

    /** 波动警报叫醒的单币轮开场白 */
    private String alertOpening(AiTrader t, String observation, AgentLang lang, boolean webSearch) {
        return flows.singleInstruction(t, "BTCUSDT", List.of(alert()), BOUNDARY, null, observation, "", "", lang, webSearch);
    }

    /** 整盘观察包：除了在盯的条件和补报的命中，别的都给空 */
    private WakeObservation.Pack pack(AgentLang lang, String planEvents, List<AiTraderWatch> firedQuietly,
                                      List<AiTraderWatch> armed, Map<String, BigDecimal> marks) {
        return wakeObservation.assemble(trader(), WakeScope.book(trader()), new BigDecimal("10000"), List.of(), List.of(), "",
                List.of(), planEvents, List.of(), Map.of(), BOUNDARY, lang, firedQuietly, armed, marks);
    }

    /** 留言消费走 Lambda 条件构造器，要查 TableInfo；不预热的话本类单独跑会炸 */
    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTrader.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderDecision.class);
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

    /** 整盘轮单段、第一段、第二段（两版），单币轮开场白和后面几段：收尾标记都 = 系统提示词模板里的那一条，两门语言各钉一遍 */
    @Test
    void 开场白的收尾标记与系统提示词同源() {
        for (AgentLang lang : AgentLang.values()) {
            String mark = prompts.get(lang, "trader.mark.conclusion");
            String template = new TraderPromptAssembler(traderMapper, prompts, mock(ExperienceLedger.class))
                    .platformTemplate(lang, "1h", "BTCUSDT", TraderRiskConfig.of(new AiTrader()), null);
            assertThat(template).as("%s 系统提示词要带收尾标记", lang.code()).contains(mark);
            Map<String, Object> coin = Map.of("symbol", "BTCUSDT", "mark", mark);
            List<String> openings = List.of(
                    routine(trader(), lang),
                    stageOne("", "", lang, false),
                    stageOne("", "", lang, true),
                    alertOpening(trader(), "", lang, false),
                    flows.stageTwoInstruction(trader(), BOUNDARY, "", "", "", null, lang, false),
                    flows.stageTwoInstruction(trader(), BOUNDARY, "", "", "", null, lang, true),
                    prompts.get(lang, "trader.wake.single.search", coin),
                    prompts.get(lang, "trader.wake.single.closing", coin),
                    prompts.get(lang, "trader.wake.single.exhausted", coin));
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

    /** 英文唤醒开场白（整盘轮单段 / 第一段 / 第二段两版 / 休眠提示 / 留言段，四种单币轮和它们后面几段）全文零中文 */
    @Test
    void 英文唤醒开场白全文无中文() {
        PromptI18nAssertions.assertNoCjk("英文单段开场白", routine(trader(), AgentLang.EN));
        AiTrader windowed = trader();
        windowed.setWakeWindow("21:00-08:00");
        PromptI18nAssertions.assertNoCjk("英文单段开场白（带休眠提示）", routine(windowed, AgentLang.EN));
        PromptI18nAssertions.assertNoCjk("英文第一段", stageOne("", "", AgentLang.EN, false));
        PromptI18nAssertions.assertNoCjk("英文第一段（联网）", stageOne("", "", AgentLang.EN, true));
        PromptI18nAssertions.assertNoCjk("英文第二段（带休眠提示）",
                flows.stageTwoInstruction(windowed, BOUNDARY, "", "", "", null, AgentLang.EN, false));
        PromptI18nAssertions.assertNoCjk("英文第二段（预算已尽）",
                flows.stageTwoInstruction(trader(), BOUNDARY, "", "", "", null, AgentLang.EN, true));
        // 留言段的段头/亲笔提示/footer 都是平台文案，英文用户一个中文字都不许见；正文是主人亲笔，拿英文造
        PromptI18nAssertions.assertNoCjk("英文第一段（带留言段）",
                stageOne("", noteBlock(AgentLang.EN, "Take profit on ETH now."), AgentLang.EN, false));

        // 单币轮：盯盘命中、波动警报、限价单成交、止损，再加强平；后面几段和拒因
        AiTraderWatch watch = new AiTraderWatch();
        watch.setSymbol("BTCUSDT");
        watch.setKind("TOUCH_ABOVE");
        watch.setLevel(new BigDecimal("101000"));
        watch.setNote("breakout, chase long");
        List<WakeTrigger> triggers = List.of(new WatchTrigger(watch, new BigDecimal("101010"), BOUNDARY), alert(),
                event(TradeEvent.LIMIT_FILLED), event(TradeEvent.STOP_LOSS), event(TradeEvent.LIQUIDATION));
        for (boolean webSearch : new boolean[]{false, true}) {
            PromptI18nAssertions.assertNoCjk("英文单币轮开场白", flows.singleInstruction(trader(), "BTCUSDT", triggers,
                    BOUNDARY, null, "", "", "", AgentLang.EN, webSearch));
        }
        Map<String, Object> coin = Map.of("symbol", "BTCUSDT", "mark", prompts.get(AgentLang.EN, "trader.mark.conclusion"));
        for (String key : List.of("single.search", "single.closing", "single.exhausted")) {
            PromptI18nAssertions.assertNoCjk(key, prompts.get(AgentLang.EN, "trader.wake." + key, coin));
        }
        PromptI18nAssertions.assertNoCjk("otherSymbol", prompts.get(AgentLang.EN, "trader.reject.otherSymbol",
                Map.of("symbol", "BTCUSDT", "given", "ETHUSDT")));
    }

    private static EventTrigger event(String type) {
        return new EventTrigger(new TradeEvent(type, 42L, 9L, null, "BTCUSDT", "LONG",
                new BigDecimal("0.5"), new BigDecimal("99000"), null, BOUNDARY));
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
        assertThat(stageOne("", block, AgentLang.ZH, false)).endsWith(block);

        String en = noteBlock(AgentLang.EN, "Take profit on ETH now.");
        assertThat(stageOne("", en, AgentLang.EN, false))
                .endsWith(en)
                .contains("Do not repeat a one-shot action");
    }

    /** 财经日历块只在整盘轮的事实区：单段在问题之前；第二段在它的问题之前。null 时一个字不加 */
    @Test
    void 财经日历块排在问题之前() {
        String calendar = "【财经日历】美国 High 级宏观事件\n- 09-04 20:30 Non Farm Payrolls 实际:206K 预测:205K 前值:207K\n";
        String single = flows.routineInstruction(trader(), List.of(), BOUNDARY, null, "", "", "", "", calendar, AgentLang.ZH);
        String second = flows.stageTwoInstruction(trader(), BOUNDARY, "", "", "", calendar, AgentLang.ZH, false);

        assertThat(single).contains(calendar);
        assertThat(single.indexOf(calendar)).isLessThan(single.indexOf("本轮：看整盘"));
        assertThat(second.indexOf(calendar)).isLessThan(second.indexOf("本段：看整盘"));
    }

    @Test
    void entryEventsShowCountsAndCancellationReasonInBothLanguages() {
        var plan = new AiTraderPlan(); plan.setSymbol("BTCUSDT"); plan.setSide("SHORT");
        var filled = new PlanEntry();
        filled.setLimitPrice(new BigDecimal("102")); filled.setQuantity(new BigDecimal("3")); filled.setFilledPrice(new BigDecimal("102.1"));
        var cancelled = new PlanEntry();
        cancelled.setLimitPrice(new BigDecimal("104")); cancelled.setQuantity(new BigDecimal("4"));
        for (AgentLang lang : AgentLang.values()) {
            cancelled.setReason(prompts.get(lang, "trader.cancelReason.groupClosed"));
            var counts = new TraderPlanStore.EntryCounts(3, 1, 0);
            List<UnreportedEvents.Line> lines = wakeObservation.planEvents(List.of(
                    new TraderPlanStore.EntryFilled(plan, filled, counts),
                    new TraderPlanStore.EntryCancelled(plan, cancelled, counts)), lang);
            assertThat(lines).extracting(UnreportedEvents.Line::symbol).containsExactly("BTCUSDT", "BTCUSDT");
            String text = pack(lang, lines.stream().map(UnreportedEvents.Line::text).collect(Collectors.joining()),
                    List.of(), List.of(), Map.of()).text();
            assertThat(text).contains("102 × 3", "104 × 4", "102.1", cancelled.getReason());
            assertThat(text).contains(lang == AgentLang.ZH ? "共 3 档，已成交 1 档，还挂 0 档" : "3 entries, 1 filled, 0 pending");
            if (lang == AgentLang.EN) PromptI18nAssertions.assertNoCjk("entry events", text);
        }
    }

    /** 观察包（账户/上一轮结论）的文案：英文侧零中文（含全角【】），中文侧段头齐全；没承诺、没事件就不算忙 */
    @Test
    void 观察包中英文案无中文() {
        WakeObservation.Pack en = pack(AgentLang.EN, "", List.of(), List.of(), Map.of());
        PromptI18nAssertions.assertNoCjk("英文观察包", en.text());
        PromptI18nAssertions.assertNoCjk("英文第一段（带观察包）", stageOne(en.text(), "", AgentLang.EN, false));
        assertThat(en.text()).contains("[Account]").contains("[Last round's conclusion]");
        assertThat(en.busy()).isFalse();

        WakeObservation.Pack zh = pack(AgentLang.ZH, "", List.of(), List.of(), Map.of());
        assertThat(zh.text()).contains("【当前账户】").contains("\"equity\":10000.00")
                .contains("【上一轮结论】本局还没有上一轮结论");
    }

    /**
     * 单币轮的观察包只列那个币的持仓和挂单、只带它自己的结论段；权益和合计风险照全账户算。
     * 账户概况里别的币只给数字，最后一行大盘参考；这个币本身是 BTCUSDT 时不给大盘参考
     */
    @Test
    void 单币轮观察包只出那个币账户概况别的币只给数字() {
        AiTrader t = trader();
        t.setSymbols("BTCUSDT,ETHUSDT");
        FuturesPositionDTO eth = openPos("ETHUSDT", "LONG", "2", "3000");
        FuturesPositionDTO btc = openPos("BTCUSDT", "SHORT", "0.1", "100000");
        Map<String, JsonNode> quotes = Map.of(
                "BTCUSDT", MAPPER.readTree("{\"markPrice\":\"100000.00\",\"lastFundingRate\":\"0.0001\"}"),
                "ETHUSDT", MAPPER.readTree("{\"markPrice\":\"3010.00\",\"lastFundingRate\":\"0.0002\"}"));
        when(positionPath.changes("BTCUSDT", BOUNDARY)).thenReturn(Arrays.asList(new BigDecimal("0.42"), null, null));
        when(positionPath.changes("ETHUSDT", BOUNDARY)).thenReturn(Arrays.asList(new BigDecimal("-0.10"), null, null));
        Map<String, WakeObservation.Segment> segments = Map.of(
                "ETHUSDT", new WakeObservation.Segment(BOUNDARY - 3_600_000L, AgentLang.ZH, "判断：区间\n动作：HOLD\n等待：无"));

        for (AgentLang lang : AgentLang.values()) {
            String packText = wakeObservation.assemble(t, WakeScope.of(t, "ETHUSDT"), new BigDecimal("10000"), List.of(eth, btc),
                    List.of(), "", List.of(), "", List.of(), segments, BOUNDARY, lang, List.of(), List.of(), Map.of()).text();
            assertThat(packText).contains("\"symbol\":\"ETHUSDT\"").doesNotContain("\"symbol\":\"BTCUSDT\"")
                    .contains("[ETHUSDT]").contains("判断：区间").doesNotContain("[BTCUSDT]");
            String overview = wakeObservation.accountOverview("ETHUSDT", new BigDecimal("10000"), new BigDecimal("8200.5"),
                    List.of(eth, btc), List.of(), quotes, BOUNDARY, lang);
            assertThat(overview).contains("10000.00").contains("8200.50").contains("BTCUSDT SHORT").contains("0.1 @100000")
                    .doesNotContain("ETHUSDT LONG");
            String coin = wakeObservation.coinFacts(t, "ETHUSDT", null, BOUNDARY, quotes, BOUNDARY, lang);
            assertThat(coin).contains("ETHUSDT").contains("3010").contains("-0.10%");
            if (lang == AgentLang.ZH) {
                assertThat(overview).contains("【账户概况】").contains("大盘参考：BTCUSDT 标记价 100000，1h / 4h / 24h 涨跌 +0.42% / — / —");
            } else {
                PromptI18nAssertions.assertNoCjk("英文账户概况", overview);
                PromptI18nAssertions.assertNoCjk("英文单币行情", coin);
            }
            assertThat(wakeObservation.accountOverview("BTCUSDT", new BigDecimal("10000"), null, List.of(btc), List.of(),
                    quotes, BOUNDARY, lang))
                    .doesNotContain(lang == AgentLang.ZH ? "大盘参考" : "Market benchmark")
                    .contains(prompts.get(lang, "trader.wake.single.otherNone"));
        }
    }

    private static FuturesPositionDTO openPos(String symbol, String side, String quantity, String entry) {
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setId((long) symbol.hashCode());
        p.setSymbol(symbol);
        p.setSide(side);
        p.setQuantity(new BigDecimal(quantity));
        p.setEntryPrice(new BigDecimal(entry));
        p.setMarkPrice(new BigDecimal(entry));
        p.setLeverage(10);
        p.setUnrealizedPnl(BigDecimal.ZERO);
        return p;
    }

    /**
     * 账户块下面那一行：只数今天（新加坡时间）净亏损了结的，止损单触发的另数；赚的、昨天的、主人标了不算数的都不数。
     * 一笔都没有、或者已平仓位没取到，就没有这一行
     */
    @Test
    void 今天净亏损了结按币和方向数() {
        // BOUNDARY 是新加坡时间 01:00
        FuturesPositionDTO ignored = closedPos("-40", "STOP_LOSS", BOUNDARY - 400_000L);
        ignored.setId(9L);
        AiTraderPlan stale = new AiTraderPlan();
        stale.setPositionId(9L);
        stale.setStale(true);
        List<FuturesPositionDTO> closed = List.of(
                closedPos("-30", "STOP_LOSS", BOUNDARY - 600_000L),
                closedPos("-12", "FILLED", BOUNDARY - 300_000L),
                closedPos("50", "TAKE_PROFIT", BOUNDARY - 200_000L),
                ignored,
                // 前一天 23:00
                closedPos("-80", "STOP_LOSS", BOUNDARY - 7_200_000L));

        WakeScope book = WakeScope.book(trader());
        assertThat(wakeObservation.lossesToday(trader(), book, () -> closed, List.of(stale), BOUNDARY, AgentLang.ZH))
                .isEqualTo("今天（新加坡时间 0 点起）净亏损了结的仓位：BTCUSDT SHORT 2 次（其中止损单触发 1 次）\n");
        String en = wakeObservation.lossesToday(trader(), book, () -> closed, List.of(stale), BOUNDARY, AgentLang.EN);
        PromptI18nAssertions.assertNoCjk("英文今天净亏损了结行", en);
        assertThat(en).contains("BTCUSDT SHORT 2 (of which 1 by stop order)");
        assertThat(wakeObservation.lossesToday(trader(), book, List::of, List.of(), BOUNDARY, AgentLang.ZH)).isEmpty();
        assertThat(wakeObservation.lossesToday(trader(), book, () -> {
            throw new IllegalStateException("sim down");
        }, List.of(), BOUNDARY, AgentLang.ZH)).isEmpty();
        // 单币轮只数那个币的
        AiTrader two = trader();
        two.setSymbols("BTCUSDT,ETHUSDT");
        assertThat(wakeObservation.lossesToday(two, WakeScope.of(two, "ETHUSDT"), () -> closed, List.of(stale), BOUNDARY, AgentLang.ZH))
                .isEmpty();
    }

    private static FuturesPositionDTO closedPos(String pnl, String closedBy, long closedMs) {
        FuturesPositionDTO p = new FuturesPositionDTO();
        p.setSymbol("BTCUSDT");
        p.setSide("SHORT");
        p.setStatus("CLOSED");
        p.setClosedPnl(new BigDecimal(pnl));
        p.setClosedBy(closedBy);
        p.setUpdatedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(closedMs), ZoneId.systemDefault()));
        return p;
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

        WakeObservation.Pack zh = pack(AgentLang.ZH, "", List.of(), List.of(w), marks);
        assertThat(zh.text()).contains("【你的承诺】")
                .contains("- BTCUSDT 触及 65000 以上（备注：breakout, chase long）：未命中；现价 64000，在 65000 下方 1.54%");
        assertThat(zh.busy()).isTrue();

        WakeObservation.Pack en = pack(AgentLang.EN, "", List.of(), List.of(w), marks);
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

        String zh = pack(AgentLang.ZH, "", List.of(fired), List.of(armed), Map.of()).text();
        assertThat(zh)
                .contains("- BTCUSDT 触及 63000 以下（备注：market breaks, cut the alt）：未命中；预案：命中后平台市价平掉 ETHUSDT LONG（positionId 12）的 50%，然后叫醒你")
                .contains("你的备注：lock breakeven｜预案已由平台执行：ETHUSDT LONG 止损 3000→3050");

        fired.setActionResult("ETHUSDT LONG stop 3000→3050");
        String en = pack(AgentLang.EN, "", List.of(fired), List.of(armed), Map.of()).text();
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

        List<String> rows = WakeObservation.board(List.of("ZECUSDT"));
        assertThat(wakeObservation.symbolsSummary(t, rows, List.of("ZECUSDT"), null, BOUNDARY, AgentLang.ZH))
                .contains("- BTCUSDT（大盘参考，不可交易） ").doesNotContain("ZECUSDT");
        PromptI18nAssertions.assertNoCjk("英文各币摘要",
                wakeObservation.symbolsSummary(t, rows, List.of("ZECUSDT"), null, BOUNDARY, AgentLang.EN));
    }

    /** 观察包紧跟头部事实：整盘轮在快照之前；单币轮在"只处理哪个币"之后、这一轮的问题之前 */
    @Test
    void 观察包排在头部事实之后() {
        String obs = "\n【当前账户】\n{}\n";
        String snapshot = "- BTCUSDT 标记价 100000，资金费率 0.0001\n";
        String routine = flows.routineInstruction(trader(), List.of(), BOUNDARY, null, obs, "", "", snapshot, null, AgentLang.ZH);
        assertThat(routine.indexOf(obs)).isGreaterThan(routine.indexOf("新一根 1h K线已收盘"));
        assertThat(routine.indexOf(obs)).isLessThan(routine.indexOf("行情快照"));

        String alert = alertOpening(trader(), obs, AgentLang.ZH, false);
        assertThat(alert.indexOf(obs)).isGreaterThan(alert.indexOf("这一轮只处理 BTCUSDT"));
        assertThat(alert.indexOf(obs)).isLessThan(alert.indexOf("上面是这个币的波动警报"));
    }

    /** 中文单段开场白逐字钉死 */
    @Test
    void 中文单段开场白逐字不变() {
        assertThat(routine(trader(), AgentLang.ZH))
                .isEqualTo("新一根 1h K线已收盘（" + TIME_FMT.format(Instant.ofEpochMilli(BOUNDARY)) + "）。"
                        + "本轮：看整盘——每个可交易币现在有没有值得做的交易。先读上面的事实，判断用工具求证。"
                        + "想让系统在某个价位、某根收盘或某个时刻叫醒你，用 set_watch 布下去"
                        + "（每个方向只留得下一条，布最先要处理的，更远的写进等待行）；不需要叫醒的币不用布。"
                        + "最后用[本轮结论]固定格式收尾（只写有变化的币）。");
    }

    /** 中文整盘轮第一段与第二段两版（正常 / 预算已尽）逐字钉死 */
    @Test
    void 中文两段开场白逐字不变() {
        assertThat(stageOne("", "", AgentLang.ZH, false))
                .isEqualTo("新一根 1h K线已收盘（" + TIME_FMT.format(Instant.ofEpochMilli(BOUNDARY)) + "）。"
                        + "本轮分两段，这是第一段，只处置你自己留下的事：【自上次唤醒以来】里命中的盯盘条件按布它时的备注办，结局先对账；"
                        + "【你的承诺】只处置有变化的——失效条件被触发或快到的、挂单该撤的、等的条件到了的，"
                        + "该动的动（移止损、平仓、撤单、按备注进场），没变化的不用逐条复述；主人的留言按它段内的说明办。"
                        + "写了等待但没布条件的，说明是不需要叫醒还是忘了。本段不布盯盘条件、不写[本轮结论]块，那是第二段的事。");
        assertThat(flows.stageTwoInstruction(trader(), BOUNDARY, "", "", "", null, AgentLang.ZH, false))
                .isEqualTo("第二段。第一段里你做的动作已经生效，下面是整盘事实。\n"
                        + "本段：看整盘——有没有值得做的新交易。想让系统在某个价位、某根收盘或某个时刻叫醒你，用 set_watch 布下去"
                        + "（一次整组替换该币全部条件，要留的旧条件一并带上；每个方向只留得下一条，布最先要处理的，更远的写进等待行）；"
                        + "还在盯的条件不用重布，不需要叫醒的币不用布。"
                        + "最后用[本轮结论]固定格式收尾（只写有变化的币）。");
        assertThat(flows.stageTwoInstruction(trader(), BOUNDARY, "", "", "", null, AgentLang.ZH, true))
                .isEqualTo("第二段。第一段里你做的动作已经生效，下面是整盘事实。\n"
                        + "本段：模型调用预算已用完，不能再调用任何工具，set_watch 也不行。"
                        + "直接用[本轮结论]固定格式收尾（只写有变化的币），要等的条件写进\"等待\"行，下一轮再布。");
    }

    /** 整盘的联网轮：开场白交代分三段，第一段只核查事实；被不归某一个币的事叫醒的整盘轮，头换成那件事，问题不变 */
    @Test
    void onlineFactsAndSearchAssessmentHaveSeparateInstructionsInBothLanguages() {
        for (AgentLang lang : AgentLang.values()) {
            String mark = prompts.get(lang, "trader.mark.conclusion");
            String facts = prompts.get(lang, "trader.wake.factsQuestion", Map.of("mark", mark));
            String search = prompts.get(lang, "trader.wake.searchQuestion");
            assertThat(stageOne("", "", lang, true))
                    .contains(prompts.get(lang, "trader.wake.routineFlow")).contains(facts).doesNotContain(search);
            assertThat(flows.stageOneInstruction(trader(), List.of(event(TradeEvent.CROSS_LIQUIDATION)), BOUNDARY, null, "", "", lang, true))
                    .contains(prompts.get(lang, "trader.wake.triggerNotice", Map.of("interval", "1h")))
                    .contains(facts).doesNotContain(search);
            for (String key : List.of("factsQuestion", "searchQuestion", "searchInstruction", "tradingQuestion", "searchClosing")) {
                String text = prompts.get(lang, "trader.wake." + key, Map.of("mark", mark));
                assertThat(text).doesNotContain("{{");
                if (lang == AgentLang.EN) {
                    PromptI18nAssertions.assertNoCjk(key, text);
                }
            }
        }
    }

    /** 权益在两小时前到过 10000，现在 8500.12，回落 15% */
    private static EquityDropTrigger equityDrop() {
        return new EquityDropTrigger(new BigDecimal("10000.00000000"), BOUNDARY - 7_200_000L,
                new BigDecimal("8500.12345678"), new BigDecimal("15.00"), new BigDecimal("15.0"), BOUNDARY + 300_000L);
    }

    /**
     * 权益回落警报叫醒的整盘轮：开头报权益回落的事实、注明当前 K 线没收盘，第一段的问题前面多问一句，后面和例行轮一样走。
     * 插进在跑的整盘轮时问的是同一件事。中英各钉一遍，英文零中文
     */
    @Test
    void 权益回落警报的开场白和插话() {
        TriggerText triggerText = new TriggerText(prompts);
        assertThat(triggerText.line(equityDrop(), AgentLang.ZH)).isEqualTo("权益回落警报（"
                + TIME_FMT.format(Instant.ofEpochMilli(BOUNDARY + 300_000L)) + "）：账户权益现在 8500.12 USDT，最高到过 10000 USDT（"
                + TIME_FMT.format(Instant.ofEpochMilli(BOUNDARY - 7_200_000L)) + "），回落 15%，到了主人设的 15%。");
        for (AgentLang lang : AgentLang.values()) {
            String mark = prompts.get(lang, "trader.mark.conclusion");
            String line = triggerText.line(equityDrop(), lang);
            for (boolean webSearch : new boolean[]{false, true}) {
                String opening = flows.stageOneInstruction(trader(), List.of(equityDrop()), BOUNDARY + 300_000L, BOUNDARY,
                        "", "", lang, webSearch);
                String stage = prompts.get(lang, webSearch ? "trader.wake.factsQuestion" : "trader.wake.stageQuestion",
                        Map.of("mark", mark));
                assertThat(opening).startsWith(line)
                        .contains(prompts.get(lang, "trader.wake.triggerNotice", Map.of("interval", "1h")))
                        .contains(prompts.get(lang, "trader.wake.equityDropQuestion") + '\n' + stage)
                        .doesNotContain("{{");
                if (lang == AgentLang.EN) {
                    PromptI18nAssertions.assertNoCjk("英文权益回落警报开场白", opening);
                }
            }
            String interjection = triggerText.interjection(equityDrop(), null, false, lang);
            assertThat(interjection).contains(line).endsWith(prompts.get(lang, "trader.wake.interject.equityDrop"));
            if (lang == AgentLang.EN) {
                PromptI18nAssertions.assertNoCjk("英文权益回落警报插话", interjection);
            }
        }
    }

    /** 警报里"距下一次例行唤醒"要看时段：时段末尾的警报，下次醒是时段再开门那一刻，不是下一根 */
    @Test
    void 警报开场白的下次例行唤醒按时段算() {
        AiTrader t = trader();
        // BOUNDARY 是新加坡时间 01:00，时段内最后一根；下一边界 02:00 已出时段，再醒是 08:00
        t.setWakeWindow("08:00-01:00");

        assertThat(alertOpening(t, "", AgentLang.ZH, false))
                .contains("距下一次例行唤醒还有约 420 分钟");
    }

    /** 中文波动警报的单币轮开场白逐字不变 */
    @Test
    void 中文警报开场白逐字不变() {
        String time = TIME_FMT.format(Instant.ofEpochMilli(BOUNDARY));
        assertThat(alertOpening(trader(), "", AgentLang.ZH, false))
                .isEqualTo("行情波动警报（" + time + "）：BTCUSDT 5分钟内波动 5.2%（方向：上涨，现价 100000）\n"
                        + "你上次唤醒本局还没有过唤醒，距下一次例行唤醒还有约 60 分钟。\n"
                        + "注意：本次不是例行唤醒，当前 1h K线尚未收盘——按 1h 收盘定义的条件此刻不作数，求证请用已收盘的K线。\n"
                        + "这一轮只处理 BTCUSDT：交易和盯盘工具只对它生效，对别的币的动作会被拒；行情和数据工具不限币。"
                        + "别的币的结论和盯盘条件原样保留，由它们自己的那一轮和例行轮管。\n"
                        + "\n本轮分两段：先处理把你叫醒的事，该做的动作当场做；再给这个币布盯盘并收尾。\n"
                        + "上面是这个币的波动警报：它的仓位和挂单怎么处理？持仓的计划有没有被这波波动动摇，要不要先用工具求证、求证到哪一步由你定。\n"
                        + "平仓、撤单、收紧止损可以当场做；开仓、加仓、反手之前，要先取得足够明确的信息。"
                        + "被叫醒本身不是动作的理由，也不是不动的理由。本段不布盯盘条件、不写[本轮结论]块，那是最后一段的事。");
    }

    /** 四种单币轮各问各的；开了联网的交代分三段 */
    @Test
    void 四种单币轮各有各的问题() {
        AiTraderWatch watch = new AiTraderWatch();
        watch.setSymbol("BTCUSDT");
        watch.setKind("TOUCH_ABOVE");
        watch.setLevel(new BigDecimal("101000"));
        watch.setNote("突破追多");
        Map<WakeTrigger, String> asked = Map.of(
                new WatchTrigger(watch, new BigDecimal("101010"), BOUNDARY), "你当时备注要做的事，现在做不做",
                alert(), "它的仓位和挂单怎么处理",
                event(TradeEvent.LIMIT_FILLED), "现在的局势和你挂单时的设想一致吗",
                event(TradeEvent.STOP_LOSS), "这个币的仓位已经没了",
                event(TradeEvent.LIQUIDATION), "这个币的仓位已经没了");
        asked.forEach((trigger, question) -> assertThat(flows.singleInstruction(trader(), "BTCUSDT", List.of(trigger),
                BOUNDARY, null, "", "", "", AgentLang.ZH, true))
                .contains(question)
                .contains("本轮分三段")
                .contains("开仓、加仓、反手之前，要先取得足够明确的信息"));
    }
}
