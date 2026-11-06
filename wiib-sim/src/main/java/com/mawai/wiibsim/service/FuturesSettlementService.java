package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.FuturesPosition;

import java.math.BigDecimal;
import java.util.List;

public interface FuturesSettlementService {

    /**
     * 这段行情穿过的限价单：买单（开多、平空）要有价低于挂单价，卖单（开空、平多）要有价高于挂单价，
     * 挂单时就穿价的 taker 单碰到挂单价就算；挂单之后的行情才算。逐笔成交、补漏 K 线、兜底巡检都走这里
     */
    List<TradeHit> limitHits(String symbol, List<PriceTick> ticks);

    void executeTriggeredOrders();

    /** 限价单索引对账：DB里的PENDING挂单全部补回ZSet（纯追加、幂等） */
    void reconcileLimitOrderIndex();

    /** 一个币某个结算时刻的资金费：给这些仓位按 rate 收，单仓失败不影响别的 */
    void chargeFundingFee(String symbol, long fundingTime, BigDecimal rate, List<FuturesPosition> positions);
}
