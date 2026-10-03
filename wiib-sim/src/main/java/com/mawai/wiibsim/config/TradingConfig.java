package com.mawai.wiibsim.config;

import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
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
    private BigDecimal futuresOpenCommissionRate = new BigDecimal("0.0002");

    /** 合约平仓手续费率（maker口径，默认0.02%） */
    private BigDecimal futuresCloseCommissionRate = new BigDecimal("0.0002");

    /** 合约taker手续费率（默认0.04%，市价/强平成交） */
    private BigDecimal futuresTakerCommissionRate = new BigDecimal("0.0004");

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
        /** 资金费率回退值（0.01%/8h）。正常结算读缓存里的真实费率——结算点由 FundingRateService 拉一次
         *  官方全量（premiumIndex.lastFundingRate）写进去，缓存缺失才回退此值；
         *  另外持仓卡片的 fundingFeePerCycle 预估恒用此值（FuturesTradingServiceImpl），不看缓存。
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
