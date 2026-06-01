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
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
            List<EtfFlowPoint> all = parseAll(fetchDocument());
            EtfFlowPoint point = all.get(all.size() - 1);   // 升序末尾 = 最新一日
            factorHistoryMapper.upsert(toRow(point));

            long staleDays = ChronoUnit.DAYS.between(point.date(), LocalDate.now());
            if (staleDays > 4) {
                log.warn("[B2.etf] STALE_ETF_FLOW latestDate={} staleDays={}", point.date(), staleDays);
            }
            log.info("[B2.etf] BTC_ETF_FLOW落库 date={} totalFlowUsdMillion={}",
                    point.date(), point.totalFlowUsdMillion().toPlainString());
        } catch (Exception e) {
            log.warn("[B2.etf] NO_ETF_FLOW reason={}", e.getMessage());
        }
    }

    /**
     * 全历史回填：Farside all-data 页本身含全部历史日行，逐行幂等 upsert（measure-first，喂回测评估）。
     * 与 collectOnce 同源同解析，绝不会漂移；失败只记录返回 0。
     */
    public int backfillHistory() {
        try {
            List<EtfFlowPoint> all = parseAll(fetchDocument());
            for (EtfFlowPoint p : all) {
                factorHistoryMapper.upsert(toRow(p));
            }
            log.info("[B2.etf] BTC_ETF_FLOW 全历史回填 行数={} 区间=[{}, {}]",
                    all.size(), all.get(0).date(), all.get(all.size() - 1).date());
            return all.size();
        } catch (Exception e) {
            log.warn("[B2.etf] BACKFILL_ETF_FLOW_FAILED reason={}", e.getMessage());
            return 0;
        }
    }

    /** 测试入口：用固定 HTML 样本验证 Farside 表结构解析没漂移（取最新一行）。 */
    public EtfFlowPoint parseLatest(String html) {
        List<EtfFlowPoint> all = parseAll(html);
        return all.get(all.size() - 1);   // parseAll 升序且非空（空表已抛异常）
    }

    /** 解析整张 ETF 全历史表 → 每个日期行一个点，按日期升序（回填用）。 */
    public List<EtfFlowPoint> parseAll(String html) {
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("empty html");
        }
        return parseAll(Jsoup.parse(html));
    }

    /** 从已解析的页面里寻找含 Date/Total 的 ETF 表，返回其全部日行（升序）。 */
    private List<EtfFlowPoint> parseAll(Document doc) {
        for (Element table : doc.select("table")) {
            List<EtfFlowPoint> points = parseTable(table);
            if (!points.isEmpty()) {
                points.sort(Comparator.comparing(EtfFlowPoint::date));
                return points;
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

    /** 定位表头中的 Date 和 Total 列，避免明细列顺序变化影响解析；返回该表所有可解析日行。 */
    private List<EtfFlowPoint> parseTable(Element table) {
        Elements rows = table.select("tr");
        if (rows.isEmpty()) {
            return List.of();
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
        return List.of();
    }

    /** 从表头之后逐行解析 (日期, Total)，收集所有有效行；底部 "Total" 汇总行因日期列非日期被跳过。 */
    private List<EtfFlowPoint> parseRowsAfterHeader(Elements rows, int startRow, int dateIdx, int totalIdx) {
        List<EtfFlowPoint> points = new ArrayList<>();
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
            points.add(new EtfFlowPoint(date, total));
        }
        return points;
    }

    /** 把一个 ETF 流入点映射成 factor_history 行（BTC_ETF_FLOW / BTCUSDT / observed=当日 0 点）。 */
    private FactorHistory toRow(EtfFlowPoint point) {
        FactorHistory row = new FactorHistory();
        row.setSymbol(SYMBOL);
        row.setFactorName(FACTOR_NAME);
        row.setFactorValue(point.totalFlowUsdMillion());
        row.setObservedAt(point.date().atStartOfDay());
        row.setMetadataJson(JSON.toJSONString(Map.of(
                "source", "Farside",
                "url", FARSIDE_URL,
                "unit", "USD_million",
                "flowDate", point.date().toString()
        )));
        return row;
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
