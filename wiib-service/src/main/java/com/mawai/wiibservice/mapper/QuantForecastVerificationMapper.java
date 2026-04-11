package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.QuantForecastVerification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface QuantForecastVerificationMapper extends BaseMapper<QuantForecastVerification> {

    @Select("SELECT * FROM quant_forecast_verification WHERE symbol = #{symbol} ORDER BY verified_at DESC LIMIT #{limit}")
    List<QuantForecastVerification> selectRecent(@Param("symbol") String symbol, @Param("limit") int limit);

    @Select("SELECT COUNT(CASE WHEN prediction_correct THEN 1 END)::float / NULLIF(COUNT(*), 0) " +
            "FROM quant_forecast_verification " +
            "WHERE symbol = #{symbol} AND verified_at > NOW() - INTERVAL '1 hour' * #{hours}")
    Double selectAccuracyRate(@Param("symbol") String symbol, @Param("hours") int hours);

    @Select("SELECT COUNT(*) FROM quant_forecast_verification WHERE cycle_id = #{cycleId}")
    int countByCycleId(@Param("cycleId") String cycleId);

    @Select("SELECT symbol, horizon, " +
            "ROUND(AVG(reversal_severity), 4) AS avg_reversal_severity, " +
            "ROUND(COUNT(CASE WHEN reversal_severity >= 0.40 THEN 1 END)::numeric " +
            "/ NULLIF(COUNT(reversal_severity), 0), 4) AS high_reversal_rate " +
            "FROM quant_forecast_verification " +
            "WHERE symbol = #{symbol} AND reversal_severity IS NOT NULL " +
            "GROUP BY symbol, horizon ORDER BY horizon")
    List<Map<String, Object>> selectReversalStats(@Param("symbol") String symbol);
}
