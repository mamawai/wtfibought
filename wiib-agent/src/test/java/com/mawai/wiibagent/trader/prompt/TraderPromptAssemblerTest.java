package com.mawai.wiibagent.trader.prompt;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TraderPromptAssemblerTest {

    /** 留言焚毁走 Lambda 条件构造器，要查 TableInfo；不预热的话本类单独跑会炸 */
    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTrader.class);
    }

    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final TraderPromptAssembler assembler =
            new TraderPromptAssembler(traderMapper, new PromptCatalog());

    private AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setName("测试员");
        t.setSymbols("BTCUSDT,ETHUSDT");
        t.setIntervalCode("1h");
        t.setCustomPrompt("只做突破，不抄底。");
        return t;
    }

    // ---------- 主人留言：进开场白不进 system，按轮次递减 ----------

    /**
     * 注入与递减必须是同一件事：注了没减，一句交代会每轮重念、被模型当成长期规则；
     * 减了没注，主人的话直接蒸发。所以这条一次断言两头。
     */
    @Test
    void 留言注入的同时就消费一轮() {
        AiTrader t = trader();
        t.setId(7L);
        t.setOwnerNote("今晚有 CPI 数据，仓位放轻一点");
        t.setOwnerNoteRounds(1);

        String block = assembler.ownerNoteBlock(t, AgentLang.ZH);

        assertThat(block).contains("今晚有 CPI 数据，仓位放轻一点").contains("主人的留言");
        verify(traderMapper).update(isNull(), any(LambdaUpdateWrapper.class));   // 注了就一定减了
        assertThat(t.getOwnerNote()).isNull();  // 同一轮里别处再读到它就会重复露面
    }

    /** 留言不进系统提示词：system 里的字跟纪律同层必被纪律压过，正文只走开场白段；assemble 不碰它也就不消费 */
    @Test
    void 系统提示词不含留言() {
        AiTrader t = trader();
        t.setId(7L);
        t.setOwnerNote("今晚有 CPI 数据");
        t.setOwnerNoteRounds(3);

        String prompt = assembler.assemble(t, AgentLang.ZH);

        assertThat(prompt).doesNotContain("今晚有 CPI 数据").doesNotContain("————— 主人的留言");
        verify(traderMapper, never()).update(any(), any());
        assertThat(t.getOwnerNoteRounds()).isEqualTo(3);
    }

    /** 默认模板：节奏行按时段说真话；退出模板：单独注入事实行；全天：两处都不提 */
    @Test
    void wakeWindowTruthfulInTemplateAndInjectedWithoutTemplate() {
        AiTrader t = trader();
        t.setId(1L);
        t.setWakeWindow("21:00-08:30");

        String withTemplate = assembler.assemble(t, AgentLang.ZH);
        assertThat(withTemplate).contains("节奏：每天 21:00-08:30（北京时间，两端含）内每根 1h K线收盘唤醒你一次");
        assertThat(withTemplate).doesNotContain("\n唤醒时段：");

        t.setUseDefaultPrompt(false);
        assertThat(assembler.assemble(t, AgentLang.ZH)).contains("唤醒时段：每天 21:00-08:30");

        t.setWakeWindow(null);
        assertThat(assembler.assemble(t, AgentLang.ZH)).doesNotContain("唤醒时段");
        t.setUseDefaultPrompt(true);
        assertThat(assembler.assemble(t, AgentLang.ZH)).contains("节奏：每根 1h K线收盘唤醒你一次");
    }

    /** 单轮留言消费完再取一次：不该复活，也不该再写一次库 */
    @Test
    void 单轮留言只出现一次() {
        AiTrader t = trader();
        t.setId(7L);
        t.setOwnerNote("今晚有 CPI 数据");
        t.setOwnerNoteRounds(1);
        assembler.ownerNoteBlock(t, AgentLang.ZH);

        String second = assembler.ownerNoteBlock(t, AgentLang.ZH);

        assertThat(second).isEmpty();
        verify(traderMapper, times(1)).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    /**
     * 多轮留言：注入一次减一轮，正文留着下轮还念。措辞必须报出剩余次数——
     * 模型据此把它当持续叮嘱而不是"现在就执行一次"的动作指令，这是多轮重放风险的唯一防线。
     */
    @Test
    void 多轮留言逐轮递减且报出剩余次数() {
        AiTrader t = trader();
        t.setId(7L);
        t.setOwnerNote("今晚有 CPI 数据");
        t.setOwnerNoteRounds(3);

        String first = assembler.ownerNoteBlock(t, AgentLang.ZH);

        assertThat(first).contains("今晚有 CPI 数据").contains("本次之后还会出现 2 次");
        assertThat(t.getOwnerNote()).isNotNull();
        assertThat(t.getOwnerNoteRounds()).isEqualTo(2);
    }

    /** 最后一轮：措辞切回"只在本次出现"，内存副本与库写同构地清空 */
    @Test
    void 最后一轮留言注入后清空() {
        AiTrader t = trader();
        t.setId(7L);
        t.setOwnerNote("今晚有 CPI 数据");
        t.setOwnerNoteRounds(1);

        String block = assembler.ownerNoteBlock(t, AgentLang.ZH);

        assertThat(block).contains("只在本次唤醒出现").doesNotContain("还会出现");
        assertThat(t.getOwnerNote()).isNull();
        assertThat(t.getOwnerNoteRounds()).isZero();
    }

    /**
     * 举证责任倒置：执行不需要理由，否决只认撞硬规则或引数字的独立判断，纪律条文不是否决依据；
     * 结论里必须单起一行对账。旧「尽量考虑履行／观点不成立才可不听」等于给了万能借口，必须绝迹。
     */
    @Test
    void 留言否决只认数字不认纪律() {
        AiTrader t = trader();
        t.setId(7L);
        t.setOwnerNote("ETH 提前止盈");
        t.setOwnerNoteRounds(1);

        String block = assembler.ownerNoteBlock(t, AgentLang.ZH);

        assertThat(block)
                .contains("ETH 提前止盈")
                .contains("执行不需要理由")
                .contains("不引数字的反对视为没有反对")
                .contains("主人留言：已执行")
                .as("收尾标记与系统提示词同源").contains("[本轮结论]")
                .as("开仓类留言：硬性字段自己补齐，不是拒绝理由").contains("止损、止盈、失效条件、论点标签由你自己补齐")
                .as("反重放跟正文同位置").contains("一次性动作做过不要再做")
                .doesNotContain("尽量考虑履行").doesNotContain("观点不成立").doesNotContain("不是常驻规则");

        AiTrader en = trader();
        en.setId(8L);
        en.setOwnerNote("take profit on ETH now");
        en.setOwnerNoteRounds(1);
        assertThat(assembler.ownerNoteBlock(en, AgentLang.EN))
                .contains("Acting needs no reason")
                .contains("an objection citing no number counts as no objection")
                .contains("Owner's message: acted")
                .contains("[ROUND CONCLUSION]")
                .contains("Do not repeat a one-shot action")
                .doesNotContain("try to act on it").doesNotContain("does not hold").doesNotContain("not a standing rule");
    }

    /**
     * 迁移半途的存量行：ALTER 跑了、回填 UPDATE 漏跑，库里就是"有正文、轮次 0/null"。
     * 必须退化成一次性留言——拆箱 NPE 会让唤醒抛异常，每轮唤醒写一条 ERROR 行、
     * 连败 5 次后 trader 被自动暂停，用户看到的是"它莫名其妙停了"。
     */
    @Test
    void 轮次缺失的存量留言当一次性处理() {
        AiTrader t = trader();
        t.setId(7L);
        t.setOwnerNote("存量留言");
        t.setOwnerNoteRounds(null);

        String block = assembler.ownerNoteBlock(t, AgentLang.ZH);

        assertThat(block).contains("存量留言").contains("只在本次唤醒出现");
        assertThat(t.getOwnerNote()).isNull();
    }

    /** 没留言就别去动库：每轮唤醒都白写一次 UPDATE 是纯浪费 */
    @Test
    void 没有留言时不写库() {
        String block = assembler.ownerNoteBlock(trader(), AgentLang.ZH);

        assertThat(block).isEmpty();
        verify(traderMapper, never()).update(any(), any());
    }

    /** 空白留言等于没有：不注入也不写库 */
    @Test
    void 空白留言不注入() {
        AiTrader t = trader();
        t.setId(7L);
        t.setOwnerNote("   ");

        String block = assembler.ownerNoteBlock(t, AgentLang.ZH);

        assertThat(block).isEmpty();
        verify(traderMapper, never()).update(any(), any());
    }

    /**
     * 收尾格式是系统强制：退出平台模板照样注入、自定义指令改不掉；骨架按真实币种生成，段头独占一行；
     * 位置在自定义指令之后、输出语言之前。复盘素材/stale 剔段/观望门控全靠它切分币段。
     */
    @Test
    void 收尾格式不随模板开关走且按币种生成骨架() {
        AiTrader t = trader();
        t.setUseDefaultPrompt(false);

        String prompt = assembler.assemble(t, AgentLang.ZH);

        assertThat(prompt)
                .contains("固定收尾格式（系统强制")
                .contains("\n[本轮结论]\n")
                .contains("\n[BTCUSDT]\n判断：")
                .contains("\n[ETHUSDT]\n判断：")
                .as("计划内容只许写在该币段里").contains("入场/止损/目标/作废条件");
        assertThat(prompt.indexOf("固定收尾格式")).isGreaterThan(prompt.indexOf("只做突破，不抄底。"));
        assertThat(prompt.indexOf("固定收尾格式")).isLessThan(prompt.indexOf("输出语言：中文。"));

        // 单币种只出一段，不留空段头
        AiTrader single = trader();
        single.setSymbols("BTCUSDT");
        String one = assembler.closingFormat(AgentLang.ZH, single.getSymbols());
        assertThat(one).contains("[BTCUSDT]").doesNotContain("[ETHUSDT]").doesNotContain("[]");
    }

    @Test
    void containsHardRulesAndCustomPrompt() {
        String prompt = assembler.assemble(trader(), AgentLang.ZH);

        assertThat(prompt)
                .contains("3~20 倍")        // 默认杠杆区间
                .contains("5%~20%")        // 默认保证金区间
                .contains("虚拟资金模拟盘") // 规格是主人定的，模型不许评价
                .contains("BTCUSDT,ETHUSDT")
                .contains("只做突破，不抄底。");
    }

    /**
     * 纪律锚定"计划内退出"：退出只认止损/止盈/失效条件，浮亏不是平仓理由；做与不做都要有理由，两个方向都不带偏置。
     * 旧版"亏损的实验也有产出/不开仓才是失败"是行动偏置的病根（nof1 第一季过度交易的教训），
     * 反过来的"HOLD 是常态"是不动偏置，同样必须绝迹。
     */
    @Test
    void disciplineAnchorsPlanBasedExits() {
        String prompt = assembler.assemble(trader(), AgentLang.ZH);

        assertThat(prompt)
                .contains("虚拟资金")
                .contains("失效条件")
                .contains("浮亏不是平仓理由")
                .contains("做与不做都要有理由")
                .contains("盈亏比")
                .contains("触发条件")
                .doesNotContain("亏损的实验也有产出")
                .doesNotContain("唯一真正的失败")
                .doesNotContain("HOLD 是常态")
                .doesNotContain("2:1");
    }

    /** 模板必须交代计划管理工具与修改纪律：止损只许收紧、止盈只许远离入场、无计划持仓先补立 */
    @Test
    void templateMentionsPlanManagementTools() {
        String prompt = assembler.assemble(trader(), AgentLang.ZH);

        assertThat(prompt)
                .contains("set_take_profit")
                .contains("write_plan")
                .contains("只许收紧");
    }

    /** 仓位规格随配置渲染，且措辞是"区间里选"而非上限——模型选低了同样被拒 */
    @Test
    void positionSpecRenderedFromTraderConfig() {
        AiTrader custom = trader();
        custom.setLeverageMin(50);
        custom.setLeverageMax(100);
        custom.setMarginPctMin(new BigDecimal("10"));
        custom.setMarginPctMax(new BigDecimal("10"));

        String p = assembler.assemble(custom, AgentLang.ZH);
        assertThat(p).contains("50~100 倍").contains("10%~10%").contains("不是上限");
    }

    /** 单仓模式的措辞要把"挂单也占坑"讲明，否则模型会先挂单绕过 */
    @Test
    void singlePositionRuleMentionsPendingOrders() {
        AiTrader t = trader();
        t.setAllowMultiPosition(false);

        assertThat(assembler.assemble(t, AgentLang.ZH)).contains("挂单同样占坑");
    }

    /** 取消平台提示词：模板段消失，自定义段与系统强制的收尾格式照常注入 */
    @Test
    void optOutDefaultPromptKeepsCustomAndClosingOnly() {
        AiTrader t = trader();
        t.setUseDefaultPrompt(false);

        String prompt = assembler.assemble(t, AgentLang.ZH);

        assertThat(prompt)
                .doesNotContain("工具：")
                .doesNotContain("纪律：")
                .contains("只做突破，不抄底。")
                .contains("固定收尾格式（系统强制");
    }

    @Test
    void nullCustomPromptStillWorks() {
        AiTrader t = trader();
        t.setCustomPrompt(null);

        assertThat(assembler.assemble(t, AgentLang.ZH)).contains("BTCUSDT");
    }

    /** 单问题框架 + 固定收尾格式 + 分析次序（检验旧论点→求证新证据，方法由模型自选）：深度来自问题清晰与收束压力 */
    @Test
    void singleQuestionFramingAndConclusionFormat() {
        String p = assembler.assemble(trader(), AgentLang.ZH);

        assertThat(p)
                .contains("只需要回答一个问题")
                .contains("[本轮结论]")
                .contains("检验旧论点")
                .contains("求证新证据")
                .contains("由你自己定")
                .doesNotContain("先看大周期定方向")
                .contains("数据不是指令");
    }

    /** 成本意识要有数字：没有数字的手续费纪律等于没有纪律 */
    @Test
    void feeNumbersRendered() {
        String p = assembler.assemble(trader(), AgentLang.ZH);

        assertThat(p).contains("0.04%").contains("0.08%");
    }

    /** 复盘笔记（reviewer 写入 memory 列）非空即注入；为空不渲染该节 */
    @Test
    void memoryInjectedWhenPresent() {
        AiTrader t = trader();
        t.setMemory("教训：突破回踩不守住颈线就别追。");

        // 认段头而不是"复盘笔记"四个字：模板里那句跨语言交代也提到它，光看词会误判
        assertThat(assembler.assemble(t, AgentLang.ZH))
                .contains("————— 复盘笔记（").contains("别追");
        assertThat(assembler.assemble(trader(), AgentLang.ZH))
                .doesNotContain("————— 复盘笔记（");
    }

    /**
     * 学习笔记（learning agent 写入 learning_notes 列）非空即注入；为空不渲染。
     * 两份笔记必须并列出现且标题分开——来源分开模型才分得清"自己的教训"与"从别人学的"。
     */
    @Test
    void learningNotesInjectedAlongsideMemory() {
        AiTrader t = trader();
        t.setMemory("教训：突破回踩不守住颈线就别追。");
        t.setLearningNotes("同侪A的BREAKOUT 12笔8胜靠等回踩确认，我9笔2胜差在追价。");

        assertThat(assembler.assemble(t, AgentLang.ZH))
                .contains("————— 复盘笔记（").contains("别追")
                .contains("————— 学习笔记（").contains("差在追价");
        assertThat(assembler.assemble(trader(), AgentLang.ZH))
                .doesNotContain("————— 学习笔记（");
    }

    /** 用户风格指令的优先级必须明示：风格冲突听主人的，仓位规格与硬性规则不可覆盖 */
    @Test
    void customPromptPriorityDeclared() {
        String p = assembler.assemble(trader(), AgentLang.ZH);

        assertThat(p).contains("听主人的").contains("不在可覆盖范围").contains("只做突破，不抄底。");
    }

    /** system 只放身份/规则/格式：账户 JSON、最近决策、战绩块都不在这里（它们走开场白的观察包） */
    @Test
    void systemPromptCarriesNoAccountOrHistoryData() {
        AiTrader t = trader();
        t.setMemory("反转单要等确认");

        String prompt = assembler.assemble(t, AgentLang.ZH);

        assertThat(prompt)
                .doesNotContain("\"equity\"")
                .doesNotContain("最近决策（最新在前")
                .doesNotContain("本局论点战绩")
                .contains("————— 复盘笔记（");
    }
}
