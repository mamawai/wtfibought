package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.KlineHistory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface KlineHistoryMapper extends BaseMapper<KlineHistory> {

    /** 幂等批插：唯一键冲突直接跳过 → 回填可重复跑、回测可复现。 */
    @Insert("""
            <script>
            INSERT INTO kline_history
              (symbol, interval_code, open_time, close_time, open, high, low, close, volume)
            VALUES
            <foreach collection='list' item='b' separator=','>
              (#{b.symbol}, #{b.intervalCode}, #{b.openTime}, #{b.closeTime},
               #{b.open}, #{b.high}, #{b.low}, #{b.close}, #{b.volume})
            </foreach>
            ON CONFLICT (symbol, interval_code, open_time) DO NOTHING
            </script>
            """)
    int batchInsertIgnore(@Param("list") List<KlineHistory> rows);
}
