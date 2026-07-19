package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.BankruptcyService;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import static com.mawai.wiibsim.service.impl.FuturesHelper.calculatePnl;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrossMarginServiceImpl implements CrossMarginService {

    // 全局：持有全仓仓位的用户；按symbol：价格tick定向找人；按用户：记录其持仓symbol，刷新时做差集清理
    static final String CROSS_USERS_KEY = "futures:cross:users";
    static final String CROSS_SYM_PREFIX = "futures:cross:sym:";
    static final String CROSS_USER_SYMS_PREFIX = "futures:cross:user:";

    /** 最大可流出的贴线缓冲：不许转到正好压在强平线上 */
    private static final BigDecimal OUTFLOW_BUFFER = new BigDecimal("0.01");

    private final UserMapper userMapper;
    private final FuturesPositionMapper positionMapper;
    private final FuturesOrderMapper orderMapper;
    private final CacheService cacheService;
    private final FuturesLeverageBracketRegistry bracketRegistry;
    private final FuturesPositionIndexService positionIndexService;
    private final BankruptcyService bankruptcyService;
    private final StringRedisTemplate redis;

    @PostConstruct
    void init() {
        // 重建索引：Redis 里的旧成员 ∪ DB 实际持有人，逐个按 DB 实况刷新（多退少补，自愈脏数据）
        Set<String> stale = redis.opsForSet().members(CROSS_USERS_KEY);
        Set<Long> userIds = positionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                        .eq(FuturesPosition::getStatus, "OPEN")
                        .eq(FuturesPosition::getMarginMode, FuturesPosition.CROSS)
                        .select(FuturesPosition::getUserId))
                .stream().map(FuturesPosition::getUserId).collect(Collectors.toSet());
        if (stale != null) {
            stale.forEach(s -> userIds.add(Long.parseLong(s)));
        }
        userIds.forEach(this::refreshUserIndex);
        log.info("重建futures全仓索引 共{}个用户", userIds.size());
    }

    @Override
    public CrossAccount snapshot(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new BizException(ErrorCode.USER_NOT_FOUND);

        List<FuturesPosition> positions = positionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                .eq(FuturesPosition::getUserId, userId)
                .eq(FuturesPosition::getStatus, "OPEN")
                .eq(FuturesPosition::getMarginMode, FuturesPosition.CROSS));

        BigDecimal upnl = BigDecimal.ZERO;
        BigDecimal usedMargin = BigDecimal.ZERO;
        BigDecimal mm = BigDecimal.ZERO;
        for (FuturesPosition pos : positions) {
            BigDecimal price = resolvePrice(pos);
            upnl = upnl.add(calculatePnl(pos.getSide(), pos.getEntryPrice(), price, pos.getQuantity()));
            usedMargin = usedMargin.add(pos.getMargin());
            mm = mm.add(bracketRegistry.calcMaintenanceMargin(pos.getSymbol(), price.multiply(pos.getQuantity())));
        }

        BigDecimal pendingReserved = orderMapper.sumPendingCrossReserved(userId);
        return new CrossAccount(user.getBalance(), upnl, usedMargin, pendingReserved, mm, positions);
    }

    /** mark价优先、合约价兜底；都缺退回开仓价（浮盈亏按0算，别让快照因行情缺失炸掉） */
    private BigDecimal resolvePrice(FuturesPosition pos) {
        try {
            return FuturesHelper.markPrice(cacheService, pos.getSymbol());
        } catch (BizException e) {
            return pos.getEntryPrice();
        }
    }

    @Override
    public CrossAccount assertCanAfford(Long userId, BigDecimal cost) {
        CrossAccount account = snapshot(userId);
        if (account.available().compareTo(cost) < 0) {
            throw new BizException(ErrorCode.FUTURES_CROSS_AVAILABLE_NOT_ENOUGH);
        }
        return account;
    }

    @Override
    public void assertOutflowAllowed(Long userId, BigDecimal amount) {
        if (!hasCrossPositions(userId)) return;
        CrossAccount account = snapshot(userId);
        if (account.positions().isEmpty()) {
            refreshUserIndex(userId); // 索引残留，顺手自愈
            return;
        }
        // 流出后净值必须仍高于维持保证金，否则下一个tick就是强平——这不是提醒能解决的，直接拒
        if (account.equity().subtract(amount).compareTo(account.maintenanceMargin()) <= 0) {
            throw new BizException(ErrorCode.CROSS_OUTFLOW_BLOCKED);
        }
    }

    @Override
    public BigDecimal maxOutflow(Long userId) {
        if (!hasCrossPositions(userId)) return null;
        CrossAccount account = snapshot(userId);
        if (account.positions().isEmpty()) return null;
        return account.equity().subtract(account.maintenanceMargin()).subtract(OUTFLOW_BUFFER)
                .max(BigDecimal.ZERO);
    }

    @Override
    public BigDecimal estimateLiqPrice(FuturesPosition position, CrossAccount account) {
        // 兜底金 = 余额 + 其他仓位浮盈亏 − 其他仓位维持保证金（其他仓位价格按当下冻结）
        // 套逐仓静态公式：margin参数换成兜底金即可，本仓占用的margin本来就没离开余额，不重复计
        BigDecimal othersUpnl = BigDecimal.ZERO;
        BigDecimal othersMm = BigDecimal.ZERO;
        for (FuturesPosition other : account.positions()) {
            if (other.getId().equals(position.getId())) continue;
            BigDecimal price = resolvePrice(other);
            othersUpnl = othersUpnl.add(calculatePnl(other.getSide(), other.getEntryPrice(), price, other.getQuantity()));
            othersMm = othersMm.add(bracketRegistry.calcMaintenanceMargin(other.getSymbol(), price.multiply(other.getQuantity())));
        }
        BigDecimal backing = account.balance().add(othersUpnl).subtract(othersMm);
        return positionIndexService.calcStaticLiqPrice(position.getSymbol(), position.getSide(),
                position.getEntryPrice(), backing, position.getQuantity());
    }

    @Override
    public void settle(Long userId, BigDecimal delta) {
        if (delta.signum() != 0) {
            userMapper.atomicSettleBalance(userId, delta);
        }
        refreshUserIndex(userId);
        User user = userMapper.selectById(userId);
        if (user.getBalance().signum() < 0 && !hasCrossPositions(userId)) {
            log.warn("全仓穿仓落地 userId={} balance={} → 立即破产", userId, user.getBalance());
            bankruptcyService.bankruptNow(userId);
        }
    }

    @Override
    public boolean hasCrossPositions(Long userId) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(CROSS_USERS_KEY, userId.toString()));
    }

    @Override
    public void refreshUserIndex(Long userId) {
        Set<String> newSyms = positionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                        .eq(FuturesPosition::getUserId, userId)
                        .eq(FuturesPosition::getStatus, "OPEN")
                        .eq(FuturesPosition::getMarginMode, FuturesPosition.CROSS)
                        .select(FuturesPosition::getSymbol))
                .stream().map(FuturesPosition::getSymbol).collect(Collectors.toSet());

        String uid = userId.toString();
        String userSymsKey = CROSS_USER_SYMS_PREFIX + uid;
        Set<String> oldSyms = redis.opsForSet().members(userSymsKey);

        if (oldSyms != null) {
            for (String sym : oldSyms) {
                if (!newSyms.contains(sym)) redis.opsForSet().remove(CROSS_SYM_PREFIX + sym, uid);
            }
        }
        redis.delete(userSymsKey);
        if (newSyms.isEmpty()) {
            redis.opsForSet().remove(CROSS_USERS_KEY, uid);
            return;
        }
        for (String sym : newSyms) {
            redis.opsForSet().add(CROSS_SYM_PREFIX + sym, uid);
        }
        redis.opsForSet().add(userSymsKey, newSyms.toArray(String[]::new));
        redis.opsForSet().add(CROSS_USERS_KEY, uid);
    }

    @Override
    public Set<String> usersOnSymbol(String symbol) {
        Set<String> members = redis.opsForSet().members(CROSS_SYM_PREFIX + symbol);
        return members != null ? members : Set.of();
    }

    @Override
    public Set<String> allCrossUsers() {
        Set<String> members = redis.opsForSet().members(CROSS_USERS_KEY);
        return members != null ? members : Set.of();
    }
}
