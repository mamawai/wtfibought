package com.mawai.wiibservice.service.impl;

import com.mawai.wiibcommon.dto.OptionQuoteDTO;
import com.mawai.wiibcommon.entity.OptionContract;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibservice.config.TradingConfig;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.OptionContractService;
import com.mawai.wiibservice.service.OptionPricingService;
import com.mawai.wiibservice.service.StockCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OptionPricingServiceImpl implements OptionPricingService {

    private final OptionContractService contractService;
    private final CacheService cacheService;
    private final StockCacheService stockCacheService;
    private final TradingConfig tradingConfig;

    private static final BigDecimal RISK_FREE_RATE = new BigDecimal("0.03");
    private static final BigDecimal MIN_PREMIUM = new BigDecimal("0.01");
    private static final BigDecimal MIN_TIME_TO_EXPIRY = new BigDecimal("0.0001");

    @Override
    public BigDecimal calculatePremium(String optionType, BigDecimal spotPrice, BigDecimal strike,
                                       LocalDateTime expireAt, BigDecimal sigma) {
        LocalDateTime now = LocalDateTime.now();
        if (spotPrice == null || spotPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.CRYPTO_PRICE_UNAVAILABLE);
        }
        if (sigma == null || sigma.compareTo(BigDecimal.ZERO) <= 0) {
            return calculateIntrinsicValue(optionType, spotPrice, strike).setScale(4, RoundingMode.HALF_UP);
        }
        if (!expireAt.isAfter(now) || Duration.between(now, expireAt).toMillis() <= 60_000L) {
            return calculateIntrinsicValue(optionType, spotPrice, strike).setScale(4, RoundingMode.HALF_UP);
        }

        BigDecimal T = calculateTimeToExpiry(expireAt, now);
        if (T.compareTo(MIN_TIME_TO_EXPIRY) < 0) {
            T = MIN_TIME_TO_EXPIRY;
        }

        double S = spotPrice.doubleValue();
        double K = strike.doubleValue();
        double t = T.doubleValue();
        double vol = sigma.doubleValue();
        double r = RISK_FREE_RATE.doubleValue();

        double sqrtT = Math.sqrt(t);
        double d1 = (Math.log(S / K) + (r + 0.5 * vol * vol) * t) / (vol * sqrtT);
        double d2 = d1 - vol * sqrtT;

        double premium;
        if ("CALL".equals(optionType)) {
            premium = S * cdf(d1) - K * Math.exp(-r * t) * cdf(d2);
        } else {
            premium = K * Math.exp(-r * t) * cdf(-d2) - S * cdf(-d1);
        }

        BigDecimal result = BigDecimal.valueOf(premium).setScale(4, RoundingMode.HALF_UP);
        if (result.compareTo(MIN_PREMIUM) < 0) {
            result = MIN_PREMIUM;
        }
        return result;
    }

    @Override
    public BigDecimal calculateIntrinsicValue(String optionType, BigDecimal spotPrice, BigDecimal strike) {
        BigDecimal diff;
        if ("CALL".equals(optionType)) {
            diff = spotPrice.subtract(strike);
        } else {
            diff = strike.subtract(spotPrice);
        }
        return diff.compareTo(BigDecimal.ZERO) > 0 ? diff.setScale(4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
    }

    private BigDecimal calculateTimeToExpiry(LocalDateTime expireAt, LocalDateTime now) {
        if (expireAt.isBefore(now)) {
            return BigDecimal.ZERO;
        }
        long minutes = Duration.between(now, expireAt).toMinutes();
        double years = minutes / (365.0 * 24 * 60);
        return BigDecimal.valueOf(years).setScale(8, RoundingMode.HALF_UP);
    }

    private double cdf(double x) {
        return 0.5 * (1 + erf(x / Math.sqrt(2)));
    }

    private double erf(double x) {
        double a1 = 0.254829592;
        double a2 = -0.284496736;
        double a3 = 1.421413741;
        double a4 = -1.453152027;
        double a5 = 1.061405429;
        double p = 0.3275911;

        int sign = x < 0 ? -1 : 1;
        x = Math.abs(x);

        double t = 1.0 / (1.0 + p * x);
        double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-x * x);

        return sign * y;
    }

    @Override
    public OptionQuoteDTO getQuote(Long contractId) {
        if (tradingConfig.isNotInTradingHours()) {
            throw new BizException(ErrorCode.NOT_IN_TRADING_HOURS);
        }

        OptionContract contract = contractService.getById(contractId);
        if (contract == null) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "合约不存在");
        }

        BigDecimal spotPrice = getSpotPrice(contract.getStockId());
        BigDecimal premium = calculatePremium(
                contract.getOptionType(), spotPrice, contract.getStrike(),
                contract.getExpireAt(), contract.getSigma());
        BigDecimal intrinsic = calculateIntrinsicValue(
                contract.getOptionType(), spotPrice, contract.getStrike());

        Map<String, String> stockStatic = stockCacheService.getStockStatic(contract.getStockId());

        OptionQuoteDTO dto = new OptionQuoteDTO();
        dto.setContractId(contract.getId());
        dto.setStockCode(stockStatic != null ? stockStatic.get("code") : "");
        dto.setStockName(stockStatic != null ? stockStatic.get("name") : "");
        dto.setOptionType(contract.getOptionType());
        dto.setStrike(contract.getStrike());
        dto.setExpireAt(contract.getExpireAt());
        dto.setPremium(premium);
        dto.setIntrinsicValue(intrinsic);
        dto.setTimeValue(premium.subtract(intrinsic));
        dto.setSpotPrice(spotPrice);
        dto.setSigma(contract.getSigma());
        return dto;
    }

    private BigDecimal getSpotPrice(Long stockId) {
        BigDecimal price = cacheService.getCurrentPrice(stockId);
        if (price == null) {
            Map<String, String> stockStatic = stockCacheService.getStockStatic(stockId);
            if (stockStatic != null) {
                price = new BigDecimal(stockStatic.getOrDefault("prevClose", "0"));
            }
        }
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.CRYPTO_PRICE_UNAVAILABLE);
        }
        return price;
    }
}
