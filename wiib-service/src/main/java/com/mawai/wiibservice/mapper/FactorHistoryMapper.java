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

@Mapper
public interface FactorHistoryMapper extends BaseMapper<FactorHistory> {

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
}
