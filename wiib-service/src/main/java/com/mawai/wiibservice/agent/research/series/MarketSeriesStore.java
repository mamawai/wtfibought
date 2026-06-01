package com.mawai.wiibservice.agent.research.series;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.entity.FactorHistory;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.mapper.FactorHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 链下时点序列落库 / 加载——统一复用 live 的 factor_history 表（Slice3 融合，废弃 Slice2 自建的 market_series_history）。
 * 资金费走 fundingRate（endTime 向前翻页），F&G 走 alternative.me（一次拉全）；按 factor_history 唯一键幂等 upsert。
 * backfill/load 是 DB+网络 I/O，不做单测（端到端验证）；解析与时间转换为纯静态函数，单测覆盖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketSeriesStore {

    /** 全市场序列(F&G)的 symbol 占位。 */
    public static final String GLOBAL = "GLOBAL";
    private static final int FUNDING_PAGE = 1000;   // /fapi/v1/fundingRate 单页上限

    private final BinanceRestClient binanceRestClient;
    private final FactorHistoryMapper factorHistoryMapper;

    /** 解析资金费率历史 JSON → 时点序列（每条对象: fundingTime(ms) + fundingRate）。 */
    public static List<MarketSeriesPoint> parseFundingRateHistory(String json) {
        if (json == null || json.isBlank()) return List.of();
        JSONArray arr = JSON.parseArray(json);
        if (arr == null || arr.isEmpty()) return List.of();
        List<MarketSeriesPoint> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JSONObject o = arr.getJSONObject(i);
            BigDecimal rate = o.getBigDecimal("fundingRate");
            if (rate == null) continue;                                     // 脏数据跳过
            out.add(new MarketSeriesPoint(o.getLongValue("fundingTime"), rate));
        }
        return out;
    }

    /** 解析恐惧贪婪 JSON(alternative.me) → 时点序列（data[].timestamp 秒→ms, value 0-100）。 */
    public static List<MarketSeriesPoint> parseFearGreed(String json) {
        if (json == null || json.isBlank()) return List.of();
        JSONObject root = JSON.parseObject(json);
        JSONArray data = root == null ? null : root.getJSONArray("data");   // 无 data 数组 → 空
        if (data == null || data.isEmpty()) return List.of();
        List<MarketSeriesPoint> out = new ArrayList<>(data.size());
        for (int i = 0; i < data.size(); i++) {
            JSONObject o = data.getJSONObject(i);
            BigDecimal value = o.getBigDecimal("value");
            if (value == null) continue;
            out.add(new MarketSeriesPoint(o.getLongValue("timestamp") * 1000L, value));   // 秒 → 毫秒
        }
        return out;
    }

    /** 回填资金费率 [fromMs, toMs)：endTime 向前翻页直到越过 fromMs。返回处理行数。幂等(upsert)。 */
    public int backfillFunding(String symbol, long fromMs, long toMs) {
        long cursor = toMs;
        int total = 0;
        while (cursor > fromMs) {
            List<MarketSeriesPoint> pts = parseFundingRateHistory(
                    binanceRestClient.getFundingRateHistory(symbol, FUNDING_PAGE, cursor));
            if (pts.isEmpty()) break;
            long oldest = pts.get(0).ts();
            for (MarketSeriesPoint p : pts) {
                if (p.ts() < fromMs) continue;
                factorHistoryMapper.upsert(toFactor(symbol, SeriesCode.FUNDING, p, "binance/fundingRate"));
                total++;
            }
            if (oldest <= fromMs) break;
            cursor = oldest - 1;   // 下一页：比本页最老一条再早 1ms
        }
        log.info("backfillFunding {} [{}, {}) 行数={}", symbol, fromMs, toMs, total);
        return total;
    }

    /** 回填恐惧贪婪（全市场, symbol=GLOBAL）：一次拉 limit 条（0=全部历史）。返回处理行数。幂等(upsert)。 */
    public int backfillFearGreed(int limit) {
        List<MarketSeriesPoint> pts = parseFearGreed(binanceRestClient.getFearGreedIndex(limit));
        for (MarketSeriesPoint p : pts) {
            factorHistoryMapper.upsert(toFactor(GLOBAL, SeriesCode.FEAR_GREED, p, "alternative.me/fng"));
        }
        log.info("backfillFearGreed limit={} 行数={}", limit, pts.size());
        return pts.size();
    }

    /** 加载 [fromMs, toMs) 的某序列，按 ts 升序。复用 factor_history.selectRange（与原 market_series 的 load 同语义：左闭右开、时间升序）。 */
    public List<MarketSeriesPoint> load(String symbol, SeriesCode code, long fromMs, long toMs) {
        List<FactorHistory> rows = factorHistoryMapper.selectRange(symbol, code.factorName(), toLdt(fromMs), toLdt(toMs));
        List<MarketSeriesPoint> out = new ArrayList<>(rows.size());
        for (FactorHistory r : rows) out.add(new MarketSeriesPoint(toMs(r.getObservedAt()), r.getFactorValue()));
        return out;
    }

    private static FactorHistory toFactor(String symbol, SeriesCode code, MarketSeriesPoint p, String source) {
        FactorHistory e = new FactorHistory();
        e.setSymbol(symbol);
        e.setFactorName(code.factorName());
        e.setFactorValue(p.value());
        e.setObservedAt(toLdt(p.ts()));
        e.setMetadataJson("{\"source\":\"" + source + "\"}");
        return e;
    }

    /** research 用 epoch ms、factor_history 用 UTC LocalDateTime —— 统一按 UTC 互转（时区错会让 as-of 偏移）。 */
    static LocalDateTime toLdt(long ms) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC);
    }

    static long toMs(LocalDateTime ldt) {
        return ldt.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
