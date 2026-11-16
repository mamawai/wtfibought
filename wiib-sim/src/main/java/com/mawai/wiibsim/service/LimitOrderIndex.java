package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.CryptoOrder;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.enums.OrderSide;
import com.mawai.wiibsim.util.AfterCommit;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 限价单的价格索引：Redis ZSet，一个币一个方向一个 key，成员是订单 id，分数是挂单价。现货、合约共用。
 * <p>
 * 库是事实，索引跟着库走：
 * <ul>
 * <li>挂：在事务里就等提交后再挂，订单行查得到了索引才出现；</li>
 * <li>摘：立刻摘，什么时候调由调用方定（改库之后）；</li>
 * <li>捞：只查不摘，摘归触发方在改库落定之后做。</li>
 * </ul>
 * 掉出索引的挂单由每小时的对账（两边各自的 reconcileLimitOrderIndex）补回来。
 */
@Component
@RequiredArgsConstructor
public class LimitOrderIndex {

    private static final String SPOT_BUY = "crypto:limit:buy:";
    private static final String SPOT_SELL = "crypto:limit:sell:";
    private static final String FUTURES_OPEN_LONG = "futures:limit:open_long:";
    private static final String FUTURES_OPEN_SHORT = "futures:limit:open_short:";
    private static final String FUTURES_CLOSE_LONG = "futures:limit:close_long:";
    private static final String FUTURES_CLOSE_SHORT = "futures:limit:close_short:";

    private final CacheService cacheService;

    /** 被行情穿过的一张挂单：tick 是第几格行情穿过的，price 是按哪个价触发 */
    public record Crossed<O>(O order, int tick, BigDecimal price) {
    }

    /** 捞的时候要从订单上读的两个值 */
    private record Placed(BigDecimal limitPrice, LocalDateTime createdAt) {
    }

    public void add(CryptoOrder order) {
        add(key(order), order.getId(), order.getLimitPrice());
    }

    public void add(FuturesOrder order) {
        add(key(order), order.getId(), order.getLimitPrice());
    }

    public void remove(CryptoOrder order) {
        cacheService.zRemove(key(order), order.getId().toString());
    }

    public void remove(FuturesOrder order) {
        cacheService.zRemove(key(order), order.getId().toString());
    }

    /** 清空一个币的现货索引 */
    public void clearSpot(String symbol) {
        cacheService.delete(SPOT_BUY + symbol);
        cacheService.delete(SPOT_SELL + symbol);
    }

    /** 这段行情穿过的现货挂单；load 按 id 从库里取订单 */
    public List<Crossed<CryptoOrder>> crossedSpot(String symbol, List<PriceTick> ticks,
                                                  Function<Long, CryptoOrder> load) {
        Function<CryptoOrder, Placed> placed = o -> new Placed(o.getLimitPrice(), o.getCreatedAt());
        List<Crossed<CryptoOrder>> out = new ArrayList<>();
        out.addAll(crossed(SPOT_BUY + symbol, ticks, true, load, placed));
        out.addAll(crossed(SPOT_SELL + symbol, ticks, false, load, placed));
        return out;
    }

    /** 这段行情穿过的合约挂单；load 按 id 从库里取订单 */
    public List<Crossed<FuturesOrder>> crossedFutures(String symbol, List<PriceTick> ticks,
                                                      Function<Long, FuturesOrder> load) {
        Function<FuturesOrder, Placed> placed = o -> new Placed(o.getLimitPrice(), o.getCreatedAt());
        List<Crossed<FuturesOrder>> out = new ArrayList<>();
        out.addAll(crossed(FUTURES_OPEN_LONG + symbol, ticks, true, load, placed));
        out.addAll(crossed(FUTURES_CLOSE_SHORT + symbol, ticks, true, load, placed));
        out.addAll(crossed(FUTURES_OPEN_SHORT + symbol, ticks, false, load, placed));
        out.addAll(crossed(FUTURES_CLOSE_LONG + symbol, ticks, false, load, placed));
        return out;
    }

    private void add(String key, Long orderId, BigDecimal limitPrice) {
        AfterCommit.run(() -> cacheService.zAdd(key, orderId.toString(), limitPrice.doubleValue()));
    }

    /**
     * 一个 key 下被这段行情穿过的挂单。buy 是买方向的单（买入、开多、平空）。
     * 要严格穿过：买单要有价低于挂单价，卖单要有价高于，正好等于不算；只认挂单之后的行情。
     * 库里已经没有的单是过期项，顺手摘掉。
     */
    private <O> List<Crossed<O>> crossed(String key, List<PriceTick> ticks, boolean buy,
                                         Function<Long, O> load, Function<O, Placed> placedOf) {
        BigDecimal[] range = PriceTick.lowHigh(ticks);
        // 买单只看挂单价高于最低价的，卖单只看低于最高价的
        double min = buy ? Math.nextUp(range[0].doubleValue()) : 0;
        double max = buy ? Double.MAX_VALUE : Math.nextDown(range[1].doubleValue());
        Set<ZSetOperations.TypedTuple<String>> candidates = cacheService.zRangeByScoreWithScores(key, min, max);
        List<Crossed<O>> out = new ArrayList<>();
        if (candidates == null) return out;
        for (var tuple : candidates) {
            String id = tuple.getValue();
            O order = load.apply(Long.parseLong(id));
            if (order == null) {
                cacheService.zRemove(key, id);
                continue;
            }
            Placed placed = placedOf.apply(order);
            int i = PriceTick.firstTouch(ticks, PriceTick.toEpochMs(placed.createdAt()), placed.limitPrice(), buy, true);
            if (i < 0) continue;
            out.add(new Crossed<>(order, i, ticks.get(i).touchPrice(placed.limitPrice(), buy)));
        }
        return out;
    }

    private static String key(CryptoOrder order) {
        return (OrderSide.BUY.getCode().equals(order.getOrderSide()) ? SPOT_BUY : SPOT_SELL) + order.getSymbol();
    }

    private static String key(FuturesOrder order) {
        String prefix = switch (order.getOrderSide()) {
            // INCREASE_* 仅存量历史挂单路由（重建/过期/撤单），触发即自清撤单，不再产生新单
            case "OPEN_LONG", "INCREASE_LONG" -> FUTURES_OPEN_LONG;
            case "OPEN_SHORT", "INCREASE_SHORT" -> FUTURES_OPEN_SHORT;
            case "CLOSE_LONG" -> FUTURES_CLOSE_LONG;
            case "CLOSE_SHORT" -> FUTURES_CLOSE_SHORT;
            default -> throw new IllegalArgumentException("Invalid orderSide: " + order.getOrderSide());
        };
        return prefix + order.getSymbol();
    }
}
