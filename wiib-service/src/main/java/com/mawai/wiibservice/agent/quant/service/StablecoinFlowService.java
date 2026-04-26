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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StablecoinFlowService extends BaseRestTemplateConfig {

    private static final String FACTOR_NAME = "STABLECOIN_SUPPLY_DELTA";
    private static final String STABLECOIN_CHART_URL = "https://stablecoins.llama.fi/stablecoincharts/all";

    private final FactorHistoryMapper factorHistoryMapper;
    private final RestTemplate restTemplate = createRestTemplate(5000, 10000);

    @Value("${factor.stablecoin_supply_delta.enabled:true}")
    private boolean enabled;

    /** 每小时拉取，但按 DeFiLlama 响应自身 date 去重，不假设它是小时级信号。 */
    @Scheduled(cron = "0 13 * * * *")
    public void collectHourly() {
        if (!enabled) {
            return;
        }
        collectOnce();
    }

    /** 拉取 DeFiLlama 全市场稳定币供给，计算最近两期 USD 供给差值并落库。 */
    public void collectOnce() {
        try {
            String raw = restTemplate.getForObject(STABLECOIN_CHART_URL, String.class);
            JSONArray arr = JSON.parseArray(raw);
            if (arr == null || arr.size() < 2) {
                log.warn("[B2.stablecoin] NO_STABLECOIN_FLOW reason=insufficient_points");
                return;
            }

            JSONObject latest = arr.getJSONObject(arr.size() - 1);
            JSONObject previous = arr.getJSONObject(arr.size() - 2);
            Long latestTs = latest.getLong("date");
            Long previousTs = previous.getLong("date");
            BigDecimal latestSupply = readPeggedUsd(latest);
            BigDecimal previousSupply = readPeggedUsd(previous);
            if (latestTs == null || latestSupply == null || previousSupply == null) {
                log.warn("[B2.stablecoin] NO_STABLECOIN_FLOW reason=missing_date_or_supply");
                return;
            }

            BigDecimal delta = latestSupply.subtract(previousSupply);
            LocalDateTime observedAt = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(latestTs), ZoneOffset.UTC);
            String metadata = JSON.toJSONString(Map.of(
                    "source", "DeFiLlama",
                    "endpoint", STABLECOIN_CHART_URL,
                    "rawMetric", "totalCirculatingUSD.peggedUSD_delta",
                    "latestSupplyUsd", latestSupply,
                    "previousSupplyUsd", previousSupply,
                    "previousDate", previousTs != null ? previousTs : 0
            ));

            for (String symbol : QuantConstants.WATCH_SYMBOLS) {
                FactorHistory row = new FactorHistory();
                row.setSymbol(symbol);
                row.setFactorName(FACTOR_NAME);
                row.setFactorValue(delta);
                row.setObservedAt(observedAt);
                row.setMetadataJson(metadata);
                factorHistoryMapper.upsert(row);
            }
            log.info("[B2.stablecoin] STABLECOIN_SUPPLY_DELTA落库 symbols={} delta={} observedAt={}",
                    QuantConstants.WATCH_SYMBOLS, delta.toPlainString(), observedAt);
        } catch (Exception e) {
            log.warn("[B2.stablecoin] NO_STABLECOIN_FLOW reason={}", e.getMessage());
        }
    }

    /** 读取 DeFiLlama totalCirculatingUSD.peggedUSD，缺字段时交给上层降级。 */
    private BigDecimal readPeggedUsd(JSONObject point) {
        JSONObject total = point.getJSONObject("totalCirculatingUSD");
        return total != null ? total.getBigDecimal("peggedUSD") : null;
    }
}
