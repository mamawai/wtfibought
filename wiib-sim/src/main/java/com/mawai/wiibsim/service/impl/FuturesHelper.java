package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class FuturesHelper {

    private FuturesHelper() {}

    static final String LIMIT_OPEN_LONG_PREFIX = "futures:limit:open_long:";
    static final String LIMIT_OPEN_SHORT_PREFIX = "futures:limit:open_short:";
    static final String LIMIT_CLOSE_LONG_PREFIX = "futures:limit:close_long:";
    static final String LIMIT_CLOSE_SHORT_PREFIX = "futures:limit:close_short:";

    // 强平/止损/止盈触发索引前缀：写入侧(PositionIndex)与消费侧(Liquidation)共用，改一处即全局生效
    static final String LIQ_LONG_PREFIX = "futures:liq:long:";
    static final String LIQ_SHORT_PREFIX = "futures:liq:short:";
    static final String SL_LONG_PREFIX = "futures:sl:long:";
    static final String SL_SHORT_PREFIX = "futures:sl:short:";
    static final String TP_LONG_PREFIX = "futures:tp:long:";
    static final String TP_SHORT_PREFIX = "futures:tp:short:";

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

    /**
     * 资金费转移额（对齐真实机制：费率>0 多头付、空头收；费率<0 反向）。
     * 返回正数=本仓应付、负数=本仓应收；名义额×费率，精度到分。
     */
    static BigDecimal fundingTransfer(String side, BigDecimal notional, BigDecimal rate) {
        BigDecimal raw = notional.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        return "LONG".equals(side) ? raw : raw.negate();
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

    static int normalizeLeverage(Integer leverage, int maxLeverage) {
        if (leverage == null || leverage < 1) return 1;
        if (leverage > maxLeverage) throw new BizException(ErrorCode.FUTURES_INVALID_LEVERAGE);
        return leverage;
    }

    /** 保证金模式归一：缺省=全仓（与Binance默认一致；quant机器人显式传ISOLATED保持原行为） */
    static String normalizeMarginMode(String marginMode) {
        if (marginMode == null || marginMode.isBlank()) return FuturesPosition.CROSS;
        if (!FuturesPosition.CROSS.equals(marginMode) && !FuturesPosition.ISOLATED.equals(marginMode)) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        return marginMode;
    }

    static FuturesOrderResponse buildOrderResponse(FuturesOrder order) {
        FuturesOrderResponse resp = new FuturesOrderResponse();
        resp.setOrderId(order.getId());
        resp.setUserId(order.getUserId());
        resp.setPositionId(order.getPositionId());
        resp.setSymbol(order.getSymbol());
        resp.setOrderSide(order.getOrderSide());
        resp.setOrderType(order.getOrderType());
        resp.setMarginMode(order.getMarginMode());
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

    /** 最新合约价：futures 实时价优先，mark 价兜底；都缺抛价不可用。 */
    static BigDecimal latestFuturesPrice(CacheService cacheService, String symbol) {
        BigDecimal price = cacheService.getFuturesPrice(symbol);
        if (price != null) return price;
        price = cacheService.getMarkPrice(symbol);
        if (price == null) throw new BizException(ErrorCode.CRYPTO_PRICE_UNAVAILABLE);
        return price;
    }

    /** mark 价：mark 优先，合约最新价兜底（三处调用方曾各写一份，其中一份还把兜底写成了现货价）。 */
    static BigDecimal markPrice(CacheService cacheService, String symbol) {
        BigDecimal mp = cacheService.getMarkPrice(symbol);
        if (mp != null) return mp;
        BigDecimal price = cacheService.getFuturesPrice(symbol);
        if (price == null) throw new BizException(ErrorCode.CRYPTO_PRICE_UNAVAILABLE);
        return price;
    }

    /** 开仓单随单止损列表构建+校验；空返回 null（市价参照当前价、限价参照挂单价）。 */
    static List<FuturesStopLoss> buildStopLosses(List<FuturesOpenRequest.StopLoss> items, String side,
                                                 BigDecimal refPrice, BigDecimal quantity) {
        if (items == null || items.isEmpty()) return null;
        if (items.size() > 4) throw new BizException(ErrorCode.FUTURES_SPLIT_LIMIT);
        BigDecimal total = BigDecimal.ZERO;
        List<FuturesStopLoss> list = new ArrayList<>();
        for (FuturesOpenRequest.StopLoss sl : items) {
            validateSlPrice(sl.getPrice(), side, refPrice);
            validateQty(sl.getQuantity());
            total = total.add(sl.getQuantity());
            list.add(new FuturesStopLoss(genId(), sl.getPrice(), sl.getQuantity()));
        }
        if (total.compareTo(quantity) > 0) throw new BizException(ErrorCode.FUTURES_INVALID_QUANTITY);
        return list;
    }

    /** 开仓单随单止盈列表构建+校验；空返回 null。 */
    static List<FuturesTakeProfit> buildTakeProfits(List<FuturesOpenRequest.TakeProfit> items, String side,
                                                    BigDecimal refPrice, BigDecimal quantity) {
        if (items == null || items.isEmpty()) return null;
        if (items.size() > 4) throw new BizException(ErrorCode.FUTURES_SPLIT_LIMIT);
        BigDecimal total = BigDecimal.ZERO;
        List<FuturesTakeProfit> list = new ArrayList<>();
        for (FuturesOpenRequest.TakeProfit tp : items) {
            validateTpPrice(tp.getPrice(), side, refPrice);
            validateQty(tp.getQuantity());
            total = total.add(tp.getQuantity());
            list.add(new FuturesTakeProfit(genId(), tp.getPrice(), tp.getQuantity()));
        }
        if (total.compareTo(quantity) > 0) throw new BizException(ErrorCode.FUTURES_INVALID_QUANTITY);
        return list;
    }

    static String genId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
