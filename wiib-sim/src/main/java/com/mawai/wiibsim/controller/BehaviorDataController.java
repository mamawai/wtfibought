package com.mawai.wiibsim.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.config.BinanceProperties;
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
import com.mawai.wiibsim.service.BStockService;
import com.mawai.wiibsim.service.CryptoPositionService;
import com.mawai.wiibsim.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 用户行为数据 internal API（sim 暴露给 quant 调用）。
 * <p>quant 的 behavior/持仓建议等 AI agent 通过 HTTP 拿用户账本统计，不直连 sim 库表——
 * sim 改表结构 quant 不受影响。鉴权走 {@code InternalApiFilter} 的 X-Internal-Token，已在 SaToken 放行。
 * <p>一个端点 = 一个统计维度，quant 侧 BehaviorDataCollector 并发把它们全拉一遍。
 * <p>键名是与 quant 侧 BehaviorAnalysisReport 的反序列化契约，改名必须两端同步。
 */
@RestController
@RequestMapping("/internal/behavior")
@RequiredArgsConstructor
public class BehaviorDataController {

    private final UserMapper userMapper;
    private final UserAssetSnapshotMapper snapshotMapper;
    private final CryptoOrderMapper cryptoOrderMapper;
    private final CryptoPositionService cryptoPositionService;
    private final BStockService bStockService;
    private final FuturesOrderMapper futuresOrderMapper;
    private final FuturesPositionMapper futuresPositionMapper;
    private final PredictionBetMapper predictionBetMapper;
    private final BlackjackAccountMapper blackjackAccountMapper;
    private final MinesGameMapper minesGameMapper;
    private final VideoPokerGameMapper videoPokerGameMapper;
    private final UserService userService;
    private final BinanceProperties binanceProperties;

    @GetMapping("/{userId}/user-profile")
    public String getUserProfile(@PathVariable Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) return "用户不存在";
        return MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("balance", user.getBalance())
                .put("frozenBalance", user.getFrozenBalance())
                .put("bankruptCount", user.getBankruptCount())
                .put("isBankrupt", user.getIsBankrupt())
                .set("bankruptAt", MAPPER.valueToTree(user.getBankruptAt()))
                .set("createdAt", MAPPER.valueToTree(user.getCreatedAt())));
    }

    @GetMapping("/{userId}/portfolio-summary")
    public String getPortfolioSummary(@PathVariable Long userId) {
        UserDTO dto = userService.getUserPortfolio(userId);
        return MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("totalAssets", dto.getTotalAssets())
                .put("balance", dto.getBalance())
                .put("frozenBalance", dto.getFrozenBalance())
                .put("positionMarketValue", dto.getPositionMarketValue())
                .put("marginLoanPrincipal", dto.getMarginLoanPrincipal())
                .put("marginInterestAccrued", dto.getMarginInterestAccrued())
                .put("profit", dto.getProfit())
                .put("profitPct", dto.getProfitPct()));
    }

    @GetMapping("/{userId}/asset-snapshots")
    public String getAssetSnapshots(@PathVariable Long userId) {
        List<UserAssetSnapshot> snapshots = snapshotMapper.listByUserAndDateRange(userId, LocalDate.now().minusDays(30));
        return MAPPER.writeValueAsString(snapshots);
    }

    @GetMapping("/{userId}/crypto-stats")
    public String getCryptoTradeStats(@PathVariable Long userId) {
        // bStock 共用现货引擎（同表同持仓服务），行为口径按 symbol 集拆开，与资产五分类对齐
        long posCount = cryptoPositionService.getUserPositions(userId).stream()
                .filter(p -> !bStockService.isBStockSymbol(p.getSymbol())).count();
        BigDecimal avgLev = cryptoOrderMapper.selectAvgLeverage(userId);
        return MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("totalBuyAmount", cryptoOrderMapper.sumBuyFilledAmount(userId))
                .put("totalSellAmount", cryptoOrderMapper.sumSellFilledAmount(userId))
                .put("positionCount", posCount)
                .put("avgLeverage", avgLev)
                .put("leverageUsage", classifyLeverageUsage(avgLev)));
    }

    @GetMapping("/{userId}/bstock-stats")
    public String getBstockTradeStats(@PathVariable Long userId) {
        long posCount = cryptoPositionService.getUserPositions(userId).stream()
                .filter(p -> bStockService.isBStockSymbol(p.getSymbol())).count();
        return MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("positionCount", posCount)
                .put("totalBuyAmount", cryptoOrderMapper.sumBstockBuyFilledAmount(userId))
                .put("totalSellAmount", cryptoOrderMapper.sumBstockSellFilledAmount(userId)));
    }

    @GetMapping("/{userId}/futures-stats")
    public String getFuturesTradeStats(@PathVariable Long userId) {
        return MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("realizedPnl", futuresOrderMapper.sumRealizedPnl(userId))
                .put("orderCount", futuresOrderMapper.countFilledOrders(userId))
                .put("direction", futuresOrderMapper.selectDirectionPreference(userId))
                .put("avgLeverage", futuresOrderMapper.selectAvgLeverage(userId))
                .put("stopLossRate", futuresPositionMapper.selectStopLossRate(userId))
                .put("liquidationCount", futuresPositionMapper.countLiquidatedPositions(userId))
                .set("byCategory", MAPPER.valueToTree(futuresByCategory(userId))));
    }

    /**
     * 合约分品类拆解：与资产五分类同源的符号集归桶（crypto 永续 / 大宗金油 / TradFi 美股ETF永续）。
     * 未知符号（已下架）归 crypto 默认桶不丢数据；三桶恒在，LLM/前端拿到的结构恒定。
     */
    private Map<String, Object> futuresByCategory(Long userId) {
        Map<String, BigDecimal> pnlBy = new LinkedHashMap<>(
                Map.of("crypto", BigDecimal.ZERO, "commodity", BigDecimal.ZERO, "tradfi", BigDecimal.ZERO));
        Map<String, Long> cntBy = new LinkedHashMap<>(
                Map.of("crypto", 0L, "commodity", 0L, "tradfi", 0L));
        for (Map<String, Object> row : futuresOrderMapper.sumRealizedPnlBySymbol(userId)) {
            String cat = futuresCategory((String) row.get("symbol"));
            pnlBy.merge(cat, (BigDecimal) row.get("amount"), BigDecimal::add);
        }
        for (Map<String, Object> row : futuresOrderMapper.countFilledOrdersBySymbol(userId)) {
            String cat = futuresCategory((String) row.get("symbol"));
            cntBy.merge(cat, ((Number) row.get("cnt")).longValue(), Long::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String cat : List.of("crypto", "commodity", "tradfi")) {
            out.put(cat, Map.of("realizedPnl", pnlBy.get(cat), "orderCount", cntBy.get(cat)));
        }
        return out;
    }

    private String futuresCategory(String symbol) {
        List<String> commodity = binanceProperties.getCommoditySymbols();
        List<String> tradfi = binanceProperties.getTradfiSymbols();
        if (commodity != null && commodity.contains(symbol)) return "commodity";
        if (tradfi != null && tradfi.contains(symbol)) return "tradfi";
        return "crypto";
    }

    @GetMapping("/{userId}/prediction-stats")
    public String getPredictionStats(@PathVariable Long userId) {
        return MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("frequency", predictionBetMapper.countSettledBets(userId))
                .put("netProfit", predictionBetMapper.sumRealizedProfit(userId))
                .put("winRate", predictionBetMapper.selectWinRate(userId))
                .put("directionPreference", predictionBetMapper.selectDirectionPreference(userId)));
    }

    @GetMapping("/{userId}/blackjack-stats")
    public String getBlackjackStats(@PathVariable Long userId) {
        BlackjackAccount account = blackjackAccountMapper.selectOne(
                new LambdaQueryWrapper<BlackjackAccount>().eq(BlackjackAccount::getUserId, userId));
        if (account == null) {
            return "{\"totalHands\":0,\"totalWon\":0,\"totalLost\":0,\"biggestWin\":0,\"todayConverted\":0}";
        }
        return MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("totalHands", account.getTotalHands())
                .put("totalWon", account.getTotalWon())
                .put("totalLost", account.getTotalLost())
                .put("biggestWin", account.getBiggestWin())
                .put("todayConverted", account.getTodayConverted()));
    }

    @GetMapping("/{userId}/mines-stats")
    public String getMinesStats(@PathVariable Long userId) {
        return MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("frequency", minesGameMapper.countFinishedGames(userId))
                .put("netProfit", minesGameMapper.sumNetProfit(userId)));
    }

    @GetMapping("/{userId}/videopoker-stats")
    public String getVideoPokerStats(@PathVariable Long userId) {
        return MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("frequency", videoPokerGameMapper.countSettledGames(userId))
                .put("netProfit", videoPokerGameMapper.sumNetProfit(userId)));
    }

    private String classifyLeverageUsage(BigDecimal avgLeverage) {
        if (avgLeverage == null || avgLeverage.compareTo(BigDecimal.ONE) <= 0) return "NONE";
        if (avgLeverage.compareTo(BigDecimal.valueOf(2)) <= 0) return "LOW";
        if (avgLeverage.compareTo(BigDecimal.valueOf(5)) <= 0) return "MEDIUM";
        return "HIGH";
    }
}
