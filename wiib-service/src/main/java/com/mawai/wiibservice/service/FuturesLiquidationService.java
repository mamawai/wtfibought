package com.mawai.wiibservice.service;

import java.math.BigDecimal;

public interface FuturesLiquidationService {

    void checkOnPriceUpdate(String symbol, BigDecimal markPrice, BigDecimal currentPrice);
}
