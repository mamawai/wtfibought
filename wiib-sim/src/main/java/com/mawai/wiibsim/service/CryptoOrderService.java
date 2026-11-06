package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.spring.service.IService;
import com.mawai.wiibcommon.dto.CryptoOrderRequest;
import com.mawai.wiibcommon.dto.CryptoOrderResponse;
import com.mawai.wiibcommon.entity.CryptoOrder;

import java.util.List;

public interface CryptoOrderService extends IService<CryptoOrder> {

    CryptoOrderResponse buy(Long userId, CryptoOrderRequest request);

    CryptoOrderResponse sell(Long userId, CryptoOrderRequest request);

    CryptoOrderResponse cancel(Long userId, Long orderId);

    IPage<CryptoOrderResponse> getUserOrders(Long userId, String status, int pageNum, int pageSize, String symbol);

    /** 执行已触发的限价单 */
    void  executeTriggeredOrders();

    /**
     * 这段行情穿过的限价单：买单要有价低于挂单价，卖单要有价高于挂单价，挂单之后的行情才算。
     * 逐笔成交、补漏 K 线、兜底巡检都走这里
     */
    List<TradeHit> limitHits(String symbol, List<PriceTick> ticks);

    /** 限价单索引对账：DB里的PENDING挂单全部补回ZSet（纯追加、幂等） */
    void reconcileLimitOrderIndex();

    /** 最新成交20条-匿名 */
    List<CryptoOrderResponse> getLatestOrders();
}
