package com.mawai.wiibagent.trader.wakeup;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibagent.i18n.PromptI18nAssertions;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.toolkit.IndicatorToolkit;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibagent.toolkit.NewsToolkit;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.trader.DecisionText;
import com.mawai.wiibagent.trader.TraderModelFactory;
import com.mawai.wiibagent.trader.prompt.EconCalendarAssembler;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
import com.mawai.wiibagent.trader.prompt.TraderPromptAssembler;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 唤醒开场白的双语契约：开场白的收尾标记与系统提示词必须同源，否则模型同时收到两条冲突的
 * 格式指令，收尾格式失守、复盘素材切不出"判断/动作/等待"。
 * <p>
 * 判据是"同源"而不是"等于某个字面量"：断言比的是 {@code trader.mark.conclusion}
 * （系统提示词模板用的同一条 key）的实际取值——改词表两边一起变，测试照样绿；
 * 开场白改成硬编码，它立刻红。
 */
class WakeInstructionI18nTest {

    private static final long BOUNDARY = 1785171600000L;

    private final PromptCatalog prompts = new PromptCatalog();
    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final BinanceRestClient binance = mock(BinanceRestClient.class);

    private final TraderWakeupRunner runner = new TraderWakeupRunner(
            mock(TraderModelFactory.class), new TraderPromptAssembler(traderMapper, prompts),
            mock(SimTradeClient.class), binance,
            new IndicatorToolkit(new KlineFetcher(binance, 60_000)),
            new MarketToolkit(mock(MarketDataService.class)),
            new NewsToolkit(mock(NewsCache.class), mock(NewsFlashLocalizer.class)),
            traderMapper, mock(AiTraderDecisionMapper.class),
            new TraderPlanStore(mock(AiTraderPlanMapper.class), prompts),
            mock(UserLangResolver.class), prompts,
            new MessageCatalog(),
            new LocalizedToolCallbacks(prompts),
            mock(DecisionText.class),
            mock(EconCalendarAssembler.class), mock(PlayStatsAssembler.class), new TraderLiveHub());

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
        return new TraderPromptAssembler(traderMapper, prompts).ownerNoteBlock(t, lang);
    }

    private static AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setSymbols("BTCUSDT");
        t.setIntervalCode("1h");
        return t;
    }

    private static AlertTrigger alert() {
        return new AlertTrigger("BTCUSDT", new BigDecimal("5.2"), new BigDecimal("100000"),
                AlertTrigger.UP, BOUNDARY);
    }

    /** 例行开场白的收尾标记 = 系统提示词模板里的那一条，两门语言各钉一遍 */
    @Test
    void 例行开场白的收尾标记与系统提示词同源() {
        for (AgentLang lang : AgentLang.values()) {
            String mark = prompts.get(lang, "trader.mark.conclusion");
            String template = new TraderPromptAssembler(traderMapper, prompts)
                    .platformTemplate(lang, "1h", "BTCUSDT", TraderRiskConfig.of(new AiTrader()), null);
            String opening = runner.routineInstruction(trader(), BOUNDARY, "", "", null, lang, "");

            assertThat(template).as("%s 系统提示词要带收尾标记", lang.code()).contains(mark);
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

    /** 警报开场白同理：它也要求"最后仍用固定格式收尾" */
    @Test
    void 警报开场白的收尾标记与系统提示词同源() {
        for (AgentLang lang : AgentLang.values()) {
            String opening = runner.alertInstruction(trader(), alert(), null, "", null, lang, "");
            assertThat(opening).as("%s 警报开场白的收尾标记", lang.code())
                    .contains(prompts.get(lang, "trader.mark.conclusion"));
            for (AgentLang other : AgentLang.values()) {
                if (other != lang) {
                    assertThat(opening).doesNotContain(prompts.get(other, "trader.mark.conclusion"));
                }
            }
        }
    }

    /** 英文唤醒开场白（例行/警报/休眠提示）全文零中文 */
    @Test
    void 英文唤醒开场白全文无中文() {
        PromptI18nAssertions.assertNoCjk("英文例行开场白",
                runner.routineInstruction(trader(), BOUNDARY, "", "", null, AgentLang.EN, ""));
        AiTrader windowed = trader();
        windowed.setWakeWindow("21:00-08:00");
        PromptI18nAssertions.assertNoCjk("英文例行开场白（带休眠提示）",
                runner.routineInstruction(windowed, BOUNDARY, "", "", null, AgentLang.EN, ""));
        PromptI18nAssertions.assertNoCjk("英文警报开场白",
                runner.alertInstruction(trader(), alert(), null, "", null, AgentLang.EN, ""));
        // 留言段的段头/亲笔提示/footer 都是平台文案，英文用户一个中文字都不许见；正文是主人亲笔，拿英文造
        PromptI18nAssertions.assertNoCjk("英文例行开场白（带留言段）",
                runner.routineInstruction(trader(), BOUNDARY, "", "", null, AgentLang.EN, noteBlock(AgentLang.EN, "Take profit on ETH now.")));
        PromptI18nAssertions.assertNoCjk("英文警报开场白（带留言段）",
                runner.alertInstruction(trader(), alert(), null, "", null, AgentLang.EN, noteBlock(AgentLang.EN, "Take profit on ETH now.")));
    }

    /**
     * 有待读留言：留言段整段压在开场白最末（user 消息最近因位置——留言在这儿才是"本轮要回答的问题之一"，
     * 放 system 里跟纪律同层必被压过），例行/警报同款；反重放跟正文同位置。
     * 没有留言则一个字不加（逐字钉死那两条已经覆盖无留言路径）。
     */
    @Test
    void 有留言时留言段压在开场白最末() {
        String block = noteBlock(AgentLang.ZH, "ETH 提前止盈");
        assertThat(block)
                .startsWith("\n")
                .contains("主人的留言").contains("ETH 提前止盈")
                .as("留言段自带反重放").contains("一次性动作做过不要再做");
        assertThat(runner.routineInstruction(trader(), BOUNDARY, "", "", null, AgentLang.ZH, block)).endsWith(block);
        assertThat(runner.alertInstruction(trader(), alert(), null, "", null, AgentLang.ZH, block)).endsWith(block);

        String en = noteBlock(AgentLang.EN, "Take profit on ETH now.");
        assertThat(runner.routineInstruction(trader(), BOUNDARY, "", "", null, AgentLang.EN, en))
                .endsWith(en)
                .contains("Do not repeat a one-shot action");
    }

    /** 财经日历块在事实区注入（例行/警报同享）；null 时一个字不加（逐字钉死用例覆盖无日历路径） */
    @Test
    void 财经日历块注入例行与警报开场白() {
        String calendar = "【财经日历】宏观事件时刻表\n- 09-04 20:30 [High] USD Non-Farm Employment Change\n";
        String routine = runner.routineInstruction(trader(), BOUNDARY, "", "", calendar, AgentLang.ZH, "");
        String alert = runner.alertInstruction(trader(), alert(), null, "", calendar, AgentLang.ZH, "");

        assertThat(routine).contains(calendar);
        assertThat(alert).contains(calendar);
        // 事实区位置：问题指令之前——日历是事实不是近因指令，不许挤到收尾
        assertThat(routine.indexOf(calendar)).isLessThan(routine.indexOf("本轮只需回答一个问题"));
        assertThat(alert.indexOf(calendar)).isLessThan(alert.indexOf("本次只需回答一个问题"));
    }

    /** 观察包（账户/上一轮结论/轨迹）的文案：英文侧零中文（含全角【】），中文侧段头齐全 */
    @Test
    void 观察包中英文案无中文() {
        String en = runner.observation(trader(), new BigDecimal("10000"), List.of(), List.of(),
                new TraderPlanStore.Rebind(List.of(), List.of(), List.of()), List.of(), BOUNDARY, AgentLang.EN);
        PromptI18nAssertions.assertNoCjk("英文观察包", en);
        PromptI18nAssertions.assertNoCjk("英文例行开场白（带观察包）",
                runner.routineInstruction(trader(), BOUNDARY, en, "", null, AgentLang.EN, ""));
        PromptI18nAssertions.assertNoCjk("英文警报开场白（带观察包）",
                runner.alertInstruction(trader(), alert(), null, en, null, AgentLang.EN, ""));
        assertThat(en).contains("[Account]").contains("[Last round's conclusion]");

        String zh = runner.observation(trader(), new BigDecimal("10000"), List.of(), List.of(),
                new TraderPlanStore.Rebind(List.of(), List.of(), List.of()), List.of(), BOUNDARY, AgentLang.ZH);
        assertThat(zh).contains("【当前账户】").contains("\"equity\":10000.00")
                .contains("【上一轮结论】本局还没有可检验的结论块");
    }

    /** 观察包紧跟头部事实：例行在快照之前，警报在"上次唤醒"之后、"尚未收盘"提醒之前 */
    @Test
    void 观察包排在头部事实之后快照与问题之前() {
        String obs = "\n【当前账户】\n{}\n";
        String snapshot = "- BTCUSDT 标记价 100000，资金费率 0.0001\n";
        String routine = runner.routineInstruction(trader(), BOUNDARY, obs, snapshot, null, AgentLang.ZH, "");
        assertThat(routine.indexOf(obs)).isGreaterThan(routine.indexOf("新一根 1h K线已收盘"));
        assertThat(routine.indexOf(obs)).isLessThan(routine.indexOf("行情快照"));

        String alert = runner.alertInstruction(trader(), alert(), null, obs, null, AgentLang.ZH, "");
        assertThat(alert.indexOf(obs)).isGreaterThan(alert.indexOf("你上次唤醒"));
        assertThat(alert.indexOf(obs)).isLessThan(alert.indexOf("注意：当前 1h K线尚未收盘"));
    }

    /** 中文侧成文逐字钉死：外置只搬位置，成文一个字不变 */
    @Test
    void 中文例行开场白逐字不变() {
        assertThat(runner.routineInstruction(trader(), BOUNDARY, "", "", null, AgentLang.ZH, ""))
                .isEqualTo("新一根 1h K线已收盘（"
                        + java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
                                .withZone(java.time.ZoneId.systemDefault())
                                .format(java.time.Instant.ofEpochMilli(BOUNDARY))
                        + "）。"
                        + "本轮只需回答一个问题：这根K线收盘后，你的计划需要改变吗？"
                        + "先逐币检验上一轮[本轮结论]里各币段的等待条件与各持仓的失效条件，再考虑新机会；"
                        + "最后按纪律用[本轮结论]固定格式收尾（每个可交易币各一段）。");
    }

    /** 中文警报开场白逐字不变 */
    @Test
    void 中文警报开场白逐字不变() {
        assertThat(runner.alertInstruction(trader(), alert(), null, "", null, AgentLang.ZH, ""))
                .isEqualTo("⚠️ 行情波动警报（非例行唤醒）：BTCUSDT 5分钟内波动 5.2%（方向：上涨，现价 100000）。\n"
                        + "你上次唤醒本局还没有过唤醒，距下一次例行唤醒还有约 60 分钟。\n"
                        + "注意：当前 1h K线尚未收盘——你的收盘制失效条件此刻不作数，"
                        + "求证请用已收盘的 5m/15m K线。你的止损单仍在自动保护你。\n"
                        + "本次只需回答一个问题：这次波动是否动摇了你的持仓计划？计划未被动摇 → HOLD 并说明理由；"
                        + "不因为被叫醒而必须动作。最后仍用[本轮结论]固定格式收尾（每个可交易币各一段）。");
    }
}
