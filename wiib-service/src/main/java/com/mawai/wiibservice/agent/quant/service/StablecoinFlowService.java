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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 稳定币供给 shadow 采集器。
 *
 * <p>定时读取 DeFiLlama 全市场稳定币供应，计算最近两期 USD 供应差值并写入 {@code factor_history}。
 * 这是慢速资金背景因子，只用于后续研究和回放，当前不参与分钟级开仓。</p>
 *
 * <p>DeFiLlama 的 all 接口本身就返回全历史，{@link #collectOnce()} 只取末尾一期；
 * {@link #backfillHistory()} 复用同一解析把每一相邻期的供给差都灌进库（measure-first，喂回测）。</p>
 */
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

    /** 拉取 DeFiLlama 全市场稳定币供给，取最新一期的供给差值落库（对每个 watch symbol 各一行）。 */
    public void collectOnce() {
        try {
            List<StablecoinPoint> points = parseSupplyDeltas(
                    restTemplate.getForObject(STABLECOIN_CHART_URL, String.class));
            if (points.isEmpty()) {
                log.warn("[B2.stablecoin] NO_STABLECOIN_FLOW reason=insufficient_or_missing");
                return;
            }
            StablecoinPoint latest = points.get(points.size() - 1);   // 升序末尾 = 最近两期之差
            for (String symbol : QuantConstants.WATCH_SYMBOLS) {
                factorHistoryMapper.upsert(toRow(symbol, latest));
            }
            log.info("[B2.stablecoin] STABLECOIN_SUPPLY_DELTA落库 symbols={} delta={} observedAt={}",
                    QuantConstants.WATCH_SYMBOLS, latest.delta().toPlainString(), toObservedAt(latest.dateSec()));
        } catch (Exception e) {
            log.warn("[B2.stablecoin] NO_STABLECOIN_FLOW reason={}", e.getMessage());
        }
    }

    /** 全历史回填：把每一相邻期供给差对每个 watch symbol 幂等 upsert。返回历史点数。 */
    public int backfillHistory() {
        try {
            List<StablecoinPoint> points = parseSupplyDeltas(
                    restTemplate.getForObject(STABLECOIN_CHART_URL, String.class));
            for (StablecoinPoint p : points) {
                for (String symbol : QuantConstants.WATCH_SYMBOLS) {
                    factorHistoryMapper.upsert(toRow(symbol, p));
                }
            }
            log.info("[B2.stablecoin] STABLECOIN_SUPPLY_DELTA 全历史回填 点数={} symbols={}",
                    points.size(), QuantConstants.WATCH_SYMBOLS);
            return points.size();
        } catch (Exception e) {
            log.warn("[B2.stablecoin] BACKFILL_STABLECOIN_FAILED reason={}", e.getMessage());
            return 0;
        }
    }

    /**
     * 解析 DeFiLlama 全市场稳定币图 → 每相邻两个有效期一个供给差点（升序）。
     * 缺 date 或 peggedUSD 的脏点跳过且不推进"上一期"（即用最近有效期桥接，差值不丢时间含义）。
     */
    public static List<StablecoinPoint> parseSupplyDeltas(String json) {
        JSONArray arr = JSON.parseArray(json);
        if (arr == null || arr.size() < 2) {
            return List.of();
        }
        List<StablecoinPoint> out = new ArrayList<>(arr.size());
        Long prevDate = null;
        BigDecimal prevSupply = null;
        for (int i = 0; i < arr.size(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Long date = o.getLong("date");
            BigDecimal supply = readPeggedUsd(o);
            if (date == null || supply == null) {
                continue;                                   // 脏点：不产差、不推进 prev
            }
            if (prevDate != null) {
                out.add(new StablecoinPoint(date, supply, prevDate, prevSupply));
            }
            prevDate = date;
            prevSupply = supply;
        }
        return out;
    }

    /** 把一个供给差点映射成 factor_history 行（对指定 symbol；observed=本期 date，UTC）。 */
    private FactorHistory toRow(String symbol, StablecoinPoint p) {
        FactorHistory row = new FactorHistory();
        row.setSymbol(symbol);
        row.setFactorName(FACTOR_NAME);
        row.setFactorValue(p.delta());
        row.setObservedAt(toObservedAt(p.dateSec()));
        row.setMetadataJson(JSON.toJSONString(Map.of(
                "source", "DeFiLlama",
                "endpoint", STABLECOIN_CHART_URL,
                "rawMetric", "totalCirculatingUSD.peggedUSD_delta",
                "latestSupplyUsd", p.supplyUsd(),
                "previousSupplyUsd", p.previousSupplyUsd(),
                "previousDate", p.previousDateSec()
        )));
        return row;
    }

    private static LocalDateTime toObservedAt(long dateSec) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(dateSec), ZoneOffset.UTC);
    }

    /** 读取 DeFiLlama totalCirculatingUSD.peggedUSD，缺字段时返回 null 交给上层降级。 */
    private static BigDecimal readPeggedUsd(JSONObject point) {
        JSONObject total = point.getJSONObject("totalCirculatingUSD");
        return total != null ? total.getBigDecimal("peggedUSD") : null;
    }

    /** 一个相邻期供给差点：本期 date/供给 + 上期 date/供给（delta 由二者求得）。 */
    public record StablecoinPoint(long dateSec, BigDecimal supplyUsd,
                                  long previousDateSec, BigDecimal previousSupplyUsd) {
        public BigDecimal delta() {
            return supplyUsd.subtract(previousSupplyUsd);
        }
    }
}
