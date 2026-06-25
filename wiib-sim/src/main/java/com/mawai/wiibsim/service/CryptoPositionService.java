package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mawai.wiibcommon.entity.CryptoPosition;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface CryptoPositionService extends IService<CryptoPosition> {

    CryptoPosition findByUserAndSymbol(Long userId, String symbol);

    List<CryptoPosition> getUserPositions(Long userId);

    /** 单用户crypto持仓市值（自动从Redis取各币种实时价） */
    BigDecimal calculateCryptoMarketValue(Long userId);

    /** 批量取所有crypto币种实时价格 {symbol -> price}，供排行榜等批量场景 */
    Map<String, BigDecimal> fetchCryptoPriceMap();

    void addPosition(Long userId, String symbol, BigDecimal quantity, BigDecimal price, BigDecimal discount);

    void reducePosition(Long userId, String symbol, BigDecimal quantity);

    void freezePosition(Long userId, String symbol, BigDecimal quantity);

    void unfreezePosition(Long userId, String symbol, BigDecimal quantity);

    void deductFrozenPosition(Long userId, String symbol, BigDecimal quantity);
}
