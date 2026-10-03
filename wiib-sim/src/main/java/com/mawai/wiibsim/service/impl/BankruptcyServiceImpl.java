package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.entity.UserLedger;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.enums.LedgerBizType;
import com.mawai.wiibcommon.enums.LedgerWallet;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.CryptoOrderMapper;
import com.mawai.wiibsim.mapper.CryptoPositionMapper;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.MinesGameMapper;
import com.mawai.wiibsim.mapper.PredictionBetMapper;
import com.mawai.wiibsim.mapper.UserLedgerMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.mapper.VideoPokerGameMapper;
import com.mawai.wiibsim.campaign.service.CampaignCarryoverService;
import com.mawai.wiibsim.service.BankruptcyService;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibsim.service.CryptoPositionService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.ResetQuotaService;
import com.mawai.wiibsim.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BankruptcyServiceImpl implements BankruptcyService {

    private final UserService userService;
    private final UserMapper userMapper;
    private final CryptoPositionService cryptoPositionService;
    private final TradingConfig tradingConfig;
    private final CryptoOrderMapper cryptoOrderMapper;
    private final CryptoPositionMapper cryptoPositionMapper;
    private final FuturesOrderMapper futuresOrderMapper;
    private final FuturesPositionMapper futuresPositionMapper;
    private final CacheService cacheService;
    private final FuturesPositionIndexService futuresPositionIndexService;
    private final PredictionBetMapper predictionBetMapper;
    private final MinesGameMapper minesGameMapper;
    private final VideoPokerGameMapper videoPokerGameMapper;
    private final AssetValuationService assetValuationService;
    private final UserLedgerMapper userLedgerMapper;
    private final ResetQuotaService resetQuotaService;
    private final CampaignCarryoverService campaignCarryoverService;

    @Value("${trading.initial-balance:10000}")
    private BigDecimal initialBalance;

    @Override
    public void checkAndLiquidateAll() {
        if (!tradingConfig.getMargin().isEnabled()) {
            return;
        }

        List<User> users = userService.list(new LambdaQueryWrapper<User>()
                .eq(User::getIsBankrupt, false)
                .and(w -> w.gt(User::getMarginLoanPrincipal, BigDecimal.ZERO)
                        .or()
                        .gt(User::getMarginInterestAccrued, BigDecimal.ZERO)));
        if (users.isEmpty()) {
            return;
        }

        LocalDate today = LocalDate.now();
        for (User user : users) {
            try {
                if (shouldBankrupt(user.getId())) {
                    SpringUtils.getAopProxy(this).liquidateUser(user.getId(), today);
                }
            } catch (Exception e) {
                log.error("爆仓检查失败 userId={}", user.getId(), e);
            }
        }
    }

    @Override
    public void resetBankruptUsers(LocalDate today) {

        List<User> users = userService.list(new LambdaQueryWrapper<User>()
                .eq(User::getIsBankrupt, true)
                .le(User::getBankruptResetDate, today));
        if (users.isEmpty()) {
            return;
        }

        for (User user : users) {
            try {
                SpringUtils.getAopProxy(this).resetUser(user.getId(), today);
            } catch (Exception e) {
                log.error("破产恢复失败 userId={}", user.getId(), e);
            }
        }
    }

    @Override
    public void bankruptNow(Long userId) {
        SpringUtils.getAopProxy(this).liquidateUser(userId, LocalDate.now());
    }

    private boolean shouldBankrupt(Long userId) {
        User user = userService.getById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        if (Boolean.TRUE.equals(user.getIsBankrupt())) {
            return false;
        }

        BigDecimal balance = user.getBalance() != null ? user.getBalance() : BigDecimal.ZERO;
        BigDecimal frozen = user.getFrozenBalance() != null ? user.getFrozenBalance() : BigDecimal.ZERO;
        BigDecimal principal = user.getMarginLoanPrincipal() != null ? user.getMarginLoanPrincipal() : BigDecimal.ZERO;
        BigDecimal interest = user.getMarginInterestAccrued() != null ? user.getMarginInterestAccrued() : BigDecimal.ZERO;

        // 现货持仓市值（crypto + bStock，均在 crypto_position）
        BigDecimal marketValue = cryptoPositionService.calculateCryptoMarketValue(userId);

        // 合约仓位 margin + 浮盈亏(统一口径见 AssetValuationService)；旧版只算保证金，深亏用户会被高估净资产、延迟破产
        List<FuturesPosition> futuresPositions = futuresPositionMapper.selectList(
                new LambdaQueryWrapper<FuturesPosition>()
                        .eq(FuturesPosition::getUserId, userId)
                        .eq(FuturesPosition::getStatus, "OPEN"));
        for (FuturesPosition fp : futuresPositions) {
            BigDecimal markPrice = assetValuationService.resolveFuturesPrice(fp.getSymbol());
            marketValue = marketValue.add(AssetValuationService.futuresPositionValue(fp, markPrice));
        }

        // 预测按立刻卖出价估值, 无bid视为不可变现
        marketValue = marketValue.add(assetValuationService.predictionMarketValue(userId));

        // 游戏钱包刻意不计入：破产判定只看交易侧净资产，游戏钱包既救不了你、破产时也会被清空
        BigDecimal netAssets = balance
                .add(frozen)
                .add(marketValue)
                .subtract(principal)
                .subtract(interest);

        return netAssets.compareTo(BigDecimal.ZERO) <= 0;
    }

    @Transactional(rollbackFor = Exception.class)
    protected void liquidateUser(Long userId, LocalDate today) {
        // 7×24 连续交易无休市日，破产次日即恢复
        LocalDate resetDate = today.plusDays(1);

        // markBankrupt 是整体覆写型 SQL（五个钱包全置 0），拿不到旧值，而算 delta 非知道旧值不可。
        // 所以先加行锁读快照：并发的资金 UPDATE 会在这把锁上排队，读到的就是这次清零真正抹掉的金额。
        // 全项目只有这两个低频方法这么写，正常资金路径一律走 atomic* + RETURNING，不许照抄。
        User before = userMapper.selectByIdForUpdate(userId);
        int affected = userMapper.markBankrupt(userId, resetDate);
        if (affected == 0) {
            return;
        }
        recordWalletSnapshotDiff(userId, before, BigDecimal.ZERO, LedgerBizType.BANKRUPT_CLEAR, "爆仓清零");

        cleanupUserHoldings(userId, "LIQUIDATED");
        log.warn("用户爆仓 userId={} resetDate={}", userId, resetDate);
    }

    @Transactional(rollbackFor = Exception.class)
    protected void resetUser(Long userId, LocalDate today) {
        // 同 liquidateUser：resetAfterBankruptcy 也是整体覆写，先加行锁读快照才算得出 delta
        User before = userMapper.selectByIdForUpdate(userId);
        int affected = userMapper.resetAfterBankruptcy(userId, initialBalance, today);
        if (affected == 0) {
            return;
        }
        recordWalletSnapshotDiff(userId, before, initialBalance, LedgerBizType.BANKRUPT_RESET, "破产恢复");

        cleanupUserHoldings(userId, "CLOSED");

        // 破产自动恢复也占一次每周重置额度：不占的话穿仓破产就是免费的重置通道。
        // 本周非首次时在活动侧记付费重置 −30（无活动时 chargeExtraReset 是空操作）。
        // 系统恢复永不被额度挡 —— 这里只计数扣分，不做任何拒绝。
        long used = resetQuotaService.recordUse(userId);
        if (used > 1) {
            campaignCarryoverService.chargeExtraReset(userId);
        }
        log.info("用户恢复初始资金 userId={} balance={} 本周第{}次重置", userId, initialBalance, used);
    }

    /**
     * 按快照差额逐钱包补记整体覆写抹掉/写入的钱。
     * <p>
     * balanceTarget 是覆写后 balance 的目标值（爆仓=0，破产恢复=初始资金），其余四个钱包两条路径都置 0。
     * delta = 新值 − 旧值，差为 0 的钱包不记（记一条 delta=0 的空行只是噪音）。
     */
    private void recordWalletSnapshotDiff(Long userId, User before, BigDecimal balanceTarget,
                                          LedgerBizType bizType, String remark) {
        record Item(LedgerWallet wallet, BigDecimal old, BigDecimal target) {}
        var items = List.of(
                new Item(LedgerWallet.BALANCE, before.getBalance(), balanceTarget),
                new Item(LedgerWallet.FROZEN, before.getFrozenBalance(), BigDecimal.ZERO),
                new Item(LedgerWallet.GAME, before.getGameBalance(), BigDecimal.ZERO),
                new Item(LedgerWallet.LOAN_PRINCIPAL, before.getMarginLoanPrincipal(), BigDecimal.ZERO),
                new Item(LedgerWallet.LOAN_INTEREST, before.getMarginInterestAccrued(), BigDecimal.ZERO));

        for (var it : items) {
            BigDecimal old = it.old() == null ? BigDecimal.ZERO : it.old();
            BigDecimal delta = it.target().subtract(old);
            if (delta.signum() == 0) continue;
            UserLedger e = new UserLedger();
            e.setUserId(userId);
            e.setWallet(it.wallet());
            e.setBizType(bizType);
            e.setDelta(delta);
            e.setBalanceAfter(it.target());
            e.setRemark(remark);
            userLedgerMapper.insert(e);
        }
    }

    /**
     * 爆仓清算/破产恢复共用的持仓清理序列；futuresCloseStatus 区分 LIQUIDATED/CLOSED。
     * 索引摘在改库之前、且整段在调用方的事务里：回滚就是单还在PENDING而索引没了，
     * 这段悬空由每小时的 reconcileLimitOrderIndex 补回来。
     */
    private void cleanupUserHoldings(Long userId, String futuresCloseStatus) {
        cryptoOrderMapper.cancelOpenOrdersByUserId(userId);
        cryptoPositionMapper.deleteByUserId(userId);
        cleanupFuturesRedisIndexes(userId);
        futuresOrderMapper.cancelOpenOrdersByUserId(userId);
        futuresPositionMapper.closeOpenByUserId(userId, futuresCloseStatus);
        // 预测: 取消活跃投注(爆仓不退款；恢复路径为防御性清残留)
        predictionBetMapper.cancelActiveByUserId(userId);
        // 游戏: 进行中的矿工/视频扑克局作废，本金不退（21点筹码不是钱包里的钱，不动）
        minesGameMapper.forfeitPlayingByUserId(userId);
        videoPokerGameMapper.forfeitDealingByUserId(userId);
    }

    private void cleanupFuturesRedisIndexes(Long userId) {
        List<FuturesOrder> pendingOrders = futuresOrderMapper.selectList(new LambdaQueryWrapper<FuturesOrder>()
                .eq(FuturesOrder::getUserId, userId)
                .eq(FuturesOrder::getStatus, "PENDING")
                .eq(FuturesOrder::getOrderType, "LIMIT"));
        for (FuturesOrder order : pendingOrders) {
            FuturesHelper.removeFromLimitZSet(order, cacheService);
        }

        List<FuturesPosition> openPositions = futuresPositionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                .eq(FuturesPosition::getUserId, userId)
                .eq(FuturesPosition::getStatus, "OPEN"));
        for (FuturesPosition position : openPositions) {
            futuresPositionIndexService.unregisterAll(position);
        }
    }

}
