package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.mawai.wiibcommon.entity.*;
import com.mawai.wiibsim.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 重置账户的事务段：12 张用户表清空 + user 复位，全成功或全回滚。
 * <p>
 * 单独成 bean 而不是放 {@link AccountResetService} 里，是因为 @Transactional 走 Spring 代理，
 * 同类内部自调用会绕过代理导致事务根本不生效——这种 bug 平时看不出来，只在出错回滚时才暴露。
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
    private final BlackjackAccountMapper blackjackAccountMapper;
    private final BlackjackConvertLogMapper blackjackConvertLogMapper;
    private final MinesGameMapper minesGameMapper;
    private final VideoPokerGameMapper videoPokerGameMapper;
    private final UserAssetSnapshotMapper userAssetSnapshotMapper;
    private final UserBuffMapper userBuffMapper;
    private final WalletTransferMapper walletTransferMapper;

    @Value("${trading.initial-balance:10000}")
    BigDecimal initialBalance;

    /**
     * 清空并复位。不碰 comment / comment_notification（社区内容不是交易数据，
     * 删根评论还会让别人的回复变孤儿），也不碰 workbench_chat_message。
     */
    @Transactional(rollbackFor = Exception.class)
    public void purge(long userId) {
        // 交易
        futuresPositionMapper.delete(eq(FuturesPosition.class, FuturesPosition::getUserId, userId));
        futuresOrderMapper.delete(eq(FuturesOrder.class, FuturesOrder::getUserId, userId));
        cryptoPositionMapper.delete(eq(CryptoPosition.class, CryptoPosition::getUserId, userId));
        cryptoOrderMapper.delete(eq(CryptoOrder.class, CryptoOrder::getUserId, userId));
        predictionBetMapper.delete(eq(PredictionBet.class, PredictionBet::getUserId, userId));
        // 游戏
        blackjackAccountMapper.delete(eq(BlackjackAccount.class, BlackjackAccount::getUserId, userId));
        blackjackConvertLogMapper.delete(eq(BlackjackConvertLog.class, BlackjackConvertLog::getUserId, userId));
        minesGameMapper.delete(eq(MinesGame.class, MinesGame::getUserId, userId));
        videoPokerGameMapper.delete(eq(VideoPokerGame.class, VideoPokerGame::getUserId, userId));
        // 流水与快照
        userAssetSnapshotMapper.delete(eq(UserAssetSnapshot.class, UserAssetSnapshot::getUserId, userId));
        userBuffMapper.delete(eq(UserBuff.class, UserBuff::getUserId, userId));
        walletTransferMapper.delete(eq(WalletTransfer.class, WalletTransfer::getUserId, userId));

        userMapper.resetToInitial(userId, initialBalance);
    }

    /** 12 张表都是同一个 user_id 条件，抽掉重复的 wrapper 构造 */
    private static <T> LambdaQueryWrapper<T> eq(Class<T> type, SFunction<T, ?> column, long userId) {
        return new LambdaQueryWrapper<>(type).eq(column, userId);
    }
}
