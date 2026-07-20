package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.CryptoOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibsim.mapper.CryptoOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 账户重置回归。锁死三件事，每件都是踩过或差点踩的坑：
 * <ol>
 *   <li>必须先注销 Redis 索引再删表。反过来会留幽灵索引，而强平服务命中后处理失败会把索引
 *       原样加回去（FuturesLiquidationServiceImpl 的 catch 里 zAdd 恢复），形成永久重试循环</li>
 *   <li>删表失败必须把索引重新注册回去。否则仓位还在、触发保护没了，等于静默关掉强平</li>
 *   <li>待结算队列 member 是 "userId:orderId:amount"，userId 在最前，必须按前缀匹配</li>
 * </ol>
 */
class AccountResetServiceTest {

    private FuturesPositionMapper positionMapper;
    private CryptoOrderMapper cryptoOrderMapper;
    private FuturesPositionIndexService indexService;
    private AccountPurgeTx purgeTx;
    private ZSetOperations<String, String> zSetOps;
    private AccountResetService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        positionMapper = mock(FuturesPositionMapper.class);
        cryptoOrderMapper = mock(CryptoOrderMapper.class);
        indexService = mock(FuturesPositionIndexService.class);
        purgeTx = mock(AccountPurgeTx.class);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        zSetOps = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zSetOps);

        when(positionMapper.selectList(any())).thenReturn(List.of());
        when(cryptoOrderMapper.selectList(any())).thenReturn(List.of());
        when(zSetOps.range(anyString(), anyLong(), anyLong())).thenReturn(Set.of());

        service = new AccountResetService(positionMapper, cryptoOrderMapper, indexService, purgeTx, redis);
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

    @Test
    void removesOnlyOwnSettlingEntries() {
        // member 格式 "userId:orderId:amount"，7 和 77 都以 7 开头，必须靠冒号区分
        when(zSetOps.range(eq("crypto:settle:pending"), anyLong(), anyLong()))
                .thenReturn(Set.of("7:1001:50.00", "77:1002:80.00", "8:1003:20.00"));

        service.reset(7L);

        verify(zSetOps).remove("crypto:settle:pending", "7:1001:50.00");
        verify(zSetOps, never()).remove("crypto:settle:pending", "77:1002:80.00");
        verify(zSetOps, never()).remove("crypto:settle:pending", "8:1003:20.00");
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
}
