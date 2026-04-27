package com.mawai.wiibservice.agent.external.etf;

import com.alibaba.fastjson2.JSON;
import com.mawai.wiibcommon.entity.FactorHistory;
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

import java.io.IOException;
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
public class EtfFlowScraper {

    private static final String FACTOR_NAME = "BTC_ETF_FLOW";
    private static final String SYMBOL = "BTCUSDT";
    private static final String FARSIDE_URL = "https://farside.co.uk/bitcoin-etf-flow-all-data/";
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36";
    private static final DateTimeFormatter DATE_FMT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("d MMM yyyy")
            .toFormatter(Locale.ENGLISH);

    private final FactorHistoryMapper factorHistoryMapper;

    @Value("${factor.btc_etf_flow.enabled:false}")
    private boolean enabled;

    /** 北京时间早 8 点：美股盘后数据通常已更新；失败只记录，不影响主流程。 */
    @Scheduled(cron = "0 0 8 * * *")
    public void collectDaily() {
        if (!enabled) {
            return;
        }
        collectOnce();
    }

    /** 拉取 Farside 最新 BTC ETF 净流入，并按日期去重写入 factor_history。 */
    public void collectOnce() {
        try {
            EtfFlowPoint point = parseLatest(fetchDocument());
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

    /** 测试入口：用固定 HTML 样本验证 Farside 表结构解析没有漂移。 */
    public EtfFlowPoint parseLatest(String html) {
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("empty html");
        }
        return parseLatest(Jsoup.parse(html));
    }

    /** 从已解析的页面里寻找包含 Date/Total 的 ETF 表，并返回最新日期。 */
    private EtfFlowPoint parseLatest(Document doc) {
        for (Element table : doc.select("table")) {
            EtfFlowPoint point = parseTable(table);
            if (point != null) {
                return point;
            }
        }
        throw new IllegalStateException("Farside ETF table not found");
    }

    /** 带浏览器请求头抓页面；Farside 对无 UA 的脚本请求会返回 403。 */
    private Document fetchDocument() throws IOException {
        return Jsoup.connect(FARSIDE_URL)
                .userAgent(BROWSER_UA)
                .referrer("https://farside.co.uk/")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .timeout(10000)
                .get();
    }

    /** 定位表头中的 Date 和 Total 列，避免 ETF 明细列顺序变化影响解析。 */
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

    /** 从表头之后逐行解析日期和 Total，取日期最新的一行作为因子值。 */
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

    /** 解析 Farside 的英文日期格式，例如 24 Apr 2026。 */
    private LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(normalize(raw), DATE_FMT);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析金额列；括号表示负数，空值和横杠不入库。 */
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

    /** 统一去掉 HTML non-breaking space，减少表格文本差异。 */
    private String normalize(String text) {
        return text == null ? "" : text.replace('\u00a0', ' ').trim();
    }

    public record EtfFlowPoint(LocalDate date, BigDecimal totalFlowUsdMillion) {}
}
