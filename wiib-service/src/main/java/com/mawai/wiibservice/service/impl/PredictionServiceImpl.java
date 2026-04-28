package com.mawai.wiibservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mawai.wiibcommon.dto.*;
import com.mawai.wiibcommon.entity.PredictionBet;
import com.mawai.wiibcommon.entity.PredictionRound;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibservice.mapper.PredictionBetMapper;
import com.mawai.wiibservice.mapper.PredictionRoundMapper;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.PredictionService;
import com.mawai.wiibservice.service.UserService;
import com.mawai.wiibservice.util.RedisLockUtil;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class PredictionServiceImpl implements PredictionService {

    private final PredictionRoundMapper roundMapper;
    private final PredictionBetMapper betMapper;
    private final UserService userService;
    private final CacheService cacheService;
    private final RedisLockUtil redisLockUtil;
    private final RedisMessageBroadcastService broadcastService;
    private final TransactionTemplate transactionTemplate;

    private static final BigDecimal FEE_RATE = new BigDecimal("0.25");
    private static final BigDecimal MIN_FEE_RATE = new BigDecimal("0.001");
    private static final BigDecimal MAX_FEE_RATE = new BigDecimal("0.02");
    private static final BigDecimal MIN_AMOUNT = BigDecimal.ONE;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("10000");
    private static final int WINDOW_SECONDS = 300;

    /** effectiveRate = 0.25 × (p×(1-p))², clamp [0.1%, 2%] */
    private static BigDecimal calcFeeRate(BigDecimal p) {
        BigDecimal pq = p.multiply(BigDecimal.ONE.subtract(p));
        BigDecimal rate = FEE_RATE.multiply(pq.pow(2));
        if (rate.compareTo(MIN_FEE_RATE) < 0) return MIN_FEE_RATE;
        if (rate.compareTo(MAX_FEE_RATE) > 0) return MAX_FEE_RATE;
        return rate;
    }

    // ==================== 窗口时间 ====================

    private static long currentWindowStart() {
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
        BigDecimal upPrice = cacheService.getPredictionAsk("UP");
        BigDecimal downPrice = cacheService.getPredictionAsk("DOWN");

        if (round == null) {
            resp.setWindowStart(currentWindowStart());
            resp.setUpPrice(upPrice);
            resp.setDownPrice(downPrice);
            resp.setStatus("OPEN");
            fillOfficialTime(resp);
            return resp;
        }

        resp.setId(round.getId());
        resp.setWindowStart(round.getWindowStart());
        resp.setStartPrice(round.getStartPrice());
        resp.setEndPrice(round.getEndPrice());
        resp.setOutcome(round.getOutcome());
        resp.setUpPrice(upPrice);
        resp.setDownPrice(downPrice);
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

        if ("ACTIVE".equals(bet.getStatus())) {
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
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.PREDICTION_PRICE_UNAVAILABLE);
        }

        long ws = currentWindowStart();
        String lockKey = "prediction:buy:" + ws + ":" + userId;

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
                BigDecimal commission = cost.multiply(calcFeeRate(price)).setScale(4, RoundingMode.HALF_UP);
                BigDecimal totalDeduct = cost.add(commission);

                userService.updateBalance(userId, totalDeduct.negate());

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

                PredictionRound round = roundMapper.selectById(bet.getRoundId());
                if (round == null || !"OPEN".equals(round.getStatus())) {
                    throw new BizException(ErrorCode.PREDICTION_ROUND_LOCKED);
                }

                BigDecimal currentPrice = cacheService.getPredictionBid(bet.getSide());
                if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BizException(ErrorCode.PREDICTION_PRICE_UNAVAILABLE);
                }

                BigDecimal sellContracts = contracts == null ? bet.getContracts() : contracts;
                if (sellContracts.compareTo(BigDecimal.ZERO) <= 0 || sellContracts.compareTo(bet.getContracts()) > 0) {
                    throw new BizException(ErrorCode.PARAM_ERROR);
                }

                BigDecimal revenue = sellContracts.multiply(currentPrice).setScale(4, RoundingMode.HALF_UP);
                BigDecimal commission = revenue.multiply(calcFeeRate(currentPrice)).setScale(4, RoundingMode.HALF_UP);
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

                userService.updateBalance(userId, netRevenue);

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
                    BigDecimal bidPrice = cacheService.getPredictionBid(bet.getSide());
                    if (bidPrice != null) {
                        activeValue = activeValue.add(
                                bet.getContracts().multiply(bidPrice).setScale(4, RoundingMode.HALF_UP));
                    }
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
        return cacheService.getBtcPriceHistory(fromMs);
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
    public void settlePreviousRound() {
        long prevWs = previousWindowStart();
        BigDecimal endPrice = cacheService.getPolymarketClosePrice(prevWs);
        if (endPrice == null) {
            log.debug("closePrice未就绪, windowStart={}", prevWs);
            return;
        }

        String settleKey = "prediction:settle:" + prevWs;
        String lockVal = redisLockUtil.tryLock(settleKey, 60);
        if (lockVal == null) return;

        PredictionRound settled;
        try {
            settled = transactionTemplate.execute(status -> {
                PredictionRound round = roundMapper.selectOne(
                        new LambdaQueryWrapper<PredictionRound>().eq(PredictionRound::getWindowStart, prevWs));
                if (round == null || !"LOCKED".equals(round.getStatus())) return null;

                String outcome;
                int cmp = endPrice.compareTo(round.getStartPrice());
                if (cmp > 0) outcome = "UP";
                else if (cmp < 0) outcome = "DOWN";
                else outcome = "DRAW";

                int affected = roundMapper.casSettleRound(round.getId(), endPrice, outcome);
                if (affected == 0) return null;

                log.info("回合结算: windowStart={}, start={}, end={}, outcome={}",
                        prevWs, round.getStartPrice(), endPrice, outcome);

                if ("DRAW".equals(outcome)) {
                    betMapper.settleDraw(round.getId());
                    List<PredictionBet> drawBets = betMapper.selectList(
                            new LambdaQueryWrapper<PredictionBet>()
                                    .eq(PredictionBet::getRoundId, round.getId())
                                    .eq(PredictionBet::getStatus, "DRAW"));
                    for (PredictionBet bet : drawBets) {
                        userService.updateBalance(bet.getUserId(), bet.getCost());
                    }
                } else {
                    String losingSide = "UP".equals(outcome) ? "DOWN" : "UP";
                    betMapper.settleWon(round.getId(), outcome);
                    betMapper.settleLost(round.getId(), losingSide);

                    List<PredictionBet> wonBets = betMapper.selectList(
                            new LambdaQueryWrapper<PredictionBet>()
                                    .eq(PredictionBet::getRoundId, round.getId())
                                    .eq(PredictionBet::getStatus, "WON"));
                    for (PredictionBet bet : wonBets) {
                        userService.updateBalance(bet.getUserId(), bet.getContracts());
                    }
                }

                round.setEndPrice(endPrice);
                round.setOutcome(outcome);
                round.setStatus("SETTLED");
                return round;
            });
        } finally {
            redisLockUtil.unlock(settleKey, lockVal);
        }

        if (settled != null) {
            broadcastRoundUpdate(settled);
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
        if (resp.getUpPrice() != null) sb.append(",\"upPrice\":\"").append(resp.getUpPrice()).append("\"");
        if (resp.getDownPrice() != null) sb.append(",\"downPrice\":\"").append(resp.getDownPrice()).append("\"");
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
