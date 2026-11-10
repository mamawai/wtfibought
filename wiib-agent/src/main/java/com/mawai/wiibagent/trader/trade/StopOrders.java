package com.mawai.wiibagent.trader.trade;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.dto.FuturesStopLossRequest;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibquant.external.sim.SimTradeClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 改止损的那条路，set_stop_loss、预案的移止损、跟踪止损共用：新止损得站在现价的止损一侧（多单在下、空单在上，
 * 不然下一个 tick 就成了市价平仓），不许比现有最紧那档松；过了才调 sim，整仓一档。
 */
public final class StopOrders {

    private StopOrders() {
    }

    /** 挪没挪成：{@link Moved} 成了，另两种是被哪条护栏挡下 */
    public sealed interface Outcome permits Moved, WrongSide, Looser {
    }

    /** 挪成了；from 是原来最紧那档，原来没挂止损为 null */
    public record Moved(BigDecimal from) implements Outcome {
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
     * 止损单数量合计超过仓位现量时，按 {@link #effective} 重挂一遍，没超就不动。
     * 加仓前要做：sim 并单时各档合计不得超过合并后的持仓，带着过期数量会把加仓拒掉
     */
    public static void refit(SimTradeClient sim, long simUserId, FuturesPositionDTO pos) {
        if (pos.getStopLosses() == null || pos.getStopLosses().stream().map(FuturesStopLoss::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(pos.getQuantity()) <= 0) {
            return;
        }
        FuturesStopLossRequest req = new FuturesStopLossRequest();
        req.setPositionId(pos.getId());
        req.setStopLosses(effective(pos).stream().map(sl -> {
            FuturesStopLossRequest.StopLossItem item = new FuturesStopLossRequest.StopLossItem();
            item.setPrice(sl.getPrice());
            item.setQuantity(sl.getQuantity());
            return item;
        }).toList());
        sim.setStopLoss(simUserId, req);
    }

    /** 仓位现挂止损里最紧的那一档；没挂为 null */
    public static BigDecimal tightest(FuturesPositionDTO pos) {
        return TradeGuard.extremePrice(pos.getStopLosses() == null ? List.of()
                : pos.getStopLosses().stream().map(FuturesStopLoss::getPrice).toList(), "LONG".equals(pos.getSide()));
    }

    public static Outcome move(SimTradeClient sim, long simUserId, FuturesPositionDTO pos, BigDecimal newStop, BigDecimal mark) {
        boolean isLong = "LONG".equals(pos.getSide());
        if (isLong ? newStop.compareTo(mark) >= 0 : newStop.compareTo(mark) <= 0) {
            return new WrongSide();
        }
        BigDecimal tightest = tightest(pos);
        if (tightest != null && (isLong ? newStop.compareTo(tightest) < 0 : newStop.compareTo(tightest) > 0)) {
            return new Looser(tightest);
        }
        FuturesStopLossRequest req = new FuturesStopLossRequest();
        req.setPositionId(pos.getId());
        FuturesStopLossRequest.StopLossItem item = new FuturesStopLossRequest.StopLossItem();
        item.setPrice(newStop);
        // 覆盖量从仓位现取：加仓、分批止盈之后仓位数量变了，止损跟着盖住整仓
        item.setQuantity(pos.getQuantity());
        req.setStopLosses(List.of(item));
        sim.setStopLoss(simUserId, req);
        return new Moved(tightest);
    }
}
