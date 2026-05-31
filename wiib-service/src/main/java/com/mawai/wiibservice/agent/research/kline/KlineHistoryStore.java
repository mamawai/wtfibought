package com.mawai.wiibservice.agent.research.kline;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mawai.wiibcommon.entity.KlineHistory;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.mapper.KlineHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 1m K 线落库 / 加载。回填走 getFuturesKlines（endTime 向前翻页），幂等批插。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KlineHistoryStore extends ServiceImpl<KlineHistoryMapper, KlineHistory> {

    private static final String INTERVAL_1M = "1m";
    private static final int PAGE = 1500;          // 合约 klines 单页上限
    private static final int FLUSH = 3000;         // 攒够多少行落一次库

    private final BinanceRestClient binanceRestClient;

    /** 解析 Binance 原始 K 线 JSON → KlineBar（raw 数组: [0]openTime [1]open [2]high [3]low [4]close [5]vol [6]closeTime ...）。 */
    public static List<KlineBar> parseRawFuturesKlines(String json) {
        if (json == null || json.isBlank()) return List.of();
        JSONArray arr = JSON.parseArray(json);
        List<KlineBar> bars = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JSONArray k = arr.getJSONArray(i);
            bars.add(new KlineBar(
                    k.getLongValue(0),               // openTime
                    k.getLongValue(6),               // closeTime
                    new BigDecimal(k.getString(1)),  // open
                    new BigDecimal(k.getString(2)),  // high
                    new BigDecimal(k.getString(3)),  // low
                    new BigDecimal(k.getString(4)),  // close
                    new BigDecimal(k.getString(5))   // volume
            ));
        }
        return bars;
    }

    /** 回填 [fromMs, toMs) 的 1m K 线。endTime 向前翻页直到越过 fromMs。返回新增/尝试写入行数。 */
    public int backfill(String symbol, long fromMs, long toMs) {
        long cursor = toMs;
        int total = 0;
        List<KlineHistory> buffer = new ArrayList<>(FLUSH);
        while (cursor > fromMs) {
            String json = binanceRestClient.getFuturesKlines(symbol, INTERVAL_1M, PAGE, cursor);
            List<KlineBar> bars = parseRawFuturesKlines(json);
            if (bars.isEmpty()) break;
            long oldest = bars.get(0).openTime();
            for (KlineBar b : bars) {
                if (b.openTime() < fromMs) continue;
                buffer.add(toEntity(symbol, b));
            }
            if (buffer.size() >= FLUSH) {
                total += baseMapper.batchInsertIgnore(buffer);
                buffer.clear();
            }
            if (oldest <= fromMs) break;
            cursor = oldest - 1;   // 下一页：比本页最老 bar 再早 1ms
        }
        if (!buffer.isEmpty()) total += baseMapper.batchInsertIgnore(buffer);
        log.info("backfill {} [{}, {}) 行数={}", symbol, fromMs, toMs, total);
        return total;
    }

    /** 加载 [fromMs, toMs) 的 1m K 线，按 openTime 升序。 */
    public List<KlineBar> load(String symbol, String intervalCode, long fromMs, long toMs) {
        List<KlineHistory> rows = baseMapper.selectList(new LambdaQueryWrapper<KlineHistory>()
                .eq(KlineHistory::getSymbol, symbol)
                .eq(KlineHistory::getIntervalCode, intervalCode)
                .ge(KlineHistory::getOpenTime, fromMs)
                .lt(KlineHistory::getOpenTime, toMs)
                .orderByAsc(KlineHistory::getOpenTime));
        List<KlineBar> bars = new ArrayList<>(rows.size());
        for (KlineHistory r : rows) {
            bars.add(new KlineBar(r.getOpenTime(), r.getCloseTime(),
                    r.getOpen(), r.getHigh(), r.getLow(), r.getClose(), r.getVolume()));
        }
        return bars;
    }

    private static KlineHistory toEntity(String symbol, KlineBar b) {
        KlineHistory e = new KlineHistory();
        e.setSymbol(symbol);
        e.setIntervalCode(INTERVAL_1M);
        e.setOpenTime(b.openTime());
        e.setCloseTime(b.closeTime());
        e.setOpen(b.open());
        e.setHigh(b.high());
        e.setLow(b.low());
        e.setClose(b.close());
        e.setVolume(b.volume());
        return e;
    }
}
