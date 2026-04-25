package com.mawai.wiibservice.agent.quant.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.entity.FactorHistory;
import com.mawai.wiibservice.config.DeribitClient;
import com.mawai.wiibservice.mapper.FactorHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class IvPercentileService {

    private static final String FACTOR_NAME = "IV_PERCENTILE";
    private static final DateTimeFormatter DERIBIT_EXPIRY_FMT =
            new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("ddMMMyy").toFormatter();

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

    private BigDecimal parseAtmIv(String bookSummaryJson) {
        if (bookSummaryJson == null || bookSummaryJson.isBlank()) {
            return null;
        }
        JSONObject root = JSON.parseObject(bookSummaryJson);
        JSONArray results = root.getJSONArray("result");
        if (results == null || results.isEmpty()) {
            return null;
        }

        double spot = 0;
        Map<String, List<OptionInfo>> byExpiry = new TreeMap<>(
                Comparator.comparing(s -> LocalDate.parse(s, DERIBIT_EXPIRY_FMT)));
        for (int i = 0; i < results.size(); i++) {
            JSONObject item = results.getJSONObject(i);
            double markIv = item.getDoubleValue("mark_iv");
            if (markIv <= 0) {
                continue;
            }
            if (spot <= 0) {
                spot = item.getDoubleValue("underlying_price");
            }
            String instrument = item.getString("instrument_name");
            if (instrument == null) {
                continue;
            }
            String[] parts = instrument.split("-");
            if (parts.length < 4 || !"C".equals(parts[3])) {
                continue;
            }
            double strike;
            try {
                strike = Double.parseDouble(parts[2]);
            } catch (NumberFormatException e) {
                continue;
            }
            byExpiry.computeIfAbsent(parts[1], k -> new ArrayList<>())
                    .add(new OptionInfo(strike, markIv));
        }

        if (spot <= 0 || byExpiry.isEmpty()) {
            return null;
        }
        List<OptionInfo> nearOptions = byExpiry.values().iterator().next();
        double currentSpot = spot;
        return nearOptions.stream()
                .min(Comparator.comparingDouble(o -> Math.abs(o.strike() - currentSpot)))
                .map(o -> BigDecimal.valueOf(o.markIv()))
                .orElse(null);
    }

    private record OptionInfo(double strike, double markIv) {}
}
