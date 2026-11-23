package com.mawai.wiibagent.trader;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.learning.ExperienceLedger;
import com.mawai.wiibagent.learning.ReviewScheduler;
import com.mawai.wiibagent.llm.LlmEndpointService;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import com.mawai.wiibagent.trader.wakeup.TraderLiveHub;
import com.mawai.wiibagent.trader.wakeup.TraderScheduler;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** trader 创建/改配置的校验与端点选择、名额与重名、归属，以及重置开新局的归档/清理。 */
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
    private final TraderWatchStore watchStore = mock(TraderWatchStore.class);
    private final AiTraderDecisionMapper decisionMapper = mock(AiTraderDecisionMapper.class);

    private final UserLangResolver langResolver = mock(UserLangResolver.class);
    private final TraderScheduler scheduler = mock(TraderScheduler.class);
    private final ReviewScheduler reviewScheduler = mock(ReviewScheduler.class);
    private final TraderLiveHub hub = mock(TraderLiveHub.class);
    private final ExperienceLedger ledger = mock(ExperienceLedger.class);
    /** 创建用的事务：看它收到 commit 还是 rollback */
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    private final TraderService service = new TraderService(
            traderMapper, decisionMapper, modelFactory, endpointService,
            simTradeClient, binanceProperties, planStore, watchStore, scheduler, reviewScheduler, hub,
            new PromptCatalog(), langResolver, new MessageCatalog(), ledger, new TransactionTemplate(txManager));

    {
        // 重置、删除要占 trader 的唤醒位和复盘位，默认都占得到
        when(scheduler.tryOccupy(anyLong())).thenReturn(true);
        when(reviewScheduler.tryOccupy(anyLong())).thenReturn(true);
        // 插行时库发 id
        when(traderMapper.insert(any(AiTrader.class))).thenAnswer(inv -> {
            inv.<AiTrader>getArgument(0).setId(7L);
            return 1;
        });
    }

    /** 名下的一个 trader：主人是 1 号 */
    private static AiTrader named(long id, String name) {
        AiTrader t = new AiTrader();
        t.setId(id);
        t.setUserId(1L);
        t.setName(name);
        t.setRoundNo(1);
        return t;
    }

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
    private static TraderService.UpsertReq req(String customPrompt,
                                               Integer levMin, Integer levMax,
                                               Boolean multi, Boolean hedge,
                                               Boolean alertEnabled, java.math.BigDecimal alertMult) {
        return new TraderService.UpsertReq("小虎", "BTCUSDT", "1h", customPrompt, null,
                levMin, levMax, null, multi, hedge, alertEnabled, alertMult, null, null, null, null, null);
    }

    /** 币种上限 3：单轮工具预算按币摊，4 个被拦；3 个过这道校验（后面才卡在端点上） */
    @Test
    void rejectsMoreThanThreeSymbols() {
        when(binanceProperties.getAllCryptoSymbols()).thenReturn(List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "DOGEUSDT"));
        when(endpointService.defaultOf(1L)).thenReturn(null);

        String four = service.create(1L, new TraderService.UpsertReq("小虎", "BTCUSDT,ETHUSDT,SOLUSDT,DOGEUSDT", "1h",
                null, null, null, null, null, null, null, null, null, null, null, null, null, null)).error();
        String three = service.create(1L, new TraderService.UpsertReq("小虎", "BTCUSDT,ETHUSDT,SOLUSDT", "1h",
                null, null, null, null, null, null, null, null, null, null, null, null, null, null)).error();

        assertThat(four).isEqualTo(new MessageCatalog().get("trader.config.tooManySymbols", Map.of("max", 3)));
        assertThat(three).doesNotContain("最多").contains("模型端点");
    }

    /** 警报灵敏度系数只能 ≥1.0：系数<1 等于把每币基准阈值（平台下限）调低 */
    @Test
    void alertMultBelowOneRejected() {
        when(binanceProperties.getAllCryptoSymbols()).thenReturn(List.of("BTCUSDT"));

        String err = service.create(1L, req(null, null, null, null, null, true, new java.math.BigDecimal("0.5"))).error();

        assertThat(err).contains("不能低于 1.0");
    }

    /** 只填权益回落警报比例的请求 */
    private static TraderService.UpsertReq dropReq(String pct) {
        return new TraderService.UpsertReq("小虎", "BTCUSDT", "1h", null, null, null, null, null, null, null,
                null, null, pct == null ? null : new java.math.BigDecimal(pct), null, null, null, null);
    }

    /** 权益回落警报的比例只收 1~90%，不设（null）是不启用 */
    @Test
    void equityDropPctOutsideRangeRejected() {
        when(binanceProperties.getAllCryptoSymbols()).thenReturn(List.of("BTCUSDT"));

        assertThat(service.create(1L, dropReq("0.5")).error()).contains("权益回落警报");
        assertThat(service.create(1L, dropReq("95")).error()).contains("权益回落警报");
        // 范围内和不设的都过了这道校验，卡在后面的端点上
        assertThat(service.create(1L, dropReq("15")).error()).isEqualTo(new MessageCatalog().get("trader.noEndpoint"));
        assertThat(service.create(1L, dropReq(null)).error()).isEqualTo(new MessageCatalog().get("trader.noEndpoint"));
    }

    /** 关掉权益回落警报，参照点一起清；改比例不动参照点 */
    @Test
    void turningEquityDropAlertOffClearsItsReferencePoint() {
        AiTrader t = named(7L, "小虎");
        when(traderMapper.selectById(7L)).thenReturn(t);
        when(traderMapper.selectList(any())).thenReturn(List.of(t));
        when(binanceProperties.getAllCryptoSymbols()).thenReturn(List.of("BTCUSDT"));
        UserLlmEndpoint current = endpoint(5, "deepseek-chat");
        when(endpointService.defaultOf(1L)).thenReturn(current);
        when(modelFactory.endpointFor(t)).thenReturn(current);

        assertThat(service.updateConfig(1L, 7L, dropReq("15"))).isNull();
        assertThat(service.updateConfig(1L, 7L, dropReq(null))).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<AiTrader>> cap = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(traderMapper, times(2)).update(isNull(), cap.capture());
        assertThat(cap.getAllValues().get(0).getSqlSet()).contains("equity_drop_pct").doesNotContain("equity_peak");
        assertThat(cap.getAllValues().get(1).getSqlSet()).contains("equity_drop_pct").contains("equity_peak")
                .contains("equity_peak_at");
    }

    /** 杠杆上界卡在 125：再往上 sim 的分档表也接不住 */
    @Test
    void leverageBeyondHardMaxRejected() {
        when(binanceProperties.getAllCryptoSymbols()).thenReturn(List.of("BTCUSDT"));

        String err = service.create(1L, req(null, 50, 200, null, null, null, null)).error();

        assertThat(err).contains("杠杆区间");
    }

    /** 区间下界大于上界＝空集，模型永远开不出仓 */
    @Test
    void invertedLeverageRangeRejected() {
        when(binanceProperties.getAllCryptoSymbols()).thenReturn(List.of("BTCUSDT"));

        String err = service.create(1L, req(null, 100, 50, null, null, null, null)).error();

        assertThat(err).contains("下界不能大于上界");
    }

    /** 单仓 + 双开是自相矛盾的组合（双开本身要两个仓位），入口就拦掉 */
    @Test
    void hedgeWithSinglePositionRejected() {
        when(binanceProperties.getAllCryptoSymbols()).thenReturn(List.of("BTCUSDT"));

        String err = service.create(1L, req(null, null, null, false, true, null, null)).error();

        assertThat(err).contains("多空双开");
    }

    /** 端点库空着不能创建：模型是从库里选的，没得选就得先去配 */
    @Test
    void createWithoutAnyEndpointRejected() {
        when(binanceProperties.getAllCryptoSymbols()).thenReturn(List.of("BTCUSDT"));
        when(endpointService.defaultOf(1L)).thenReturn(null);

        String err = service.create(1L, req(null, null, null, null, null, null, null)).error();

        assertThat(err).contains("模型端点");
        verify(modelFactory, never()).testConnection(any());
        verify(traderMapper, never()).insert(any(AiTrader.class));
    }

    /**
     * 创建：显式选的端点要连通性测试，端点 id 记在行上；
     * 一个事务里先插行拿 id，再按 traderId 开 sim 子账户，最后回写 sim_user_id 并提交；回给调用方新 trader 的 id
     */
    @Test
    void createInsertsRowThenOpensAccountByTraderId() {
        when(binanceProperties.getAllCryptoSymbols()).thenReturn(List.of("BTCUSDT"));
        UserLlmEndpoint chosen = endpoint(5, "deepseek-chat");
        when(endpointService.get(1L, 5L)).thenReturn(chosen);
        when(modelFactory.testConnection(chosen)).thenReturn(null);
        when(simTradeClient.ensureAccount(any(), any())).thenReturn(99L);

        TraderService.CreateResult r = service.create(1L, new TraderService.UpsertReq("小虎", "BTCUSDT", "1h", null, 5L,
                null, null, null, null, null, null, null, null, null, null, null, null));

        assertThat(r.error()).isNull();
        assertThat(r.id()).isEqualTo(7L);
        verify(modelFactory).testConnection(chosen);
        ArgumentCaptor<AiTrader> inserted = ArgumentCaptor.forClass(AiTrader.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<AiTrader>> simUpdate = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        InOrder order = inOrder(traderMapper, simTradeClient, txManager);
        order.verify(traderMapper).insert(inserted.capture());
        order.verify(simTradeClient).ensureAccount("ai_trader_t7_r1", TraderService.INITIAL_BALANCE);
        order.verify(traderMapper).update(isNull(), simUpdate.capture());
        order.verify(txManager).commit(any());
        assertThat(inserted.getValue().getLlmEndpointId()).isEqualTo(5L);
        assertThat(simUpdate.getValue().getSqlSet()).contains("sim_user_id=");
        assertThat(simUpdate.getValue().getParamNameValuePairs()).containsValue(99L);
    }

    /** 开 sim 子账户失败：异常照抛、事务回滚，插的那行不留，sim_user_id 也不回写 */
    @Test
    void createRollsBackWhenOpeningAccountFails() {
        when(binanceProperties.getAllCryptoSymbols()).thenReturn(List.of("BTCUSDT"));
        when(endpointService.defaultOf(1L)).thenReturn(endpoint(5, "deepseek-chat"));
        when(simTradeClient.ensureAccount(any(), any())).thenThrow(new IllegalStateException("sim down"));

        assertThatThrownBy(() -> service.create(1L, req(null, null, null, null, null, null, null)))
                .hasMessageContaining("sim down");

        verify(traderMapper).insert(any(AiTrader.class));
        verify(txManager).rollback(any());
        verify(txManager, never()).commit(any());
        verify(traderMapper, never()).update(any(), any());
    }

    /** 名下满 3 个：直接拒，不测连通、不入库 */
    @Test
    void createRejectedWhenLimitReached() {
        when(traderMapper.selectList(any())).thenReturn(List.of(named(1L, "a"), named(2L, "b"), named(3L, "c")));

        String err = service.create(1L, req(null, null, null, null, null, null, null)).error();

        assertThat(err).isEqualTo(new MessageCatalog().get("trader.limitReached", Map.of("max", 3)));
        verify(modelFactory, never()).testConnection(any());
        verify(traderMapper, never()).insert(any(AiTrader.class));
    }

    /** 名下重名：去首尾空格、不分大小写比 */
    @Test
    void createRejectedWhenNameTaken() {
        when(binanceProperties.getAllCryptoSymbols()).thenReturn(List.of("BTCUSDT"));
        when(traderMapper.selectList(any())).thenReturn(List.of(named(8L, "Alpha")));

        String err = service.create(1L, new TraderService.UpsertReq(" alpha ", "BTCUSDT", "1h", null, null,
                null, null, null, null, null, null, null, null, null, null, null, null)).error();

        assertThat(err).isEqualTo(new MessageCatalog().get("trader.config.nameTaken"));
        verify(traderMapper, never()).insert(any(AiTrader.class));
    }

    /** 时段格式错直接回消息 */
    @Test
    void wakeWindowBadFormatRejected() {
        when(binanceProperties.getAllCryptoSymbols()).thenReturn(List.of("BTCUSDT"));

        String err = service.create(1L, new TraderService.UpsertReq("小虎", "BTCUSDT", "1h", null, null,
                null, null, null, null, null, null, null, null, null, null, null, "21:03-08:30")).error();

        assertThat(err).contains("0/5");
        verify(traderMapper, never()).insert(any(AiTrader.class));
    }

    /** 4h 档 21:00-23:00 一根都不收盘 = 永眠，拦在入口 */
    @Test
    void wakeWindowWithoutAnyBoundaryRejected() {
        when(binanceProperties.getAllCryptoSymbols()).thenReturn(List.of("BTCUSDT"));

        String err = service.create(1L, new TraderService.UpsertReq("小虎", "BTCUSDT", "4h", null, null,
                null, null, null, null, null, null, null, null, null, null, null, "21:00-23:00")).error();

        assertThat(err).contains("永远不会醒");
        verify(traderMapper, never()).insert(any(AiTrader.class));
    }

    /** 合法时段落库为归一化文本 */
    @Test
    void wakeWindowPersistedNormalized() {
        when(binanceProperties.getAllCryptoSymbols()).thenReturn(List.of("BTCUSDT"));
        UserLlmEndpoint chosen = endpoint(5, "deepseek-chat");
        when(endpointService.get(1L, 5L)).thenReturn(chosen);
        when(modelFactory.testConnection(chosen)).thenReturn(null);
        when(simTradeClient.ensureAccount(any(), any())).thenReturn(99L);

        String err = service.create(1L, new TraderService.UpsertReq("小虎", "BTCUSDT", "1h", null, 5L,
                null, null, null, null, null, null, null, null, null, null, null, " 21:00-08:30 ")).error();

        assertThat(err).isNull();
        ArgumentCaptor<AiTrader> captor = ArgumentCaptor.forClass(AiTrader.class);
        verify(traderMapper).insert(captor.capture());
        assertThat(captor.getValue().getWakeWindow()).isEqualTo("21:00-08:30");
    }

    /** 联网搜索开关：选的端点开了联网搜索才存得成 true，没开的存 false */
    @Test
    void webSearchSwitchNeedsEndpointSearch() {
        when(binanceProperties.getAllCryptoSymbols()).thenReturn(List.of("BTCUSDT"));
        UserLlmEndpoint searching = endpoint(5, "grok-4");
        searching.setApiProtocol("responses");
        searching.setWebSearch(true);
        when(endpointService.get(1L, 5L)).thenReturn(searching);
        when(endpointService.get(1L, 6L)).thenReturn(endpoint(6, "deepseek-chat"));
        when(simTradeClient.ensureAccount(any(), any())).thenReturn(99L);

        service.create(1L, new TraderService.UpsertReq("小虎", "BTCUSDT", "1h", null, 5L,
                null, null, null, null, null, null, null, null, null, null, true, null));
        service.create(1L, new TraderService.UpsertReq("小虎", "BTCUSDT", "1h", null, 6L,
                null, null, null, null, null, null, null, null, null, null, true, null));

        ArgumentCaptor<AiTrader> captor = ArgumentCaptor.forClass(AiTrader.class);
        verify(traderMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues().get(0).getWebSearchEnabled()).isTrue();
        assertThat(captor.getAllValues().get(1).getWebSearchEnabled()).isFalse();
    }

    /** 连通性测试不过：不入库、不开账户 */
    @Test
    void createRejectedWhenEndpointUnreachable() {
        when(binanceProperties.getAllCryptoSymbols()).thenReturn(List.of("BTCUSDT"));
        UserLlmEndpoint chosen = endpoint(5, "deepseek-chat");
        when(endpointService.defaultOf(1L)).thenReturn(chosen);
        when(modelFactory.testConnection(chosen)).thenReturn("401");

        String err = service.create(1L, req(null, null, null, null, null, null, null)).error();

        assertThat(err).contains("连通性测试失败");
        verify(traderMapper, never()).insert(any(AiTrader.class));
        verify(simTradeClient, never()).ensureAccount(any(), any());
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
        when(traderMapper.selectById(7L)).thenReturn(t);
        when(simTradeClient.ensureAccount(any(), any())).thenReturn(99L);

        assertThat(service.reset(1L, 7L, true)).isNull();

        verify(planStore).archiveRound(eq(7L), eq(3), org.mockito.ArgumentMatchers.anyLong());
        verify(watchStore).archiveRound(7L, 3);
        // 窗口内（R4 ≤ 10 局）不触发过期清理
        verify(planStore, never()).purgeRounds(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt());
        verify(simTradeClient, never()).deleteAccount(any());
    }

    /** 有唤醒或复盘在跑：不重置，什么都不动 */
    @Test
    void resetRefusedWhileBusy() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setRoundNo(3);
        when(traderMapper.selectById(7L)).thenReturn(t);
        when(scheduler.tryOccupy(7L)).thenReturn(false);

        assertThat(service.reset(1L, 7L, true)).contains("等这一轮跑完再重置");

        verify(simTradeClient, never()).ensureAccount(any(), any());
        verify(planStore, never()).archiveRound(anyLong(), anyInt(), anyLong());
        verify(scheduler, never()).release(anyLong());
    }

    /** 唤醒位拿到了、复盘位被占着（单笔复盘或统一复盘在跑）：照样不重置，拿到的唤醒位放回去 */
    @Test
    void resetRefusedWhileReviewingAndGivesTheWakeSlotBack() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setRoundNo(3);
        when(traderMapper.selectById(7L)).thenReturn(t);
        when(reviewScheduler.tryOccupy(7L)).thenReturn(false);

        assertThat(service.reset(1L, 7L, true)).contains("等这一轮跑完再重置");

        verify(simTradeClient, never()).ensureAccount(any(), any());
        verify(scheduler).release(7L);
        verify(reviewScheduler, never()).release(anyLong());
    }

    /** 重置先给旧账户收尾：挂单全撤、仓位市价全平，然后才开新账户；位子用完放掉 */
    @Test
    void resetClosesOutOldAccountBeforeOpeningNewRound() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setRoundNo(3);
        t.setSimUserId(88L);
        when(traderMapper.selectById(7L)).thenReturn(t);
        FuturesOrderResponse pending = new FuturesOrderResponse();
        pending.setOrderId(600L);
        when(simTradeClient.getPendingOrders(88L, null)).thenReturn(List.of(pending));
        FuturesPositionDTO position = new FuturesPositionDTO();
        position.setId(5L);
        when(simTradeClient.getAllPositions(88L)).thenReturn(List.of(position));
        when(simTradeClient.ensureAccount(any(), any())).thenReturn(99L);

        assertThat(service.reset(1L, 7L, true)).isNull();

        var calls = inOrder(simTradeClient, scheduler);
        calls.verify(simTradeClient).cancelOrder(88L, 600L);
        ArgumentCaptor<FuturesCloseRequest> close = ArgumentCaptor.forClass(FuturesCloseRequest.class);
        calls.verify(simTradeClient).closePosition(eq(88L), close.capture());
        calls.verify(simTradeClient).ensureAccount("ai_trader_t7_r4", TraderService.INITIAL_BALANCE);
        calls.verify(scheduler).release(7L);
        assertThat(close.getValue().getPositionId()).isEqualTo(5L);
        assertThat(close.getValue().getOrderType()).isEqualTo("MARKET");
        assertThat(close.getValue().getQuantity()).isNull();
    }

    /** 旧账户清不干净：不重置，局号和账户都不动，位子照样放掉 */
    @Test
    void resetAbortsWhenOldAccountCannotBeClosedOut() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setRoundNo(3);
        t.setSimUserId(88L);
        when(traderMapper.selectById(7L)).thenReturn(t);
        FuturesPositionDTO position = new FuturesPositionDTO();
        position.setId(5L);
        when(simTradeClient.getAllPositions(88L)).thenReturn(List.of(position));
        when(simTradeClient.closePosition(eq(88L), any())).thenThrow(new IllegalStateException("仓位在强平中"));

        assertThat(service.reset(1L, 7L, true)).contains("这次没有重置").contains("仓位在强平中");

        verify(simTradeClient, never()).ensureAccount(any(), any());
        verify(traderMapper, never()).update(any(), any());
        verify(scheduler).release(7L);
    }

    /** 保留窗口 10 局：开 R11 时 R1 整局清除——两表 roundNo≤1 删 + sim 子账户 ai_trader_t7_r1 销户 */
    @Test
    void resetBeyondWindowPurgesOldestRound() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setRoundNo(10);
        when(traderMapper.selectById(7L)).thenReturn(t);
        when(simTradeClient.ensureAccount(any(), any())).thenReturn(99L);

        assertThat(service.reset(1L, 7L, true)).isNull();

        verify(decisionMapper).delete(any());
        verify(planStore).purgeRounds(7L, 1);
        verify(watchStore).purgeRounds(7L, 1);
        verify(simTradeClient).deleteAccount("ai_trader_t7_r1");
    }

    /** sim 销户失败不阻断开新局：新局账户已就绪，孤儿账户无业务引用留 warn 即可 */
    @Test
    void simDeleteFailureDoesNotBlockReset() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setRoundNo(10);
        when(traderMapper.selectById(7L)).thenReturn(t);
        when(simTradeClient.ensureAccount(any(), any())).thenReturn(99L);
        org.mockito.Mockito.doThrow(new IllegalStateException("sim down"))
                .when(simTradeClient).deleteAccount(any());

        assertThat(service.reset(1L, 7L, true)).isNull();

        verify(planStore).purgeRounds(7L, 1);   // quant 三表照删
    }

    /** 删 trader 的样板：R3、名字 alpha */
    private AiTrader deletable() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setName("alpha");
        t.setRoundNo(3);
        when(traderMapper.selectById(7L)).thenReturn(t);
        return t;
    }

    /**
     * 删 trader：两表全轮次清、每一局的 sim 子账户逐个销，
     * 三处进程内记账（模型缓存/调度器/现场 hub）跟着摘干净。
     */
    @Test
    void deleteWipesEveryRoundAndForgetsRuntimeState() {
        deletable();

        assertThat(service.delete(1L, 7L, "alpha")).isNull();

        verify(decisionMapper).delete(any());
        verify(planStore).purgeAll(7L);
        verify(traderMapper).deleteById(7L);
        // 每局一个子账户，按 traderId 命名，R1~R3 一个不落
        verify(simTradeClient).deleteAccount("ai_trader_t7_r1");
        verify(simTradeClient).deleteAccount("ai_trader_t7_r2");
        verify(simTradeClient).deleteAccount("ai_trader_t7_r3");
        verify(modelFactory).evict(7L);
        verify(scheduler).forget(7L);
        verify(hub).forget(7L);
    }

    /** 名字对不上一个字都不动——这是删除唯一的人工闸 */
    @Test
    void deleteRejectsNameMismatch() {
        deletable();

        assertThat(service.delete(1L, 7L, "alpha2")).contains("名字对不上");

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
        when(scheduler.tryOccupy(7L)).thenReturn(false);

        assertThat(service.delete(1L, 7L, "alpha")).contains("正在唤醒或复盘中");

        verify(traderMapper, never()).deleteById(org.mockito.ArgumentMatchers.anyLong());
        verify(decisionMapper, never()).delete(any());
        verify(simTradeClient, never()).deleteAccount(any());
    }

    /** 某局 sim 销户失败不阻断：本地已经删干净了，剩下的孤儿账户无业务引用 */
    @Test
    void deleteSurvivesSimFailure() {
        deletable();
        org.mockito.Mockito.doThrow(new IllegalStateException("sim down"))
                .when(simTradeClient).deleteAccount("ai_trader_t7_r2");

        assertThat(service.delete(1L, 7L, "alpha")).isNull();

        // 中间那局炸了，后面那局照删
        verify(simTradeClient).deleteAccount("ai_trader_t7_r3");
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
        AiTrader t = named(7L, "小虎");
        t.setStatus(AiTrader.STATUS_RUNNING);
        when(traderMapper.selectById(7L)).thenReturn(t);
        // 名下列表里有它自己，名字没改不算重名
        when(traderMapper.selectList(any())).thenReturn(List.of(t));
        when(binanceProperties.getAllCryptoSymbols()).thenReturn(List.of("BTCUSDT"));
        UserLlmEndpoint current = endpoint(5, "deepseek-chat");
        when(endpointService.defaultOf(1L)).thenReturn(current);
        when(modelFactory.endpointFor(t)).thenReturn(current);

        String err = service.updateConfig(1L, 7L, req("稳一点", null, null, null, null, null, null));

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
        assertThat(sqlSet).contains("web_search_enabled");
        assertThat(sqlSet).doesNotContain("status").doesNotContain("consecutive_failures").doesNotContain("api_key");
        // 跟随默认：端点列写 null，不把眼下的默认端点 5 钉死在行上
        assertThat(sqlSet).contains("llm_endpoint_id=");
        assertThat(cap.getValue().getParamNameValuePairs()).doesNotContainValue(5L);
    }

    /** 换到另一条端点：先测连通，通过后把端点 id 写进行里并逐出模型缓存 */
    @Test
    void updateConfigWithNewEndpointTestsAndEvicts() {
        AiTrader t = named(7L, "小虎");
        when(traderMapper.selectById(7L)).thenReturn(t);
        when(binanceProperties.getAllCryptoSymbols()).thenReturn(List.of("BTCUSDT"));
        when(modelFactory.endpointFor(t)).thenReturn(endpoint(5, "deepseek-chat"));
        UserLlmEndpoint next = endpoint(6, "deepseek-reasoner");
        when(endpointService.get(1L, 6L)).thenReturn(next);
        when(modelFactory.testConnection(next)).thenReturn(null);

        String err = service.updateConfig(1L, 7L, new TraderService.UpsertReq("小虎", "BTCUSDT", "1h", null, 6L,
                null, null, null, null, null, null, null, null, null, null, null, null));

        assertThat(err).isNull();
        verify(modelFactory).testConnection(next);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<AiTrader>> cap = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(traderMapper).update(isNull(), cap.capture());
        assertThat(cap.getValue().getParamNameValuePairs()).containsValue(6L);
        verify(modelFactory).evict(7L);
    }

    /** 改名撞上名下别的 trader 同样拒（不分大小写） */
    @Test
    void updateConfigRejectsNameOfAnotherTrader() {
        AiTrader self = named(7L, "小虎");
        when(traderMapper.selectById(7L)).thenReturn(self);
        when(traderMapper.selectList(any())).thenReturn(List.of(self, named(8L, "Alpha")));
        when(binanceProperties.getAllCryptoSymbols()).thenReturn(List.of("BTCUSDT"));

        String err = service.updateConfig(1L, 7L, new TraderService.UpsertReq("ALPHA", "BTCUSDT", "1h", null, null,
                null, null, null, null, null, null, null, null, null, null, null, null));

        assertThat(err).isEqualTo(new MessageCatalog().get("trader.config.nameTaken"));
        verify(traderMapper, never()).update(any(), any());
    }

    /** 别人的 traderId 一律当不存在：取不到，也动不了 */
    @Test
    void othersTraderIdTreatedAsMissing() {
        AiTrader others = named(7L, "小虎");
        others.setUserId(2L);
        when(traderMapper.selectById(7L)).thenReturn(others);

        assertThat(service.owned(1L, 7L)).isNull();
        assertThat(service.start(1L, 7L)).isEqualTo(new MessageCatalog().get("trader.notFound"));
        verify(traderMapper, never()).update(any(), any());
    }

    /** 本局收益率：权益相对初始资金，留两位小数；竞技场和对话用的都是这一份 */
    @Test
    void pnlPctAgainstInitialBalance() {
        assertThat(TraderService.pnlPct(new BigDecimal("10500"))).isEqualTo(new BigDecimal("5.00"));
        assertThat(TraderService.pnlPct(new BigDecimal("9876.5"))).isEqualTo(new BigDecimal("-1.24"));
    }

    /** 不带入笔记的重置：memory 只清生效版本（历届存档在 REVIEW 决策行里，不动） */
    @Test
    void resetWithoutCarryClearsNotes() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setRoundNo(3);
        when(traderMapper.selectById(7L)).thenReturn(t);
        when(simTradeClient.ensureAccount(any(), any())).thenReturn(99L);

        assertThat(service.reset(1L, 7L, false)).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<AiTrader>> cap = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(traderMapper).update(isNull(), cap.capture());
        // 权益回落警报的参照点按局记，新局一起清
        assertThat(cap.getValue().getSqlSet()).contains("memory").contains("equity_peak").contains("equity_peak_at");
    }

    /** 带入时本局账本条目连同名下单子复制到新局、memory 按新局条目重渲染（账本空着就不碰 memory，旧自由文本留给首次复盘迁移） */
    @Test
    void resetWithCarryCopiesLedgerAndRerendersMemory() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setRoundNo(3);
        t.setLearnedRestrictionHold(true);
        when(traderMapper.selectById(7L)).thenReturn(t);
        when(simTradeClient.ensureAccount(any(), any())).thenReturn(99L);
        when(langResolver.of(1L)).thenReturn(AgentLang.ZH);
        when(ledger.carryOver(7L, 3, 4)).thenReturn(2);
        when(ledger.render(7L, 4, AgentLang.ZH)).thenReturn("【经验账本】\n#9 规律");

        assertThat(service.reset(1L, 7L, true)).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<AiTrader>> cap = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(traderMapper).update(isNull(), cap.capture());
        assertThat(cap.getValue().getSqlSet()).contains("memory");
        assertThat(cap.getValue().getParamNameValuePairs()).containsValue("【经验账本】\n#9 规律");
    }

    /** 默认带入（carryNotes=true）：账本空着时 memory 不进 set 列表，跨局认知积累照旧 */
    @Test
    void resetWithCarryKeepsNotes() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        t.setRoundNo(3);
        when(traderMapper.selectById(7L)).thenReturn(t);
        when(simTradeClient.ensureAccount(any(), any())).thenReturn(99L);

        assertThat(service.reset(1L, 7L, true)).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<AiTrader>> cap = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(traderMapper).update(isNull(), cap.capture());
        assertThat(cap.getValue().getSqlSet()).doesNotContain("memory");
    }

    /** stale 标记：本人的 CLOSED 计划可标可取消（教材层忽略，钱账与公开记录不动）；经验账本的账跟着变，memory 重渲染 */
    @Test
    void setPlanStaleMarksClosedPlanAndRerendersMemory() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        when(traderMapper.selectById(7L)).thenReturn(t);
        when(langResolver.of(1L)).thenReturn(AgentLang.ZH);
        com.mawai.wiibcommon.entity.AiTraderPlan p = new com.mawai.wiibcommon.entity.AiTraderPlan();
        p.setId(9L);
        p.setTraderId(7L);
        p.setStatus(com.mawai.wiibcommon.entity.AiTraderPlan.STATUS_CLOSED);
        when(planStore.byId(9L)).thenReturn(p);

        assertThat(service.setPlanStale(1L, 7L, 9L, true)).isNull();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(planStore, ledger);
        order.verify(planStore).setStale(9L, true);
        order.verify(ledger).writeMemory(t, AgentLang.ZH);
    }

    /** 别人的计划标不了：stale 是主人对自己教材的治理权 */
    @Test
    void setPlanStaleRejectsOthersPlan() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        when(traderMapper.selectById(7L)).thenReturn(t);
        com.mawai.wiibcommon.entity.AiTraderPlan p = new com.mawai.wiibcommon.entity.AiTraderPlan();
        p.setId(9L);
        p.setTraderId(8L);
        p.setStatus(com.mawai.wiibcommon.entity.AiTraderPlan.STATUS_CLOSED);
        when(planStore.byId(9L)).thenReturn(p);

        assertThat(service.setPlanStale(1L, 7L, 9L, true)).isNotNull();

        verify(planStore, never()).setStale(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean());
        verify(ledger, never()).writeMemory(any(), any());
    }

    /** LIVE 是在场纪律不许藏，只有 CLOSED 可标 */
    @Test
    void setPlanStaleRejectsLivePlan() {
        AiTrader t = new AiTrader();
        t.setId(7L);
        t.setUserId(1L);
        when(traderMapper.selectById(7L)).thenReturn(t);
        com.mawai.wiibcommon.entity.AiTraderPlan p = new com.mawai.wiibcommon.entity.AiTraderPlan();
        p.setId(9L);
        p.setTraderId(7L);
        p.setStatus(com.mawai.wiibcommon.entity.AiTraderPlan.STATUS_LIVE);
        when(planStore.byId(9L)).thenReturn(p);

        assertThat(service.setPlanStale(1L, 7L, 9L, true)).isNotNull();

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
        when(traderMapper.selectById(7L)).thenReturn(t);
        when(langResolver.of(1L)).thenReturn(AgentLang.EN);
        // 没爆仓，改到一行
        when(traderMapper.update(any(), any())).thenReturn(1);

        assertThat(service.pause(1L, 7L)).isNull();

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

    /** 权益是每行落库前取的：最新权益和净值曲线都按落库时刻排，不按这一轮开始的时刻 */
    @Test
    @SuppressWarnings("unchecked")
    void equityFollowsInsertTime() {
        AiTrader t = traderOnRound(2);
        when(decisionMapper.selectOne(any())).thenReturn(null);
        when(decisionMapper.selectList(any())).thenReturn(List.of());

        service.latestEquity(t);
        service.equityCurve(t, null);

        ArgumentCaptor<LambdaQueryWrapper<AiTraderDecision>> latest = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(decisionMapper).selectOne(latest.capture());
        assertThat(latest.getValue().getSqlSegment()).contains("ORDER BY created_at DESC").doesNotContain("wake_time");
        ArgumentCaptor<LambdaQueryWrapper<AiTraderDecision>> curve = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(decisionMapper).selectList(curve.capture());
        assertThat(curve.getValue().getSqlSegment()).contains("ORDER BY created_at ASC").doesNotContain("wake_time");
        assertThat(curve.getValue().getSqlSelect()).contains("created_at");
    }
}
