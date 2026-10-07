package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.CryptoOrder;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.mapper.CryptoOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 账户重置回归。锁死两件事，每件都是踩过或差点踩的坑：
 * <ol>
 *   <li>必须先注销 Redis 索引再删表。反过来会留幽灵索引，而强平服务命中后处理失败会把索引
 *       原样加回去（FuturesLiquidationServiceImpl 的 catch 里 zAdd 恢复），形成永久重试循环</li>
 *   <li>删表失败必须把索引重新注册回去。否则仓位还在、触发保护没了，等于静默关掉强平</li>
 * </ol>
 */
class AccountResetServiceTest {

    private FuturesPositionMapper positionMapper;
    private CryptoOrderMapper cryptoOrderMapper;
    private FuturesPositionIndexService indexService;
    private AccountPurgeTx purgeTx;
    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zSetOps;
    private ResetQuotaService resetQuota;
    private UserMapper userMapper;
    private AccountResetService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        positionMapper = mock(FuturesPositionMapper.class);
        cryptoOrderMapper = mock(CryptoOrderMapper.class);
        indexService = mock(FuturesPositionIndexService.class);
        purgeTx = mock(AccountPurgeTx.class);
        resetQuota = mock(ResetQuotaService.class);
        userMapper = mock(UserMapper.class);

        redis = mock(StringRedisTemplate.class);
        zSetOps = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zSetOps);

        when(positionMapper.selectList(any())).thenReturn(List.of());
        when(cryptoOrderMapper.selectList(any())).thenReturn(List.of());

        service = new AccountResetService(positionMapper, cryptoOrderMapper, indexService,
                purgeTx, redis, resetQuota, userMapper, new MessageCatalog());
    }

    private static FuturesPosition openPosition() {
        FuturesPosition p = new FuturesPosition();
        p.setId(1L);
        p.setUserId(7L);
        p.setSymbol("BTCUSDT");
        p.setSide("LONG");
        return p;
    }

    @Test
    void unregistersIndexBeforePurgingTables() {
        FuturesPosition p = openPosition();
        when(positionMapper.selectList(any())).thenReturn(List.of(p));

        service.reset(7L);

        InOrder order = inOrder(indexService, purgeTx);
        order.verify(indexService).unregisterAll(p);
        order.verify(purgeTx).purge(7L);
    }

    @Test
    void reRegistersIndexWhenPurgeFails() {
        FuturesPosition p = openPosition();
        when(positionMapper.selectList(any())).thenReturn(List.of(p));
        doThrow(new RuntimeException("db down")).when(purgeTx).purge(7L);

        assertThrows(RuntimeException.class, () -> service.reset(7L));

        // 补偿：删表失败必须把触发保护装回去，否则仓位裸奔
        verify(indexService).registerPositionIndex(p);
    }

    /** 每周限 1 次：第二次直接拒，额度退回，业务一步不走 */
    @Test
    void 每周第二次重置被拒且退回额度() {
        when(resetQuota.recordUse(7L)).thenReturn(1L, 2L);

        service.resetWithGuard(7L, "alice", "alice");
        assertThrows(BizException.class, () -> service.resetWithGuard(7L, "alice", "alice"));

        verify(purgeTx, times(1)).purge(7L);
        verify(resetQuota, times(1)).refund(7L);
    }

    /** 重置失败必须把本周额度退回去，否则一次故障吃掉一次额度 */
    @Test
    void 重置失败退回本周额度() {
        when(resetQuota.recordUse(7L)).thenReturn(1L);
        doThrow(new RuntimeException("db down")).when(purgeTx).purge(7L);

        assertThrows(RuntimeException.class, () -> service.resetWithGuard(7L, "alice", "alice"));

        verify(resetQuota).refund(7L);
    }

    @Test
    void removesPendingLimitOrderIndex() {
        CryptoOrder order = new CryptoOrder();
        order.setId(500L);
        order.setUserId(7L);
        order.setSymbol("BTCUSDT");
        order.setOrderSide("BUY");
        when(cryptoOrderMapper.selectList(any())).thenReturn(List.of(order));

        service.reset(7L);

        verify(zSetOps).remove("crypto:limit:buy:BTCUSDT", "500");
    }

    // ==================== 量化子账户销户（AI Trader 过期轮次清理） ====================

    private static User robotUser(long id, String username) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setLinuxDoId("internal:" + username);
        return u;
    }

    @Test
    void deleteQuantAccountUnregistersIndexThenDeletes() {
        when(userMapper.selectOne(any())).thenReturn(robotUser(9L, "ai_trader_1_r1"));
        FuturesPosition p = openPosition();
        when(positionMapper.selectList(any())).thenReturn(List.of(p));

        service.deleteQuantAccount("ai_trader_1_r1");

        // 弃局账户可能还挂着仓位，同样必须先摘触发索引再删表（幽灵索引问题与重置一致）
        InOrder order = inOrder(indexService, purgeTx);
        order.verify(indexService).unregisterAll(p);
        order.verify(purgeTx).deleteAccount(9L);
    }

    @Test
    void deleteQuantAccountIdempotentWhenMissing() {
        when(userMapper.selectOne(any())).thenReturn(null);

        service.deleteQuantAccount("ai_trader_1_r1");   // 不抛：quant 重试无害

        verify(purgeTx, never()).deleteAccount(anyLong());
    }

    /** 护栏：quant-FIBO 策略常驻账户、真人用户（linuxDoId 非 internal:）都不许从这条路删 */
    @Test
    void deleteQuantAccountGuardsNonTraderAccounts() {
        when(userMapper.selectOne(any())).thenReturn(robotUser(9L, "quant-FIBO"));
        assertThrows(BizException.class, () -> service.deleteQuantAccount("quant-FIBO"));

        User human = new User();
        human.setId(10L);
        human.setUsername("ai_trader_9_r1");
        human.setLinuxDoId("linuxdo-oauth-123");
        when(userMapper.selectOne(any())).thenReturn(human);
        assertThrows(BizException.class, () -> service.deleteQuantAccount("ai_trader_9_r1"));

        verify(purgeTx, never()).deleteAccount(anyLong());
    }
}
