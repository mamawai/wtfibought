package com.mawai.wiibquant.agent.quant.service;

import com.alibaba.fastjson2.JSON;
import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.entity.FactorHistory;
import com.mawai.wiibquant.config.DeribitClient;
import com.mawai.wiibquant.mapper.FactorHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * ATM IV 历史 shadow 采集器。
 *
 * <p>每小时从 Deribit 期权簿取近月最接近现价的 call mark_iv，作为 ATM IV 原值写入
 * {@code factor_history}。用于后续计算 30 天 IV 分位，当前不直接影响预测和开仓。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IvPercentileService {

    private static final String FACTOR_NAME = "IV_PERCENTILE";

    private final DeribitClient deribitClient;
    private final FactorHistoryMapper factorHistoryMapper;

    @Value("${factor.iv_percentile.enabled:true}")
    private boolean enabled;

    /**
     * Deribit bookSummary 没有稳定观测时间字段，这里用小时桶去重。
     * factor_value 存 ATM IV 原值；percentile 由 factor_history 历史窗口 SQL 计算。
     */
    @Scheduled(cron = "0 3 * * * *")
    public void collectHourly() {
        if (!enabled) {
            return;
        }
        for (String symbol : QuantConstants.WATCH_SYMBOLS) {
            collectOnce(symbol);
        }
    }

    /** 拉取单个币种期权簿，取近月最接近现价的 call IV 作为 ATM IV 原值。 */
    public void collectOnce(String symbol) {
        String normalized = QuantConstants.normalizeSymbol(symbol);
        String currency = normalized.replace("USDT", "").replace("USDC", "");
        try {
            String bookSummary = deribitClient.getBookSummaryByCurrency(currency);
            BigDecimal atmIv = parseAtmIv(bookSummary);
            if (atmIv == null || atmIv.signum() <= 0) {
                log.warn("[B2.iv] NO_IV_PERCENTILE symbol={} currency={} reason=no_atm_iv", normalized, currency);
                return;
            }

            LocalDateTime observedAt = LocalDateTime.now()
                    .withMinute(0).withSecond(0).withNano(0);

            FactorHistory row = new FactorHistory();
            row.setSymbol(normalized);
            row.setFactorName(FACTOR_NAME);
            row.setFactorValue(atmIv);
            row.setObservedAt(observedAt);
            row.setMetadataJson(JSON.toJSONString(Map.of(
                    "source", "Deribit",
                    "rawMetric", "ATM_IV",
                    "currency", currency
            )));
            factorHistoryMapper.upsert(row);

            BigDecimal percentile = factorHistoryMapper.selectPercentileRank(
                    normalized, FACTOR_NAME, atmIv, observedAt.minusDays(30), observedAt.plusSeconds(1));
            log.info("[B2.iv] IV_PERCENTILE落库 symbol={} atmIv={} percentile={} observedAt={}",
                    normalized, atmIv.toPlainString(),
                    percentile != null ? percentile.toPlainString() : "0",
                    observedAt);
        } catch (Exception e) {
            log.warn("[B2.iv] NO_IV_PERCENTILE symbol={} reason={}", normalized, e.getMessage());
        }
    }

    /** 近月最接近现价(underlying_price)的 call mark_iv；解析统一走 {@link DeribitOptionBook}(日期格式坑见其注释)。 */
    private BigDecimal parseAtmIv(String bookSummaryJson) {
        DeribitOptionBook book = DeribitOptionBook.parse(bookSummaryJson);
        if (book.underlyingPrice() <= 0) {
            return null;
        }
        for (List<DeribitOptionBook.Quote> group : book.byExpiry().values()) {
            double iv = DeribitOptionBook.atmCallIv(group, book.underlyingPrice());
            if (iv > 0) {
                return BigDecimal.valueOf(iv);
            }
        }
        return null;
    }
}
