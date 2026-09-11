package com.mawai.wiibagent.trader;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.UserLlmBinding;
import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.llm.LlmEndpointService;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import com.mawai.wiibagent.trader.wakeup.TraderLiveHub;
import com.mawai.wiibagent.trader.wakeup.TraderScheduler;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** trader 创建/改配置的校验与端点选择，以及重置开新局的归档/清理。 */
class TraderServiceTest {

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTrader.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderDecision.class);
    }

    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final TraderModelFactory modelFactory = mock(TraderModelFactory.class);
    private final LlmEndpointService endpointService = mock(LlmEndpointService.class);
    private final BinanceProperties binanceProperties = mock(BinanceProperties.class);

    private final SimTradeClient simTradeClient = mock(SimTradeClient.class);
    private final TraderPlanStore planStore = mock(TraderPlanStore.class);
    private final AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);

    private final UserLangResolver langResolver = mock(UserLangResolver.class);
    private final TraderScheduler scheduler = mock(TraderScheduler.class);
    private final TraderLiveHub hub = mock(TraderLiveHub.class);

    private final TraderService service = new TraderService(
            traderMapper, decisionMapper, modelFactory, endpointService,
            simTradeClient, binanceProperties, planStore, scheduler, hub,
            new PromptCatalog(), langResolver, new MessageCatalog());

    /** 端点库里的一条 */
    private static UserLlmEndpoint endpoint(long id, String model) {
        UserLlmEndpoint e = new UserLlmEndpoint();
        e.setId(id);
        e.setUserId(1L);
        e.setModel(model);
        e.setApiProtocol("openai");
        e.setBaseUrl("https://8.8.8.8");
        e.setApiKeyEnc("enc");
        return e;
    }

    /** 只填校验相关字段的创建请求；llmEndpointId=null 跟随默认端点 */
    private static TraderService.UpsertReq req(String customPrompt, Boolean useDefaultPrompt,
                                               Integer levMin, Integer levMax,
                                               Boolean multi, Boolean hedge,
                                               Boolean alertEnabled, java.math.BigDecimal alertMult) {
        return new TraderService.UpsertReq("小虎", "BTCUSDT", "5m", customPrompt, null, useDefaultPrompt,
                levMin, levMax, null, null, multi, hedge, alertEnabled, alertMult, null, null, null);
    }

    /** 币种上限 3：单轮工具预算按币摊，4 个被拦；3 个过这道校验（后面才卡在端点上） */
    @Test
    void rejectsMoreThanThreeSymbols() {
        when(binanceProperties.getSymbols()).thenReturn(List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "DOGEUSDT"));
        when(traderMapper.selectOne(any())).thenReturn(null);
        when(endpointService.defaultOf(1L)).thenReturn(null);

        String four = service.create(1L, new TraderService.UpsertReq("小虎", "BTCUSDT,ETHUSDT,SOLUSDT,DOGEUSDT", "5m",
                null, null, true, null, null, null, null, null, null, null, null, null, null, null));
        String three = service.create(1L, new TraderService.UpsertReq("小虎", "BTCUSDT,ETHUSDT,SOLUSDT", "5m",
                null, null, true, null, null, null, null, null, null, null, null, null, null, null));

        assertThat(four).isEqualTo(new MessageCatalog().get("trader.config.tooManySymbols", Map.of("max", 3)));
        assertThat(three).doesNotContain("最多").contains("模型端点");
    }

    /** 退出平台模板后自定义就是唯一指令来源，空着=模型裸奔 */
    @Test
    void optOutDefaultPromptRequiresCustomPrompt() {
        when(binanceProperties.getSymbols()).thenReturn(List.of("BTCUSDT"));
        when(traderMapper.selectOne(any())).thenReturn(null);

        String err = service.create(1L, req(" ", false, null, null, null, null, null, null));

        assertThat(err).contains("自定义提示词");
    }

    /** 警报灵敏度系数只能 ≥1.0：系数<1 等于把每币基准阈值（平台下限）调低 */
    @Test
    void alertMultBelowOneRejected() {
        when(binanceProperties.getSymbols()).thenReturn(List.of("BTCUSDT"));
        when(traderMapper.selectOne(any())).thenReturn(null);

        String err = service.create(1L, req(null, true, null, null, null, null, true, new java.math.BigDecimal("0.5")));

        assertThat(err).contains("不能低于 1.0");
    }

    /** 杠杆上界卡在 125：再往上 sim 的分档表也接不住 */
    @Test
    void leverageBeyondHardMaxRejected() {
        when(binanceProperties.getSymbols()).thenReturn(List.of("BTCUSDT"));
        when(traderMapper.selectOne(any())).thenReturn(null);

        String err = service.create(1L, req(null, true, 50, 200, null, null, null, null));

        assertThat(err).contains("杠杆区间");
    }

    /** 区间下界大于上界＝空集，模型永远开不出仓 */
    @Test
    void invertedLeverageRangeRejected() {
        when(binanceProperties.getSymbols()).thenReturn(List.of("BTCUSDT"));
        when(traderMapper.selectOne(any())).thenReturn(null);

        String err = service.create(1L, req(null, true, 100, 50, null, null, null, null));

        assertThat(err).contains("下界不能大于上界");
    }

    /** 单仓 + 双开是自相矛盾的组合（双开本身要两个仓位），入口就拦掉 */
    @Test
    void hedgeWithSinglePositionRejected() {
        when(binanceProperties.getSymbols()).thenReturn(List.of("BTCUSDT"));
        when(traderMapper.selectOne(any())).thenReturn(null);

        String err = service.create(1L, req(null, true, null, null, false, true, null, null));

        assertThat(err).contains("多空双开");
    }

    /** 端点库空着不能创建：模型是从库里选的，没得选就得先去配 */
    @Test
    void createWithoutAnyEndpointRejected() {
        when(binanceProperties.getSymbols()).thenReturn(List.of("BTCUSDT"));
        when(traderMapper.selectOne(any())).thenReturn(null);
        when(endpointService.defaultOf(1L)).thenReturn(null);

        String err = service.create(1L, req(null, true, null, null, null, null, null, null));

        assertThat(err).contains("模型端点");
        verify(modelFactory, never()).testConnection(any());
        verify(traderMapper, never()).insert(any(AiTrader.class));
    }

    /** 创建：显式选的端点要连通性测试；通过后入库并把 TRADER 用途绑到它 */
    @Test
    void createTestsChosenEndpointAndBinds() {
        when(binanceProperties.getSymbols()).thenReturn(List.of("BTCUSDT"));
        when(traderMapper.selectOne(any())).thenReturn(null);
        UserLlmEndpoint chosen = endpoint(5, "deepseek-chat");
        when(endpointService.get(1L, 5L)).thenReturn(chosen);
        when(modelFactory.testConnection(chosen)).thenReturn(null);
        when(simTradeClient.ensureAccount(any(), any())).thenReturn(99L);

        String err = service.create(1L, new TraderService.UpsertReq("小虎", "BTCUSDT", "5m", null, 5L, true,
                null, null, null, null, null, null, null, null, null, null, null));

        assertThat(err).isNull();
        verify(modelFactory).testConnection(chosen);
        verify(traderMapper).insert(any(AiTrader.class));
        verify(endpointService).bind(1L, UserLlmBinding.TRADER, 5L);
    }

    /** 时段格式错直接回消息 */
    @Test
    void wakeWindowBadFormatRejected() {
        when(binanceProperties.getSymbols()).thenReturn(List.of("BTCUSDT"));
        when(traderMapper.selectOne(any())).thenReturn(null);

        String err = service.create(1L, new TraderService.UpsertReq("小虎", "BTCUSDT", "5m", null, null, true,
                null, null, null, null, null, null, null, null, null, null, "21:03-08:30"));

        assertThat(err).contains("0/5");
        verify(traderMapper, never()).insert(any(AiTrader.class));
    }

    /** 4h 档 21:00-23:00 一根都不收盘 = 永眠，拦在入口 */
    @Test
    void wakeWindowWithoutAnyBoundaryRejected() {
        when(binanceProperties.getSymbols()).thenReturn(List.of("BTCUSDT"));
        when(traderMapper.selectOne(any())).thenReturn(null);

        String err = service.create(1L, new TraderService.UpsertReq("小虎", "BTCUSDT", "4h", null, null, true,
                null, null, null, null, null, null, null, null, null, null, "21:00-23:00"));

        assertThat(err).contains("永远不会醒");
        verify(traderMapper, never()).insert(any(AiTrader.class));
    }

    /** 合法时段落库为归一化文本 */
    @Test
    void wakeWindowPersistedNormalized() {
        when(binanceProperties.getSymbols()).thenReturn(List.of("BTCUSDT"));
        when(traderMapper.selectOne(any())).thenReturn(null);
        UserLlmEndpoint chosen = endpoint(5, "deepseek-chat");
        when(endpointService.get(1L, 5L)).thenReturn(chosen);
        when(modelFactory.testConnection(chosen)).thenReturn(null);
        when(simTradeClient.ensureAccount(any(), any())).thenReturn(99L);

        String err = service.create(1L, new TraderService.UpsertReq("小虎", "BTCUSDT", "5m", null, 5L, true,
                null, null, null, null, null, null, null, null, null, null, " 21:00-08:30 "));

        assertThat(err).isNull();
        ArgumentCaptor<AiTrader> captor = ArgumentCaptor.forClass(AiTrader.class);
        verify(traderMapper).insert(captor.capture());
        assertThat(captor.getValue().getWakeWindow()).isEqualTo("21:00-08:30");
    }

    /** 连通性测试不过：不入库、不绑定 */
    @Test
    void createRejectedWhenEndpointUnreachable() {
        when(binanceProperties.getSymbols()).thenReturn(List.of("BTCUSDT"));
        when(traderMapper.selectOne(any())).thenReturn(null);
        UserLlmEndpoint chosen = endpoint(5, "deepseek-chat");
        when(endpointService.defaultOf(1L)).thenReturn(chosen);
        when(modelFactory.testConnection(chosen)).thenReturn("401");

        String err = service.create(1L, req(null, true, null, null, null, null, null, null));

        assertThat(err).contains("连通性测试失败");
        verify(traderMapper, never()).insert(any(AiTrader.class));
        verify(endpointService, never()).bind(any(Long.class), any(), any());
    }

    /**
     * 重置开新局：本局存活计划归档（不删——论点/失效条件/修订史是公开凭证与复盘原料）。
     * 顺带把未处理的请求作废：换了新账户，旧 positionId 早已不存在，留着永远处理不掉。
     */
    @Test
    void resetArchivesCurrentRoundPlans() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setRoundNo(3);
        when(traderMapper.selectOne(any())).thenReturn(t);
        when(simTradeClient.ensureAccount(any(), any())).thenReturn(99L);

        assertThat(service.reset(1L, true)).isNull();

        verify(planStore).archiveRound(eq(7L), eq(3), org.mockito.ArgumentMatchers.anyLong());
        // 窗口内（R4 ≤ 10 局）不触发过期清理
        verify(planStore, never()).purgeRounds(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt());
        verify(simTradeClient, never()).deleteAccount(any());
    }

    /** 保留窗口 10 局：开 R11 时 R1 整局清除——两表 roundNo≤1 删 + sim 子账户 ai_trader_1_r1 销户 */
    @Test
    void resetBeyondWindowPurgesOldestRound() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setRoundNo(10);
        when(traderMapper.selectOne(any())).thenReturn(t);
        when(simTradeClient.ensureAccount(any(), any())).thenReturn(99L);

        assertThat(service.reset(1L, true)).isNull();

        verify(decisionMapper).delete(any());
        verify(planStore).purgeRounds(7L, 1);
        verify(simTradeClient).deleteAccount("ai_trader_1_r1");
    }

    /** sim 销户失败不阻断开新局：新局账户已就绪，孤儿账户无业务引用留 warn 即可 */
    @Test
    void simDeleteFailureDoesNotBlockReset() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setRoundNo(10);
        when(traderMapper.selectOne(any())).thenReturn(t);
        when(simTradeClient.ensureAccount(any(), any())).thenReturn(99L);
        org.mockito.Mockito.doThrow(new IllegalStateException("sim down"))
                .when(simTradeClient).deleteAccount(any());

        assertThat(service.reset(1L, true)).isNull();

        verify(planStore).purgeRounds(7L, 1);   // quant 三表照删
    }

    /** 删 trader 的样板：R3、名字 alpha */
    private AiTrader deletable() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setName("alpha");
        t.setRoundNo(3);
        when(traderMapper.selectOne(any())).thenReturn(t);
        return t;
    }

    /**
     * 删 trader：两表全轮次清、每一局的 sim 子账户逐个销、TRADER 绑定解开，
     * 三处进程内记账（模型缓存/调度器/现场 hub）跟着摘干净。
     */
    @Test
    void deleteWipesEveryRoundAndForgetsRuntimeState() {
        deletable();

        assertThat(service.delete(1L, "alpha")).isNull();

        verify(decisionMapper).delete(any());
        verify(planStore).purgeAll(7L);
        verify(traderMapper).deleteById(7L);
        // 端点本身不删，只解开 TRADER 用途的绑定
        verify(endpointService).bind(1L, UserLlmBinding.TRADER, null);
        // 每局一个子账户，R1~R3 一个不落
        verify(simTradeClient).deleteAccount("ai_trader_1_r1");
        verify(simTradeClient).deleteAccount("ai_trader_1_r2");
        verify(simTradeClient).deleteAccount("ai_trader_1_r3");
        verify(modelFactory).evict(7L);
        verify(scheduler).forget(7L);
        verify(hub).forget(7L);
    }

    /** 名字对不上一个字都不动——这是删除唯一的人工闸 */
    @Test
    void deleteRejectsNameMismatch() {
        deletable();

        assertThat(service.delete(1L, "alpha2")).contains("名字对不上");

        verify(traderMapper, never()).deleteById(org.mockito.ArgumentMatchers.anyLong());
        verify(planStore, never()).purgeAll(org.mockito.ArgumentMatchers.anyLong());
        verify(simTradeClient, never()).deleteAccount(any());
    }

    /**
     * 在途唤醒时拒删：那一轮跑完要写 decision 行、要调 sim 交易工具，
     * 中途删了就是留一行永远查不到的孤儿加一串报错。
     */
    @Test
    void deleteRefusedWhileWaking() {
        deletable();
        when(scheduler.isBusy(7L)).thenReturn(true);

        assertThat(service.delete(1L, "alpha")).contains("正在唤醒中");

        verify(traderMapper, never()).deleteById(org.mockito.ArgumentMatchers.anyLong());
        verify(decisionMapper, never()).delete(any());
        verify(simTradeClient, never()).deleteAccount(any());
    }

    /** 某局 sim 销户失败不阻断：本地已经删干净了，剩下的孤儿账户无业务引用 */
    @Test
    void deleteSurvivesSimFailure() {
        deletable();
        org.mockito.Mockito.doThrow(new IllegalStateException("sim down"))
                .when(simTradeClient).deleteAccount("ai_trader_1_r2");

        assertThat(service.delete(1L, "alpha")).isNull();

        // 中间那局炸了，后面那局照删
        verify(simTradeClient).deleteAccount("ai_trader_1_r3");
        verify(traderMapper).deleteById(7L);
        verify(hub).forget(7L);
    }

    /**
     * 改配置必须列级更新且不碰运行态列：整行 updateById 会把唤醒回路并发写的
     * status/consecutive_failures 盖回读取时的旧值（连通性测试要出网数秒，窗口不小）——
     * 与 runner 侧"状态回写列级更新"是同一条铁律的两半。
     * 端点没换（当前用的就是默认那条）→ 不触发连通性测试。
     */
    @Test
    void updateConfigWritesConfigColumnsOnly() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setStatus(AiTrader.STATUS_RUNNING);
        when(traderMapper.selectOne(any())).thenReturn(t);
        when(binanceProperties.getSymbols()).thenReturn(List.of("BTCUSDT"));
        UserLlmEndpoint current = endpoint(5, "deepseek-chat");
        when(endpointService.defaultOf(1L)).thenReturn(current);
        when(modelFactory.endpointFor(t)).thenReturn(current);

        String err = service.updateConfig(1L, req("稳一点", true, null, null, null, null, null, null));

        assertThat(err).isNull();
        verify(modelFactory, never()).testConnection(any());
        verify(traderMapper, never()).updateById(any(AiTrader.class));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<AiTrader>> cap = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(traderMapper).update(isNull(), cap.capture());
        String sqlSet = cap.getValue().getSqlSet();
        assertThat(sqlSet).contains("custom_prompt");
        // 改回全天=写 null 只靠这条列级 set（@TableField ALWAYS 对 LambdaUpdateWrapper 不生效）：wake_window 必须在 set 列表里
        assertThat(sqlSet).contains("wake_window");
        assertThat(sqlSet).doesNotContain("status").doesNotContain("consecutive_failures").doesNotContain("api_key");
        verify(endpointService).bind(1L, UserLlmBinding.TRADER, null);   // 跟随默认 = 解绑
    }

    /** 换到另一条端点：先测连通，通过后绑定并逐出模型缓存 */
    @Test
    void updateConfigWithNewEndpointTestsAndEvicts() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        when(traderMapper.selectOne(any())).thenReturn(t);
        when(binanceProperties.getSymbols()).thenReturn(List.of("BTCUSDT"));
        when(modelFactory.endpointFor(t)).thenReturn(endpoint(5, "deepseek-chat"));
        UserLlmEndpoint next = endpoint(6, "deepseek-reasoner");
        when(endpointService.get(1L, 6L)).thenReturn(next);
        when(modelFactory.testConnection(next)).thenReturn(null);

        String err = service.updateConfig(1L, new TraderService.UpsertReq("小虎", "BTCUSDT", "5m", null, 6L, true,
                null, null, null, null, null, null, null, null, null, null, null));

        assertThat(err).isNull();
        verify(modelFactory).testConnection(next);
        verify(endpointService).bind(1L, UserLlmBinding.TRADER, 6L);
        verify(modelFactory).evict(7L);
    }

    /** 不带入笔记的重置：memory/learning_notes 只清生效版本（历届存档在 REVIEW/LEARN 决策行里，不动） */
    @Test
    void resetWithoutCarryClearsNotes() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setRoundNo(3);
        when(traderMapper.selectOne(any())).thenReturn(t);
        when(simTradeClient.ensureAccount(any(), any())).thenReturn(99L);

        assertThat(service.reset(1L, false)).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<AiTrader>> cap = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(traderMapper).update(isNull(), cap.capture());
        assertThat(cap.getValue().getSqlSet()).contains("memory").contains("learning_notes");
    }

    /** 默认带入（carryNotes=true）：两份笔记不进 set 列表，跨局认知积累照旧 */
    @Test
    void resetWithCarryKeepsNotes() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setRoundNo(3);
        when(traderMapper.selectOne(any())).thenReturn(t);
        when(simTradeClient.ensureAccount(any(), any())).thenReturn(99L);

        assertThat(service.reset(1L, true)).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<AiTrader>> cap = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(traderMapper).update(isNull(), cap.capture());
        assertThat(cap.getValue().getSqlSet()).doesNotContain("memory").doesNotContain("learning_notes");
    }

    /** stale 标记：本人的 CLOSED 计划可标可取消（教材层忽略，钱账与公开记录不动） */
    @Test
    void setPlanStaleMarksClosedPlan() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        when(traderMapper.selectOne(any())).thenReturn(t);
        com.mawai.wiibcommon.entity.AiTraderPlan p = new com.mawai.wiibcommon.entity.AiTraderPlan();
        p.setId(9L);
        p.setTraderId(7L);
        p.setStatus(com.mawai.wiibcommon.entity.AiTraderPlan.STATUS_CLOSED);
        when(planStore.byId(9L)).thenReturn(p);

        assertThat(service.setPlanStale(1L, 9L, true)).isNull();

        verify(planStore).setStale(9L, true);
    }

    /** 别人的计划标不了：stale 是主人对自己教材的治理权 */
    @Test
    void setPlanStaleRejectsOthersPlan() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        when(traderMapper.selectOne(any())).thenReturn(t);
        com.mawai.wiibcommon.entity.AiTraderPlan p = new com.mawai.wiibcommon.entity.AiTraderPlan();
        p.setId(9L);
        p.setTraderId(8L);
        p.setStatus(com.mawai.wiibcommon.entity.AiTraderPlan.STATUS_CLOSED);
        when(planStore.byId(9L)).thenReturn(p);

        assertThat(service.setPlanStale(1L, 9L, true)).isNotNull();

        verify(planStore, never()).setStale(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    /** LIVE 是在场纪律不许藏，只有 CLOSED 可标 */
    @Test
    void setPlanStaleRejectsLivePlan() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        when(traderMapper.selectOne(any())).thenReturn(t);
        com.mawai.wiibcommon.entity.AiTraderPlan p = new com.mawai.wiibcommon.entity.AiTraderPlan();
        p.setId(9L);
        p.setTraderId(7L);
        p.setStatus(com.mawai.wiibcommon.entity.AiTraderPlan.STATUS_LIVE);
        when(planStore.byId(9L)).thenReturn(p);

        assertThat(service.setPlanStale(1L, 9L, true)).isNotNull();

        verify(planStore, never()).setStale(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    /**
     * 暂停原因落库即上屏（trader 面板 + 竞技场），跟 trader 主人的语言写入——
     * 与自动暂停那三种（keyInvalid/连败/爆仓）同一口径，英文用户不该在面板上看见一行中文
     */
    @Test
    void pauseWritesReasonInOwnerLanguage() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        when(traderMapper.selectOne(any())).thenReturn(t);
        when(langResolver.of(1L)).thenReturn(AgentLang.EN);

        assertThat(service.pause(1L)).isNull();

        ArgumentCaptor<LambdaUpdateWrapper<AiTrader>> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(traderMapper).update(isNull(), captor.capture());
        assertThat(captor.getValue().getParamNameValuePairs().values())
                .contains(new PromptCatalog().get(AgentLang.EN, "trader.pause.manual"));
    }

    // ---- token 合计（竞技场详情页那格仪表） ----

    private AiTrader traderOnRound(int roundNo) {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setRoundNo(roundNo);
        return t;
    }

    /** PG 的 SUM(bigint) 回 numeric，JDBC 给的是 BigDecimal，得能收下 */
    @Test
    void sumTokensTakesBigDecimalFromSum() {
        when(traderMapper.selectById(7L)).thenReturn(traderOnRound(3));
        when(decisionMapper.selectMaps(any())).thenReturn(List.of(Map.of("total", new BigDecimal("123456"))));

        assertThat(service.sumTokens(7L, null, null, null)).isEqualTo(123456L);
    }

    /** 整段都没 usage 时 SUM 本身就是 null，原样返回——补成 0 会被读成"这段没花 token" */
    @Test
    void sumTokensNullWhenNoUsageReported() {
        when(traderMapper.selectById(7L)).thenReturn(traderOnRound(1));
        Map<String, Object> row = new HashMap<>();
        row.put("total", null);
        when(decisionMapper.selectMaps(any())).thenReturn(List.of(row));

        assertThat(service.sumTokens(7L, null, null, null)).isNull();
    }

    /** 真实形态：整行只有 total 一列且为 NULL，MyBatis 把这行映射成 null 元素，取首行得先判空 */
    @Test
    void sumTokensNullWhenRowMappedToNull() {
        when(traderMapper.selectById(7L)).thenReturn(traderOnRound(1));
        when(decisionMapper.selectMaps(any())).thenReturn(Arrays.asList((Map<String, Object>) null));

        assertThat(service.sumTokens(7L, null, null, null)).isNull();
    }

    @Test
    void sumTokensNullWhenNoRows() {
        when(traderMapper.selectById(7L)).thenReturn(traderOnRound(1));
        when(decisionMapper.selectMaps(any())).thenReturn(List.of());

        assertThat(service.sumTokens(7L, null, null, null)).isNull();
    }

    /** trader 不在就别去查库 */
    @Test
    void sumTokensNullWhenTraderMissing() {
        when(traderMapper.selectById(9L)).thenReturn(null);

        assertThat(service.sumTokens(9L, null, null, null)).isNull();
        verify(decisionMapper, never()).selectMaps(any());
    }

    /** round 不传就落到当前局，跟 decisions() 同一个规矩；from/to 给了就进条件 */
    @Test
    void sumTokensFallsBackToCurrentRoundAndKeepsBounds() {
        when(traderMapper.selectById(7L)).thenReturn(traderOnRound(5));
        when(decisionMapper.selectMaps(any())).thenReturn(List.of());

        service.sumTokens(7L, null, 1000L, 2000L);

        ArgumentCaptor<QueryWrapper<AiTraderDecision>> cap = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(decisionMapper).selectMaps(cap.capture());
        QueryWrapper<AiTraderDecision> q = cap.getValue();
        // 条件值要 getSqlSegment() 拼过之后才会落进 paramNameValuePairs，顺序反了读到的是空表
        assertThat(q.getSqlSegment()).contains("round_no", "wake_time >=", "wake_time <");
        assertThat(q.getParamNameValuePairs().values()).contains(5, 1000L, 2000L);
    }
}
