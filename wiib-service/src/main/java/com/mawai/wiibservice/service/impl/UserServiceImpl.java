package com.mawai.wiibservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mawai.wiibcommon.dto.OptionPositionDTO;
import com.mawai.wiibcommon.dto.UserDTO;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.Settlement;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibservice.mapper.CryptoOrderMapper;
import com.mawai.wiibservice.mapper.FuturesPositionMapper;
import com.mawai.wiibservice.mapper.UserMapper;
import com.mawai.wiibservice.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    private final PositionService positionService;
    private final SettlementService settlementService;
    private final CryptoPositionService cryptoPositionService;
    private final CryptoOrderMapper cryptoOrderMapper;
    private final FuturesPositionMapper futuresPositionMapper;
    private final CacheService cacheService;
    private final OptionPositionService optionPositionService;

    @Value("${trading.initial-balance:100000}")
    private BigDecimal initialBalance;

    @Override
    public User findByLinuxDoId(String linuxDoId) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getLinuxDoId, linuxDoId);
        return baseMapper.selectOne(wrapper);
    }

    @Override
    public UserDTO getUserPortfolio(Long userId) {
        User user = baseMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        BigDecimal marketValue = positionService.calculateTotalMarketValue(userId);

        // crypto持仓市值（取查询时刻的BTC实时价格）
        BigDecimal cryptoMarketValue = cryptoPositionService.calculateCryptoMarketValue(userId);
        marketValue = marketValue.add(cryptoMarketValue);

        BigDecimal frozenBalance = user.getFrozenBalance() != null ? user.getFrozenBalance() : BigDecimal.ZERO;
        BigDecimal marginLoanPrincipal = user.getMarginLoanPrincipal() != null ? user.getMarginLoanPrincipal() : BigDecimal.ZERO;
        BigDecimal marginInterestAccrued = user.getMarginInterestAccrued() != null ? user.getMarginInterestAccrued() : BigDecimal.ZERO;
        
        // 计算待结算金额
        BigDecimal pendingSettlement = settlementService.getPendingSettlements(userId).stream()
                .map(Settlement::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // crypto待结算（SETTLING状态）
        BigDecimal cryptoSettling = cryptoOrderMapper.sumSettlingAmount(userId);
        pendingSettlement = pendingSettlement.add(cryptoSettling);

        // 合约仓位: margin + unrealizedPnl
        BigDecimal futuresValue = BigDecimal.ZERO;
        List<FuturesPosition> futuresPositions = futuresPositionMapper.selectList(
                new LambdaQueryWrapper<FuturesPosition>()
                        .eq(FuturesPosition::getUserId, userId)
                        .eq(FuturesPosition::getStatus, "OPEN"));
        for (FuturesPosition fp : futuresPositions) {
            BigDecimal markPrice = cacheService.getMarkPrice(fp.getSymbol());
            if (markPrice == null) markPrice = cacheService.getCryptoPrice(fp.getSymbol());
            if (markPrice == null) {
                // 行情短缺时保留保证金，跳过浮盈亏
                futuresValue = futuresValue.add(fp.getMargin());
                continue;
            }
            BigDecimal unrealizedPnl = "LONG".equals(fp.getSide())
                    ? markPrice.subtract(fp.getEntryPrice()).multiply(fp.getQuantity())
                    : fp.getEntryPrice().subtract(markPrice).multiply(fp.getQuantity());
            futuresValue = futuresValue.add(fp.getMargin()).add(unrealizedPnl);
        }

        // 期权持仓市值
        BigDecimal optionValue = BigDecimal.ZERO;
        List<OptionPositionDTO> optionPositions = optionPositionService.getUserPositions(userId);
        for (OptionPositionDTO op : optionPositions) {
            optionValue = optionValue.add(op.getMarketValue());
        }

        BigDecimal totalAssets = user.getBalance()
                .add(frozenBalance)
                .add(marketValue)
                .add(pendingSettlement)
                .add(futuresValue)
                .add(optionValue)
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

}
