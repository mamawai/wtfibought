package com.mawai.wiibagent.trader.trade;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.dto.FuturesStopLossRequest;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibquant.external.sim.SimTradeClient;

import java.math.BigDecimal;
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
