package com.mawai.wiibservice.agent.trading.ops;

import com.mawai.wiibservice.agent.trading.runtime.SymbolProfile;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesStopLossRequest;
import com.mawai.wiibcommon.dto.FuturesTakeProfitRequest;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibservice.agent.risk.CircuitBreakerService;
import com.mawai.wiibservice.mapper.FuturesPositionMapper;
import com.mawai.wiibservice.mapper.UserMapper;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.FuturesRiskService;
import com.mawai.wiibservice.service.FuturesTradingService;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 确定性执行器的真实交易适配器。
 * 这里不暴露 Spring AI Tool，避免 LLM 绕过策略链直接写交易。
 */
@Slf4j
public class FuturesTradingOperationsAdapter implements TradingOperations {

    private static final int MAX_LEVERAGE = 50;
    private static final int MIN_LEVERAGE = 5;
    private static final BigDecimal MIN_POSITION_VALUE = new BigDecimal("5000");
    private static final BigDecimal MAX_POSITION_RATIO = new BigDecimal("0.35");
    private static final BigDecimal MIN_MARGIN_FLOOR = new BigDecimal("100");
    private static final BigDecimal MIN_MARGIN_RATIO = new BigDecimal("0.01");
    private static final int MAX_OPEN_POSITIONS = 6;
    private static final int MAX_SYMBOL_POSITIONS = 3;
    private static final int ENTRY_COOLDOWN_MINUTES = 10;
    private static final BigDecimal SL_MIN_TOLERANCE = new BigDecimal("0.0002");

    private final Long aiUserId;
    private final String currentSymbol;
    private final UserMapper userMapper;
    private final FuturesTradingService futuresTradingService;
    private final FuturesRiskService futuresRiskService;
    private final FuturesPositionMapper futuresPositionMapper;
    private final CacheService cacheService;
    private final CircuitBreakerService circuitBreakerService;

    public FuturesTradingOperationsAdapter(Long aiUserId, String currentSymbol,
                                           UserMapper userMapper,
                                           FuturesTradingService futuresTradingService,
                                           FuturesRiskService futuresRiskService,
                                           FuturesPositionMapper futuresPositionMapper,
                                           CacheService cacheService,
                                           CircuitBreakerService circuitBreakerService) {
        this.aiUserId = aiUserId;
        this.currentSymbol = currentSymbol;
        this.userMapper = userMapper;
        this.futuresTradingService = futuresTradingService;
        this.futuresRiskService = futuresRiskService;
        this.futuresPositionMapper = futuresPositionMapper;
        this.cacheService = cacheService;
        this.circuitBreakerService = circuitBreakerService;
    }

    @Override
    public BigDecimal peakEquity() {
        CircuitBreakerService.BreakerStatus status = circuitBreakerService.status(aiUserId);
        if (status == null || status.peakEquity() == null || status.peakEquity().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(status.peakEquity());
        } catch (NumberFormatException e) {
            log.warn("[AI-Trade] peakEquity解析失败 raw={}", status.peakEquity());
            return null;
        }
    }

    @Override
    public String openPosition(String side, BigDecimal quantity, Integer leverage,
                               String orderType, BigDecimal limitPrice,
                               BigDecimal stopLossPrice, BigDecimal takeProfitPrice,
                               String memo) {
        return openPositionWithResult(side, quantity, leverage, orderType, limitPrice,
                stopLossPrice, takeProfitPrice, memo).message();
    }

    @Override
    public OpenResult openPositionWithResult(String side, BigDecimal quantity, Integer leverage,
                                             String orderType, BigDecimal limitPrice,
                                             BigDecimal stopLossPrice, BigDecimal takeProfitPrice,
                                             String memo) {

        String symbol = currentSymbol;
        if (!"LONG".equals(side) && !"SHORT".equals(side)) {
            return OpenResult.fromMessage("错误：side必须是LONG或SHORT");
        }
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return OpenResult.fromMessage("错误：quantity必须大于0");
        }
        if (leverage == null || leverage < MIN_LEVERAGE) {
            return OpenResult.fromMessage("错误：杠杆最低" + MIN_LEVERAGE + "倍，你传了" + leverage + "，请用5-50倍");
        }
        if (leverage > MAX_LEVERAGE) {
            return OpenResult.fromMessage("错误：杠杆上限" + MAX_LEVERAGE + "倍，你传了" + leverage);
        }
        if (stopLossPrice == null || stopLossPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return OpenResult.fromMessage("错误：必须设置止损价格");
        }
        if (takeProfitPrice == null || takeProfitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return OpenResult.fromMessage("错误：必须设置止盈价格");
        }

        boolean isLimit = "LIMIT".equalsIgnoreCase(orderType);
        if (isLimit && (limitPrice == null || limitPrice.compareTo(BigDecimal.ZERO) <= 0)) {
            return OpenResult.fromMessage("错误：限价单必须设置limitPrice");
        }

        CircuitBreakerService.OpenDecision breaker = circuitBreakerService.allowOpen(aiUserId, memo);
        if (!breaker.allowed()) {
            log.warn("[AI-Trade] 开仓被熔断拦截 symbol={} memo={} reason={}", symbol, memo, breaker.reason());
            return OpenResult.fromMessage("错误：熔断中，" + breaker.reason());
        }

        List<FuturesPosition> openPositions = futuresPositionMapper.selectList(
                new LambdaQueryWrapper<FuturesPosition>()
                        .eq(FuturesPosition::getUserId, aiUserId)
                        .eq(FuturesPosition::getStatus, "OPEN"));
        if (openPositions.size() >= MAX_OPEN_POSITIONS) {
            return OpenResult.fromMessage("错误：已有" + openPositions.size() + "个持仓，上限" + MAX_OPEN_POSITIONS);
        }

        List<FuturesPosition> symbolOpenPositions = openPositions.stream()
                .filter(p -> symbol.equals(p.getSymbol()))
                .toList();
        if (!symbolOpenPositions.isEmpty()) {
            boolean hasOpposite = symbolOpenPositions.stream()
                    .anyMatch(p -> !side.equals(p.getSide()));
            if (hasOpposite) {
                String existingSide = symbolOpenPositions.getFirst().getSide();
                return OpenResult.fromMessage("错误：" + symbol + "已有" + existingSide + "持仓，暂不允许开反向新仓，请先平仓");
            }
        }
        if (symbolOpenPositions.size() >= MAX_SYMBOL_POSITIONS) {
            return OpenResult.fromMessage("错误：" + symbol + "已有" + symbolOpenPositions.size() + "个持仓，上限" + MAX_SYMBOL_POSITIONS);
        }

        FuturesPosition lastPosition = futuresPositionMapper.selectOne(
                new LambdaQueryWrapper<FuturesPosition>()
                        .eq(FuturesPosition::getUserId, aiUserId)
                        .eq(FuturesPosition::getSymbol, symbol)
                        .orderByDesc(FuturesPosition::getCreatedAt)
                        .last("LIMIT 1"));
        if (lastPosition != null && lastPosition.getCreatedAt() != null) {
            long minutesSinceLast = Duration.between(lastPosition.getCreatedAt(), LocalDateTime.now()).toMinutes();
            if (minutesSinceLast < ENTRY_COOLDOWN_MINUTES) {
                return OpenResult.fromMessage("错误：" + symbol + "距上次开仓仅" + minutesSinceLast + "分钟，需间隔" + ENTRY_COOLDOWN_MINUTES + "分钟");
            }
        }

        User user = userMapper.selectById(aiUserId);
        if (user == null) return OpenResult.fromMessage("错误：AI账户不存在");
        BigDecimal price = isLimit ? limitPrice : cacheService.getFuturesPrice(symbol);
        if (price == null) price = cacheService.getMarkPrice(symbol);
        if (price == null) return OpenResult.fromMessage("错误：无法获取" + symbol + "价格");

        SymbolProfile profile = SymbolProfile.of(symbol);
        BigDecimal slMinPct = BigDecimal.valueOf(profile.slMinPct());
        BigDecimal slMaxPct = BigDecimal.valueOf(profile.slMaxPct());
        BigDecimal slDistance = stopLossPrice.subtract(price).abs();
        BigDecimal slPct = slDistance.divide(price, 6, RoundingMode.HALF_UP);
        BigDecimal slMinWithTolerance = slMinPct.subtract(SL_MIN_TOLERANCE);
        if (slPct.compareTo(slMinWithTolerance) < 0) {
            return OpenResult.fromMessage("错误：止损距当前价仅" + slPct.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP)
                    + "%，太近（噪音区，最低" + slMinPct.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP) + "%）");
        }
        if (slPct.compareTo(slMaxPct) > 0) {
            return OpenResult.fromMessage("错误：止损距当前价" + slPct.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP)
                    + "%，太远（>" + slMaxPct.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP) + "%），风险过大");
        }

        BigDecimal positionValue = price.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        if (positionValue.compareTo(MIN_POSITION_VALUE) < 0) {
            return OpenResult.fromMessage("错误：名义价值" + positionValue + " USDT太小，最低" + MIN_POSITION_VALUE + " USDT，请加大仓位");
        }
        BigDecimal margin = positionValue.divide(BigDecimal.valueOf(leverage), 2, RoundingMode.CEILING);
        BigDecimal dynamicMinMargin = user.getBalance().multiply(MIN_MARGIN_RATIO)
                .max(MIN_MARGIN_FLOOR).setScale(2, RoundingMode.HALF_UP);
        if (margin.compareTo(dynamicMinMargin) < 0 && user.getBalance().compareTo(dynamicMinMargin) >= 0) {
            return OpenResult.fromMessage("错误：保证金" + margin + " USDT低于最低要求" + dynamicMinMargin + " USDT，请加大仓位或降低杠杆");
        }
        BigDecimal maxMargin = user.getBalance().multiply(MAX_POSITION_RATIO);
        if (margin.compareTo(maxMargin) > 0) {
            return OpenResult.fromMessage("错误：保证金" + margin + "超过余额35%上限" + maxMargin.setScale(2, RoundingMode.HALF_UP));
        }

        FuturesOpenRequest req = new FuturesOpenRequest();
        req.setSymbol(symbol);
        req.setSide(side);
        req.setQuantity(quantity);
        req.setLeverage(leverage);
        req.setOrderType(isLimit ? "LIMIT" : "MARKET");
        req.setMemo(memo);
        if (isLimit) req.setLimitPrice(limitPrice);

        FuturesOpenRequest.StopLoss sl = new FuturesOpenRequest.StopLoss();
        sl.setPrice(stopLossPrice);
        sl.setQuantity(quantity);
        req.setStopLosses(List.of(sl));

        FuturesOpenRequest.TakeProfit tp = new FuturesOpenRequest.TakeProfit();
        tp.setPrice(takeProfitPrice);
        tp.setQuantity(quantity);
        req.setTakeProfits(List.of(tp));

        try {
            FuturesOrderResponse resp = futuresTradingService.openPosition(aiUserId, req);
            log.info("[AI-Trade] 开仓成功 {} {} {} qty={} lev={} sl={}",
                    symbol, side, req.getOrderType(), quantity, leverage, stopLossPrice);
            return new OpenResult(true, resp != null ? resp.getPositionId() : null,
                    "开仓成功：" + JSON.toJSONString(resp));
        } catch (Exception e) {
            log.warn("[AI-Trade] 开仓失败 {} {} : {}", symbol, side, e.getMessage());
            return OpenResult.fromMessage("开仓失败：" + e.getMessage());
        }
    }

    @Override
    public String closePosition(Long positionId, BigDecimal quantity) {
        if (positionId == null) return "错误：positionId不能为空";
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) return "错误：quantity必须大于0";

        FuturesCloseRequest req = new FuturesCloseRequest();
        req.setPositionId(positionId);
        req.setQuantity(quantity);
        req.setOrderType("MARKET");

        try {
            FuturesOrderResponse resp = futuresTradingService.closePosition(aiUserId, req);
            log.info("[AI-Trade] 平仓成功 posId={} qty={} pnl={}", positionId, quantity, resp.getRealizedPnl());
            return "平仓成功：" + JSON.toJSONString(resp);
        } catch (Exception e) {
            log.warn("[AI-Trade] 平仓失败 posId={} : {}", positionId, e.getMessage());
            return "平仓失败：" + e.getMessage();
        }
    }

    @Override
    public String setStopLoss(Long positionId, BigDecimal stopLossPrice, BigDecimal quantity) {
        if (positionId == null) return "错误：positionId不能为空";
        if (stopLossPrice == null || stopLossPrice.compareTo(BigDecimal.ZERO) <= 0) return "错误：止损价格必须大于0";
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) return "错误：数量必须大于0";

        FuturesStopLossRequest req = new FuturesStopLossRequest();
        req.setPositionId(positionId);
        FuturesStopLossRequest.StopLossItem item = new FuturesStopLossRequest.StopLossItem();
        item.setPrice(stopLossPrice);
        item.setQuantity(quantity);
        req.setStopLosses(List.of(item));

        try {
            futuresRiskService.setStopLoss(aiUserId, req);
            log.info("[AI-Trade] 修改止损成功 posId={} sl={} qty={}", positionId, stopLossPrice, quantity);
            return "修改止损成功：positionId=" + positionId + " stopLoss=" + stopLossPrice;
        } catch (Exception e) {
            log.warn("[AI-Trade] 修改止损失败 posId={} : {}", positionId, e.getMessage());
            return "修改止损失败：" + e.getMessage();
        }
    }

    @Override
    public String setTakeProfit(Long positionId, BigDecimal takeProfitPrice, BigDecimal quantity) {
        if (positionId == null) return "错误：positionId不能为空";
        if (takeProfitPrice == null || takeProfitPrice.compareTo(BigDecimal.ZERO) <= 0) return "错误：止盈价格必须大于0";
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) return "错误：数量必须大于0";

        FuturesTakeProfitRequest req = new FuturesTakeProfitRequest();
        req.setPositionId(positionId);
        FuturesTakeProfitRequest.TakeProfitItem item = new FuturesTakeProfitRequest.TakeProfitItem();
        item.setPrice(takeProfitPrice);
        item.setQuantity(quantity);
        req.setTakeProfits(List.of(item));

        try {
            futuresRiskService.setTakeProfit(aiUserId, req);
            log.info("[AI-Trade] 修改止盈成功 posId={} tp={} qty={}", positionId, takeProfitPrice, quantity);
            return "修改止盈成功：positionId=" + positionId + " takeProfit=" + takeProfitPrice;
        } catch (Exception e) {
            log.warn("[AI-Trade] 修改止盈失败 posId={} : {}", positionId, e.getMessage());
            return "修改止盈失败：" + e.getMessage();
        }
    }
}
