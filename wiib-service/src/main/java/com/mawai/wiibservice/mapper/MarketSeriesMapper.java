package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.MarketSeriesHistory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MarketSeriesMapper extends BaseMapper<MarketSeriesHistory> {

    /** 幂等批插：唯一键冲突直接跳过 → 回填可重复跑、可复现。 */
    @Insert("""
            <script>
            INSERT INTO market_series_history
              (symbol, series_code, ts, value)
            VALUES
            <foreach collection='list' item='r' separator=','>
              (#{r.symbol}, #{r.seriesCode}, #{r.ts}, #{r.value})
            </foreach>
            ON CONFLICT (symbol, series_code, ts) DO NOTHING
            </script>
            """)
    int batchInsertIgnore(@Param("list") List<MarketSeriesHistory> rows);
}
