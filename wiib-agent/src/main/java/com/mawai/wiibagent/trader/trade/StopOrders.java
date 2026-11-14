package com.mawai.wiibagent.trader.trade;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesStopLossRequest;
import com.mawai.wiibcommon.dto.FuturesOrderProtectionRequest;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibquant.external.sim.SimTradeClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * 改止损的那条路，set_stop_loss 和预案的移止损共用：新止损得站在现价的止损一侧（多单在下、空单在上，
 * 不然下一个 tick 就成了市价平仓），不许比现有最紧那档松；过了才调 sim，整仓一档。
 */
public final class StopOrders {

    private StopOrders() {
    }

    /** 挪没挪成：{@link Moved} 成了，另两种是被哪条护栏挡下 */
    public sealed interface Outcome permits Moved, WrongSide, Looser {
    }

    /** 挪成了；from 是原来最紧那档，原来没挂止损为 null */
    public record Moved(BigDecimal from, List<PendingProtection.Change> pending) implements Outcome {
    }

    /** 新止损在现价的另一侧 */
    public record WrongSide() implements Outcome {
    }

    /** 新止损比现有最紧那档松 */
    public record Looser(BigDecimal current) implements Outcome {
    }

    /**
     * 仓位现挂的止损单按触发先后（最紧的先）摊到仓位现量上，摊不到的不算。
     * 分批止盈或部分平仓之后 sim 只减仓位数量、不减止损单的数量，各档合计会超过仓位；真触发时 sim 也是按仓位现量封顶
     */
    public static List<FuturesStopLoss> effective(FuturesPositionDTO pos) {
        if (pos.getStopLosses() == null) {
            return List.of();
        }
        boolean isLong = "LONG".equals(pos.getSide());
        Comparator<FuturesStopLoss> byPrice = Comparator.comparing(FuturesStopLoss::getPrice);
        List<FuturesStopLoss> out = new ArrayList<>();
        BigDecimal left = pos.getQuantity();
        for (FuturesStopLoss sl : pos.getStopLosses().stream().sorted(isLong ? byPrice.reversed() : byPrice).toList()) {
            BigDecimal qty = sl.getQuantity().min(left);
            if (qty.signum() > 0) {
                out.add(new FuturesStopLoss(sl.getId(), sl.getPrice(), qty));
                left = left.subtract(qty);
            }
        }
        return out;
    }

    /**
     * 这个仓位当前的止损单全被打掉，相对开仓价亏多少（止损已经挪过成本的是负数，即锁住的利润）；不计手续费，没挂止损的仓位算 0。
     * 数量按仓位现量封顶：分批止盈之后止损单上的数量还是旧的
     */
    public static BigDecimal lossAtStops(FuturesPositionDTO pos) {
        return effective(pos).stream().map(sl -> ("LONG".equals(pos.getSide())
                ? pos.getEntryPrice().subtract(sl.getPrice()) : sl.getPrice().subtract(pos.getEntryPrice()))
                .multiply(sl.getQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 这张开仓挂单成交后被它带的止损打掉的价差损失，不含手续费；平仓挂单算 0 */
    public static BigDecimal lossAtStops(FuturesOrderResponse order) {
        if (order.getOrderSide() == null || !order.getOrderSide().startsWith("OPEN_")) {
            return BigDecimal.ZERO;
        }
        FuturesPositionDTO pending = new FuturesPositionDTO();
        pending.setSide(order.getOrderSide().substring("OPEN_".length()));
        pending.setEntryPrice(order.getLimitPrice());
        pending.setQuantity(order.getQuantity());
        pending.setStopLosses(order.getStopLosses());
        return lossAtStops(pending);
    }

    /** 仓位现挂止损里最紧的那一档；没挂为 null */
    public static BigDecimal tightest(FuturesPositionDTO pos) {
        return TradeGuard.extremePrice(pos.getStopLosses() == null ? List.of()
                : pos.getStopLosses().stream().map(FuturesStopLoss::getPrice).toList(), "LONG".equals(pos.getSide()));
    }

    public static Outcome move(SimTradeClient sim, long simUserId, FuturesPositionDTO pos, BigDecimal newStop, BigDecimal mark,
                               String group, Function<Exception, String> describe) {
        boolean isLong = "LONG".equals(pos.getSide());
        if (isLong ? newStop.compareTo(mark) >= 0 : newStop.compareTo(mark) <= 0) {
            return new WrongSide();
        }
        BigDecimal tightest = tightest(pos);
        if (tightest != null && (isLong ? newStop.compareTo(tightest) < 0 : newStop.compareTo(tightest) > 0)) {
            return new Looser(tightest);
        }
        // 先改同组还挂着的档，最后改仓位：一档要么挂着时被改好，要么已经成交、被最后那次整仓止损盖住
        List<PendingProtection.Change> changes = new ArrayList<>();
        for (FuturesOrderResponse order : PendingProtection.orders(sim, simUserId, pos.getSymbol(), pos.getSide(), group)) {
            BigDecimal ownStop = TradeGuard.extremePrice(order.getStopLosses() == null ? List.of()
                    : order.getStopLosses().stream().map(FuturesStopLoss::getPrice).toList(), isLong);
            // 新止损得在这档入场价的外侧，还得比它自己带的止损紧，不然这档保留原样
            boolean outside = isLong ? newStop.compareTo(order.getLimitPrice()) < 0 : newStop.compareTo(order.getLimitPrice()) > 0;
            boolean tighter = ownStop == null || (isLong ? newStop.compareTo(ownStop) > 0 : newStop.compareTo(ownStop) < 0);
            if (!outside || !tighter) {
                changes.add(new PendingProtection.Change(order.getOrderId(), PendingProtection.RETAINED, order.getStopLosses(), null));
                continue;
            }
            FuturesOpenRequest.StopLoss sl = new FuturesOpenRequest.StopLoss();
            sl.setPrice(newStop);
            sl.setQuantity(order.getQuantity());
            FuturesOrderProtectionRequest protection = new FuturesOrderProtectionRequest();
            protection.setStopLosses(List.of(sl));
            changes.add(PendingProtection.update(sim, simUserId, order, protection, order.getStopLosses(), describe));
        }
        FuturesStopLossRequest req = new FuturesStopLossRequest();
        req.setPositionId(pos.getId());
        FuturesStopLossRequest.StopLossItem item = new FuturesStopLossRequest.StopLossItem();
        item.setPrice(newStop);
        // 不传数量：sim 在仓位锁内取整仓现量。
        req.setStopLosses(List.of(item));
        sim.setStopLoss(simUserId, req);
        return new Moved(tightest, changes);
    }
}
