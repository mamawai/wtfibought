package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.mawai.wiibcommon.dto.UserDTO;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.entity.UserLedger;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.enums.LedgerBizType;
import com.mawai.wiibcommon.enums.LedgerWallet;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.ledger.Ledger;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserLedgerMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static com.mawai.wiibcommon.enums.LedgerBizType.WALLET_TRANSFER_IN;
import static com.mawai.wiibcommon.enums.LedgerBizType.WALLET_TRANSFER_OUT;

/**
 * 用户服务实现
 * 使用数据库原子操作保证并发安全
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    private final CryptoPositionService cryptoPositionService;
    private final FuturesPositionMapper futuresPositionMapper;
    private final AssetValuationService assetValuationService;
    private final UserLedgerMapper userLedgerMapper;

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
    @Transactional(rollbackFor = Exception.class)
    public void ensureAdminUser() {
        // 幂等：id=1 已存在则 ON CONFLICT 跳过；balance 用配置的初始资金
        int inserted = baseMapper.insertAdmin(initialBalance);
        baseMapper.syncIdSequence();
        // 只有真插进去了才补记。ON CONFLICT DO NOTHING 撞车时返 0（PG 实测 "INSERT 0 0"），
        // 而本方法每次启动/每次直登都会调一遍，不看这个返回值就是每次都多记一笔凭空的初始资金
        if (inserted > 0) {
            recordInitialGrant(1L, initialBalance);
        }
    }

    /**
     * 幂等建/取量化机器人账户。并发重复创建概率极低（quant 每策略仅首次取用时调一次），不加锁。
     * <p>
     * 事务边界在这层而不是 controller：建号 INSERT 与补记初始资金必须同生共死，
     * 只成一半就是一个账实不符、且此后再也发现不了的账户。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public User ensureQuantAccount(String username, BigDecimal initialBalance) {
        User existing = baseMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username).last("LIMIT 1"));
        if (existing != null) {
            return existing;   // 幂等：已有账户不重复入金
        }

        User user = new User();
        user.setUsername(username);
        user.setLinuxDoId("internal:" + username);   // 机器人标识，避开 OAuth 用户命名空间
        user.setBalance(initialBalance);
        user.setFrozenBalance(BigDecimal.ZERO);
        user.setIsBankrupt(false);
        user.setBankruptCount(0);
        baseMapper.insert(user);
        recordInitialGrant(user.getId(), initialBalance);
        log.info("创建量化账户 username={} userId={} balance={}", username, user.getId(), initialBalance);
        return user;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public User ensureGameAccount(String username, BigDecimal initialGameBalance) {
        User existing = baseMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username).last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        User user = new User();
        user.setUsername(username);
        user.setLinuxDoId("internal:" + username);
        user.setBalance(BigDecimal.ZERO);
        user.setFrozenBalance(BigDecimal.ZERO);
        user.setGameBalance(initialGameBalance);
        user.setIsBankrupt(false);
        user.setBankruptCount(0);
        baseMapper.insert(user);
        // 建号赠送记在游戏钱包上；不变量按 (user_id, wallet) 分别成立，交易钱包是 0 不用记
        UserLedger entry = new UserLedger();
        entry.setUserId(user.getId());
        entry.setWallet(LedgerWallet.GAME);
        entry.setBizType(LedgerBizType.INITIAL_GRANT);
        entry.setDelta(initialGameBalance);
        entry.setBalanceAfter(initialGameBalance);
        entry.setRemark("建号赠送（游戏钱包）");
        userLedgerMapper.insert(entry);
        log.info("创建游戏机器人账户 username={} userId={} gameBalance={}", username, user.getId(), initialGameBalance);
        return user;
    }

    /**
     * 补记建号赠送的初始资金。三个建号入口（OAuth 首登、邀请码注册、量化建号）加 admin 引导
     * 都走 INSERT，balance 是列值而不是 atomic* 调用，记账切面完全抓不到；
     * 不补这一笔，用户一落库就是 SUM(delta)=0 而 balance=10000，不变量当场破。
     */
    @Override
    public void recordInitialGrant(Long userId, BigDecimal balance) {
        UserLedger entry = new UserLedger();
        entry.setUserId(userId);
        entry.setWallet(LedgerWallet.BALANCE);
        entry.setBizType(LedgerBizType.INITIAL_GRANT);
        entry.setDelta(balance);
        entry.setBalanceAfter(balance);   // 建号那一刻余额就是这个数，不是估算
        entry.setRemark("建号赠送");
        userLedgerMapper.insert(entry);
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
                .add(futuresValue)
                .add(predictionValue)
                .subtract(marginLoanPrincipal)
                .subtract(marginInterestAccrued);

        return getUserDTO(totalAssets, user, frozenBalance, marketValue, marginLoanPrincipal, marginInterestAccrued);
    }

    private UserDTO getUserDTO(BigDecimal totalAssets, User user,
                               BigDecimal frozenBalance,
                               BigDecimal positionMarketValue,
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
        BigDecimal after = baseMapper.atomicUpdateBalance(userId, amount);
        if (after == null) {
            if (baseMapper.selectById(userId) == null) {
                throw new BizException(ErrorCode.USER_NOT_FOUND);
            }
            throw new BizException(ErrorCode.BALANCE_NOT_ENOUGH);
        }
        log.info("用户{}余额更新: {} 余额: {}", userId, amount, after);
    }

    @Override
    public void freezeBalance(Long userId, BigDecimal amount) {
        var r = baseMapper.atomicFreezeBalance(userId, amount);
        if (r == null) {
            if (baseMapper.selectById(userId) == null) {
                throw new BizException(ErrorCode.USER_NOT_FOUND);
            }
            throw new BizException(ErrorCode.BALANCE_NOT_ENOUGH);
        }
        log.info("用户{}冻结余额: {} 可用: {} 冻结: {}", userId, amount, r.balance(), r.frozenBalance());
    }

    @Override
    public void unfreezeBalance(Long userId, BigDecimal amount) {
        var r = baseMapper.atomicUnfreezeBalance(userId, amount);
        if (r == null) {
            if (baseMapper.selectById(userId) == null) {
                throw new BizException(ErrorCode.USER_NOT_FOUND);
            }
            throw new BizException(ErrorCode.FROZEN_BALANCE_NOT_ENOUGH);
        }
        log.info("用户{}解冻余额: {} 可用: {} 冻结: {}", userId, amount, r.balance(), r.frozenBalance());
    }

    @Override
    public void deductFrozenBalance(Long userId, BigDecimal amount) {
        BigDecimal afterFrozen = baseMapper.atomicDeductFrozenBalance(userId, amount);
        if (afterFrozen == null) {
            if (baseMapper.selectById(userId) == null) {
                throw new BizException(ErrorCode.USER_NOT_FOUND);
            }
            throw new BizException(ErrorCode.FROZEN_BALANCE_NOT_ENOUGH);
        }
        log.info("用户{}扣除冻结余额: {} 冻结: {}", userId, amount, afterFrozen);
    }

    @Override
    public BigDecimal getGameBalance(Long userId) {
        User user = baseMapper.selectById(userId);
        if (user == null) throw new BizException(ErrorCode.USER_NOT_FOUND);
        return user.getGameBalance() != null ? user.getGameBalance() : BigDecimal.ZERO;
    }

    @Override
    public void updateGameBalance(Long userId, BigDecimal amount) {
        BigDecimal afterGame = baseMapper.atomicUpdateGameBalance(userId, amount);
        if (afterGame == null) {
            if (baseMapper.selectById(userId) == null) {
                throw new BizException(ErrorCode.USER_NOT_FOUND);
            }
            throw new BizException(ErrorCode.GAME_BALANCE_NOT_ENOUGH);
        }
        log.info("用户{}游戏钱包更新: {} 游戏钱包: {}", userId, amount, afterGame);
    }

    /** 划转手续费率 1%：转出方全额扣，到账 = amount − fee，手续费即销毁（平台无账户） */
    private static final BigDecimal TRANSFER_FEE_RATE = new BigDecimal("0.01");

    // updateBalance/freezeBalance/... 这些通用方法刻意不标 @Ledger：它们是所有业务的公共出口，
    // 语义由调用方给（标在这里等于把全项目的流水都写成同一个类型）。只有划转这两个是自带语义的终点。
    //
    // 划转的唯一记录就是 @Ledger 落的那两条 user_ledger 行（原来另有一张 wallet_transfer 双轨表，
    // 职责被账本完全覆盖，已删）：转出钱包记 -amount、到账钱包记 +net，
    // 差额是被销毁的手续费，不落任何钱包——不变量是按 (user_id, wallet) 分别成立的，
    // 跨钱包不守恒本来就不在不变量里。
    @Override
    @Transactional(rollbackFor = Exception.class)
    @Ledger(WALLET_TRANSFER_OUT)
    public void transferToGame(Long userId, BigDecimal amount) {
        BigDecimal fee = validateAndCalcFee(amount);
        var r = baseMapper.atomicTransferToGame(userId, amount, amount.subtract(fee));
        if (r == null) {
            throw new BizException(ErrorCode.BALANCE_NOT_ENOUGH);
        }
        log.info("用户{}划转 余额→游戏: {} 手续费: {} 余额: {} 游戏钱包: {}",
                userId, amount, fee, r.balance(), r.gameBalance());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Ledger(WALLET_TRANSFER_IN)
    public void transferToBalance(Long userId, BigDecimal amount) {
        BigDecimal fee = validateAndCalcFee(amount);
        var r = baseMapper.atomicTransferToBalance(userId, amount, amount.subtract(fee));
        if (r == null) {
            throw new BizException(ErrorCode.GAME_BALANCE_NOT_ENOUGH);
        }
        log.info("用户{}划转 游戏→余额: {} 手续费: {} 余额: {} 游戏钱包: {}",
                userId, amount, fee, r.balance(), r.gameBalance());
    }

    private BigDecimal validateAndCalcFee(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.WALLET_TRANSFER_INVALID);
        }
        return amount.multiply(TRANSFER_FEE_RATE).setScale(2, RoundingMode.HALF_UP);
    }

}
