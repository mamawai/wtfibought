package com.mawai.wiibsim.service.impl;
import com.mawai.wiibcommon.broadcast.MarketBroadcaster;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mawai.wiibcommon.dto.*;
import com.mawai.wiibcommon.entity.PredictionBet;
import com.mawai.wiibcommon.entity.PredictionRound;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.ledger.Ledger;
import com.mawai.wiibsim.ledger.LedgerCtx;
import com.mawai.wiibsim.mapper.PredictionBetMapper;
import com.mawai.wiibsim.mapper.PredictionRoundMapper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.market.PolymarketPriceClient;
import com.mawai.wiibcommon.market.PredictionFee;
import com.mawai.wiibsim.service.PredictionService;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.RedisLockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.mawai.wiibcommon.enums.LedgerBizType.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PredictionServiceImpl implements PredictionService {

    private final PredictionRoundMapper roundMapper;
    private final PredictionBetMapper betMapper;
    private final UserService userService;
    private final CacheService cacheService;
    private final RedisLockUtil redisLockUtil;
    private final MarketBroadcaster broadcastService;
    private final TransactionTemplate transactionTemplate;
    private final PolymarketPriceClient priceClient;

    private static final BigDecimal MIN_AMOUNT = BigDecimal.ONE;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("10000");
    private static final int WINDOW_SECONDS = 300;
    /** 缺价等这么久还等不到就作废退本金 */
    private static final long VOID_AFTER_SECONDS = 3600;

    // ==================== 窗口时间 ====================

    /** 当前 5 分钟窗口起点(秒)；只有这个窗口的注单卖得掉、按盘口估值 */
    static long currentWindowStart() {
        long now = Instant.now().getEpochSecond();
        return now - (now % WINDOW_SECONDS);
    }

    private static long previousWindowStart() {
        return currentWindowStart() - WINDOW_SECONDS;
    }

    private static int remainingSeconds() {
        long now = Instant.now().getEpochSecond();
        return WINDOW_SECONDS - (int) (now % WINDOW_SECONDS);
    }

    // ==================== 转换 ====================

    private PredictionRoundResponse toRoundResponse(PredictionRound round) {
        PredictionRoundResponse resp = new PredictionRoundResponse();

        if (round == null) {
            resp.setWindowStart(currentWindowStart());
            resp.setStatus("OPEN");
            fillOfficialTime(resp);
            return resp;
        }

        resp.setId(round.getId());
        resp.setWindowStart(round.getWindowStart());
        resp.setStartPrice(round.getStartPrice());
        resp.setEndPrice(round.getEndPrice());
        resp.setOutcome(round.getOutcome());
        resp.setStatus(round.getStatus());
        fillOfficialTime(resp);
        return resp;
    }

    private void fillOfficialTime(PredictionRoundResponse resp) {
        long serverNowMs = System.currentTimeMillis();
        resp.setServerTimeMs(serverNowMs);

        CacheService.PredictionOfficialWindow official =
                cacheService.getPredictionOfficialWindow(resp.getWindowStart());
        if (official != null) {
            long officialNowMs = official.referenceNowMs()
                    + Math.max(0, serverNowMs - official.referenceLocalTimeMs());
            resp.setOfficialNowTimeMs(officialNowMs);
            resp.setOfficialStartTimeMs(official.startTimeMs());
            resp.setOfficialEndTimeMs(official.endTimeMs());
            long remainingMs = Math.max(0, official.endTimeMs() - officialNowMs);
            resp.setRemainingSeconds((int) Math.ceil(remainingMs / 1000.0));
            return;
        }

        resp.setRemainingSeconds(remainingSeconds());
    }

    private PredictionBetResponse toBetResponse(PredictionBet bet) {
        PredictionBetResponse resp = new PredictionBetResponse();
        resp.setId(bet.getId());
        resp.setRoundId(bet.getRoundId());
        resp.setWindowStart(bet.getWindowStart());
        resp.setSide(bet.getSide());
        resp.setContracts(bet.getContracts());
        resp.setCost(bet.getCost());
        resp.setAvgPrice(bet.getAvgPrice());
        resp.setPayout(bet.getPayout());
        resp.setStatus(bet.getStatus());
        resp.setCreatedAt(bet.getCreatedAt());

        // 当前价值 = 现在卖掉拿多少，只有本回合的注单有
        if ("ACTIVE".equals(bet.getStatus()) && bet.getWindowStart() == currentWindowStart()) {
            BigDecimal valPrice = cacheService.getPredictionBid(bet.getSide());
            if (valPrice != null) {
                resp.setCurrentValue(bet.getContracts().multiply(valPrice).setScale(4, RoundingMode.HALF_UP));
            }
        }
        return resp;
    }

    // ==================== 接口实现 ====================

    @Override
    public PredictionRoundResponse getCurrentRound() {
        long ws = currentWindowStart();
        PredictionRound round = roundMapper.selectOne(
                new LambdaQueryWrapper<PredictionRound>().eq(PredictionRound::getWindowStart, ws));
        if (round == null) {
            roundMapper.insertIfAbsent(ws, null);
            round = roundMapper.selectOne(
                    new LambdaQueryWrapper<PredictionRound>().eq(PredictionRound::getWindowStart, ws));
        }
        return toRoundResponse(round);
    }

    @Override
    @Ledger(PREDICTION_BUY)
    public PredictionBetResponse buy(Long userId, PredictionBuyRequest req) {
        String side = req.getSide();
        BigDecimal amount = req.getAmount();

        if ((!"UP".equals(side) && !"DOWN".equals(side))) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        if (amount == null || amount.compareTo(MIN_AMOUNT) < 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new BizException(ErrorCode.PREDICTION_AMOUNT_INVALID);
        }

        int remaining = remainingSeconds();
        if (remaining <= 0) {
            throw new BizException(ErrorCode.PREDICTION_ROUND_LOCKED);
        }

        BigDecimal price = cacheService.getPredictionAsk(side);
        if (price == null) {
            throw new BizException(ErrorCode.PREDICTION_PRICE_UNAVAILABLE);
        }

        long ws = currentWindowStart();
        String lockKey = "prediction:buy:" + ws + ":" + userId;

        // 广播刻意留在事务外——发出去就撤不回，事务回滚了广播已经出去就是假消息
        return redisLockUtil.executeWithLock(lockKey, 10, 3000, () -> {
            PredictionBetResponse response = transactionTemplate.execute(tx -> {
                PredictionRound round = roundMapper.selectOne(
                        new LambdaQueryWrapper<PredictionRound>().eq(PredictionRound::getWindowStart, ws));
                if (round == null) {
                    throw new BizException(ErrorCode.PREDICTION_ROUND_LOCKED);
                }
                if (!"OPEN".equals(round.getStatus())) {
                    throw new BizException(ErrorCode.PREDICTION_ROUND_LOCKED);
                }

                BigDecimal contracts = amount.divide(price, 4, RoundingMode.DOWN);
                BigDecimal cost = contracts.multiply(price).setScale(4, RoundingMode.HALF_UP);
                // Polymarket 吃单费，公式见 PredictionFee
                BigDecimal commission = PredictionFee.commission(contracts, price);
                BigDecimal totalDeduct = cost.add(commission);

                userService.updateGameBalance(userId, totalDeduct.negate());

                PredictionBet bet = new PredictionBet();
                bet.setUserId(userId);
                bet.setRoundId(round.getId());
                bet.setWindowStart(round.getWindowStart());
                bet.setSide(side);
                bet.setContracts(contracts);
                bet.setCost(cost);
                bet.setAvgPrice(price);
                bet.setStatus("ACTIVE");
                betMapper.insert(bet);

                return toBetResponse(bet);
            });

            if (response == null) {
                throw new IllegalStateException("prediction buy transaction returned null");
            }

            broadcastOurActivity(userId, side, response.getCost());
            return response;
        });
    }

    @Override
    @Ledger(PREDICTION_SELL)
    public PredictionBetResponse sell(Long userId, Long betId, BigDecimal contracts) {
        String lockKey = "prediction:sell:" + betId;
        return redisLockUtil.executeWithLock(lockKey, 10, 3000, () -> {
            PredictionBetResponse response = transactionTemplate.execute(tx -> {
                PredictionBet bet = betMapper.selectById(betId);
                if (bet == null || !bet.getUserId().equals(userId)) {
                    throw new BizException(ErrorCode.PREDICTION_BET_NOT_FOUND);
                }
                if (!"ACTIVE".equals(bet.getStatus())) {
                    throw new BizException(ErrorCode.PREDICTION_BET_NOT_FOUND);
                }

                // 卖价取的是全局当前盘口（不分回合），所以只卖得掉当前窗口这一回合的注单：
                // 巡检还没扫到的那几分钟里，卡在 OPEN 的旧回合结果早已定死，不挡就是照今天的价卖已知结果
                PredictionRound round = roundMapper.selectById(bet.getRoundId());
                if (round == null || !"OPEN".equals(round.getStatus())
                        || round.getWindowStart() != currentWindowStart()) {
                    throw new BizException(ErrorCode.PREDICTION_ROUND_LOCKED);
                }

                BigDecimal currentPrice = cacheService.getPredictionBid(bet.getSide());
                if (currentPrice == null) {
                    throw new BizException(ErrorCode.PREDICTION_PRICE_UNAVAILABLE);
                }

                BigDecimal sellContracts = contracts == null ? bet.getContracts() : contracts;
                if (sellContracts.compareTo(BigDecimal.ZERO) <= 0 || sellContracts.compareTo(bet.getContracts()) > 0) {
                    throw new BizException(ErrorCode.PARAM_ERROR);
                }

                BigDecimal revenue = sellContracts.multiply(currentPrice).setScale(4, RoundingMode.HALF_UP);
                BigDecimal commission = PredictionFee.commission(sellContracts, currentPrice);
                BigDecimal netRevenue = revenue.subtract(commission);

                boolean fullSell = sellContracts.compareTo(bet.getContracts()) == 0;
                int affected;
                BigDecimal soldCost = bet.getCost();
                if (fullSell) {
                    affected = betMapper.casSell(bet.getId(), netRevenue);
                } else {
                    soldCost = bet.getCost().multiply(sellContracts)
                            .divide(bet.getContracts(), 4, RoundingMode.HALF_UP);
                    affected = betMapper.casPartialSell(bet.getId(), sellContracts, soldCost);
                }
                if (affected == 0) {
                    throw new BizException(ErrorCode.PREDICTION_BET_NOT_FOUND);
                }

                PredictionBet soldBet = null;
                if (!fullSell) {
                    soldBet = new PredictionBet();
                    soldBet.setUserId(bet.getUserId());
                    soldBet.setRoundId(bet.getRoundId());
                    soldBet.setWindowStart(bet.getWindowStart());
                    soldBet.setSide(bet.getSide());
                    soldBet.setContracts(sellContracts);
                    soldBet.setCost(soldCost);
                    soldBet.setAvgPrice(soldCost.divide(sellContracts, 4, RoundingMode.HALF_UP));
                    soldBet.setPayout(netRevenue);
                    soldBet.setStatus("SOLD");
                    betMapper.insert(soldBet);
                }

                userService.updateGameBalance(userId, netRevenue);

                bet.setStatus(fullSell ? "SOLD" : "ACTIVE");
                bet.setContracts(fullSell ? bet.getContracts() : bet.getContracts().subtract(sellContracts));
                bet.setCost(fullSell ? bet.getCost() : bet.getCost().subtract(soldCost));
                bet.setPayout(netRevenue);
                return toBetResponse(fullSell ? bet : soldBet);
            });

            if (response == null) {
                throw new IllegalStateException("prediction sell transaction returned null");
            }
            return response;
        });
    }

    @Override
    public IPage<PredictionBetResponse> getUserBets(Long userId, int pageNum, int pageSize) {
        Page<PredictionBet> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<PredictionBet> wrapper = new LambdaQueryWrapper<PredictionBet>()
                .eq(PredictionBet::getUserId, userId)
                .orderByDesc(PredictionBet::getCreatedAt);
        IPage<PredictionBet> betPage = betMapper.selectPage(page, wrapper);
        return betPage.convert(this::toBetResponse);
    }

    @Override
    public IPage<PredictionRoundResponse> getSettledRounds(int pageNum, int pageSize) {
        Page<PredictionRound> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<PredictionRound> wrapper = new LambdaQueryWrapper<PredictionRound>()
                .eq(PredictionRound::getStatus, "SETTLED")
                .orderByDesc(PredictionRound::getWindowStart);
        IPage<PredictionRound> roundPage = roundMapper.selectPage(page, wrapper);
        return roundPage.convert(this::toRoundResponse);
    }

    @Override
    public List<PredictionBetLiveResponse> getLiveActivity() {
        Page<PredictionBet> page = new Page<>(1, 20);
        LambdaQueryWrapper<PredictionBet> wrapper = new LambdaQueryWrapper<PredictionBet>()
                .orderByDesc(PredictionBet::getCreatedAt);
        List<PredictionBet> bets = betMapper.selectPage(page, wrapper).getRecords();
        if (bets.isEmpty()) return List.of();

        // 批量查用户，避免N+1
        List<Long> userIds = bets.stream().map(PredictionBet::getUserId).distinct().toList();
        Map<Long, User> userMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        List<PredictionBetLiveResponse> result = new ArrayList<>();
        for (PredictionBet bet : bets) {
            User user = userMap.get(bet.getUserId());
            if (user == null) continue;
            PredictionBetLiveResponse live = new PredictionBetLiveResponse();
            live.setUsername(maskUsername(user.getUsername()));
            live.setAvatar(user.getAvatar());
            live.setSide(bet.getSide());
            live.setAmount(bet.getCost());
            live.setCreatedAt(bet.getCreatedAt());
            result.add(live);
        }
        return result;
    }

    @Override
    public PredictionPnlResponse getUserPnl(Long userId) {
        List<PredictionBet> bets = betMapper.selectList(
                new LambdaQueryWrapper<PredictionBet>().eq(PredictionBet::getUserId, userId));

        long ws = currentWindowStart();
        int total = 0, won = 0, lost = 0, active = 0;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal realizedPnl = BigDecimal.ZERO;
        BigDecimal activeCost = BigDecimal.ZERO;
        BigDecimal activeValue = BigDecimal.ZERO;
        int settled = 0;

        for (PredictionBet bet : bets) {
            total++;
            totalCost = totalCost.add(bet.getCost());
            switch (bet.getStatus()) {
                case "WON" -> {
                    won++;
                    settled++;
                    realizedPnl = realizedPnl.add(bet.getPayout().subtract(bet.getCost()));
                }
                case "LOST" -> {
                    lost++;
                    settled++;
                    realizedPnl = realizedPnl.add(bet.getPayout().subtract(bet.getCost()));
                }
                case "DRAW" -> settled++;
                case "SOLD" -> {
                    settled++;
                    realizedPnl = realizedPnl.add(bet.getPayout().subtract(bet.getCost()));
                }
                case "CANCELLED" -> realizedPnl = realizedPnl.subtract(bet.getCost());
                case "ACTIVE" -> {
                    active++;
                    activeCost = activeCost.add(bet.getCost());
                    activeValue = activeValue.add(AssetValuationService.predictionBetValue(
                            bet, cacheService.getPredictionBid(bet.getSide()), ws).setScale(4, RoundingMode.HALF_UP));
                }
                default -> { }
            }
        }

        BigDecimal unrealizedPnl = activeValue.subtract(activeCost);
        BigDecimal totalPnl = realizedPnl.add(unrealizedPnl);

        BigDecimal winRate = BigDecimal.ZERO;
        if (settled > 0) {
            winRate = new BigDecimal(won).divide(new BigDecimal(settled), 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
        }

        PredictionPnlResponse resp = new PredictionPnlResponse();
        resp.setTotalBets(total);
        resp.setActiveBets(active);
        resp.setWonBets(won);
        resp.setLostBets(lost);
        resp.setTotalCost(totalCost.setScale(2, RoundingMode.HALF_UP));
        resp.setRealizedPnl(realizedPnl.setScale(2, RoundingMode.HALF_UP));
        resp.setActiveCost(activeCost.setScale(2, RoundingMode.HALF_UP));
        resp.setActiveValue(activeValue.setScale(2, RoundingMode.HALF_UP));
        resp.setTotalPnl(totalPnl.setScale(2, RoundingMode.HALF_UP));
        resp.setWinRate(winRate);
        return resp;
    }

    @Override
    public List<Map<String, Object>> getPriceHistory() {
        long fromMs = System.currentTimeMillis() - WINDOW_SECONDS * 1000L;
        return cacheService.getBtcTwapHistory(fromMs);
    }

    // ==================== 调度 ====================

    @Override
    public void createNewRound() {
        long ws = currentWindowStart();
        BigDecimal startPrice = cacheService.getPolymarketOpenPrice(ws);
        roundMapper.insertIfAbsent(ws, startPrice);
        PredictionRound round = roundMapper.selectOne(
                new LambdaQueryWrapper<PredictionRound>().eq(PredictionRound::getWindowStart, ws));
        if (round != null) {
            log.info("新回合已创建: windowStart={}, startPrice={}", ws, round.getStartPrice());
            broadcastRoundUpdate(round);
        }
    }

    @Override
    public void lockRound(long windowStart) {
        int affected = roundMapper.casLockRound(windowStart);
        if (affected > 0) {
            PredictionRound round = roundMapper.selectOne(
                    new LambdaQueryWrapper<PredictionRound>().eq(PredictionRound::getWindowStart, windowStart));
            if (round != null) {
                log.info("回合已锁定: windowStart={}", windowStart);
                broadcastRoundUpdate(round);
            }
        }
    }

    @Override
    public void syncOpenPrice() {
        long ws = currentWindowStart();
        BigDecimal openPrice = cacheService.getPolymarketOpenPrice(ws);
        if (openPrice == null) return;
        PredictionRound round = roundMapper.selectOne(
                new LambdaQueryWrapper<PredictionRound>().eq(PredictionRound::getWindowStart, ws));
        if (round == null || !"OPEN".equals(round.getStatus())) return;
        if (round.getStartPrice() != null && openPrice.compareTo(round.getStartPrice()) == 0) return;

        int affected = roundMapper.updateStartPrice(ws, openPrice);
        if (affected > 0) {
            log.info("openPrice已更新: windowStart={}, {} -> {}", ws, round.getStartPrice(), openPrice);
            round.setStartPrice(openPrice);
            broadcastRoundUpdate(round);
        }
    }

    @Override
    public void settleRound(long windowStart) {
        String settleKey = "prediction:settle:" + windowStart;
        String lockVal = redisLockUtil.tryLock(settleKey, 60);
        if (lockVal == null) return;

        PredictionRound settled;
        try {
            settled = doSettle(windowStart);
        } finally {
            redisLockUtil.unlock(settleKey, lockVal);
        }

        if (settled != null) {
            broadcastRoundUpdate(settled);
        }
    }

    /** 取价 → 定盘 → 派彩。取价刻意放事务外：REST 最多等 8 秒，不能攥着数据库事务等它。 */
    private PredictionRound doSettle(long windowStart) {
        PredictionRound round = roundMapper.selectOne(
                new LambdaQueryWrapper<PredictionRound>().eq(PredictionRound::getWindowStart, windowStart));
        if (round == null || !"LOCKED".equals(round.getStatus())) return null;

        BigDecimal endPrice = cacheService.getPolymarketClosePrice(windowStart);
        // startPrice 可能一直是空：建行时 openPrice 还没到，syncOpenPrice 又没赶在锁定前回填
        BigDecimal startPrice = round.getStartPrice() != null
                ? round.getStartPrice()
                : cacheService.getPolymarketOpenPrice(windowStart);

        // 缓存 TTL 只有 20 分钟，补结算时多半已过期；回源 REST，一次响应开收盘价都带，顺手写回缓存
        if (startPrice == null || endPrice == null) {
            PolymarketPriceClient.CryptoPrice price = priceClient.fetch(windowStart);
            if (price != null) {
                if (startPrice == null && price.openPrice() != null) {
                    startPrice = price.openPrice();
                    cacheService.putPolymarketOpenPrice(windowStart, startPrice);
                }
                // completed=false 说明这窗口还没收官，closePrice 不作数
                if (endPrice == null && price.completed() && price.closePrice() != null) {
                    endPrice = price.closePrice();
                    cacheService.putPolymarketClosePrice(windowStart, endPrice);
                }
            }
        }

        if (startPrice == null || endPrice == null) {
            return voidRound(round, windowStart);
        }
        return settleWithPrice(round, windowStart, startPrice, endPrice);
    }

    private PredictionRound settleWithPrice(PredictionRound round, long windowStart,
                                            BigDecimal startPrice, BigDecimal endPrice) {
        // 【事务粒度：整个回合一个事务，不是每个用户一个】
        // 这里不是"一批互相独立的任务"，而是一件事：回合定盘 + 全部注单改状态 + 全部派彩。
        // 注单状态是 settleDraw/settleWon/settleLost 三条批量 UPDATE 一次刷完的，
        // 拆成按用户提交的话，状态先落地、派彩后失败 = 有人标了 WON 却没拿到钱，
        // 而 casSettleRound 已把回合置 SETTLED，这笔钱静默丢掉。
        // 失败整批回滚回 LOCKED，回合留给 sweepStuckRounds 每 5 分钟重跑；
        // casSettleRound(WHERE status='LOCKED') 就是重跑的幂等边界，派彩不会来第二遍。
        return transactionTemplate.execute(status -> {
            // 锁定后 updateStartPrice(WHERE status='OPEN') 已经够不着这行，另走一条 CAS 补
            if (round.getStartPrice() == null) {
                roundMapper.fillStartPrice(round.getId(), startPrice);
                round.setStartPrice(startPrice);
            }

            // Polymarket 规则：窗口末 60 秒 Chainlink TWAP 不低于开盘时的 60 秒 TWAP 判 Up（相等算 Up），否则 Down；
            // 开收盘价都按 TWAP 口径取（PolymarketPriceClient 带了 twap 参数），没有平局
            String outcome = endPrice.compareTo(startPrice) >= 0 ? "UP" : "DOWN";
            String losingSide = "UP".equals(outcome) ? "DOWN" : "UP";

            int affected = roundMapper.casSettleRound(round.getId(), endPrice, outcome);
            if (affected == 0) return null;

            log.info("回合结算: windowStart={}, start={}, end={}, outcome={}",
                    windowStart, startPrice, endPrice, outcome);

            betMapper.settleWon(round.getId(), outcome);
            betMapper.settleLost(round.getId(), losingSide);

            List<PredictionBet> wonBets = betMapper.selectList(
                    new LambdaQueryWrapper<PredictionBet>()
                            .eq(PredictionBet::getRoundId, round.getId())
                            .eq(PredictionBet::getStatus, "WON"));
            // mark 在循环体内、每笔之前，理由见 refundActiveBets
            for (PredictionBet bet : wonBets) {
                LedgerCtx.mark(PREDICTION_SETTLE, "PREDICTION_BET", bet.getId());
                userService.updateGameBalance(bet.getUserId(), bet.getContracts());
            }

            round.setEndPrice(endPrice);
            round.setOutcome(outcome);
            round.setStatus("SETTLED");
            return round;
        });
    }

    /**
     * 价格实在拿不到时的兜底：回合作废、退本金。end_price 留空、outcome='VOID'，
     * 注单走 DRAW 那条路（payout=cost）——PnL 和排行榜 SQL 已把 DRAW 当已结算、盈亏 0，不用改。
     */
    private PredictionRound voidRound(PredictionRound round, long windowStart) {
        Long activeCount = betMapper.selectCount(new LambdaQueryWrapper<PredictionBet>()
                .eq(PredictionBet::getRoundId, round.getId())
                .eq(PredictionBet::getStatus, "ACTIVE"));

        // 有钱压着就先等：Polymarket 晚出数据是常事，急着作废等于把该赢的判成退本金。
        // 等满一小时还没有就认它没了——本金不能无限期冻着。
        // 没注单的回合不涉及钱，但也得过了两个窗口再作废：settle 事件路径（刚收官几十秒）
        // 偶发缓存和 REST 同时缺价，不该把本可正常结算的回合标成作废，留给巡检下一轮就够。
        long ageSeconds = Instant.now().getEpochSecond() - windowStart;
        long waitSeconds = activeCount > 0 ? VOID_AFTER_SECONDS : 2L * WINDOW_SECONDS;
        if (ageSeconds < waitSeconds) {
            log.warn("[Prediction] 结算缺价，留给下次巡检: windowStart={}, activeBets={}", windowStart, activeCount);
            return null;
        }

        return transactionTemplate.execute(status -> {
            int affected = roundMapper.casVoidRound(round.getId());
            if (affected == 0) return null;

            log.warn("[Prediction] 回合作废（取不到价）: windowStart={}, activeBets={}", windowStart, activeCount);
            refundActiveBets(round.getId());

            round.setOutcome("VOID");
            round.setStatus("SETTLED");
            return round;
        });
    }

    /** 退本金：注单 ACTIVE→DRAW(payout=cost) 后逐笔退钱。只有作废走这里，DRAW 状态沿用作退款标记 */
    private void refundActiveBets(Long roundId) {
        betMapper.settleDraw(roundId);
        List<PredictionBet> refundBets = betMapper.selectList(
                new LambdaQueryWrapper<PredictionBet>()
                        .eq(PredictionBet::getRoundId, roundId)
                        .eq(PredictionBet::getStatus, "DRAW"));
        // mark 必须写在循环体内、每笔之前：它是"消费即清"的一次性标注，
        // 提到循环外只有第一个用户拿到 PREDICTION_REFUND，其余全部静默落 UNKNOWN
        // （本方法表达不了两种类型，刻意没有方法级 @Ledger 兜底）
        for (PredictionBet bet : refundBets) {
            LedgerCtx.mark(PREDICTION_REFUND, "PREDICTION_BET", bet.getId());
            userService.updateGameBalance(bet.getUserId(), bet.getCost());
        }
    }

    /**
     * 补结算巡检：捞出窗口早该结束、却还停在 OPEN / LOCKED 的回合，补锁并重跑结算。
     * lock 与 settle 都靠 Stream 事件单次触发（feed 跨窗口重启只发 create、不补发 lock），
     * 漏一次这个回合就没人管了——买入扣的 cost + commission 既卖不掉也退不了。
     * 重跑口径：缺价回源 REST，实在没价的按 VOID 退本金。
     */
    @Override
    public void sweepStuckRounds() {
        // 阈值取上一窗口的起点：prevWs 那个回合正被正常结算，合法地停在 LOCKED，必须排除
        long staleBefore = previousWindowStart();
        for (PredictionRound round : roundMapper.selectUnsettledBefore(staleBefore)) {
            // doSettle 只认 LOCKED，OPEN 的先补一次锁；casLockRound 的 WHERE 带 status='OPEN'，重复跑无害
            if ("OPEN".equals(round.getStatus())) {
                lockRound(round.getWindowStart());
            }
            settleRound(round.getWindowStart());
        }
    }

    // ==================== 广播 ====================

    private void broadcastRoundUpdate(PredictionRound round) {
        try {
            PredictionRoundResponse resp = toRoundResponse(round);
            String json = roundToJson(resp);
            broadcastService.broadcastPrediction("round", json);
        } catch (Exception e) {
            log.warn("广播round更新失败", e);
        }
    }

    private void broadcastOurActivity(Long userId, String side, BigDecimal cost) {
        try {
            User user = userService.getById(userId);
            if (user == null) return;
            String json = "{\"username\":\"" + maskUsername(user.getUsername())
                    + "\",\"avatar\":\"" + (user.getAvatar() != null ? user.getAvatar() : "")
                    + "\",\"side\":\"" + side
                    + "\",\"amount\":" + cost.setScale(2, RoundingMode.HALF_UP)
                    + ",\"source\":\"local\""
                    + ",\"ts\":" + System.currentTimeMillis() + "}";
            broadcastService.broadcastPrediction("activity", json);
        } catch (Exception e) {
            log.warn("广播activity失败", e);
        }
    }

    private String roundToJson(PredictionRoundResponse resp) {
        StringBuilder sb = new StringBuilder("{");
        if (resp.getId() != null) sb.append("\"id\":").append(resp.getId()).append(",");
        sb.append("\"windowStart\":").append(resp.getWindowStart());
        if (resp.getStartPrice() != null) sb.append(",\"startPrice\":\"").append(resp.getStartPrice()).append("\"");
        if (resp.getEndPrice() != null) sb.append(",\"endPrice\":\"").append(resp.getEndPrice()).append("\"");
        if (resp.getOutcome() != null) sb.append(",\"outcome\":\"").append(resp.getOutcome()).append("\"");
        sb.append(",\"status\":\"").append(resp.getStatus()).append("\"");
        sb.append(",\"remainingSeconds\":").append(resp.getRemainingSeconds());
        if (resp.getServerTimeMs() != null) sb.append(",\"serverTimeMs\":").append(resp.getServerTimeMs());
        if (resp.getOfficialNowTimeMs() != null) sb.append(",\"officialNowTimeMs\":").append(resp.getOfficialNowTimeMs());
        if (resp.getOfficialStartTimeMs() != null) {
            sb.append(",\"officialStartTimeMs\":").append(resp.getOfficialStartTimeMs());
        }
        if (resp.getOfficialEndTimeMs() != null) {
            sb.append(",\"officialEndTimeMs\":").append(resp.getOfficialEndTimeMs());
        }
        sb.append("}");
        return sb.toString();
    }

    private static String maskUsername(String username) {
        if (username == null || username.length() <= 2) return username;
        return username.substring(0, 2) + "***";
    }
}
