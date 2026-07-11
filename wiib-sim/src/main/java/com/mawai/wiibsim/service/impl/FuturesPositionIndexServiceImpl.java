package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
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

import static com.mawai.wiibsim.service.impl.FuturesHelper.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FuturesPositionIndexServiceImpl implements FuturesPositionIndexService {

    private static final int PRICE_SCALE = 8;

    private final FuturesPositionMapper positionMapper;
    private final CacheService cacheService;
    private final FuturesLeverageBracketRegistry bracketRegistry;
    private final StringRedisTemplate stringRedisTemplate;


    @PostConstruct
    void init() {
        List<FuturesPosition> positions = positionMapper.selectList(new LambdaQueryWrapper<FuturesPosition>()
                .eq(FuturesPosition::getStatus, "OPEN"));

        int ok = 0;
        int failed = 0;
        for (FuturesPosition pos : positions) {
            try {
                registerPositionIndex(pos);
                ok++;
            } catch (Exception e) {
                // 单条仓位失败不挂启动：未配置档位的 symbol 或脏数据时仅记录告警
                failed++;
                log.warn("重建futures ZSet索引失败 posId={} symbol={} side={}: {}",
                        pos.getId(), pos.getSymbol(), pos.getSide(), e.getMessage());
            }
        }

        log.info("重建futures ZSet索引 共{}个仓位 成功={} 失败={}", positions.size(), ok, failed);
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
            BigDecimal liqPrice = calcStaticLiqPrice(symbol, side, position.getEntryPrice(), position.getMargin(),
                    position.getQuantity());
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
     * 强平价计算：按 Binance 档位 MMR+速算数。
     * <p>
     * 强平条件：margin + unrealizedPnl = notional × MMR − maintAmount
     * <p>
     * LONG (价格下跌亏损):
     *   margin + (liq - entry) × qty = liq × qty × MMR − maintAmount
     *   entry × qty − margin − maintAmount = liq × qty × (1 − MMR) </br>
     *   liq = (entry × qty − margin − maintAmount) / (qty × (1 − MMR))
     * <p>
     * SHORT (价格上涨亏损):
     *   margin + (entry − liq) × qty = liq × qty × MMR − maintAmount
     *   entry × qty + margin + maintAmount = liq × qty × (1 + MMR) </br>
     *   liq = (entry × qty + margin + maintAmount) / (qty × (1 + MMR))
     * <p>
     * 档位选择：按"强平价 × qty"定档。强平价不随当前 markPrice 跳动，但候选价若
     * 跨档，必须用落点档位重算。
     * 未配置 symbol 抛 FUTURES_SYMBOL_NOT_CONFIGURED。
     */
    @Override
    public BigDecimal calcStaticLiqPrice(String symbol, String side, BigDecimal entryPrice, BigDecimal margin, BigDecimal quantity) {
        BigDecimal notional = entryPrice.multiply(quantity);
        List<FuturesLeverageBracketRegistry.Bracket> brackets = bracketRegistry.getBrackets(symbol);
        if (brackets == null || brackets.isEmpty()) {
            throw new BizException(ErrorCode.FUTURES_SYMBOL_NOT_CONFIGURED);
        }

        BigDecimal entryBracketPrice = null;
        FuturesLeverageBracketRegistry.Bracket entryBracket = bracketRegistry.findBracket(symbol, notional);
        for (int i = 0; i < brackets.size(); i++) {
            FuturesLeverageBracketRegistry.Bracket bracket = brackets.get(i);
            BigDecimal liqPrice = calcLiqPriceByBracket(side, notional, margin, quantity, bracket);
            if (entryBracketPrice == null && entryBracket != null && entryBracket.tier() == bracket.tier()) {
                entryBracketPrice = liqPrice;
            }

            BigDecimal liqNotional = liqPrice.multiply(quantity);
            boolean lastBracket = i == brackets.size() - 1;
            if (isInBracket(liqNotional, bracket, lastBracket)) {
                return liqPrice;
            }
        }

        // 全档位无落点：保证金极大导致强平价为负或越界。返回开仓档位算出值，前端见负值视为"永不强平"展示 N/A。
        return entryBracketPrice != null
                ? entryBracketPrice
                : calcLiqPriceByBracket(side, notional, margin, quantity, brackets.getLast());
    }

    private BigDecimal calcLiqPriceByBracket(String side, BigDecimal notional, BigDecimal margin, BigDecimal quantity,
                                             FuturesLeverageBracketRegistry.Bracket bracket) {
        BigDecimal mmr = bracket.mmr();
        BigDecimal maintAmount = bracket.maintAmount();
        BigDecimal num;
        BigDecimal den;
        if ("LONG".equals(side)) {
            num = notional.subtract(margin).subtract(maintAmount);
            den = quantity.multiply(BigDecimal.ONE.subtract(mmr));
        } else {
            num = notional.add(margin).add(maintAmount);
            den = quantity.multiply(BigDecimal.ONE.add(mmr));
        }
        return num.divide(den, PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private boolean isInBracket(BigDecimal notional, FuturesLeverageBracketRegistry.Bracket bracket,
                                boolean lastBracket) {
        if (notional.compareTo(bracket.notionalFloor()) < 0) {
            return false;
        }
        return lastBracket || notional.compareTo(bracket.notionalCap()) < 0;
    }
}
