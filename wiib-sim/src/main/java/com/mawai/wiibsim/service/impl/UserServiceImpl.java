package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mawai.wiibcommon.dto.UserDTO;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.entity.WalletTransfer;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.mapper.CryptoOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.mapper.WalletTransferMapper;
import com.mawai.wiibsim.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 用户服务实现
 * 使用数据库原子操作保证并发安全
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final CryptoPositionService cryptoPositionService;
    private final CryptoOrderMapper cryptoOrderMapper;
    private final FuturesPositionMapper futuresPositionMapper;
    private final AssetValuationService assetValuationService;
    private final WalletTransferMapper walletTransferMapper;

    @Value("${trading.initial-balance:10000}")
    private BigDecimal initialBalance;

    @Override
    public User findByLinuxDoId(String linuxDoId) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getLinuxDoId, linuxDoId);
        return baseMapper.selectOne(wrapper);
    }

    @Override
    public User findByUsername(String username) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username);
        return baseMapper.selectOne(wrapper);
    }

    @Override
    public void ensureAdminUser() {
        // 幂等：id=1 已存在则 ON CONFLICT 跳过；balance 用配置的初始资金
        baseMapper.insertAdmin(initialBalance);
        baseMapper.syncIdSequence();
    }

    @Override
    public UserDTO getUserPortfolio(Long userId) {
        User user = baseMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        // 现货持仓市值（crypto + bStock，均在 crypto_position）
        BigDecimal marketValue = cryptoPositionService.calculateCryptoMarketValue(userId);

        BigDecimal frozenBalance = user.getFrozenBalance() != null ? user.getFrozenBalance() : BigDecimal.ZERO;
        BigDecimal marginLoanPrincipal = user.getMarginLoanPrincipal() != null ? user.getMarginLoanPrincipal() : BigDecimal.ZERO;
        BigDecimal marginInterestAccrued = user.getMarginInterestAccrued() != null ? user.getMarginInterestAccrued() : BigDecimal.ZERO;

        // crypto待结算（SETTLING状态；老股 T+1 已退）
        BigDecimal pendingSettlement = cryptoOrderMapper.sumSettlingAmount(userId);

        // 合约仓位: margin + unrealizedPnl（统一口径见 AssetValuationService）
        BigDecimal futuresValue = BigDecimal.ZERO;
        List<FuturesPosition> futuresPositions = futuresPositionMapper.selectList(
                new LambdaQueryWrapper<FuturesPosition>()
                        .eq(FuturesPosition::getUserId, userId)
                        .eq(FuturesPosition::getStatus, "OPEN"));
        for (FuturesPosition fp : futuresPositions) {
            BigDecimal markPrice = assetValuationService.resolveFuturesPrice(fp.getSymbol());
            futuresValue = futuresValue.add(AssetValuationService.futuresPositionValue(fp, markPrice));
        }

        // 预测持仓按 bid 可变现价值计入总资产
        BigDecimal predictionValue = assetValuationService.predictionMarketValue(userId);

        BigDecimal gameBalance = user.getGameBalance() != null ? user.getGameBalance() : BigDecimal.ZERO;
        BigDecimal totalAssets = user.getBalance()
                .add(frozenBalance)
                .add(gameBalance)
                .add(marketValue)
                .add(pendingSettlement)
                .add(futuresValue)
                .add(predictionValue)
                .subtract(marginLoanPrincipal)
                .subtract(marginInterestAccrued);

        return getUserDTO(totalAssets, user, frozenBalance, marketValue, pendingSettlement, marginLoanPrincipal, marginInterestAccrued);
    }

    private UserDTO getUserDTO(BigDecimal totalAssets, User user,
                               BigDecimal frozenBalance,
                               BigDecimal positionMarketValue,
                               BigDecimal pendingSettlement,
                               BigDecimal marginLoanPrincipal,
                               BigDecimal marginInterestAccrued) {
        BigDecimal profit = totalAssets.subtract(initialBalance);
        BigDecimal profitPct = profit.divide(initialBalance, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));

        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setAvatar(user.getAvatar());
        dto.setBalance(user.getBalance());
        dto.setFrozenBalance(frozenBalance);
        dto.setGameBalance(user.getGameBalance() != null ? user.getGameBalance() : BigDecimal.ZERO);
        dto.setPositionMarketValue(positionMarketValue);
        dto.setPendingSettlement(pendingSettlement);
        dto.setMarginLoanPrincipal(marginLoanPrincipal);
        dto.setMarginInterestAccrued(marginInterestAccrued);
        dto.setBankrupt(Boolean.TRUE.equals(user.getIsBankrupt()));
        dto.setBankruptCount(user.getBankruptCount() != null ? user.getBankruptCount() : 0);
        dto.setBankruptResetDate(user.getBankruptResetDate());
        dto.setTotalAssets(totalAssets);
        dto.setProfit(profit);
        dto.setProfitPct(profitPct);
        return dto;
    }

    @Override
    public void updateBalance(Long userId, BigDecimal amount) {
        int affected = baseMapper.atomicUpdateBalance(userId, amount);
        if (affected == 0) {
            if (baseMapper.selectById(userId) == null) {
                throw new BizException(ErrorCode.USER_NOT_FOUND);
            }
            throw new BizException(ErrorCode.BALANCE_NOT_ENOUGH);
        }
        log.info("用户{}余额更新: {}", userId, amount);
    }

    @Override
    public void freezeBalance(Long userId, BigDecimal amount) {
        int affected = baseMapper.atomicFreezeBalance(userId, amount);
        if (affected == 0) {
            if (baseMapper.selectById(userId) == null) {
                throw new BizException(ErrorCode.USER_NOT_FOUND);
            }
            throw new BizException(ErrorCode.BALANCE_NOT_ENOUGH);
        }
        log.info("用户{}冻结余额: {}", userId, amount);
    }

    @Override
    public void unfreezeBalance(Long userId, BigDecimal amount) {
        int affected = baseMapper.atomicUnfreezeBalance(userId, amount);
        if (affected == 0) {
            if (baseMapper.selectById(userId) == null) {
                throw new BizException(ErrorCode.USER_NOT_FOUND);
            }
            throw new BizException(ErrorCode.FROZEN_BALANCE_NOT_ENOUGH);
        }
        log.info("用户{}解冻余额: {}", userId, amount);
    }

    @Override
    public void deductFrozenBalance(Long userId, BigDecimal amount) {
        int affected = baseMapper.atomicDeductFrozenBalance(userId, amount);
        if (affected == 0) {
            if (baseMapper.selectById(userId) == null) {
                throw new BizException(ErrorCode.USER_NOT_FOUND);
            }
            throw new BizException(ErrorCode.FROZEN_BALANCE_NOT_ENOUGH);
        }
        log.info("用户{}扣除冻结余额: {}", userId, amount);
    }

    @Override
    public BigDecimal getGameBalance(Long userId) {
        User user = baseMapper.selectById(userId);
        if (user == null) throw new BizException(ErrorCode.USER_NOT_FOUND);
        return user.getGameBalance() != null ? user.getGameBalance() : BigDecimal.ZERO;
    }

    @Override
    public void updateGameBalance(Long userId, BigDecimal amount) {
        int affected = baseMapper.atomicUpdateGameBalance(userId, amount);
        if (affected == 0) {
            if (baseMapper.selectById(userId) == null) {
                throw new BizException(ErrorCode.USER_NOT_FOUND);
            }
            throw new BizException(ErrorCode.GAME_BALANCE_NOT_ENOUGH);
        }
        log.info("用户{}游戏钱包更新: {}", userId, amount);
    }

    /** 划转手续费率 1%：转出方全额扣，到账 = amount − fee，手续费即销毁（平台无账户） */
    private static final BigDecimal TRANSFER_FEE_RATE = new BigDecimal("0.01");

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transferToGame(Long userId, BigDecimal amount) {
        BigDecimal fee = validateAndCalcFee(amount);
        int affected = baseMapper.atomicTransferToGame(userId, amount, amount.subtract(fee));
        if (affected == 0) {
            throw new BizException(ErrorCode.BALANCE_NOT_ENOUGH);
        }
        insertTransferLog(userId, WalletTransfer.TO_GAME, amount, fee);
        log.info("用户{}划转 余额→游戏: {} 手续费: {}", userId, amount, fee);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void transferToBalance(Long userId, BigDecimal amount) {
        BigDecimal fee = validateAndCalcFee(amount);
        int affected = baseMapper.atomicTransferToBalance(userId, amount, amount.subtract(fee));
        if (affected == 0) {
            throw new BizException(ErrorCode.GAME_BALANCE_NOT_ENOUGH);
        }
        insertTransferLog(userId, WalletTransfer.TO_BALANCE, amount, fee);
        log.info("用户{}划转 游戏→余额: {} 手续费: {}", userId, amount, fee);
    }

    private BigDecimal validateAndCalcFee(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.WALLET_TRANSFER_INVALID);
        }
        return amount.multiply(TRANSFER_FEE_RATE).setScale(2, RoundingMode.HALF_UP);
    }

    private void insertTransferLog(Long userId, String direction, BigDecimal amount, BigDecimal fee) {
        WalletTransfer transfer = new WalletTransfer();
        transfer.setUserId(userId);
        transfer.setDirection(direction);
        transfer.setAmount(amount);
        transfer.setFee(fee);
        walletTransferMapper.insert(transfer);
    }

}
