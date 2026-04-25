package com.mawai.wiibservice.agent.quant.service;

import com.alibaba.fastjson2.JSON;
import com.mawai.wiibcommon.entity.FactorHistory;
import com.mawai.wiibservice.config.BaseRestTemplateConfig;
import com.mawai.wiibservice.mapper.FactorHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EtfFlowScraper extends BaseRestTemplateConfig {

    private static final String FACTOR_NAME = "BTC_ETF_FLOW";
    private static final String SYMBOL = "BTCUSDT";
    private static final String FARSIDE_URL = "https://farside.co.uk/bitcoin-etf-flow-all-data/";
    private static final DateTimeFormatter DATE_FMT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("d MMM yyyy")
            .toFormatter(Locale.ENGLISH);

    private final FactorHistoryMapper factorHistoryMapper;
    private final RestTemplate restTemplate = createRestTemplate(5000, 10000);

    @Value("${factor.btc_etf_flow.enabled:true}")
    private boolean enabled;

    /** 北京时间早 8 点：美股盘后数据通常已更新；失败只记录，不影响主流程。 */
    @Scheduled(cron = "0 0 8 * * *")
    public void collectDaily() {
        if (!enabled) {
            return;
        }
        collectOnce();
    }

    public void collectOnce() {
        try {
            String html = restTemplate.getForObject(FARSIDE_URL, String.class);
            EtfFlowPoint point = parseLatest(html);
            LocalDateTime observedAt = point.date().atStartOfDay();

            FactorHistory row = new FactorHistory();
            row.setSymbol(SYMBOL);
            row.setFactorName(FACTOR_NAME);
            row.setFactorValue(point.totalFlowUsdMillion());
            row.setObservedAt(observedAt);
            row.setMetadataJson(JSON.toJSONString(Map.of(
                    "source", "Farside",
                    "url", FARSIDE_URL,
                    "unit", "USD_million",
                    "flowDate", point.date().toString()
            )));
            factorHistoryMapper.upsert(row);

            long staleDays = java.time.temporal.ChronoUnit.DAYS.between(point.date(), LocalDate.now());
            if (staleDays > 4) {
                log.warn("[B2.etf] STALE_ETF_FLOW latestDate={} staleDays={}", point.date(), staleDays);
            }
            log.info("[B2.etf] BTC_ETF_FLOW落库 date={} totalFlowUsdMillion={}",
                    point.date(), point.totalFlowUsdMillion().toPlainString());
        } catch (Exception e) {
            log.warn("[B2.etf] NO_ETF_FLOW reason={}", e.getMessage());
        }
    }

    public EtfFlowPoint parseLatest(String html) {
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("empty html");
        }
        Document doc = Jsoup.parse(html);
        for (Element table : doc.select("table")) {
            EtfFlowPoint point = parseTable(table);
            if (point != null) {
                return point;
            }
        }
        throw new IllegalStateException("Farside ETF table not found");
    }

    private EtfFlowPoint parseTable(Element table) {
        Elements rows = table.select("tr");
        if (rows.isEmpty()) {
            return null;
        }
        int dateIdx = -1;
        int totalIdx = -1;
        for (int r = 0; r < rows.size(); r++) {
            Elements cells = rows.get(r).select("th,td");
            for (int i = 0; i < cells.size(); i++) {
                String text = normalize(cells.get(i).text());
                if ("date".equalsIgnoreCase(text)) {
                    dateIdx = i;
                } else if ("total".equalsIgnoreCase(text)) {
                    totalIdx = i;
                }
            }
            if (dateIdx >= 0 && totalIdx >= 0) {
                return parseRowsAfterHeader(rows, r + 1, dateIdx, totalIdx);
            }
        }
        return null;
    }

    private EtfFlowPoint parseRowsAfterHeader(Elements rows, int startRow, int dateIdx, int totalIdx) {
        EtfFlowPoint latest = null;
        for (int r = startRow; r < rows.size(); r++) {
            Elements cells = rows.get(r).select("th,td");
            if (cells.size() <= Math.max(dateIdx, totalIdx)) {
                continue;
            }
            LocalDate date = parseDate(cells.get(dateIdx).text());
            BigDecimal total = parseMoney(cells.get(totalIdx).text());
            if (date == null || total == null) {
                continue;
            }
            if (latest == null || date.isAfter(latest.date())) {
                latest = new EtfFlowPoint(date, total);
            }
        }
        return latest;
    }

    private LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(normalize(raw), DATE_FMT);
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal parseMoney(String raw) {
        String value = normalize(raw);
        if (value.isBlank() || "-".equals(value)) {
            return null;
        }
        boolean negative = value.startsWith("(") && value.endsWith(")");
        value = value.replace("(", "")
                .replace(")", "")
                .replace(",", "")
                .replace("$", "")
                .trim();
        try {
            BigDecimal parsed = new BigDecimal(value);
            return negative ? parsed.negate() : parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String normalize(String text) {
        return text == null ? "" : text.replace('\u00a0', ' ').trim();
    }

    public record EtfFlowPoint(LocalDate date, BigDecimal totalFlowUsdMillion) {}
}
