package com.mawai.wiibservice.agent.research.kline;

import com.mawai.wiibcommon.market.KlineBar;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 把底层 bars 聚合到 5m/15m 等固定周期，按 epoch 边界对齐（可复现）。 */
public final class KlineAggregator {

    private KlineAggregator() {
    }

    /**
     * 入参须按 openTime 升序（store.load 已保证）。按 openTime/horizonMillis 分桶：
     * open=桶内首根 open，high/low=极值，close=末根 close，volume=求和，
     * openTime=对齐到桶左边界(bucket*horizonMillis)。不完整桶照常输出（缺口由上游容忍）。
     */
    public static List<KlineBar> aggregate(List<KlineBar> oneMin, long horizonMillis) {
        if (oneMin == null || oneMin.isEmpty()) return List.of();

        List<KlineBar> out = new ArrayList<>();
        long curBucket = Long.MIN_VALUE;
        long bucketOpenTime = 0, lastCloseTime = 0;
        BigDecimal open = null, high = null, low = null, close = null, vol = BigDecimal.ZERO;

        for (KlineBar b : oneMin) {
            long bucket = Math.floorDiv(b.openTime(), horizonMillis);
            if (bucket != curBucket) {
                if (curBucket != Long.MIN_VALUE) {
                    out.add(new KlineBar(bucketOpenTime, lastCloseTime, open, high, low, close, vol));
                }
                curBucket = bucket;
                bucketOpenTime = bucket * horizonMillis;
                open = b.open();
                high = b.high();
                low = b.low();
                close = b.close();
                vol = b.volume();
                lastCloseTime = b.closeTime();
            } else {
                high = high.max(b.high());
                low = low.min(b.low());
                close = b.close();
                vol = vol.add(b.volume());
                lastCloseTime = b.closeTime();
            }
        }
        if (curBucket != Long.MIN_VALUE) {
            out.add(new KlineBar(bucketOpenTime, lastCloseTime, open, high, low, close, vol));
        }
        return out;
    }
}
