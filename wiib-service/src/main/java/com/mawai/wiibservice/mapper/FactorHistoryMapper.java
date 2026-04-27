package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.FactorHistory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface FactorHistoryMapper extends BaseMapper<FactorHistory> {

    /** 外部因子按 symbol+factor+observed_at 幂等写入，重复采集时更新值和 metadata。 */
    @Insert("""
            INSERT INTO factor_history(symbol, factor_name, factor_value, observed_at, metadata_json)
            VALUES (
                #{symbol},
                #{factorName},
                #{factorValue},
                #{observedAt},
                #{metadataJson,jdbcType=OTHER,typeHandler=com.mawai.wiibcommon.handler.JsonbStringTypeHandler}::jsonb
            )
            ON CONFLICT (symbol, factor_name, observed_at) DO UPDATE
            SET factor_value = EXCLUDED.factor_value,
                metadata_json = EXCLUDED.metadata_json
            """)
    int upsert(FactorHistory entity);

    /** 查询某个因子的最新观测值。 */
    @Select("""
            SELECT *
            FROM factor_history
            WHERE symbol = #{symbol}
              AND factor_name = #{factorName}
            ORDER BY observed_at DESC
            LIMIT 1
            """)
    FactorHistory selectLatest(@Param("symbol") String symbol,
                               @Param("factorName") String factorName);

    /** 查询指定窗口内的因子时间序列，供回放和分位计算使用。 */
    @Select("""
            SELECT *
            FROM factor_history
            WHERE symbol = #{symbol}
              AND factor_name = #{factorName}
              AND observed_at >= #{from}
              AND observed_at < #{to}
            ORDER BY observed_at ASC
            """)
    List<FactorHistory> selectRange(@Param("symbol") String symbol,
                                    @Param("factorName") String factorName,
                                    @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to);

    /** 计算当前因子值在历史窗口中的经验分位，返回 0-1。 */
    @Select("""
            SELECT COALESCE(AVG(
                CASE WHEN factor_value <= #{factorValue} THEN 1.0 ELSE 0.0 END
            ), 0)
            FROM factor_history
            WHERE symbol = #{symbol}
              AND factor_name = #{factorName}
              AND observed_at >= #{from}
              AND observed_at < #{to}
              AND factor_value IS NOT NULL
            """)
    BigDecimal selectPercentileRank(@Param("symbol") String symbol,
                                    @Param("factorName") String factorName,
                                    @Param("factorValue") BigDecimal factorValue,
                                    @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to);

    /** Admin 看板用：统计每个外部因子在窗口内的样本数、最新值和滞后小时数。 */
    @Select("""
            WITH scoped AS (
                SELECT *
                FROM factor_history
                WHERE observed_at >= #{from}
                  AND observed_at < #{to}
            ),
            agg AS (
                SELECT
                    symbol,
                    factor_name,
                    COUNT(*)::int AS samples,
                    MIN(observed_at) AS first_observed_at,
                    MAX(observed_at) AS latest_observed_at
                FROM scoped
                GROUP BY symbol, factor_name
            ),
            latest AS (
                SELECT DISTINCT ON (symbol, factor_name)
                    symbol,
                    factor_name,
                    factor_value,
                    observed_at
                FROM scoped
                ORDER BY symbol, factor_name, observed_at DESC
            )
            SELECT
                agg.symbol AS "symbol",
                agg.factor_name AS "factorName",
                agg.samples AS "samples",
                agg.first_observed_at AS "firstObservedAt",
                agg.latest_observed_at AS "latestObservedAt",
                latest.factor_value AS "latestValue",
                ROUND(EXTRACT(EPOCH FROM (NOW() - agg.latest_observed_at)) / 3600, 2) AS "latestAgeHours"
            FROM agg
            JOIN latest ON latest.symbol = agg.symbol
                       AND latest.factor_name = agg.factor_name
            ORDER BY agg.factor_name, agg.symbol
            """)
    List<Map<String, Object>> selectCoverage(@Param("from") LocalDateTime from,
                                             @Param("to") LocalDateTime to);
}
