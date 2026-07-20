package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.CryptoOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.mapper.CryptoOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 账户重置：清空全部交易与游戏数据，账户回到初始状态。
 * <p>
 * 顺序是先清 Redis 触发索引再删表，且删表失败要把索引装回去。反过来（先删表）会留下
 * 指向已删仓位的幽灵索引，而 {@code FuturesLiquidationServiceImpl} 命中索引后处理失败会
 * 把索引原样 zAdd 回去——幽灵会永远复活，每个 tick 重试一次。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountResetService {

    private static final String SETTLE_ZSET_KEY = "crypto:settle:pending";
    private static final String LIMIT_BUY_PREFIX = "crypto:limit:buy:";
    private static final String LIMIT_SELL_PREFIX = "crypto:limit:sell:";
    private static final String RESET_LOCK_PREFIX = "user:reset:";
    private static final String RANKING_KEY = "ranking:top";
    /** 三个游戏"进行中的那一局"只存在 Redis 里，不在库表（见各 ServiceImpl 的 SK 常量） */
    private static final List<String> GAME_SESSION_PREFIXES =
            List.of("bj:session:", "mines:session:", "vp:session:");
    private static final String BUFF_STATUS_PREFIX = "buff:status:";

    private final FuturesPositionMapper futuresPositionMapper;
    private final CryptoOrderMapper cryptoOrderMapper;
    private final FuturesPositionIndexService indexService;
    private final AccountPurgeTx purgeTx;
    private final StringRedisTemplate redis;

    /** 策略账户（quant-FIBO 这类）是 user 表里的真实行，永不可重置；用户名须逐字匹配，防误点 */
    public static void assertResettable(String actualUsername, String confirmUsername) {
        if (actualUsername == null || actualUsername.startsWith("quant-")) {
            throw new BizException(ErrorCode.RESET_NOT_ALLOWED);
        }
        if (!actualUsername.equals(confirmUsername)) {
            throw new BizException(ErrorCode.RESET_NOT_ALLOWED);
        }
    }

    /** 每周一次。抢不到键说明 7 天内重置过 */
    public void resetWithGuard(long userId, String actualUsername, String confirmUsername) {
        assertResettable(actualUsername, confirmUsername);
        Boolean first = redis.opsForValue()
                .setIfAbsent(RESET_LOCK_PREFIX + userId, "1", Duration.ofDays(7));
        if (!Boolean.TRUE.equals(first)) {
            throw new BizException(ErrorCode.RESET_TOO_FREQUENT);
        }
        try {
            reset(userId);
        } catch (RuntimeException e) {
            // 没重置成功就不该占着一周的额度
            redis.delete(RESET_LOCK_PREFIX + userId);
            throw e;
        }
    }

    void reset(long userId) {
        List<FuturesPosition> openPositions = futuresPositionMapper.selectList(
                new LambdaQueryWrapper<FuturesPosition>()
                        .eq(FuturesPosition::getUserId, userId)
                        .eq(FuturesPosition::getStatus, "OPEN"));

        unregisterIndexes(userId, openPositions);
        try {
            purgeTx.purge(userId);
        } catch (RuntimeException e) {
            // 删表失败=仓位还在，但触发保护已经摘了，必须装回去，否则强平/止损静默失效
            for (FuturesPosition p : openPositions) {
                try {
                    indexService.registerPositionIndex(p);
                } catch (Exception re) {
                    log.error("[AccountReset] 索引回滚失败 positionId={} 该仓位已失去触发保护", p.getId(), re);
                }
            }
            throw e;
        }
        redis.delete(RANKING_KEY);   // 榜单缓存 15min，不清的话重置结果要等下一轮才可见
        log.info("[AccountReset] 账户已重置 userId={} 清理仓位数={}", userId, openPositions.size());
    }

    /** 清掉本用户挂在 Redis 上的三类触发索引：合约 LIQ/SL/TP、现货限价单、待结算队列 */
    private void unregisterIndexes(long userId, List<FuturesPosition> openPositions) {
        for (FuturesPosition p : openPositions) {
            indexService.unregisterAll(p);
        }

        List<CryptoOrder> pending = cryptoOrderMapper.selectList(
                new LambdaQueryWrapper<CryptoOrder>()
                        .eq(CryptoOrder::getUserId, userId)
                        .eq(CryptoOrder::getStatus, "PENDING"));
        for (CryptoOrder o : pending) {
            String prefix = "BUY".equals(o.getOrderSide()) ? LIMIT_BUY_PREFIX : LIMIT_SELL_PREFIX;
            redis.opsForZSet().remove(prefix + o.getSymbol(), String.valueOf(o.getId()));
        }

        // member 格式是 "userId:orderId:amount"（见 CryptoOrderServiceImpl 的拼接与 split(":", 3)），
        // userId 在最前面，必须带冒号做前缀匹配——否则 userId=7 会误伤 77、700
        Set<String> settling = redis.opsForZSet().range(SETTLE_ZSET_KEY, 0, -1);
        if (settling != null) {
            String prefix = userId + ":";
            settling.stream().filter(m -> m.startsWith(prefix))
                    .forEach(m -> redis.opsForZSet().remove(SETTLE_ZSET_KEY, m));
        }

        // 进行中的牌局。库表行被 purge 删了、游戏钱包也归零了，这一局要是留着，
        // 用户回去接着"提现"就能从一局本该抹掉的游戏里拿到派彩，等于凭空造钱
        for (String prefix : GAME_SESSION_PREFIXES) {
            redis.delete(prefix + userId);
        }

        // 今日 buff 状态缓存(TTL 1h)。user_buff 行删了但缓存还写着"今天已抽"，
        // 不清的话用户重置完最长一小时抽不了新 buff
        redis.delete(BUFF_STATUS_PREFIX + userId + ":" + LocalDate.now());
    }
}
