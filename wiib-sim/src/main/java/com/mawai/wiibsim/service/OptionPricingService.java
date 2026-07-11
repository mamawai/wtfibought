package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.dto.OptionQuoteDTO;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 期权定价服务
 * 基于Black-Scholes模型计算期权权利金
 */
public interface OptionPricingService {

    /**
     * 计算期权权利金
     * @param optionType CALL/PUT
     * @param spotPrice 标的当前价格
     * @param strike 行权价
     * @param expireAt 到期时间
     * @param sigma 年化波动率
     * @return 权利金
     */
    BigDecimal calculatePremium(String optionType, BigDecimal spotPrice, BigDecimal strike,
                                LocalDateTime expireAt, BigDecimal sigma);

    /**
     * 计算期权内在价值
     * @param optionType CALL/PUT
     * @param spotPrice 标的当前价格
     * @param strike 行权价
     * @return 内在价值
     */
    BigDecimal calculateIntrinsicValue(String optionType, BigDecimal spotPrice, BigDecimal strike);

    OptionQuoteDTO getQuote(Long contractId);

    /** 标的现价：实时价缺失时回退 prevClose；仍无效抛价格不可用。定价与下单共用此口径。 */
    BigDecimal getSpotPrice(Long stockId);
}
