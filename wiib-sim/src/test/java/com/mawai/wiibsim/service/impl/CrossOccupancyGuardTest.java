package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.dto.FuturesAddMarginRequest;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.dto.CrossSnapshotRow;
import com.mawai.wiibsim.ledger.LedgerTx;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.BankruptcyService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.FairLockRegistry;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 全仓占用隔离：被全仓仓位占着的保证金，逐仓/现货/划转一分都动不了。
 *
 * <p>历史 bug——这些出口过去走的是"流出后会不会当场爆仓"（equity − 维持保证金）。
 * 维持保证金比起始保证金小一个数量级（20x 下 0.5% vs 5%），于是全仓占用形同虚设，
 * 用户看到的现象是"开了全仓后逐仓还能用所有的钱去开"。</p>
 *
 * <p>本类场景固定为：钱包 1000 + 一笔 20x 全仓（名义额 2000、占用 100、维持保证金 10），
 * available = 1000 − 100 = 900（对齐 Binance availableBalance，不是 equity − 维持保证金的 990）。</p>
 */
class CrossOccupancyGuardTest {

    private static final Long UID = 7L;
    private static final String CROSS_SYMBOL = "BTCUSDT";

    private UserMapper userMapper;
    private FuturesPositionMapper positionMapper;
    private FuturesOrderMapper orderMapper;
    private CacheService cacheService;
    private FuturesLeverageBracketRegistry bracketRegistry;
    private CrossMarginServiceImpl crossMargin;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        positionMapper = mock(FuturesPositionMapper.class);
        orderMapper = mock(FuturesOrderMapper.class);
        cacheService = mock(CacheService.class);
        bracketRegistry = mock(FuturesLeverageBracketRegistry.class);

        // entry=mark=100 → 浮盈0，把变量收敛到只剩"占用"一项。
        // snapshot 已三查合一（selectCrossSnapshot），stub 也只剩这一个口
        when(positionMapper.selectCrossSnapshot(UID)).thenReturn(List.of(crossSnapshotRow()));
        when(cacheService.getMarkPrice(CROSS_SYMBOL)).thenReturn(new BigDecimal("100"));
        when(bracketRegistry.calcMaintenanceMargin(eq(CROSS_SYMBOL), any())).thenReturn(new BigDecimal("10"));

        crossMargin = new CrossMarginServiceImpl(userMapper, positionMapper, cacheService,
                bracketRegistry, mock(FuturesPositionIndexService.class), mock(BankruptcyService.class),
                mock(StringRedisTemplate.class), new CrossBandRegistry());
    }

    /** 20x 全仓快照行：钱包 1000，qty 20 @100 → 名义额 2000，占用 2000/20 = 100 */
    private static CrossSnapshotRow crossSnapshotRow() {
        CrossSnapshotRow row = new CrossSnapshotRow();
        row.setBalance(new BigDecimal("1000"));
        row.setPendingReserved(BigDecimal.ZERO);
        row.setPositionId(1L);
        row.setSymbol(CROSS_SYMBOL);
        row.setSide("LONG");
        row.setLeverage(20);
        row.setEntryPrice(new BigDecimal("100"));
        row.setQuantity(new BigDecimal("20"));
        row.setMargin(new BigDecimal("100"));
        return row;
    }

    @Test
    void 可用额度按起始保证金扣到900_多一分就拒() {
        assertThat(crossMargin.snapshot(UID).available()).isEqualByComparingTo("900");

        assertThatCode(() -> crossMargin.assertCanAfford(UID, new BigDecimal("900")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> crossMargin.assertCanAfford(UID, new BigDecimal("900.01")))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.FUTURES_CROSS_AVAILABLE_NOT_ENOUGH.getCode());
    }

    /** 并发闸：先锁 user 行、后读快照（锁持有到调用方事务提交） */
    @Test
    void 校验额度前先锁user行_锁在读快照之前() {
        crossMargin.assertCanAfford(UID, new BigDecimal("900"));

        var order = inOrder(userMapper, positionMapper);
        order.verify(userMapper).selectByIdForUpdate(UID);
        order.verify(positionMapper).selectCrossSnapshot(UID);
    }

    /** 快照是 tick 巡检热路径（每用户每秒），只读不锁 */
    @Test
    void 单纯取快照不加锁() {
        crossMargin.snapshot(UID);
        crossMargin.snapshot(UID, CROSS_SYMBOL, new BigDecimal("90"));

        verify(userMapper, never()).selectByIdForUpdate(anyLong());
    }

    /** 这条是本次修复的锚：989 恰好落在两个口径中间，旧守卫放行、新守卫必须拒 */
    @Test
    void 回归_旧维持保证金口径放行的989_现在拒() {
        assertThatThrownBy(() -> crossMargin.assertCanAfford(UID, new BigDecimal("989")))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.FUTURES_CROSS_AVAILABLE_NOT_ENOUGH.getCode());
    }

    @Test
    void 最大可流出取小_浮盈抬不动现金上限() {
        // 无浮盈：卡在可用额度
        assertThat(crossMargin.snapshot(UID).maxOutflow()).isEqualByComparingTo("900");

        // 浮盈500（mark 125 → qty20 涨25）：可用变1400，但钱包现金仍是1000
        when(cacheService.getMarkPrice(CROSS_SYMBOL)).thenReturn(new BigDecimal("125"));
        when(bracketRegistry.calcMaintenanceMargin(eq(CROSS_SYMBOL), any())).thenReturn(new BigDecimal("12.5"));

        assertThat(crossMargin.snapshot(UID).available()).isEqualByComparingTo("1400");
        assertThat(crossMargin.snapshot(UID).maxOutflow()).isEqualByComparingTo("1000");
    }

    /**
     * 挂单占用含 PENDING 以及触发后还没落成仓位的 TRIGGERED / PROCESSING。
     * 只钉 SQL 文本里的状态集合，真查询语义要连 PG 才验得到。
     */
    @Test
    void 挂单占用口径含已触发未落仓的单() throws NoSuchMethodException {
        String sql = String.join("\n", FuturesPositionMapper.class
                .getMethod("selectCrossSnapshot", Long.class).getAnnotation(Select.class).value());
        String reserved = sql.substring(0, sql.indexOf("AS pending_reserved"));

        assertThat(reserved).contains("'PENDING'", "'TRIGGERED'", "'PROCESSING'");
    }

    /**
     * 逐仓追加保证金端到端：真实 CrossMarginServiceImpl 挂进交易服务，
     * 钱包 1000 够付 950、可用 900 不够 → 在动钱之前就被拒。
     */
    @Test
    void 逐仓追加保证金_钱包够但可用不够_动钱前拒() {
        FuturesPosition isolated = new FuturesPosition();
        isolated.setId(2L);
        isolated.setUserId(UID);
        isolated.setSymbol("ETHUSDT");
        isolated.setSide("LONG");
        isolated.setMarginMode(FuturesPosition.ISOLATED);
        isolated.setLeverage(10);
        isolated.setEntryPrice(new BigDecimal("2000"));
        isolated.setQuantity(new BigDecimal("0.25"));
        isolated.setMargin(new BigDecimal("50"));
        isolated.setStatus("OPEN");
        when(positionMapper.selectById(2L)).thenReturn(isolated);

        FuturesTradingServiceImpl trading = new FuturesTradingServiceImpl(
                mock(UserService.class), userMapper, positionMapper, orderMapper,
                new TradingConfig(), mock(FairLockRegistry.class), cacheService,
                mock(FuturesPositionIndexService.class), bracketRegistry, crossMargin,
                new TradeFilterRegistry(mock(BinanceRestClient.class)),
                new MessageCatalog(), new LedgerTx(new TransactionTemplate(mock(PlatformTransactionManager.class))));

        FuturesAddMarginRequest req = new FuturesAddMarginRequest();
        req.setPositionId(2L);
        req.setAmount(new BigDecimal("950"));

        assertThatThrownBy(() -> trading.doAddMargin(UID, req))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.FUTURES_CROSS_AVAILABLE_NOT_ENOUGH.getCode());

        verify(userMapper, never()).atomicUpdateBalance(anyLong(), any());
        verify(positionMapper, never()).atomicAddMargin(anyLong(), any());
    }
}
