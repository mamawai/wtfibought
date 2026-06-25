package com.mawai.wiibquant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface QuantForecastCycleMapper extends BaseMapper<QuantForecastCycle> {

    @Select("SELECT * FROM quant_forecast_cycle WHERE symbol = #{symbol} ORDER BY forecast_time DESC LIMIT 1")
    QuantForecastCycle selectLatest(@Param("symbol") String symbol);

    /**
     * AI-Trader 专用：读取最新正式 research cycle。
     * 方法名保留 Heavy 是兼容旧调用方；过滤 light 前缀只用于跳过历史遗留轻周期记录。
     */
    @Select("SELECT * FROM quant_forecast_cycle WHERE symbol = #{symbol} AND cycle_id NOT LIKE 'light-%' " +
            "ORDER BY forecast_time DESC LIMIT 1")
    QuantForecastCycle selectLatestHeavy(@Param("symbol") String symbol);

    @Select("SELECT * FROM quant_forecast_cycle WHERE symbol = #{symbol} ORDER BY forecast_time DESC LIMIT #{limit}")
    List<QuantForecastCycle> selectRecent(@Param("symbol") String symbol, @Param("limit") int limit);

    @Select("""
            SELECT c.*
            FROM quant_forecast_cycle c
            WHERE c.symbol = #{symbol}
              AND EXISTS (
                  SELECT 1
                  FROM quant_horizon_forecast h
                  WHERE h.cycle_id = c.cycle_id
                    AND NOT EXISTS (
                        SELECT 1
                        FROM quant_forecast_verification v
                        WHERE v.cycle_id = h.cycle_id
                          AND v.horizon = h.horizon
                    )
                    AND NOW() >= c.forecast_time + CASE h.horizon
                        WHEN 'H6' THEN INTERVAL '6 hours'
                        WHEN 'H12' THEN INTERVAL '12 hours'
                        WHEN 'H24' THEN INTERVAL '24 hours'
                        WHEN '0_10' THEN INTERVAL '10 minutes'
                        WHEN '10_20' THEN INTERVAL '20 minutes'
                        WHEN '20_30' THEN INTERVAL '30 minutes'
                        ELSE INTERVAL '100 years'
                    END
              )
            ORDER BY c.forecast_time DESC
            LIMIT #{limit}
            """)
    List<QuantForecastCycle> selectUnverified(@Param("symbol") String symbol, @Param("limit") int limit);

    @Select("SELECT * FROM quant_forecast_cycle " +
            "WHERE symbol = #{symbol} AND forecast_time >= #{from} AND forecast_time < #{to} " +
            "ORDER BY forecast_time ASC")
    List<QuantForecastCycle> selectBySymbolAndTimeRange(@Param("symbol") String symbol,
                                                        @Param("from") java.time.LocalDateTime from,
                                                        @Param("to") java.time.LocalDateTime to);

    @Select("""
            WITH base AS (
                SELECT
                    COALESCE(c.snapshot_json #>> '{regime}', 'UNKNOWN') AS regime,
                    a.agent,
                    a.horizon,
                    a.score::numeric AS vote_score,
                    a.confidence::numeric AS vote_confidence,
                    CASE a.direction
                        WHEN 'LONG' THEN v.actual_change_bps::numeric
                        WHEN 'SHORT' THEN -v.actual_change_bps::numeric
                    END AS aligned_return_bps
                FROM quant_forecast_cycle c
                JOIN quant_agent_vote a ON a.cycle_id = c.cycle_id
                JOIN quant_forecast_verification v
                  ON v.cycle_id = a.cycle_id
                 AND v.horizon = a.horizon
                WHERE c.forecast_time >= NOW() - (CAST(#{days} AS integer) * INTERVAL '1 day')
                  AND v.actual_change_bps IS NOT NULL
                  AND a.direction IN ('LONG', 'SHORT')
            )
            SELECT
                agent AS "agent",
                horizon AS "horizon",
                regime AS "regime",
                COUNT(*)::int AS "samples",
                ROUND(AVG(aligned_return_bps), 4) AS "meanAlignedReturnBps",
                ROUND(STDDEV_SAMP(aligned_return_bps), 4) AS "stdAlignedReturnBps",
                ROUND(AVG(aligned_return_bps) / NULLIF(STDDEV_SAMP(aligned_return_bps), 0), 4) AS "ir",
                ROUND(AVG(CASE WHEN aligned_return_bps > 0 THEN 1.0 ELSE 0.0 END), 4) AS "winRate",
                ROUND(AVG(vote_confidence), 4) AS "avgConfidence",
                ROUND(AVG(ABS(vote_score)), 4) AS "avgAbsScore"
            FROM base
            GROUP BY agent, horizon, regime
            HAVING COUNT(*) >= 5
            ORDER BY "ir" DESC NULLS LAST, "samples" DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectAgentIrRanking(@Param("days") int days,
                                                   @Param("limit") int limit);

    @Select("""
            WITH scoped AS (
                SELECT
                    c.*,
                    EXISTS (
                        SELECT 1
                        FROM jsonb_array_elements_text(
                            CASE
                                WHEN jsonb_typeof(c.snapshot_json -> 'qualityFlags') = 'array'
                                THEN c.snapshot_json -> 'qualityFlags'
                                ELSE '[]'::jsonb
                            END
                        ) AS q(flag)
                        WHERE q.flag = 'LOW_CONFIDENCE'
                    ) AS low_confidence
                FROM quant_forecast_cycle c
                WHERE c.forecast_time >= NOW() - (CAST(#{days} AS integer) * INTERVAL '1 day')
                  AND c.cycle_id NOT LIKE 'light-%'
            )
            SELECT
                COUNT(*)::int AS "cycles",
                COUNT(*) FILTER (WHERE low_confidence)::int AS "lowConfidenceCycles",
                ROUND(AVG(CASE WHEN low_confidence THEN 1.0 ELSE 0.0 END), 4) AS "lowConfidenceRate",
                ROUND(AVG(NULLIF(snapshot_json #>> '{regimeConfidence}', '')::numeric), 4) AS "avgRegimeConfidence",
                ROUND(AVG(NULLIF(snapshot_json #>> '{regimeConfidenceStddev}', '')::numeric), 4) AS "avgRegimeConfidenceStddev",
                ROUND(AVG(NULLIF(snapshot_json #>> '{newsConfidenceStddev}', '')::numeric), 4) AS "avgNewsConfidenceStddev",
                COUNT(*) FILTER (
                    WHERE COALESCE(snapshot_json #>> '{regimeTransition}', 'NONE') <> 'NONE'
                )::int AS "transitionCycles",
                COUNT(*) FILTER (
                    WHERE COALESCE(NULLIF(snapshot_json #>> '{regimeConfidenceStddev}', '')::numeric, 0) >= 0.15
                       OR COALESCE(NULLIF(snapshot_json #>> '{newsConfidenceStddev}', '')::numeric, 0) >= 0.15
                )::int AS "highVarianceCycles"
            FROM scoped
            """)
    Map<String, Object> selectLlmVarianceSummary(@Param("days") int days);
}
