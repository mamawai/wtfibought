package com.mawai.wiibquant.market.service;

import com.mawai.wiibquant.external.yahoo.YahooFinanceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 跨资产快照：美股指数与期货、美元指数、十年美债、VIX、黄金、原油的现价和涨跌，Yahoo 取数，整份一起缓存。
 * TTL 内复用；取新数据一个都没拿到时兜 15 分钟内的旧快照。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrossAssetService {

    record Instrument(String symbol, String name) {
    }

    /** 顺序就是输出顺序 */
    static final List<Instrument> INSTRUMENTS = List.of(
            new Instrument("^GSPC", "S&P 500"),
            new Instrument("^IXIC", "Nasdaq Composite"),
            new Instrument("ES=F", "S&P 500 futures"),
            new Instrument("NQ=F", "Nasdaq 100 futures"),
            new Instrument("DX-Y.NYB", "US Dollar Index"),
            new Instrument("^TNX", "US 10Y Treasury yield (%)"),
            new Instrument("^VIX", "VIX"),
            new Instrument("GC=F", "Gold futures (USD/oz)"),
            new Instrument("CL=F", "WTI crude futures (USD/bbl)"));

    /** changePct1d 相对昨收，changePct5d 相对 5 个交易日前的收盘，都是百分数 */
    public record Quote(String symbol, String name, BigDecimal price,
                        BigDecimal changePct1d, BigDecimal changePct5d, long timeMs) {
    }

    private record Cached(List<Quote> quotes, long at) {
    }

    static final long TTL_MS = 60_000L;
    /** 兜旧快照的年龄上限 */
    static final long MAX_STALE_MS = 15 * 60_000L;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final YahooFinanceClient yahoo;
    private volatile Cached cached;
    /** 可注入的墙钟，测 TTL 和兜底用 */
    LongSupplier nowMs = System::currentTimeMillis;

    /** 整份快照；取不到也没旧的可兜就空列表 */
    public List<Quote> snapshot() {
        Cached hit = cached;
        long now = nowMs.getAsLong();
        if (hit != null && now - hit.at() < TTL_MS) {
            return hit.quotes();
        }
        List<Quote> fresh = fetch();
        if (!fresh.isEmpty()) {
            cached = new Cached(fresh, now);
            return fresh;
        }
        return hit != null && now - hit.at() < MAX_STALE_MS ? hit.quotes() : List.of();
    }

    /** 逐个取，拿到几个算几个 */
    private List<Quote> fetch() {
        List<Quote> out = new ArrayList<>();
        for (Instrument i : INSTRUMENTS) {
            String raw = yahoo.chart5d(i.symbol());
            if (raw == null) {
                continue;
            }
            try {
                out.add(parse(i, MAPPER.readTree(raw).path("chart").path("result").path(0).path("meta")));
            } catch (RuntimeException e) {
                log.warn("[CrossAsset] {} 回包解析失败: {}", i.symbol(), e.toString());
            }
        }
        return out;
    }

    /** meta：regularMarketPrice 现价、regularMarketChangePercent 相对昨收、chartPreviousClose 是 5 日窗口前一天的收盘 */
    static Quote parse(Instrument i, JsonNode meta) {
        BigDecimal price = meta.get("regularMarketPrice").asDecimal(null);
        BigDecimal prev5d = meta.get("chartPreviousClose").asDecimal(null);
        BigDecimal pct5d = price.subtract(prev5d).multiply(HUNDRED).divide(prev5d, 2, RoundingMode.HALF_UP);
        return new Quote(i.symbol(), i.name(), price.setScale(2, RoundingMode.HALF_UP),
                meta.get("regularMarketChangePercent").asDecimal(null).setScale(2, RoundingMode.HALF_UP),
                pct5d, meta.get("regularMarketTime").asLong() * 1000);
    }
}
