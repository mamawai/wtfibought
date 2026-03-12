package com.mawai.wiibservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibservice.config.TradingConfig;
import com.mawai.wiibservice.mapper.FuturesPositionMapper;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.FuturesPositionIndexService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FuturesPositionIndexServiceImpl implements FuturesPositionIndexService {

    private final FuturesPositionMapper positionMapper;
    private final CacheService cacheService;
    private final TradingConfig tradingConfig;
    private final StringRedisTemplate stringRedisTemplate;

    private static final String LIQ_LONG_PREFIX = "futures:liq:long:";
    private static final String LIQ_SHORT_PREFIX = "futures:liq:short:";
    private static final String SL_LONG_PREFIX = "futures:sl:long:";
    private static final String SL_SHORT_PREFIX = "futures:sl:short:";
    private static final String TP_LONG_PREFIX = "futures:tp:long:";
    private static final String TP_SHORT_PREFIX = "futures:tp:short:";

    @PostConstruct
    void init() {
        List<FuturesPosition> positions = positionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                .eq(FuturesPosition::getStatus, "OPEN"));

        for (FuturesPosition pos : positions) {
            registerPositionIndex(pos);
        }

        log.info("重建futures ZSet索引 共{}个仓位", positions.size());
    }

    @Override
    public void registerPositionIndex(FuturesPosition position) {
        Long positionId = position.getId();
        String symbol = position.getSymbol();
        String side = position.getSide();

        List<FuturesStopLoss> sls = position.getStopLosses();
        List<FuturesTakeProfit> tps = position.getTakeProfits();

        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection conn = (StringRedisConnection) connection;
            // 始终注册LIQ
            BigDecimal liqPrice = calcStaticLiqPrice(side, position.getEntryPrice(), position.getMargin(), position.getQuantity());
            String liqKey = "LONG".equals(side) ? LIQ_LONG_PREFIX + symbol : LIQ_SHORT_PREFIX + symbol;
            conn.zAdd(liqKey, liqPrice.doubleValue(), positionId.toString());

            if (sls != null && !sls.isEmpty()) {
                String slKey = "LONG".equals(side) ? SL_LONG_PREFIX + symbol : SL_SHORT_PREFIX + symbol;
                for (FuturesStopLoss sl : sls) {
                    conn.zAdd(slKey, sl.getPrice().doubleValue(), positionId + ":" + sl.getId());
                }
            }
            if (tps != null && !tps.isEmpty()) {
                String tpKey = "LONG".equals(side) ? TP_LONG_PREFIX + symbol : TP_SHORT_PREFIX + symbol;
                for (FuturesTakeProfit tp : tps) {
                    conn.zAdd(tpKey, tp.getPrice().doubleValue(), positionId + ":" + tp.getId());
                }
            }
            return null;
        });
    }

    @Override
    public void unregisterAll(FuturesPosition position) {
        String id = position.getId().toString();
        String symbol = position.getSymbol();
        String side = position.getSide();

        List<FuturesStopLoss> sls = position.getStopLosses();
        List<FuturesTakeProfit> tps = position.getTakeProfits();

        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection conn = (StringRedisConnection) connection;
            conn.zRem("LONG".equals(side) ? LIQ_LONG_PREFIX + symbol : LIQ_SHORT_PREFIX + symbol, id);

            if (sls != null) {
                String slKey = "LONG".equals(side) ? SL_LONG_PREFIX + symbol : SL_SHORT_PREFIX + symbol;
                for (FuturesStopLoss sl : sls) {
                    conn.zRem(slKey, id + ":" + sl.getId());
                }
            }

            if (tps != null) {
                String tpKey = "LONG".equals(side) ? TP_LONG_PREFIX + symbol : TP_SHORT_PREFIX + symbol;
                for (FuturesTakeProfit tp : tps) {
                    conn.zRem(tpKey, id + ":" + tp.getId());
                }
            }
            return null;
        });
    }

    @Override
    public void updateLiquidationPrice(Long positionId, String symbol, String side, BigDecimal liqPrice) {
        String key = "LONG".equals(side) ? LIQ_LONG_PREFIX + symbol : LIQ_SHORT_PREFIX + symbol;
        Double existing = cacheService.zScore(key, positionId.toString());
        if (existing != null) {
            cacheService.zAdd(key, positionId.toString(), liqPrice.doubleValue());
        }
    }

    @Override
    public void registerStopLosses(Long positionId, String symbol, String side, List<FuturesStopLoss> stopLosses) {
        String slKey = "LONG".equals(side) ? SL_LONG_PREFIX + symbol : SL_SHORT_PREFIX + symbol;
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection conn = (StringRedisConnection) connection;
            for (FuturesStopLoss sl : stopLosses) {
                conn.zAdd(slKey, sl.getPrice().doubleValue(), positionId + ":" + sl.getId());
            }
            return null;
        });
    }

    @Override
    public void registerTakeProfits(Long positionId, String symbol, String side, List<FuturesTakeProfit> takeProfits) {
        String tpKey = "LONG".equals(side) ? TP_LONG_PREFIX + symbol : TP_SHORT_PREFIX + symbol;
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection conn = (StringRedisConnection) connection;
            for (FuturesTakeProfit tp : takeProfits) {
                conn.zAdd(tpKey, tp.getPrice().doubleValue(), positionId + ":" + tp.getId());
            }
            return null;
        });
    }

    @Override
    public void unregisterStopLosses(Long positionId, String symbol, String side, List<FuturesStopLoss> stopLosses) {
        String slKey = "LONG".equals(side) ? SL_LONG_PREFIX + symbol : SL_SHORT_PREFIX + symbol;
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection conn = (StringRedisConnection) connection;
            for (FuturesStopLoss sl : stopLosses) {
                conn.zRem(slKey, positionId + ":" + sl.getId());
            }
            return null;
        });
    }

    @Override
    public void unregisterTakeProfits(Long positionId, String symbol, String side, List<FuturesTakeProfit> takeProfits) {
        String tpKey = "LONG".equals(side) ? TP_LONG_PREFIX + symbol : TP_SHORT_PREFIX + symbol;
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection conn = (StringRedisConnection) connection;
            for (FuturesTakeProfit tp : takeProfits) {
                conn.zRem(tpKey, positionId + ":" + tp.getId());
            }
            return null;
        });
    }

    /**
     * 强平价计算
     * <p>
     * 强平条件: 有效保证金 = 维持保证金
     * 即: margin + unrealizedPnl = liqPrice * qty * mmr
     * <p>
     * LONG (价格下跌亏损):
     *   margin + (liqPrice - entryPrice) * qty = liqPrice * qty * mmr
     *   margin = liqPrice * qty * mmr - liqPrice * qty + entryPrice * qty
     *   margin = liqPrice * qty * (mmr - 1) + entryPrice * qty
     *   entryPrice * qty - margin = liqPrice * qty * (1 - mmr) </br>
     *   liqPrice = (entryPrice * qty - margin) / (qty * (1 - mmr))
     * <p>
     * SHORT (价格上涨亏损):
     *   margin + (entryPrice - liqPrice) * qty = liqPrice * qty * mmr
     *   margin = liqPrice * qty * mmr + liqPrice * qty - entryPrice * qty
     *   margin = liqPrice * qty * (mmr + 1) - entryPrice * qty
     *   entryPrice * qty + margin = liqPrice * qty * (1 + mmr) </br>
     *   liqPrice = (entryPrice * qty + margin) / (qty * (1 + mmr))
     */
    public BigDecimal calcStaticLiqPrice(String side, BigDecimal entryPrice, BigDecimal margin, BigDecimal quantity) {
        BigDecimal mmr = tradingConfig.getFutures().getMaintenanceMarginRate();
        BigDecimal num;
        BigDecimal den;
        if ("LONG".equals(side)) {
            num = entryPrice.multiply(quantity).subtract(margin);
            den = quantity.multiply(BigDecimal.ONE.subtract(mmr));
        } else {
            num = entryPrice.multiply(quantity).add(margin);
            den = quantity.multiply(BigDecimal.ONE.add(mmr));
        }
        return num.divide(den, 2, RoundingMode.HALF_UP);
    }
}
