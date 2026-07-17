package com.mawai.wiibcommon.market;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mawai.wiibcommon.entity.KlineHistory;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.mapper.KlineHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 5m K 线落库 / 加载。回填走 getFuturesKlines（endTime 向前翻页），幂等批插。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KlineHistoryStore extends ServiceImpl<KlineHistoryMapper, KlineHistory> {

    public static final String DEFAULT_INTERVAL = "5m";
    public static final long DEFAULT_BAR_MILLIS = 5 * 60_000L;
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

    /** 回填 [fromMs, toMs) 的默认 5m K 线。endTime 向前翻页直到越过 fromMs。返回新增/尝试写入行数。 */
    public int backfill(String symbol, long fromMs, long toMs) {
        String normalizedSymbol = normalizeSymbol(symbol);
        long cursor = toMs;
        int total = 0;
        List<KlineHistory> buffer = new ArrayList<>(FLUSH);
        while (cursor > fromMs) {
            String json = binanceRestClient.getFuturesKlines(normalizedSymbol, DEFAULT_INTERVAL, PAGE, cursor);
            List<KlineBar> bars = parseRawFuturesKlines(json);
            if (bars.isEmpty()) break;
            long oldest = bars.getFirst().openTime();
            for (KlineBar b : bars) {
                if (b.openTime() < fromMs || b.closeTime() >= toMs) continue; // 只落闭合K线，跳过当前未收完bar
                buffer.add(toEntity(normalizedSymbol, DEFAULT_INTERVAL, b));
            }
            if (buffer.size() >= FLUSH) {
                total += baseMapper.batchInsertIgnore(buffer);
                buffer.clear();
            }
            if (oldest <= fromMs) break;
            cursor = oldest - 1;   // 下一页：比本页最老 bar 再早 1ms
        }
        if (!buffer.isEmpty()) total += baseMapper.batchInsertIgnore(buffer);
        log.info("backfill {} {} [{}, {}) 行数={}", normalizedSymbol, DEFAULT_INTERVAL, fromMs, toMs, total);
        return total;
    }

    /**
     * 只回补 [fromMs, toMs) 内缺失的默认 5m 段：先取库里已有 open_time 与期望网格比对找出缺口，
     * 仅对缺口区间调用 {@link #backfill}。无缺口时零 REST 请求；冷库时整窗即一个大缺口，退化为全量回补。
     */
    public int backfillMissing(String symbol, long fromMs, long toMs) {
        String normalizedSymbol = normalizeSymbol(symbol);
        long firstOpen = alignUp(fromMs, DEFAULT_BAR_MILLIS);                  // 窗口内第一根期望 bar
        long lastOpen = toMs / DEFAULT_BAR_MILLIS * DEFAULT_BAR_MILLIS - DEFAULT_BAR_MILLIS; // 最后一根已闭合 bar
        if (firstOpen > lastOpen) return 0;
        // 只取 open_time 列（走唯一索引覆盖扫描），90 天 5m 约 2.6 万个 long，内存可忽略
        List<Object> rows = baseMapper.selectObjs(new LambdaQueryWrapper<KlineHistory>()
                .select(KlineHistory::getOpenTime)
                .eq(KlineHistory::getSymbol, normalizedSymbol)
                .eq(KlineHistory::getIntervalCode, DEFAULT_INTERVAL)
                .ge(KlineHistory::getOpenTime, firstOpen)
                .le(KlineHistory::getOpenTime, lastOpen));
        Set<Long> existing = new HashSet<>(rows.size() * 2);
        for (Object o : rows) existing.add(((Number) o).longValue());
        List<long[]> gaps = missingRanges(firstOpen, lastOpen, DEFAULT_BAR_MILLIS, existing);
        int total = 0;
        for (long[] g : gaps) total += backfill(normalizedSymbol, g[0], g[1]);
        log.info("backfillMissing {} {} 缺口数={} 补入={}", normalizedSymbol, DEFAULT_INTERVAL, gaps.size(), total);
        return total;
    }

    /** 网格比对：期望 openTime 序列 [firstOpen..lastOpen]（步长 bar）中不在 existing 里的，合并成连续区间 [from, to)。 */
    static List<long[]> missingRanges(long firstOpen, long lastOpen, long bar, Set<Long> existing) {
        List<long[]> gaps = new ArrayList<>();
        long runStart = -1;
        for (long t = firstOpen; t <= lastOpen; t += bar) {
            if (!existing.contains(t)) {
                if (runStart < 0) runStart = t;
            } else if (runStart >= 0) {
                gaps.add(new long[]{runStart, t});
                runStart = -1;
            }
        }
        if (runStart >= 0) gaps.add(new long[]{runStart, lastOpen + bar});
        return gaps;
    }

    /** 向上对齐到 bar 网格（fromMs 通常是 now-90d，不落在整 5m 上）。 */
    static long alignUp(long ms, long bar) {
        return (ms + bar - 1) / bar * bar;
    }

    /**
     * 实时闭合 K 线落库；调用方先写库再发事件，下游才能读到最新 5m。
     */
    public void saveClosedBar(String symbol, String intervalCode, KlineBar bar) {
        if (bar == null) {
            return;
        }
        String normalizedSymbol = normalizeSymbol(symbol);
        String normalizedInterval = normalizeInterval(intervalCode);
        baseMapper.batchInsertIgnore(List.of(toEntity(normalizedSymbol, normalizedInterval, bar)));
    }

    /** 加载 [fromMs, toMs) 的指定周期 K 线，按 openTime 升序。 */
    public List<KlineBar> load(String symbol, String intervalCode, long fromMs, long toMs) {
        List<KlineHistory> rows = baseMapper.selectList(new LambdaQueryWrapper<KlineHistory>()
                .eq(KlineHistory::getSymbol, normalizeSymbol(symbol))
                .eq(KlineHistory::getIntervalCode, normalizeInterval(intervalCode))
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

    public Long latestOpenTime(String symbol, String intervalCode) {
        KlineHistory row = selectLatest(symbol, intervalCode);
        return row != null ? row.getOpenTime() : null;
    }

    public Long latestCloseTime(String symbol, String intervalCode) {
        KlineHistory row = selectLatest(symbol, intervalCode);
        return row != null ? row.getCloseTime() : null;
    }

    private KlineHistory selectLatest(String symbol, String intervalCode) {
        return baseMapper.selectOne(new LambdaQueryWrapper<KlineHistory>()
                .eq(KlineHistory::getSymbol, normalizeSymbol(symbol))
                .eq(KlineHistory::getIntervalCode, normalizeInterval(intervalCode))
                .orderByDesc(KlineHistory::getOpenTime)   // 走唯一索引(symbol,interval_code,open_time)，免filesort
                .last("LIMIT 1"));
    }

    private static KlineHistory toEntity(String symbol, String intervalCode, KlineBar b) {
        KlineHistory e = new KlineHistory();
        e.setSymbol(symbol);
        e.setIntervalCode(intervalCode);
        e.setOpenTime(b.openTime());
        e.setCloseTime(b.closeTime());
        e.setOpen(b.open());
        e.setHigh(b.high());
        e.setLow(b.low());
        e.setClose(b.close());
        e.setVolume(b.volume());
        return e;
    }

    // 空值直接抛错：此前 blank→BTCUSDT 兜底会把坏数据无声写进 BTC 历史，污染回测
    static String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("kline symbol 不能为空");
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    static String normalizeInterval(String intervalCode) {
        if (intervalCode == null || intervalCode.isBlank()) {
            throw new IllegalArgumentException("kline intervalCode 不能为空");
        }
        return intervalCode.trim().toLowerCase(Locale.ROOT);
    }
}
