package com.mawai.wiibservice.service.impl;

import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibservice.service.CacheService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

final class FuturesHelper {

    private FuturesHelper() {}

    static final String LIMIT_OPEN_LONG_PREFIX = "futures:limit:open_long:";
    static final String LIMIT_OPEN_SHORT_PREFIX = "futures:limit:open_short:";
    static final String LIMIT_CLOSE_LONG_PREFIX = "futures:limit:close_long:";
    static final String LIMIT_CLOSE_SHORT_PREFIX = "futures:limit:close_short:";

    static void addToLimitZSet(FuturesOrder order, CacheService cacheService) {
        String key = getLimitZSetKey(order.getOrderSide(), order.getSymbol());
        cacheService.zAdd(key, order.getId().toString(), order.getLimitPrice().doubleValue());
    }

    static void removeFromLimitZSet(FuturesOrder order, CacheService cacheService) {
        String key = getLimitZSetKey(order.getOrderSide(), order.getSymbol());
        cacheService.zRemove(key, order.getId().toString());
    }

    static String getLimitZSetKey(String orderSide, String symbol) {
        return switch (orderSide) {
            case "OPEN_LONG", "INCREASE_LONG" -> LIMIT_OPEN_LONG_PREFIX + symbol;
            case "OPEN_SHORT", "INCREASE_SHORT" -> LIMIT_OPEN_SHORT_PREFIX + symbol;
            case "CLOSE_LONG" -> LIMIT_CLOSE_LONG_PREFIX + symbol;
            case "CLOSE_SHORT" -> LIMIT_CLOSE_SHORT_PREFIX + symbol;
            default -> throw new IllegalArgumentException("Invalid orderSide: " + orderSide);
        };
    }

    static BigDecimal calculatePnl(String side, BigDecimal entryPrice, BigDecimal exitPrice, BigDecimal quantity) {
        if ("LONG".equals(side)) {
            return exitPrice.subtract(entryPrice).multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        } else {
            return entryPrice.subtract(exitPrice).multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        }
    }

    static void validateSlPrice(BigDecimal price, String side, BigDecimal refPrice) {
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) throw new BizException(ErrorCode.FUTURES_INVALID_STOP_LOSS);
        if ("LONG".equals(side) && price.compareTo(refPrice) >= 0) throw new BizException(ErrorCode.FUTURES_INVALID_STOP_LOSS);
        if ("SHORT".equals(side) && price.compareTo(refPrice) <= 0) throw new BizException(ErrorCode.FUTURES_INVALID_STOP_LOSS);
    }

    static void validateTpPrice(BigDecimal price, String side, BigDecimal refPrice) {
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) throw new BizException(ErrorCode.FUTURES_INVALID_TAKE_PROFIT);
        if ("LONG".equals(side) && price.compareTo(refPrice) <= 0) throw new BizException(ErrorCode.FUTURES_INVALID_TAKE_PROFIT);
        if ("SHORT".equals(side) && price.compareTo(refPrice) >= 0) throw new BizException(ErrorCode.FUTURES_INVALID_TAKE_PROFIT);
    }

    static void validateQty(BigDecimal qty) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) throw new BizException(ErrorCode.FUTURES_INVALID_QUANTITY);
    }

    static void validateOpenRequest(FuturesOpenRequest request) {
        if (request.getQuantity() == null || request.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.FUTURES_INVALID_QUANTITY);
        }
        if (request.getSymbol() == null || request.getSymbol().isBlank()) {
            throw new BizException(ErrorCode.CRYPTO_SYMBOL_INVALID);
        }
        if (!"LONG".equals(request.getSide()) && !"SHORT".equals(request.getSide())) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        if (!"MARKET".equals(request.getOrderType()) && !"LIMIT".equals(request.getOrderType())) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
    }

    static int normalizeLeverage(Integer leverage, int maxLeverage, BigDecimal maintenanceMarginRate) {
        if (leverage == null || leverage < 1) return 1;
        if (leverage > maxLeverage) throw new BizException(ErrorCode.FUTURES_INVALID_LEVERAGE);
        if (maintenanceMarginRate != null && maintenanceMarginRate.signum() > 0) {
            BigDecimal initialMarginRate = BigDecimal.ONE.divide(BigDecimal.valueOf(leverage), 12, RoundingMode.HALF_UP);
            if (initialMarginRate.compareTo(maintenanceMarginRate) <= 0) {
                throw new BizException(ErrorCode.FUTURES_INVALID_LEVERAGE);
            }
        }
        return leverage;
    }

    static FuturesOrderResponse buildOrderResponse(FuturesOrder order) {
        FuturesOrderResponse resp = new FuturesOrderResponse();
        resp.setOrderId(order.getId());
        resp.setUserId(order.getUserId());
        resp.setPositionId(order.getPositionId());
        resp.setSymbol(order.getSymbol());
        resp.setOrderSide(order.getOrderSide());
        resp.setOrderType(order.getOrderType());
        resp.setQuantity(order.getQuantity());
        resp.setLeverage(order.getLeverage());
        resp.setLimitPrice(order.getLimitPrice());
        resp.setFrozenAmount(order.getFrozenAmount());
        resp.setFilledPrice(order.getFilledPrice());
        resp.setFilledAmount(order.getFilledAmount());
        resp.setMarginAmount(order.getMarginAmount());
        resp.setCommission(order.getCommission());
        resp.setRealizedPnl(order.getRealizedPnl());
        resp.setStatus(order.getStatus());
        resp.setExpireAt(order.getExpireAt());
        resp.setCreatedAt(order.getCreatedAt());
        return resp;
    }

    static String genId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
