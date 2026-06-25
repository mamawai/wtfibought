package com.mawai.wiibservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * aggTrade 流式聚合：按需输出 order flow 指标。BinanceWsClient 写入，BuildFeaturesNode 读取。
 *
 * <p>存储后端为 Redis Stream（每 symbol 一条），feed 进程 {@link #onAggTrade} 写、quant 进程
 * {@link #getMetrics} 读算，天然跨进程。窗口聚合在读侧（quant）按交易所 ts 现算，符合"feed 只转发、
 * quant 自己重算 orderflow"的拆分约定。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderFlowAggregator {

    private static final String KEY_PREFIX = "market:orderflow:";
    private static final double LARGE_TRADE_USDT = 50_000; // BTC 大单阈值
    private static final long TRIM_MAXLEN = 30_000L;       // 兜底防无限增长，覆盖远超窗口(180s)的量

    private final StringRedisTemplate redisTemplate;

    /**
     * WS aggTrade 回调，高频，每笔 XADD 到 symbol 专属 Stream。
     * isBuyerMaker=true → taker 是卖方（主动卖）。写失败丢一笔，对窗口统计影响可忽略。
     */
    public void onAggTrade(String symbol, double price, double qty, boolean isBuyerMaker, long timestamp) {
        try {
            Map<String, String> f = new LinkedHashMap<>();
            f.put("ts", Long.toString(timestamp));
            f.put("p", Double.toString(price));
            f.put("q", Double.toString(qty));
            f.put("bm", isBuyerMaker ? "1" : "0");
            redisTemplate.opsForStream().add(StreamRecords.newRecord().in(KEY_PREFIX + symbol).ofMap(f));
        } catch (Exception e) {
            // 降级：丢一笔无妨
        }
    }

    /** 最近一次成交时间戳；无数据返回 0。 */
    public long getLastUpdateMs(String symbol) {
        try {
            List<MapRecord<String, Object, Object>> last = redisTemplate.opsForStream()
                    .reverseRange(KEY_PREFIX + symbol, Range.unbounded(), Limit.limit().count(1));
            if (last == null || last.isEmpty()) return 0L;
            Object ts = last.get(0).getValue().get("ts");
            return ts == null ? 0L : Long.parseLong(ts.toString());
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 获取指定窗口（秒）内的 order flow 指标。读全部后按交易所 ts 精确过滤窗口（不依赖进程/Redis 时钟同步）。
     * 返回 null 表示无数据（WS 未连接或刚启动）。
     */
    public Metrics getMetrics(String symbol, int windowSeconds) {
        try {
            List<MapRecord<String, Object, Object>> records =
                    redisTemplate.opsForStream().range(KEY_PREFIX + symbol, Range.unbounded());
            if (records == null || records.isEmpty()) return null;

            long cutoff = System.currentTimeMillis() - windowSeconds * 1000L;
            double buyVol = 0, sellVol = 0, largeBuyVol = 0, largeSellVol = 0;
            int count = 0;
            for (MapRecord<String, Object, Object> r : records) {
                Map<Object, Object> v = r.getValue();
                long ts = Long.parseLong(String.valueOf(v.get("ts")));
                if (ts < cutoff) continue;
                double p = Double.parseDouble(String.valueOf(v.get("p")));
                double q = Double.parseDouble(String.valueOf(v.get("q")));
                double usdt = p * q;
                boolean isSell = "1".equals(String.valueOf(v.get("bm")));
                if (isSell) {
                    sellVol += usdt;
                    if (usdt > LARGE_TRADE_USDT) largeSellVol += usdt;
                } else {
                    buyVol += usdt;
                    if (usdt > LARGE_TRADE_USDT) largeBuyVol += usdt;
                }
                count++;
            }

            double total = buyVol + sellVol;
            if (total < 1) return new Metrics(0, 0, 0, 0, 0);

            double tradeDelta = (buyVol - sellVol) / total;
            double intensity = (double) count / Math.max(1, windowSeconds);
            double largeTotal = largeBuyVol + largeSellVol;
            double largeBias = largeTotal > 0 ? (largeBuyVol - largeSellVol) / largeTotal : 0;
            return new Metrics(tradeDelta, intensity, largeBias, total, count);
        } catch (Exception e) {
            return null;
        }
    }

    /** 是否有足够数据（至少 10 笔成交）。 */
    public boolean hasData(String symbol) {
        try {
            Long size = redisTemplate.opsForStream().size(KEY_PREFIX + symbol);
            return size != null && size >= 10;
        } catch (Exception e) {
            return false;
        }
    }

    /** 兜底裁剪，防 Stream 无限增长（近似 MAXLEN）。由 WS 线程概率触发。 */
    public void trim(String symbol) {
        try {
            redisTemplate.opsForStream().trim(KEY_PREFIX + symbol, TRIM_MAXLEN, true);
        } catch (Exception e) {
            // 裁剪失败无妨，getMetrics 按 ts 过滤不受影响
        }
    }

    public record Metrics(
            double tradeDelta,      // [-1,1] 主动买卖差
            double tradeIntensity,  // 笔/秒
            double largeTradeBias,  // [-1,1] 大单方向偏差
            double totalVolumeUsdt, // 窗口总成交额
            int tradeCount          // 窗口成交笔数
    ) {}
}
