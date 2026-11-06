package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.market.KlineBar;

import java.math.BigDecimal;
import java.util.List;

public interface FuturesLiquidationService {

    /** markprice tick：逐仓强平看标记价 */
    void checkOnPriceUpdate(String symbol, BigDecimal markPrice);

    /** 标记价空窗补漏：逐仓强平，逐仓位按开仓时间过滤（开仓之后的行情才算） */
    void recoverGap(String symbol, List<KlineBar> markBars);

    /**
     * 这段行情摸到的止损/止盈（成交价摸到触发价就算）。命中的先从索引摘下来，同一档同一时刻只有一条路径在处理；
     * 档位挂上之前的行情不算、原样放回，执行失败也放回。逐笔成交、补漏 K 线、兜底巡检都走这里
     */
    List<TradeHit> stopHits(String symbol, List<PriceTick> ticks);

    /** 全 symbol 兜底巡检，拿缓存的标记价再走一遍 {@link #checkOnPriceUpdate} */
    void sweepAll();
}
