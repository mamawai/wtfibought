package com.mawai.wiibservice.agent.research.series;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mawai.wiibcommon.entity.MarketSeriesHistory;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.mapper.MarketSeriesMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 链下时点序列落库 / 加载。资金费走 fundingRate（endTime 向前翻页），F&G 走 alternative.me（一次拉全）；幂等批插。
 * backfill/load 是 DB+网络 I/O，不做单测；其正确性在端到端任务(T8)验证。解析为纯静态函数，单测覆盖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketSeriesStore extends ServiceImpl<MarketSeriesMapper, MarketSeriesHistory> {

    /** 全市场序列(F&G)的 symbol 占位。 */
    public static final String GLOBAL = "GLOBAL";
    private static final int FUNDING_PAGE = 1000;   // /fapi/v1/fundingRate 单页上限
    private static final int FLUSH = 3000;          // 攒够多少行落一次库

    private final BinanceRestClient binanceRestClient;

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

    /** 回填资金费率 [fromMs, toMs)：endTime 向前翻页直到越过 fromMs。返回写入行数。幂等。 */
    public int backfillFunding(String symbol, long fromMs, long toMs) {
        long cursor = toMs;
        int total = 0;
        List<MarketSeriesHistory> buffer = new ArrayList<>(FLUSH);
        while (cursor > fromMs) {
            String json = binanceRestClient.getFundingRateHistory(symbol, FUNDING_PAGE, cursor);
            List<MarketSeriesPoint> pts = parseFundingRateHistory(json);
            if (pts.isEmpty()) break;
            long oldest = pts.get(0).ts();
            for (MarketSeriesPoint p : pts) {
                if (p.ts() < fromMs) continue;
                buffer.add(toEntity(symbol, SeriesCode.FUNDING, p));
            }
            if (buffer.size() >= FLUSH) {
                total += baseMapper.batchInsertIgnore(buffer);
                buffer.clear();
            }
            if (oldest <= fromMs) break;
            cursor = oldest - 1;   // 下一页：比本页最老一条再早 1ms
        }
        if (!buffer.isEmpty()) total += baseMapper.batchInsertIgnore(buffer);
        log.info("backfillFunding {} [{}, {}) 行数={}", symbol, fromMs, toMs, total);
        return total;
    }

    /** 回填恐惧贪婪（全市场, symbol=GLOBAL）：一次拉 limit 条（0=全部历史）。返回写入行数。幂等。 */
    public int backfillFearGreed(int limit) {
        List<MarketSeriesPoint> pts = parseFearGreed(binanceRestClient.getFearGreedIndex(limit));
        if (pts.isEmpty()) return 0;
        List<MarketSeriesHistory> rows = new ArrayList<>(pts.size());
        for (MarketSeriesPoint p : pts) rows.add(toEntity(GLOBAL, SeriesCode.FEAR_GREED, p));
        int total = baseMapper.batchInsertIgnore(rows);
        log.info("backfillFearGreed limit={} 行数={}", limit, total);
        return total;
    }

    /** 加载 [fromMs, toMs) 的某序列，按 ts 升序。 */
    public List<MarketSeriesPoint> load(String symbol, SeriesCode code, long fromMs, long toMs) {
        List<MarketSeriesHistory> rows = baseMapper.selectList(new LambdaQueryWrapper<MarketSeriesHistory>()
                .eq(MarketSeriesHistory::getSymbol, symbol)
                .eq(MarketSeriesHistory::getSeriesCode, code.name())
                .ge(MarketSeriesHistory::getTs, fromMs)
                .lt(MarketSeriesHistory::getTs, toMs)
                .orderByAsc(MarketSeriesHistory::getTs));
        List<MarketSeriesPoint> out = new ArrayList<>(rows.size());
        for (MarketSeriesHistory r : rows) out.add(new MarketSeriesPoint(r.getTs(), r.getValue()));
        return out;
    }

    private static MarketSeriesHistory toEntity(String symbol, SeriesCode code, MarketSeriesPoint p) {
        MarketSeriesHistory e = new MarketSeriesHistory();
        e.setSymbol(symbol);
        e.setSeriesCode(code.name());
        e.setTs(p.ts());
        e.setValue(p.value());
        return e;
    }
}
