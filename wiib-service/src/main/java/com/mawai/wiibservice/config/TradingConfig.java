package com.mawai.wiibservice.config;

import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 交易配置
 * 包含手续费、滑点保护、交易时段等配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "trading")
public class TradingConfig {

    /** 手续费率（默认0.05%） */
    private BigDecimal commissionRate = new BigDecimal("0.0005");

    /** 最低手续费（默认5元） */
    private BigDecimal minCommission = new BigDecimal("5.00");

    /** crypto现货手续费率（默认0.1%，用于现货加密货币交易） */
    private BigDecimal cryptoCommissionRate = new BigDecimal("0.001");

    /** 合约maker手续费率（默认0.02%，兼容旧配置名） */
    private BigDecimal futuresOpenCommissionRate = new BigDecimal("0.0002");

    /** 合约maker手续费率（默认0.02%，兼容旧配置名） */
    private BigDecimal futuresCloseCommissionRate = new BigDecimal("0.0002");

    /** 合约taker手续费率（默认0.04%，市价/强平成交） */
    private BigDecimal futuresTakerCommissionRate = new BigDecimal("0.0004");

    /** 市价单滑点保护（默认±2%） */
    private BigDecimal slippageLimit = new BigDecimal("0.02");

    /** 限价单最长有效期（小时） */
    private int limitOrderMaxHours = 24;

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
        /** 维持保证金率（默认0.5%） */
        private BigDecimal maintenanceMarginRate = new BigDecimal("0.005");
        /** 资金费率（默认0.01%/8h） */
        private BigDecimal fundingRate = new BigDecimal("0.0001");
        /** 最大杠杆倍数 */
        private int maxLeverage = 250;
        /** 开仓余额滑点容差（USDT），补偿前后端价格时间差 */
        private BigDecimal balanceTolerance = new BigDecimal("0.05");
        /** 仓位操作分布式锁超时时间（秒） */
        private int lockTimeoutSeconds = 30;
    }

    /** 乐观锁最大重试次数 */
    private int optimisticLockMaxRetries = 3;

    /** 限价单并发处理配置 */
    private LimitOrderProcessing limitOrderProcessing = new LimitOrderProcessing();

    @Data
    public static class LimitOrderProcessing {
        /** 最大并发处理订单数（建议设为数据库连接池的一半） */
        private int maxConcurrency = 50;
        /** 单个订单处理超时时间（秒） */
        private int orderTimeoutSeconds = 6;
    }

    /** 是否启用交易时段限制 */
    private boolean tradingHoursEnabled = true;

    /** 交易时段配置 */
    private TradingHours tradingHours = new TradingHours();

    @Data
    public static class TradingHours {
        private String morningStart = "09:30";
        private String morningEnd = "11:30";
        private String afternoonStart = "13:00";
        private String afternoonEnd = "15:00";
    }

    /**
     * 检查当前是否在交易时段内
     * 周一至周五 + 上午/下午时段
     */
    public boolean isNotInTradingHours() {
        if (!tradingHoursEnabled) {
            return false;
        }

        DayOfWeek day = LocalDate.now().getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return true;
        }

        LocalTime now = LocalTime.now();
        LocalTime morningStart = LocalTime.parse(tradingHours.getMorningStart());
        LocalTime morningEnd = LocalTime.parse(tradingHours.getMorningEnd());
        LocalTime afternoonStart = LocalTime.parse(tradingHours.getAfternoonStart());
        LocalTime afternoonEnd = LocalTime.parse(tradingHours.getAfternoonEnd());

        boolean inMorning = !now.isBefore(morningStart) && !now.isAfter(morningEnd);
        boolean inAfternoon = !now.isBefore(afternoonStart) && !now.isAfter(afternoonEnd);

        return !inMorning && !inAfternoon;
    }

    /**
     * 计算手续费（不满5元按5元收取）
     */
    public BigDecimal calculateCommission(BigDecimal amount) {
        BigDecimal commission = amount.multiply(commissionRate).setScale(2, RoundingMode.HALF_UP);
        return commission.compareTo(minCommission) < 0 ? minCommission : commission;
    }

    public BigDecimal calculateCryptoCommission(BigDecimal amount) {
        return amount.multiply(cryptoCommissionRate).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal calculateFuturesCommission(BigDecimal amount, boolean isTaker) {
        return calculateFuturesCommission(amount, false, isTaker);
    }

    public BigDecimal calculateFuturesCommission(BigDecimal amount, boolean isClose, boolean isTaker) {
        BigDecimal rate = isTaker ? futuresTakerCommissionRate
                : (isClose ? futuresCloseCommissionRate : futuresOpenCommissionRate);
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 检查价格是否在滑点保护范围内
     * 
     * @param expectedPrice 预期价格
     * @param actualPrice   实际价格
     * @return 是否在允许范围内
     */
    public boolean isWithinSlippageLimit(BigDecimal expectedPrice, BigDecimal actualPrice) {
        BigDecimal diff = actualPrice.subtract(expectedPrice).abs();
        BigDecimal maxDiff = expectedPrice.multiply(slippageLimit);
        return diff.compareTo(maxDiff) <= 0;
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
