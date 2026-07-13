package com.mawai.wiibsim.controller;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.dto.UserDTO;
import com.mawai.wiibcommon.entity.BlackjackAccount;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.entity.UserAssetSnapshot;
import com.mawai.wiibsim.mapper.BlackjackAccountMapper;
import com.mawai.wiibsim.mapper.CryptoOrderMapper;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.MinesGameMapper;
import com.mawai.wiibsim.mapper.PredictionBetMapper;
import com.mawai.wiibsim.mapper.UserAssetSnapshotMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.mapper.VideoPokerGameMapper;
import com.mawai.wiibsim.service.CryptoPositionService;
import com.mawai.wiibsim.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 用户行为数据 internal API（sim 暴露给 quant 调用）。
 * <p>quant 的 behavior/持仓建议等 AI agent 通过 HTTP 拿用户账本统计，不直连 sim 库表——
 * sim 改表结构 quant 不受影响。鉴权走 {@code InternalApiFilter} 的 X-Internal-Token，已在 SaToken 放行。
 * <p>每个端点逻辑 = 原 BehaviorAnalysisTools 对应 @Tool，返回同样的统计 JSON。
 */
@RestController
@RequestMapping("/internal/behavior")
@RequiredArgsConstructor
public class BehaviorDataController {

    private final UserMapper userMapper;
    private final UserAssetSnapshotMapper snapshotMapper;
    private final CryptoOrderMapper cryptoOrderMapper;
    private final CryptoPositionService cryptoPositionService;
    private final FuturesOrderMapper futuresOrderMapper;
    private final FuturesPositionMapper futuresPositionMapper;
    private final PredictionBetMapper predictionBetMapper;
    private final BlackjackAccountMapper blackjackAccountMapper;
    private final MinesGameMapper minesGameMapper;
    private final VideoPokerGameMapper videoPokerGameMapper;
    private final UserService userService;

    @GetMapping("/{userId}/user-profile")
    public String getUserProfile(@PathVariable Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) return "用户不存在";
        return JSON.toJSONString(new Object() {
            public final BigDecimal balance = user.getBalance();
            public final BigDecimal frozenBalance = user.getFrozenBalance();
            public final int bankruptCount = user.getBankruptCount();
            public final boolean isBankrupt = user.getIsBankrupt();
            public final Object bankruptAt = user.getBankruptAt();
            public final Object createdAt = user.getCreatedAt();
        });
    }

    @GetMapping("/{userId}/portfolio-summary")
    public String getPortfolioSummary(@PathVariable Long userId) {
        UserDTO dto = userService.getUserPortfolio(userId);
        return JSON.toJSONString(new Object() {
            public final BigDecimal totalAssets = dto.getTotalAssets();
            public final BigDecimal balance = dto.getBalance();
            public final BigDecimal frozenBalance = dto.getFrozenBalance();
            public final BigDecimal positionMarketValue = dto.getPositionMarketValue();
            public final BigDecimal pendingSettlement = dto.getPendingSettlement();
            public final BigDecimal marginLoanPrincipal = dto.getMarginLoanPrincipal();
            public final BigDecimal marginInterestAccrued = dto.getMarginInterestAccrued();
            public final BigDecimal profit = dto.getProfit();
            public final BigDecimal profitPct = dto.getProfitPct();
        });
    }

    @GetMapping("/{userId}/asset-snapshots")
    public String getAssetSnapshots(@PathVariable Long userId) {
        List<UserAssetSnapshot> snapshots = snapshotMapper.listByUserAndDateRange(userId, LocalDate.now().minusDays(30));
        return JSON.toJSONString(snapshots);
    }

    @GetMapping("/{userId}/crypto-stats")
    public String getCryptoTradeStats(@PathVariable Long userId) {
        BigDecimal buyTotal = cryptoOrderMapper.sumBuyFilledAmount(userId);
        BigDecimal sellTotal = cryptoOrderMapper.sumSellFilledAmount(userId);
        List<?> positions = cryptoPositionService.getUserPositions(userId);
        BigDecimal avgLev = cryptoOrderMapper.selectAvgLeverage(userId);
        String levUsage = classifyLeverageUsage(avgLev);
        return JSON.toJSONString(new Object() {
            public final BigDecimal totalBuyAmount = buyTotal;
            public final BigDecimal totalSellAmount = sellTotal;
            public final int positionCount = positions.size();
            public final BigDecimal avgLeverage = avgLev;
            public final String leverageUsage = levUsage;
        });
    }

    @GetMapping("/{userId}/futures-stats")
    public String getFuturesTradeStats(@PathVariable Long userId) {
        BigDecimal pnl = futuresOrderMapper.sumRealizedPnl(userId);
        long count = futuresOrderMapper.countFilledOrders(userId);
        String dir = futuresOrderMapper.selectDirectionPreference(userId);
        BigDecimal lev = futuresOrderMapper.selectAvgLeverage(userId);
        BigDecimal slRate = futuresPositionMapper.selectStopLossRate(userId);
        int liqCount = futuresPositionMapper.countLiquidatedPositions(userId);
        return JSON.toJSONString(new Object() {
            public final BigDecimal realizedPnl = pnl;
            public final long orderCount = count;
            public final String direction = dir;
            public final BigDecimal avgLeverage = lev;
            public final BigDecimal stopLossRate = slRate;
            public final int liquidationCount = liqCount;
        });
    }

    @GetMapping("/{userId}/prediction-stats")
    public String getPredictionStats(@PathVariable Long userId) {
        BigDecimal profit = predictionBetMapper.sumRealizedProfit(userId);
        int freq = predictionBetMapper.countSettledBets(userId);
        BigDecimal rate = predictionBetMapper.selectWinRate(userId);
        String dirPref = predictionBetMapper.selectDirectionPreference(userId);
        return JSON.toJSONString(new Object() {
            public final int frequency = freq;
            public final BigDecimal netProfit = profit;
            public final BigDecimal winRate = rate;
            public final String directionPreference = dirPref;
        });
    }

    @GetMapping("/{userId}/blackjack-stats")
    public String getBlackjackStats(@PathVariable Long userId) {
        BlackjackAccount account = blackjackAccountMapper.selectOne(
                new LambdaQueryWrapper<BlackjackAccount>().eq(BlackjackAccount::getUserId, userId));
        if (account == null) {
            return "{\"totalHands\":0,\"totalWon\":0,\"totalLost\":0,\"biggestWin\":0,\"todayConverted\":0}";
        }
        return JSON.toJSONString(new Object() {
            public final long totalHands = account.getTotalHands();
            public final long totalWon = account.getTotalWon();
            public final long totalLost = account.getTotalLost();
            public final long biggestWin = account.getBiggestWin();
            public final long todayConverted = account.getTodayConverted();
        });
    }

    @GetMapping("/{userId}/mines-stats")
    public String getMinesStats(@PathVariable Long userId) {
        int freq = minesGameMapper.countFinishedGames(userId);
        BigDecimal profit = minesGameMapper.sumNetProfit(userId);
        return JSON.toJSONString(new Object() {
            public final int frequency = freq;
            public final BigDecimal netProfit = profit;
        });
    }

    @GetMapping("/{userId}/videopoker-stats")
    public String getVideoPokerStats(@PathVariable Long userId) {
        int freq = videoPokerGameMapper.countSettledGames(userId);
        BigDecimal profit = videoPokerGameMapper.sumNetProfit(userId);
        return JSON.toJSONString(new Object() {
            public final int frequency = freq;
            public final BigDecimal netProfit = profit;
        });
    }

    private String classifyLeverageUsage(BigDecimal avgLeverage) {
        if (avgLeverage == null || avgLeverage.compareTo(BigDecimal.ONE) <= 0) return "NONE";
        if (avgLeverage.compareTo(BigDecimal.valueOf(2)) <= 0) return "LOW";
        if (avgLeverage.compareTo(BigDecimal.valueOf(5)) <= 0) return "MEDIUM";
        return "HIGH";
    }
}
