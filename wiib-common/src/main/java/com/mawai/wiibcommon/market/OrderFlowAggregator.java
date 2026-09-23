package com.mawai.wiibcommon.market;

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
 * <p>存储后端为 Redis Stream（每 symbol 一条），feed 进程 {@link #onAggTrade} 写、agent 进程
 * {@link #getMetrics} 读算，天然跨进程。窗口聚合放读侧按交易所 ts 现算——feed 只转发，orderflow 由读侧自己重算。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderFlowAggregator {

    private static final String KEY_PREFIX = "market:orderflow:";
    private static final double LARGE_TRADE_USDT = 50_000; // BTC 大单阈值
    private static final long TRIM_MAXLEN = 30_000L;       // 兜底防无限增长，覆盖远超窗口(180s)的量
    /** 服务端粗筛的时钟余量，见 {@link #getMetrics} 注释 */
    private static final long CLOCK_SKEW_MS = 5_000L;

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
            Object ts = last.getFirst().getValue().get("ts");
            return ts == null ? 0L : Long.parseLong(ts.toString());
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 获取指定窗口（秒）内的 order flow 指标。返回 null 表示无数据（WS 未连接或刚启动）。
     *
     * <p>两层过滤：先按 Stream ID 让 Redis 在服务端切好只传窗口那段，再按交易所 ts 精确定窗口。
     * Stream 自动 ID 的高位就是写入时的毫秒时间戳，所以能直接当时间游标用。不这么切的话，
     * 整条 Stream 存着 {@link #TRIM_MAXLEN} 条（约 50 分钟），要 180 秒的量得全拉回来丢掉九成，
     * 白白占满 Redis 单线程 20ms。</p>
     *
     * <p>起点再往前退 {@link #CLOCK_SKEW_MS}：cutoff 是本进程时钟，ID 是 Redis 服务器时钟，
     * 两者不同步时正好会切掉窗口边缘。粗筛留余量，宁可多传一点也不能漏；精确窗口交给下面的
     * ts 过滤，"不依赖时钟同步"的原语义不变。</p>
     */
    public Metrics getMetrics(String symbol, int windowSeconds) {
        try {
            long cutoff = System.currentTimeMillis() - windowSeconds * 1000L;
            List<MapRecord<String, Object, Object>> records = since(symbol, cutoff);
            if (records == null || records.isEmpty()) return null;

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

    /** 最近 windowSeconds 秒第一笔到最后一笔成交的价差（USDT）；窗口里不到两笔回 null。按交易所 ts 定窗口，同 {@link #getMetrics} */
    public Double priceChange(String symbol, int windowSeconds) {
        try {
            long cutoff = System.currentTimeMillis() - windowSeconds * 1000L;
            List<MapRecord<String, Object, Object>> records = since(symbol, cutoff);
            if (records == null) return null;
            Double first = null;
            double last = 0;
            int count = 0;
            for (MapRecord<String, Object, Object> r : records) {
                Map<Object, Object> v = r.getValue();
                if (Long.parseLong(String.valueOf(v.get("ts"))) < cutoff) continue;
                last = Double.parseDouble(String.valueOf(v.get("p")));
                if (first == null) first = last;
                count++;
            }
            return count < 2 ? null : last - first;
        } catch (Exception e) {
            return null;
        }
    }

    /** 按 Stream ID 粗筛 cutoff 以来的记录，起点多退 {@link #CLOCK_SKEW_MS}；精确窗口由调用方按 ts 再筛，见 {@link #getMetrics} */
    private List<MapRecord<String, Object, Object>> since(String symbol, long cutoff) {
        String startId = (cutoff - CLOCK_SKEW_MS) + "-0";
        return redisTemplate.opsForStream().range(KEY_PREFIX + symbol, Range.rightUnbounded(Range.Bound.inclusive(startId)));
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
