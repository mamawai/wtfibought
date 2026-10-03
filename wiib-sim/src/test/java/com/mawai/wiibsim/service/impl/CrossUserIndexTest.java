package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.BankruptcyService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mawai.wiibsim.service.impl.CrossMarginServiceImpl.CROSS_SYM_PREFIX;
import static com.mawai.wiibsim.service.impl.CrossMarginServiceImpl.CROSS_USERS_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 全仓 Redis 索引（tick 定向找人 / 兜底巡检的名单）的刷新时机与串行：
 * 事务内的刷新等提交后才读库，回滚不刷；同一用户的"读库+写 Redis"整体互斥。
 * settle 的"扣穿且无全仓仓位→破产"按库判，不看索引。
 */
class CrossUserIndexTest {

    private static final Long UID = 7L;
    private static final String SYM = "BTCUSDT";

    private UserMapper userMapper;
    private FuturesPositionMapper positionMapper;
    private BankruptcyService bankruptcyService;
    private StringRedisTemplate redis;
    private SetOperations<String, String> setOps;
    private CrossMarginServiceImpl crossMargin;

    /** 刷新读库用了 .select(lambda)，要查 ColumnCache；裸 mock 没有 MyBatis 上下文，手动补表信息 */
    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FuturesPosition.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userMapper = mock(UserMapper.class);
        positionMapper = mock(FuturesPositionMapper.class);
        bankruptcyService = mock(BankruptcyService.class);
        redis = mock(StringRedisTemplate.class);
        setOps = mock(SetOperations.class);
        when(redis.opsForSet()).thenReturn(setOps);
        when(positionMapper.selectList(any())).thenReturn(List.of(crossPos()));

        crossMargin = new CrossMarginServiceImpl(userMapper, positionMapper, mock(CacheService.class),
                mock(FuturesLeverageBracketRegistry.class), mock(FuturesPositionIndexService.class),
                bankruptcyService, redis, new CrossBandRegistry());
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static FuturesPosition crossPos() {
        FuturesPosition p = new FuturesPosition();
        p.setSymbol(SYM);
        return p;
    }

    /** 照 Spring 提交流程收尾：先摘同步器、再逐个回调 afterCompletion */
    private static void completeTx(int status) {
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        syncs.forEach(s -> s.afterCompletion(status));
    }

    @Test
    void 事务内刷新_提交前不读库不碰Redis_提交后才刷() {
        TransactionSynchronizationManager.initSynchronization();
        crossMargin.refreshUserIndex(UID);

        verify(positionMapper, never()).selectList(any());
        verify(redis, never()).opsForSet();

        completeTx(TransactionSynchronization.STATUS_COMMITTED);

        verify(positionMapper).selectList(any());
        verify(setOps).add(CROSS_SYM_PREFIX + SYM, "7");
        verify(setOps).add(CROSS_USERS_KEY, "7");
    }

    @Test
    void 事务回滚_不刷索引() {
        TransactionSynchronizationManager.initSynchronization();
        crossMargin.refreshUserIndex(UID);

        completeTx(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(positionMapper, never()).selectList(any());
        verify(redis, never()).opsForSet();
    }

    /** 启动重建 / checkUser 清残留：不在事务里，照常立即生效 */
    @Test
    void 非事务调用_立即刷() {
        crossMargin.refreshUserIndex(UID);

        verify(positionMapper).selectList(any());
        verify(setOps).add(CROSS_USERS_KEY, "7");
    }

    @Test
    void 结算扣穿且库里已无全仓仓位_立即破产() {
        when(userMapper.selectById(UID)).thenReturn(userWithBalance("-50"));
        when(positionMapper.exists(any())).thenReturn(false);

        TransactionSynchronizationManager.initSynchronization();
        crossMargin.settle(UID, new BigDecimal("-100"));

        verify(bankruptcyService).bankruptNow(UID);
    }

    /** Redis 索引里没这个人（mock 默认 isMember=false），库里还有全仓仓位：不破产 */
    @Test
    void 结算扣穿但库里仍有全仓仓位_不看索引_不破产() {
        when(userMapper.selectById(UID)).thenReturn(userWithBalance("-50"));
        when(positionMapper.exists(any())).thenReturn(true);

        TransactionSynchronizationManager.initSynchronization();
        crossMargin.settle(UID, new BigDecimal("-100"));

        verify(bankruptcyService, never()).bankruptNow(any());
    }

    /** 同一用户两次刷新：第一次停在读库上时，第二次卡在用户锁上、还没读库 */
    @Test
    void 同一用户两次刷新_读库写Redis整体串行() throws Exception {
        CountDownLatch firstReading = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        when(positionMapper.selectList(any())).thenAnswer(inv -> {
            if (reads.incrementAndGet() == 1) {
                firstReading.countDown();
                releaseFirst.await(5, TimeUnit.SECONDS);
            }
            return List.of(crossPos());
        });

        Thread first = new Thread(() -> crossMargin.refreshUserIndex(UID));
        first.start();
        assertThat(firstReading.await(5, TimeUnit.SECONDS)).isTrue();

        Thread second = new Thread(() -> crossMargin.refreshUserIndex(UID));
        second.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (second.getState() != Thread.State.BLOCKED && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(second.getState()).isEqualTo(Thread.State.BLOCKED);
        assertThat(reads.get()).isEqualTo(1);

        releaseFirst.countDown();
        first.join(5000);
        second.join(5000);
        assertThat(reads.get()).isEqualTo(2);
        verify(setOps, times(2)).add(CROSS_USERS_KEY, "7");
    }

    private static User userWithBalance(String balance) {
        User u = new User();
        u.setId(UID);
        u.setBalance(new BigDecimal(balance));
        return u;
    }
}
