package com.mawai.wiibsim.service;

import java.math.BigDecimal;

public interface FuturesLiquidationService {

    void checkOnPriceUpdate(String symbol, BigDecimal markPrice, BigDecimal currentPrice);
}
