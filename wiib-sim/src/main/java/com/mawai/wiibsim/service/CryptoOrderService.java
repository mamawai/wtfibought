package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.spring.service.IService;
import com.mawai.wiibcommon.dto.CryptoOrderRequest;
import com.mawai.wiibcommon.dto.CryptoOrderResponse;
import com.mawai.wiibcommon.entity.CryptoOrder;
import com.mawai.wiibcommon.market.KlineBar;

import java.math.BigDecimal;
import java.util.List;

public interface CryptoOrderService extends IService<CryptoOrder> {

    CryptoOrderResponse buy(Long userId, CryptoOrderRequest request);

    CryptoOrderResponse sell(Long userId, CryptoOrderRequest request);

    CryptoOrderResponse cancel(Long userId, Long orderId);

    IPage<CryptoOrderResponse> getUserOrders(Long userId, String status, int pageNum, int pageSize, String symbol);

    /** 执行已触发的限价单 */
    void  executeTriggeredOrders();

    /** WS价格到达时检查限价单（事件驱动） */
    void onPriceUpdate(String symbol, BigDecimal price);

    /** 空窗补漏：按空窗期 1m K 线补触发限价单，逐单按创建时间过滤（挂单之后的行情才算） */
    void recoverGap(String symbol, List<KlineBar> bars);

    /** 限价单索引对账：DB里的PENDING挂单全部补回ZSet（纯追加、幂等） */
    void reconcileLimitOrderIndex();

    /** 最新成交20条-匿名 */
    List<CryptoOrderResponse> getLatestOrders();
}
