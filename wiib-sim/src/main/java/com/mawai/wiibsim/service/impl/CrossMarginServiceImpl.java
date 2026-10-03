package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.dto.CrossSnapshotRow;
import com.mawai.wiibsim.ledger.Ledger;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.mawai.wiibcommon.enums.LedgerBizType.CROSS_SETTLE;
import static com.mawai.wiibsim.service.impl.FuturesHelper.calculatePnl;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrossMarginServiceImpl implements CrossMarginService {

    // 全局：持有全仓仓位的用户；按symbol：价格tick定向找人；按用户：记录其持仓symbol，刷新时做差集清理
    static final String CROSS_USERS_KEY = "futures:cross:users";
    static final String CROSS_SYM_PREFIX = "futures:cross:sym:";
    static final String CROSS_USER_SYMS_PREFIX = "futures:cross:user:";

    private final UserMapper userMapper;
    private final FuturesPositionMapper positionMapper;
    private final CacheService cacheService;
    private final FuturesLeverageBracketRegistry bracketRegistry;
    private final FuturesPositionIndexService positionIndexService;
    private final BankruptcyService bankruptcyService;
    private final StringRedisTemplate redis;
    private final CrossBandRegistry bandRegistry;

    /** userId → 索引刷新锁，只增不删 */
    private final ConcurrentHashMap<Long, Object> indexLocks = new ConcurrentHashMap<>();

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
        return snapshot(userId, null, null);
    }

    @Override
    public CrossAccount snapshot(Long userId, String pinSymbol, BigDecimal pinPrice) {
        // 三查合一（余额/持仓/挂单占用）：tick 巡检每用户每秒打一次 snapshot，
        // 拿池次数 3→1 是压测定的主优化（见 selectCrossSnapshot 注释）
        List<CrossSnapshotRow> rows = positionMapper.selectCrossSnapshot(userId);
        if (rows.isEmpty()) throw new BizException(ErrorCode.USER_NOT_FOUND);

        BigDecimal upnl = BigDecimal.ZERO;
        BigDecimal usedMargin = BigDecimal.ZERO;
        BigDecimal mm = BigDecimal.ZERO;
        List<FuturesPosition> positions = new ArrayList<>();
        // 每 symbol 只解析一次并记入 refPrices：安全带必须锚在快照实际所用价上，
        // 同 symbol 多仓（多空对冲）估值也因此严格同价。钉价 symbol 用 pinPrice（插针语义）
        Map<String, BigDecimal> refPrices = new HashMap<>();
        for (CrossSnapshotRow row : rows) {
            if (row.getPositionId() == null) continue; // LEFT JOIN 空行：有账号无全仓持仓
            FuturesPosition pos = toPosition(userId, row);
            BigDecimal price = refPrices.computeIfAbsent(pos.getSymbol(),
                    s -> s.equals(pinSymbol) ? pinPrice : resolvePrice(pos));
            upnl = upnl.add(calculatePnl(pos.getSide(), pos.getEntryPrice(), price, pos.getQuantity()));
            usedMargin = usedMargin.add(pos.getMargin());
            mm = mm.add(bracketRegistry.calcMaintenanceMargin(pos.getSymbol(), price.multiply(pos.getQuantity())));
            positions.add(pos);
        }

        CrossSnapshotRow head = rows.getFirst();
        return new CrossAccount(head.getBalance(), upnl, usedMargin, head.getPendingReserved(), mm, positions, refPrices);
    }

    /** 快照行还原为仓位对象。不含 SL/TP（快照消费方不读，见 CrossSnapshotRow 注释） */
    private static FuturesPosition toPosition(Long userId, CrossSnapshotRow row) {
        FuturesPosition p = new FuturesPosition();
        p.setId(row.getPositionId());
        p.setUserId(userId);
        p.setSymbol(row.getSymbol());
        p.setSide(row.getSide());
        p.setMarginMode(FuturesPosition.CROSS);
        p.setLeverage(row.getLeverage());
        p.setQuantity(row.getQuantity());
        p.setEntryPrice(row.getEntryPrice());
        p.setMargin(row.getMargin());
        p.setFundingFeeTotal(row.getFundingFeeTotal());
        p.setStatus("OPEN");
        return p;
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
        // 先锁 user 行再读快照，锁持有到调用方事务提交
        userMapper.selectByIdForUpdate(userId);
        CrossAccount account = snapshot(userId);
        if (account.available().compareTo(cost) < 0) {
            throw new BizException(ErrorCode.FUTURES_CROSS_AVAILABLE_NOT_ENOUGH);
        }
        return account;
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

    // 全仓的钱全从这一个口子结（平仓净额、资金费、SL/TP），所以类型标在方法上就够。
    // symbol 拿不到：本方法只收 userId+delta，调用方 frame 上的 symbol 又被本方法自己的 frame 盖住了
    // （currentSymbol 取栈顶）。全仓流水的币种要显示得改签名带进来，留给账单那一步定。
    @Override
    @Ledger(CROSS_SETTLE)
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
        return positionMapper.exists(new LambdaQueryWrapper<FuturesPosition>()
                .eq(FuturesPosition::getUserId, userId)
                .eq(FuturesPosition::getStatus, "OPEN")
                .eq(FuturesPosition::getMarginMode, FuturesPosition.CROSS));
    }

    @Override
    public void refreshUserIndex(Long userId) {
        // 全仓状态变动（开平仓/成交/SL·TP/强平/资金费/调杠杆）的汇合点：
        // 先作废安全带（事务内自动补提交后第二跳，竞态闭合见 bump 注释），再刷 Redis 索引
        bandRegistry.bump(userId);
        // 在事务里：挂到提交后再刷，回滚不刷；不在事务里：立即刷
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED) syncIndexFromDb(userId);
                }
            });
        } else {
            syncIndexFromDb(userId);
        }
    }

    /** 按库里的全仓持仓重写该用户的 Redis 索引。同一用户的读库+写 Redis 整体串行 */
    private void syncIndexFromDb(Long userId) {
        synchronized (indexLocks.computeIfAbsent(userId, k -> new Object())) {
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
