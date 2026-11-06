package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.TradeRecord;

import java.math.BigDecimal;
import java.util.List;

/**
 * 判触发用的一格行情：逐笔成交一笔一格（开、高、低都是成交价），补漏的 1m K 线一根一格。
 * at 是这一格从哪一刻起：挂单/档位在它之后才挂上的不算，超过 1 秒就按旧价处理。
 * 逐笔成交是 feed 收到它的时刻（跟 sim 记的创建时间同一个钟）；K 线是开盘时刻，挂单之后才开的那根才算。
 */
public record PriceTick(BigDecimal open, BigDecimal low, BigDecimal high, long at) {

    public static PriceTick of(TradeRecord trade) {
        return new PriceTick(trade.price(), trade.price(), trade.price(), trade.receivedAt());
    }

    public static PriceTick of(KlineBar bar) {
        return new PriceTick(bar.open(), bar.low(), bar.high(), bar.openTime());
    }

    /** 兜底巡检：缓存里的最新价当作这一刻的一笔成交 */
    public static PriceTick of(BigDecimal price, long nowMs) {
        return new PriceTick(price, price, price, nowMs);
    }

    /** 这些格子里的最低价、最高价 */
    public static BigDecimal[] lowHigh(List<PriceTick> ticks) {
        BigDecimal low = ticks.getFirst().low;
        BigDecimal high = ticks.getFirst().high;
        for (PriceTick t : ticks) {
            low = low.min(t.low);
            high = high.max(t.high);
        }
        return new BigDecimal[]{low, high};
    }

    /**
     * since 之后第一格碰到 level 的下标，没碰到返回 -1。
     * below：价从上往下碰（买单、多头止损、空头止盈），否则从下往上；strict：要穿过去才算（限价单），否则摸到就算
     */
    public static int firstTouch(List<PriceTick> ticks, long since, BigDecimal level, boolean below, boolean strict) {
        for (int i = 0; i < ticks.size(); i++) {
            PriceTick t = ticks.get(i);
            if (t.at < since) continue;
            int cmp = (below ? t.low : t.high).compareTo(level);
            boolean touched = below ? (strict ? cmp < 0 : cmp <= 0) : (strict ? cmp > 0 : cmp >= 0);
            if (touched) return i;
        }
        return -1;
    }

    /** 碰到 level 时的价：开盘就已经在 level 那一边（跳空）按开盘价，不然就是 level；逐笔成交就是那笔的价 */
    public BigDecimal touchPrice(BigDecimal level, boolean below) {
        int cmp = open.compareTo(level);
        return (below ? cmp <= 0 : cmp >= 0) ? open : level;
    }
}
