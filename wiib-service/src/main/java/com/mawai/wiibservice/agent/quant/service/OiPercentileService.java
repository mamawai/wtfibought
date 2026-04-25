package com.mawai.wiibservice.agent.quant.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.entity.FactorHistory;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.mapper.FactorHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OiPercentileService {

    private static final String FACTOR_NAME = "OI_PERCENTILE";
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    private final BinanceRestClient binanceRestClient;
    private final FactorHistoryMapper factorHistoryMapper;

    @Value("${factor.oi_percentile.enabled:true}")
    private boolean enabled;

    /** 每小时补最近 24 根 1h OI，靠唯一键自然去重，30 天后形成 720 点窗口。 */
    @Scheduled(cron = "0 8 * * * *")
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
        try {
            String raw = binanceRestClient.getOpenInterestHist(normalized, "1h", 24);
            JSONArray arr = JSON.parseArray(raw);
            if (arr == null || arr.isEmpty()) {
                log.warn("[B2.oi] NO_OI_PERCENTILE symbol={} reason=empty_response", normalized);
                return;
            }

            int saved = 0;
            FactorHistory latest = null;
            for (int i = 0; i < arr.size(); i++) {
                JSONObject item = arr.getJSONObject(i);
                BigDecimal value = readOiValue(item);
                Long timestamp = item.getLong("timestamp");
                if (value == null || timestamp == null) {
                    continue;
                }

                LocalDateTime observedAt = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(timestamp), SYSTEM_ZONE);
                FactorHistory row = new FactorHistory();
                row.setSymbol(normalized);
                row.setFactorName(FACTOR_NAME);
                row.setFactorValue(value);
                row.setObservedAt(observedAt);
                row.setMetadataJson(JSON.toJSONString(Map.of(
                        "source", "Binance",
                        "period", "1h",
                        "rawMetric", item.containsKey("sumOpenInterestValue")
                                ? "sumOpenInterestValue" : "sumOpenInterest"
                )));
                factorHistoryMapper.upsert(row);
                saved++;
                if (latest == null || observedAt.isAfter(latest.getObservedAt())) {
                    latest = row;
                }
            }

            BigDecimal percentile = latest != null
                    ? factorHistoryMapper.selectPercentileRank(
                            normalized, FACTOR_NAME, latest.getFactorValue(),
                            latest.getObservedAt().minusDays(30), latest.getObservedAt().plusSeconds(1))
                    : BigDecimal.ZERO;
            log.info("[B2.oi] OI_PERCENTILE落库 symbol={} saved={} latest={} percentile={}",
                    normalized, saved,
                    latest != null ? latest.getObservedAt() : "-",
                    percentile != null ? percentile.toPlainString() : "0");
        } catch (Exception e) {
            log.warn("[B2.oi] NO_OI_PERCENTILE symbol={} reason={}", normalized, e.getMessage());
        }
    }

    private BigDecimal readOiValue(JSONObject item) {
        BigDecimal value = item.getBigDecimal("sumOpenInterestValue");
        if (value != null && value.signum() > 0) {
            return value;
        }
        value = item.getBigDecimal("sumOpenInterest");
        return value != null && value.signum() > 0 ? value : null;
    }
}
