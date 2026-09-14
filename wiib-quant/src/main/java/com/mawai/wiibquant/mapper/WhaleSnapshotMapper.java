package com.mawai.wiibquant.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

/**
 * 大户持仓每币每轮聚合快照读写。与 {@link WhaleAddressMapper} 同款口径：不建 entity，参数直进直出。
 */
@Mapper
public interface WhaleSnapshotMapper {

    /** 一行 = 一个币一轮；没仓位的一侧 count=0、notional=0、其余 null */
    @Data
    class Row {
        private long observedAt;
        private String coin;
        private BigDecimal price;
        private BigDecimal hlOpenInterest;
        private int poolSize;
        private int longCount;
        private BigDecimal longNotional;
        private BigDecimal longWavgEntry;
        private BigDecimal longMedianEntry;
        private BigDecimal longTop1Share;
        private BigDecimal longUpnl;
        private int shortCount;
        private BigDecimal shortNotional;
        private BigDecimal shortWavgEntry;
        private BigDecimal shortMedianEntry;
        private BigDecimal shortTop1Share;
        private BigDecimal shortUpnl;
        private String entryBucketsJson;
        private String liqBucketsJson;
    }

    /** 同一轮同一币只留第一份：cron 与手工跑撞同一个 10 分钟槽时后者静默 */
    @Insert("""
            INSERT INTO whale_snapshot (observed_at, coin, price, hl_open_interest, pool_size,
                long_count, long_notional, long_wavg_entry, long_median_entry, long_top1_share, long_upnl,
                short_count, short_notional, short_wavg_entry, short_median_entry, short_top1_share, short_upnl,
                entry_buckets_json, liq_buckets_json)
            VALUES (#{observedAt}, #{coin}, #{price}, #{hlOpenInterest}, #{poolSize},
                #{longCount}, #{longNotional}, #{longWavgEntry}, #{longMedianEntry}, #{longTop1Share}, #{longUpnl},
                #{shortCount}, #{shortNotional}, #{shortWavgEntry}, #{shortMedianEntry}, #{shortTop1Share}, #{shortUpnl},
                #{entryBucketsJson}, #{liqBucketsJson})
            ON CONFLICT (observed_at, coin) DO NOTHING
            """)
    int insert(Row row);

    /** 一个币最近一行，走 idx_whale_snapshot_coin_time；没有回 null */
    @Select("""
            SELECT observed_at AS observedAt, coin, price, hl_open_interest AS hlOpenInterest, pool_size AS poolSize,
                   long_count AS longCount, long_notional AS longNotional, long_wavg_entry AS longWavgEntry,
                   long_median_entry AS longMedianEntry, long_top1_share AS longTop1Share, long_upnl AS longUpnl,
                   short_count AS shortCount, short_notional AS shortNotional, short_wavg_entry AS shortWavgEntry,
                   short_median_entry AS shortMedianEntry, short_top1_share AS shortTop1Share, short_upnl AS shortUpnl,
                   entry_buckets_json AS entryBucketsJson, liq_buckets_json AS liqBucketsJson
              FROM whale_snapshot
             WHERE coin = #{coin}
             ORDER BY observed_at DESC
             LIMIT 1
            """)
    Row selectLatest(String coin);
}
