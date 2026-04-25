package com.mawai.wiibservice.agent.quant.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.entity.FactorHistory;
import com.mawai.wiibservice.config.BaseRestTemplateConfig;
import com.mawai.wiibservice.mapper.FactorHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrossMarketService extends BaseRestTemplateConfig {

    private static final String FACTOR_NAME = "CROSS_MARKET_RISK";
    private static final String YAHOO_CHART_URL = "https://query1.finance.yahoo.com/v8/finance/chart/{ticker}";
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    private final FactorHistoryMapper factorHistoryMapper;
    private final RestTemplate restTemplate = createRestTemplate(5000, 10000);

    @Value("${factor.cross_market_risk.enabled:true}")
    private boolean enabled;

    /** 每 5min shadow 采集，休市期间允许复用最后一个 bar 但 metadata 标 stale。 */
    @Scheduled(cron = "0 */5 * * * *")
    public void collectEveryFiveMinutes() {
        if (!enabled) {
            return;
        }
        collectOnce();
    }

    public void collectOnce() {
        try {
            Map<String, MarketQuote> quotes = new LinkedHashMap<>();
            quotes.put("DXY", fetchQuote("DX-Y.NYB"));
            quotes.put("QQQ", fetchQuote("QQQ"));
            quotes.put("GOLD", fetchQuote("GC=F"));
            quotes.put("US10Y", fetchQuote("^TNX"));

            if (quotes.values().stream().anyMatch(q -> q == null)) {
                log.warn("[B2.cross] NO_CROSS_MARKET reason=partial_quote_missing quotes={}", quotes.keySet());
                return;
            }

            double riskScore = calcRiskScore(quotes);
            Instant observedInstant = quotes.values().stream()
                    .map(MarketQuote::observedAt)
                    .min(Instant::compareTo)
                    .orElse(Instant.now());
            boolean stale = quotes.values().stream()
                    .anyMatch(q -> Duration.between(q.observedAt(), Instant.now()).toHours() > 36);
            LocalDateTime observedAt = LocalDateTime.ofInstant(observedInstant, SYSTEM_ZONE);
            String metadata = JSON.toJSONString(Map.of(
                    "source", "YahooFinanceChart",
                    "stale", stale,
                    "quotes", quotes
            ));

            for (String symbol : QuantConstants.WATCH_SYMBOLS) {
                FactorHistory row = new FactorHistory();
                row.setSymbol(symbol);
                row.setFactorName(FACTOR_NAME);
                row.setFactorValue(BigDecimal.valueOf(riskScore));
                row.setObservedAt(observedAt);
                row.setMetadataJson(metadata);
                factorHistoryMapper.upsert(row);
            }

            if (stale) {
                log.warn("[B2.cross] STALE_CROSS_MARKET observedAt={} score={}", observedAt, riskScore);
            }
            log.info("[B2.cross] CROSS_MARKET_RISK落库 symbols={} score={} observedAt={}",
                    QuantConstants.WATCH_SYMBOLS, String.format("%.6f", riskScore), observedAt);
        } catch (Exception e) {
            log.warn("[B2.cross] NO_CROSS_MARKET reason={}", e.getMessage());
        }
    }

    private MarketQuote fetchQuote(String ticker) {
        String url = UriComponentsBuilder
                .fromUriString(YAHOO_CHART_URL)
                .queryParam("range", "2d")
                .queryParam("interval", "5m")
                .build(ticker)
                .toString();
        String raw = restTemplate.getForObject(url, String.class);
        JSONObject root = JSON.parseObject(raw);
        JSONArray result = root.getJSONObject("chart").getJSONArray("result");
        if (result == null || result.isEmpty()) {
            return null;
        }
        JSONObject first = result.getJSONObject(0);
        JSONArray timestamps = first.getJSONArray("timestamp");
        JSONArray closes = first.getJSONObject("indicators")
                .getJSONArray("quote")
                .getJSONObject(0)
                .getJSONArray("close");
        if (timestamps == null || closes == null || timestamps.isEmpty()) {
            return null;
        }

        Double latest = null;
        Double previous = null;
        Long latestTs = null;
        for (int i = closes.size() - 1; i >= 0; i--) {
            Double close = closes.getDouble(i);
            if (close == null || close <= 0) {
                continue;
            }
            if (latest == null) {
                latest = close;
                latestTs = timestamps.getLong(i);
            } else {
                previous = close;
                break;
            }
        }
        if (latest == null || previous == null || latestTs == null) {
            return null;
        }
        double changePct = (latest - previous) / previous;
        return new MarketQuote(ticker, latest, changePct, Instant.ofEpochSecond(latestTs));
    }

    private double calcRiskScore(Map<String, MarketQuote> quotes) {
        double dxy = quotes.get("DXY").changePct();
        double qqq = quotes.get("QQQ").changePct();
        double gold = quotes.get("GOLD").changePct();
        double us10y = quotes.get("US10Y").changePct();
        // 正值=risk-off：美元/利率上行、股票/黄金走弱时对加密更不友好。
        return 0.35 * dxy + 0.25 * us10y - 0.30 * qqq - 0.10 * gold;
    }

    public record MarketQuote(String ticker, double close, double changePct, Instant observedAt) {}
}
