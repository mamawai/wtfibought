package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.mawai.wiibcommon.entity.*;
import com.mawai.wiibsim.campaign.service.CampaignCarryoverService;
import com.mawai.wiibsim.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 账户清除的事务段：重置路径先固化活动遗留积分，再 7 张用户表清空 + user 复位；
 * 销户路径清表后直接删行。全成功或全回滚。
 * <p>
 * 单独成 bean，这么写为了 @Transactional 走 Spring 代理（同类自调用会绕过代理，事务不生效）。
 */
@Component
@RequiredArgsConstructor
public class AccountPurgeTx {

    private final UserMapper userMapper;
    private final FuturesPositionMapper futuresPositionMapper;
    private final FuturesOrderMapper futuresOrderMapper;
    private final CryptoPositionMapper cryptoPositionMapper;
    private final CryptoOrderMapper cryptoOrderMapper;
    private final PredictionBetMapper predictionBetMapper;
    private final UserAssetSnapshotMapper userAssetSnapshotMapper;
    private final UserLedgerMapper userLedgerMapper;
    private final UserService userService;
    private final CampaignCarryoverService campaignCarryoverService;

    @Value("${trading.initial-balance:10000}")
    BigDecimal initialBalance;

    /**
     * 清空并复位。不碰 comment / comment_notification（社区内容不是交易数据，
     * 删根评论还会让别人的回复变孤儿），也不碰 workbench_chat_message。
     *
     * @param chargeExtraReset 本次是付费重置（本周非首次且活动进行中），在活动侧记 −30
     */
    @Transactional(rollbackFor = Exception.class)
    public void purge(long userId, boolean chargeExtraReset) {
        lockUserRow(userId);
        // 活动遗留积分：删表前先把已达成次数固化进 campaign_carryover。
        // 必须同一事务：删表回滚则快照也回滚，否则下次算分双算。无活动时是空操作
        campaignCarryoverService.carryOver(userId);
        if (chargeExtraReset) {
            campaignCarryoverService.chargeExtraReset(userId);
        }

        clearUserData(userId);
        userMapper.resetToInitial(userId, initialBalance);
        // 账本刚清空、resetToInitial 又是整体覆写（切面抓不到），补一条初始资金让不变量重新成立。
        // 开头那次 FOR UPDATE 只为取锁不读旧值：旧账本整张删了，新账本从这一笔起算
        userService.recordInitialGrant(userId, initialBalance);
    }

    /**
     * 量化子账户销户：同一套清表后直接删 user 行（不复位不入金，也不动活动积分——
     * 机器人不参加活动），AI Trader 过期轮次清理用。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteAccount(long userId) {
        lockUserRow(userId);
        clearUserData(userId);
        userMapper.deleteById(userId);
    }

    /**
     * 清表前先锁 user 行（那个 7 天键只是限频，不是互斥）：动钱的 UPDATE...RETURNING 都打这一行，
     * 并发中的下单/成交/派彩会排到本事务提交后再动，动的已是复位后的余额。
     * <p>
     * 两处边界要知道：不涉资金的挂单插入（限价平仓单只 insert）不打 user 行，
     * 不受这把锁保护；交易侧的加锁顺序是"先订单行后 user 行"、这里反过来，同一用户重置与交易
     * 真撞上时靠 PG 死锁检测打回其中一个（重置或那笔交易失败，都可重试）。
     */
    private void lockUserRow(long userId) {
        userMapper.selectByIdForUpdate(userId);
    }

    private void clearUserData(long userId) {
        // 交易
        futuresPositionMapper.delete(eq(FuturesPosition.class, FuturesPosition::getUserId, userId));
        futuresOrderMapper.delete(eq(FuturesOrder.class, FuturesOrder::getUserId, userId));
        cryptoPositionMapper.delete(eq(CryptoPosition.class, CryptoPosition::getUserId, userId));
        cryptoOrderMapper.delete(eq(CryptoOrder.class, CryptoOrder::getUserId, userId));
        predictionBetMapper.delete(eq(PredictionBet.class, PredictionBet::getUserId, userId));
        // 流水与快照
        userAssetSnapshotMapper.delete(eq(UserAssetSnapshot.class, UserAssetSnapshot::getUserId, userId));
        userLedgerMapper.deleteByUserId(userId);   // 账本随账户一起重来
    }

    /** 这 6 张表都是同一个 user_id 条件，抽掉重复的 wrapper 构造（账本第 7 张走自己的 deleteByUserId） */
    private static <T> LambdaQueryWrapper<T> eq(Class<T> type, SFunction<T, ?> column, long userId) {
        return new LambdaQueryWrapper<>(type).eq(column, userId);
    }
}
