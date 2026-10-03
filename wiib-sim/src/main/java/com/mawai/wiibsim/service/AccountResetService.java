package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.CryptoOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibsim.campaign.service.CampaignCarryoverService;
import com.mawai.wiibsim.mapper.CryptoOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 账户重置：清空全部交易与预测数据，账户回到初始状态；另承接量化子账户销户（清完直接删行）。
 * <p>
 * 顺序是先清 Redis 触发索引再删表，且删表失败要把索引装回去。反过来（先删表）会留下
 * 指向已删仓位的幽灵索引，而 {@code FuturesLiquidationServiceImpl} 命中索引后处理失败会
 * 把索引原样 zAdd 回去——幽灵会永远复活，每个 tick 重试一次。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountResetService {

    private static final String LIMIT_BUY_PREFIX = "crypto:limit:buy:";
    private static final String LIMIT_SELL_PREFIX = "crypto:limit:sell:";
    private static final String RANKING_KEY = "ranking:top";
    private static final String BUFF_STATUS_PREFIX = "buff:status:";

    private final FuturesPositionMapper futuresPositionMapper;
    private final CryptoOrderMapper cryptoOrderMapper;
    private final FuturesPositionIndexService indexService;
    private final AccountPurgeTx purgeTx;
    private final StringRedisTemplate redis;
    private final ResetQuotaService resetQuota;
    private final CampaignCarryoverService campaignCarryoverService;
    private final UserMapper userMapper;
    /** 管理页的拦阻提示也跟界面语言 */
    private final MessageCatalog messages;

    /** 策略账户（quant-FIBO 这类）是 user 表里的真实行，永不可重置；用户名须逐字匹配，防误点 */
    public static void assertResettable(String actualUsername, String confirmUsername) {
        if (actualUsername == null || actualUsername.startsWith("quant-")) {
            throw new BizException(ErrorCode.RESET_NOT_ALLOWED);
        }
        if (!actualUsername.equals(confirmUsername)) {
            throw new BizException(ErrorCode.RESET_NOT_ALLOWED);
        }
    }

    /**
     * 手动重置的额度闸。自然周（周一~周日）计数，破产自动恢复共用同一计数
     * （{@link ResetQuotaService}，那边永不被拦、只计数）。
     * <p>
     * 活动进行中：每周首次免费，之后每次在活动积分里扣 30（不限次数，扣分与删表同事务）；
     * 平时：每周限 1 次，超了直接拒。被拒或失败的尝试都退回额度。
     */
    public void resetWithGuard(long userId, String actualUsername, String confirmUsername) {
        assertResettable(actualUsername, confirmUsername);

        long used = resetQuota.recordUse(userId);
        boolean extra = used > 1;
        if (extra && !campaignCarryoverService.campaignRunning()) {
            resetQuota.refund(userId);
            throw new BizException(ErrorCode.RESET_TOO_FREQUENT);
        }
        try {
            reset(userId, extra);
        } catch (RuntimeException e) {
            // 没重置成功就不占本周额度
            resetQuota.refund(userId);
            throw e;
        }
    }

    void reset(long userId) {
        reset(userId, false);
    }

    void reset(long userId, boolean chargeExtraReset) {
        int cleared = wipe(userId, () -> purgeTx.purge(userId, chargeExtraReset));
        log.info("[AccountReset] 账户已重置 userId={} 清理仓位数={}", userId, cleared);
    }

    /**
     * 量化子账户销户（内部 API，AI Trader 过期轮次清理用）：清索引→清表→删 user 行。
     * 双重护栏：用户名须 ai_trader_ 开头且 user 行是量化建号（linuxDoId internal: 前缀）——
     * quant-FIBO 策略常驻账户和真人用户都拒；账户不存在视为已删（幂等，quant 重试无害）。
     */
    public void deleteQuantAccount(String username) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username).last("LIMIT 1"));
        if (user == null) {
            return;
        }
        if (!username.startsWith("ai_trader_")
                || user.getLinuxDoId() == null || !user.getLinuxDoId().startsWith("internal:")) {
            throw new BizException(messages.get("sim.reset.onlyQuantSubAccount", Map.of("name", username)));
        }
        wipe(user.getId(), () -> purgeTx.deleteAccount(user.getId()));
        log.info("[AccountReset] 量化子账户已删除 username={} userId={}", username, user.getId());
    }

    /** 重置/销户共用骨架：先摘触发索引再清表，清表失败装回索引（顺序理由见类注释）；返回清理仓位数 */
    private int wipe(long userId, Runnable purge) {
        List<FuturesPosition> openPositions = futuresPositionMapper.selectList(
                new LambdaQueryWrapper<FuturesPosition>()
                        .eq(FuturesPosition::getUserId, userId)
                        .eq(FuturesPosition::getStatus, "OPEN"));

        unregisterIndexes(userId, openPositions);
        try {
            purge.run();
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
        return openPositions.size();
    }

    /** 清掉本用户挂在 Redis 上的两类触发索引：合约 LIQ/SL/TP、现货限价单 */
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

        // 今日 buff 状态缓存(TTL 1h)。user_buff 行删了但缓存还写着"今天已抽"，
        // 不清的话用户重置完最长一小时抽不了新 buff
        redis.delete(BUFF_STATUS_PREFIX + userId + ":" + LocalDate.now());
    }
}
