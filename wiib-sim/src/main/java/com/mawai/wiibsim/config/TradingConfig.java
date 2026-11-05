package com.mawai.wiibsim.config;

import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.market.FuturesFeeRates;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 交易配置：手续费 / 杠杆融资 / 永续合约参数
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "trading")
public class TradingConfig {

    /** crypto现货手续费率（默认0.1%，用于现货加密货币交易） */
    private BigDecimal cryptoCommissionRate = new BigDecimal("0.001");

    /** 合约开仓手续费率（maker口径，默认0.02%） */
    private BigDecimal futuresOpenCommissionRate = FuturesFeeRates.MAKER;

    /** 合约平仓手续费率（maker口径，默认0.02%） */
    private BigDecimal futuresCloseCommissionRate = FuturesFeeRates.MAKER;

    /** 合约taker手续费率（默认0.04%，市价/止损止盈触发/强平成交） */
    private BigDecimal futuresTakerCommissionRate = FuturesFeeRates.TAKER;

    /** 杠杆/融资配置 */
    private Margin margin = new Margin();

    @Data
    public static class Margin {
        /** 是否启用杠杆 */
        private boolean enabled = true;
        /** 最大杠杆倍率 */
        private int maxLeverage = 50;
        /** 日利率（默认0.05%/天） */
        private BigDecimal dailyInterestRate = new BigDecimal("0.0005");
    }

    /** 永续合约配置 */
    private Futures futures = new Futures();

    @Data
    public static class Futures {
        /** 持仓卡片"每期预估资金费"用的费率（0.01%/期，FuturesTradingServiceImpl 的 fundingFeePerCycle）。
         *  实际结算按 Binance 每个币每期的真实结算记录收（FundingRateService），不用这个值。
         *  符号约定同真实机制：正=多头付、空头收。 */
        private BigDecimal fundingRate = new BigDecimal("0.0001");
        /**
         * 全局最大杠杆上限（与 Binance 主流币档位 1 对齐，单 symbol 实际生效上限由
         * {@link com.mawai.wiibsim.config.FuturesLeverageBracketRegistry} 按 notional 取档）。
         */
        private int maxLeverage = 150;
        /** 开仓余额滑点容差（USDT），补偿前后端价格时间差 */
        private BigDecimal balanceTolerance = new BigDecimal("0.05");
    }

    public BigDecimal calculateCryptoCommission(BigDecimal amount) {
        return amount.multiply(cryptoCommissionRate).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal calculateFuturesCommission(BigDecimal amount, boolean isClose, boolean isTaker) {
        BigDecimal rate = isTaker ? futuresTakerCommissionRate : (isClose ? futuresCloseCommissionRate : futuresOpenCommissionRate);
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    public void validateLimitPrice(BigDecimal limitPrice, BigDecimal marketPrice) {
        if (limitPrice == null || limitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.LIMIT_PRICE_INVALID);
        }
        BigDecimal ratio = limitPrice.divide(marketPrice, 4, RoundingMode.HALF_UP);
        if (ratio.compareTo(new BigDecimal("0.5")) < 0 || ratio.compareTo(new BigDecimal("1.5")) > 0) {
            throw new BizException(ErrorCode.LIMIT_PRICE_INVALID);
        }
    }
}
