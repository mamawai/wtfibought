package com.mawai.wiibsim.service;

import java.math.BigDecimal;

public interface FuturesSettlementService {

    void onPriceUpdate(String symbol, BigDecimal price);

    void recoverLimitOrders(String symbol, BigDecimal periodLow, BigDecimal periodHigh);

    void expireLimitOrders();

    void executeTriggeredOrders();

    void chargeFundingFeeAll();
}
