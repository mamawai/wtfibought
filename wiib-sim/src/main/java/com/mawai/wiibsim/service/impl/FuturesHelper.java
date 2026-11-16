package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.service.FuturesPositionIndexService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** 合约的计算、校验和 DTO 组装，全是静态方法。查价见 FuturesPrices，限价索引见 LimitOrderIndex，仓位全平的收尾见 ClosedPositionCleanup */
final class FuturesHelper {

    private FuturesHelper() {}

    // 强平/止损/止盈触发索引前缀：写入侧(PositionIndex)与消费侧(Liquidation)共用，改一处即全局生效
    static final String LIQ_LONG_PREFIX = "futures:liq:long:";
    static final String LIQ_SHORT_PREFIX = "futures:liq:short:";
    static final String SL_LONG_PREFIX = "futures:sl:long:";
    static final String SL_SHORT_PREFIX = "futures:sl:short:";
    static final String TP_LONG_PREFIX = "futures:tp:long:";
    static final String TP_SHORT_PREFIX = "futures:tp:short:";

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

    /** 一笔平仓落账用的成交价、盈亏、手续费 */
    record CloseFill(BigDecimal price, BigDecimal pnl, BigDecimal commission) {
        /** 成交额 */
        BigDecimal value(BigDecimal qty) {
            return price.multiply(qty).setScale(2, RoundingMode.HALF_UP);
        }

        /** 逐仓这部分平完还剩多少：保证金 ± 盈亏 − 手续费 */
        BigDecimal left(BigDecimal marginPart) {
            return marginPart.add(pnl).subtract(commission);
        }
    }

    /**
     * 按 price 平掉 qty 的结算，marginPart 是这部分对应的保证金，手续费按成交额和吃单/挂单算。
     * 逐仓亏损封在 marginPart 以内：成交价越过破产价就按破产价记、盈亏记 −marginPart、不收手续费；
     * 没越过但剩下的不够付手续费，手续费收到剩下的为止。全仓原样，亏穿由账户结算走破产。
     */
    static CloseFill closeFill(FuturesPosition position, BigDecimal price, BigDecimal qty, BigDecimal marginPart,
                               TradingConfig tradingConfig, boolean isTaker) {
        BigDecimal commission = tradingConfig.calculateFuturesCommission(
                price.multiply(qty).setScale(2, RoundingMode.HALF_UP), true, isTaker);
        BigDecimal pnl = calculatePnl(position.getSide(), position.getEntryPrice(), price, qty);
        if (position.isCross()) return new CloseFill(price, pnl, commission);
        BigDecimal left = marginPart.add(pnl);
        if (left.signum() <= 0) return new CloseFill(bankruptcyPrice(position), marginPart.negate(), BigDecimal.ZERO);
        return new CloseFill(price, pnl, commission.min(left));
    }

    /** 逐仓破产价：亏光全部保证金的那个价 */
    static BigDecimal bankruptcyPrice(FuturesPosition position) {
        BigDecimal perUnit = position.getMargin().divide(position.getQuantity(), 8, RoundingMode.HALF_UP);
        return "LONG".equals(position.getSide())
                ? position.getEntryPrice().subtract(perUnit)
                : position.getEntryPrice().add(perUnit);
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

    /** position 是按 id 刚查出来的那行（可能是 null）：得是这个用户的、还开着，否则抛对应的错 */
    static FuturesPosition requireOpen(FuturesPosition position, Long userId) {
        if (position == null || !position.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.FUTURES_POSITION_NOT_FOUND);
        }
        if (!"OPEN".equals(position.getStatus())) {
            throw new BizException(ErrorCode.FUTURES_POSITION_CLOSED);
        }
        return position;
    }

    /** 杠杆不能超过这笔名义额所在档位的上限 maxAllowed */
    static void validateLeverage(String symbol, int leverage, int maxAllowed) {
        if (leverage > maxAllowed) {
            throw new BizException(ErrorCode.FUTURES_INVALID_LEVERAGE.getCode(),
                    symbol + " 当前仓位档位最大支持 " + maxAllowed + "x，请求 " + leverage + "x 超限");
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
        resp.setLiquidationFee(order.getLiquidationFee());
        resp.setRealizedPnl(order.getRealizedPnl());
        resp.setStatus(order.getStatus());
        resp.setCreatedAt(order.getCreatedAt());
        resp.setUpdatedAt(order.getUpdatedAt());
        resp.setGroupId(order.getGroupId());
        resp.setCancelReason(order.getCancelReason());
        resp.setStopLosses(order.getStopLosses());
        resp.setTakeProfits(order.getTakeProfits());
        return resp;
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
            FuturesStopLoss item = new FuturesStopLoss(genId(), sl.getPrice(), sl.getQuantity());
            item.setCreatedAt(System.currentTimeMillis());
            list.add(item);
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
            FuturesTakeProfit item = new FuturesTakeProfit(genId(), tp.getPrice(), tp.getQuantity());
            item.setCreatedAt(System.currentTimeMillis());
            list.add(item);
        }
        if (total.compareTo(quantity) > 0) throw new BizException(ErrorCode.FUTURES_INVALID_QUANTITY);
        return list;
    }

    static String genId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 合并SL档位（开仓并入同向仓位用）。新增为空返回 null＝不改库。
     * 旧档先按触发先后（最紧的先）摊到加仓前的持仓量上，摊不到的去掉。再并入新档：同价的合进旧档（沿用旧档的 id 和生效时刻），不同价才追加。条数≤4
     */
    static List<FuturesStopLoss> mergeSlList(List<FuturesStopLoss> existing, List<FuturesStopLoss> added,
                                             BigDecimal oldQty, String side) {
        if (added == null || added.isEmpty()) return null;
        List<FuturesStopLoss> combined = new ArrayList<>();
        Comparator<FuturesStopLoss> byPrice = Comparator.comparing(FuturesStopLoss::getPrice);
        BigDecimal left = oldQty;
        List<FuturesStopLoss> tightestFirst = existing == null ? List.of()
                : existing.stream().sorted("LONG".equals(side) ? byPrice.reversed() : byPrice).toList();
        for (FuturesStopLoss old : tightestFirst) {
            BigDecimal qty = old.getQuantity().min(left);
            if (qty.signum() <= 0) continue;
            FuturesStopLoss copy = new FuturesStopLoss(old.getId(), old.getPrice(), qty);
            copy.setCreatedAt(old.getCreatedAt());
            combined.add(copy);
            left = left.subtract(qty);
        }
        for (FuturesStopLoss item : added) {
            FuturesStopLoss same = combined.stream().filter(x -> x.getPrice().compareTo(item.getPrice()) == 0)
                    .findFirst().orElse(null);
            if (same == null) {
                combined.add(item);
            } else {
                same.setQuantity(same.getQuantity().add(item.getQuantity()));
            }
        }
        if (combined.size() > 4) throw new BizException(ErrorCode.FUTURES_SPLIT_LIMIT);
        return combined;
    }

    /** 合并TP档位：规则同SL，旧档从最近的一档摊起 */
    static List<FuturesTakeProfit> mergeTpList(List<FuturesTakeProfit> existing, List<FuturesTakeProfit> added,
                                               BigDecimal oldQty, String side) {
        if (added == null || added.isEmpty()) return null;
        List<FuturesTakeProfit> combined = new ArrayList<>();
        Comparator<FuturesTakeProfit> byPrice = Comparator.comparing(FuturesTakeProfit::getPrice);
        BigDecimal left = oldQty;
        List<FuturesTakeProfit> nearestFirst = existing == null ? List.of()
                : existing.stream().sorted("LONG".equals(side) ? byPrice : byPrice.reversed()).toList();
        for (FuturesTakeProfit old : nearestFirst) {
            BigDecimal qty = old.getQuantity().min(left);
            if (qty.signum() <= 0) continue;
            FuturesTakeProfit copy = new FuturesTakeProfit(old.getId(), old.getPrice(), qty);
            copy.setCreatedAt(old.getCreatedAt());
            combined.add(copy);
            left = left.subtract(qty);
        }
        for (FuturesTakeProfit item : added) {
            FuturesTakeProfit same = combined.stream().filter(x -> x.getPrice().compareTo(item.getPrice()) == 0)
                    .findFirst().orElse(null);
            if (same == null) {
                combined.add(item);
            } else {
                same.setQuantity(same.getQuantity().add(item.getQuantity()));
            }
        }
        if (combined.size() > 4) throw new BizException(ErrorCode.FUTURES_SPLIT_LIMIT);
        return combined;
    }

    /**
     * 并单后同步触发索引：摊不到数量被去掉的旧档摘掉，新增价位的档注册；合进旧档的沿用旧 id，不用动。
     * 哪一类的合并结果是 null（这次没带），那一类不动
     */
    static void syncProtectionIndex(FuturesPosition position, List<FuturesStopLoss> sls,
                                    List<FuturesTakeProfit> tps, FuturesPositionIndexService index) {
        if (sls != null) {
            List<FuturesStopLoss> old = position.getStopLosses() == null ? List.of() : position.getStopLosses();
            List<FuturesStopLoss> removed = old.stream()
                    .filter(x -> sls.stream().noneMatch(y -> y.getId().equals(x.getId()))).toList();
            List<FuturesStopLoss> added = sls.stream()
                    .filter(x -> old.stream().noneMatch(y -> y.getId().equals(x.getId()))).toList();
            index.unregisterStopLosses(position.getId(), position.getSymbol(), position.getSide(), removed);
            index.registerStopLosses(position.getId(), position.getSymbol(), position.getSide(), added);
        }
        if (tps != null) {
            List<FuturesTakeProfit> old = position.getTakeProfits() == null ? List.of() : position.getTakeProfits();
            List<FuturesTakeProfit> removed = old.stream()
                    .filter(x -> tps.stream().noneMatch(y -> y.getId().equals(x.getId()))).toList();
            List<FuturesTakeProfit> added = tps.stream()
                    .filter(x -> old.stream().noneMatch(y -> y.getId().equals(x.getId()))).toList();
            index.unregisterTakeProfits(position.getId(), position.getSymbol(), position.getSide(), removed);
            index.registerTakeProfits(position.getId(), position.getSymbol(), position.getSide(), added);
        }
    }
}
