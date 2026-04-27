package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.QuantAgentVote;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface QuantAgentVoteMapper extends BaseMapper<QuantAgentVote> {

    @Select("SELECT * FROM quant_agent_vote WHERE cycle_id = #{cycleId}")
    List<QuantAgentVote> selectByCycleId(@Param("cycleId") String cycleId);

    @Select("""
            SELECT
                v.agent AS agent,
                v.horizon AS horizon,
                COUNT(CASE WHEN ABS(fv.actual_change_bps) >= 10 THEN 1 END) AS sample_count,
                AVG(CASE
                    WHEN v.direction = 'LONG' AND fv.actual_change_bps >= 10 THEN 1.0
                    WHEN v.direction = 'SHORT' AND fv.actual_change_bps <= -10 THEN 1.0
                    WHEN ABS(fv.actual_change_bps) >= 10 THEN 0.0
                    ELSE NULL
                END) AS accuracy,
                AVG(CASE
                    WHEN ABS(fv.actual_change_bps) >= 10 AND v.direction = 'LONG' THEN fv.actual_change_bps::numeric
                    WHEN ABS(fv.actual_change_bps) >= 10 AND v.direction = 'SHORT' THEN -fv.actual_change_bps::numeric
                    ELSE NULL
                END) AS avg_edge_bps,
                AVG(CASE
                    WHEN v.direction = 'LONG' AND fv.actual_change_bps <= -10 THEN 1.0
                    WHEN v.direction = 'SHORT' AND fv.actual_change_bps >= 10 THEN 1.0
                    WHEN ABS(fv.actual_change_bps) >= 10 THEN 0.0
                    ELSE NULL
                END) AS bad_rate
            FROM quant_agent_vote v
            JOIN quant_forecast_verification fv
              ON fv.cycle_id = v.cycle_id
             AND fv.horizon = v.horizon
            JOIN quant_forecast_cycle c
              ON c.cycle_id = v.cycle_id
            WHERE fv.symbol = #{symbol}
              AND fv.actual_change_bps IS NOT NULL
              AND fv.verified_at >= NOW() - (#{lookbackDays} * INTERVAL '1 day')
              AND v.direction IN ('LONG', 'SHORT')
              AND ABS(v.score) >= 0.05
              AND (#{regime} IS NULL OR COALESCE(c.snapshot_json #>> '{regime}', 'UNKNOWN') = #{regime})
            GROUP BY v.agent, v.horizon
            """)
    List<Map<String, Object>> selectAgentPerformanceStats(@Param("symbol") String symbol,
                                                          @Param("regime") String regime,
                                                          @Param("lookbackDays") int lookbackDays);
}
