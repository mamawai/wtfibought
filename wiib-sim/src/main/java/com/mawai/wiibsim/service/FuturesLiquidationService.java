package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.market.KlineBar;

import java.math.BigDecimal;
import java.util.List;

public interface FuturesLiquidationService {

    void checkOnPriceUpdate(String symbol, BigDecimal markPrice, BigDecimal currentPrice);

    /**
     * 空窗补漏：按空窗期 1m K 线补触发强平/止损（mark 区间）与止盈（合约价区间），
     * 逐仓位、逐档位按创建时间过滤（开仓/挂档之后的行情才算）。
     */
    void recoverGap(String symbol, List<KlineBar> markBars, List<KlineBar> futBars);

    /** 全 symbol 兜底巡检，拿缓存现价再走一遍 {@link #checkOnPriceUpdate} */
    void sweepAll();
}
