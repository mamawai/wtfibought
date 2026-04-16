package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QuantForecastCycleMapper extends BaseMapper<QuantForecastCycle> {

    @Select("SELECT * FROM quant_forecast_cycle WHERE symbol = #{symbol} ORDER BY forecast_time DESC LIMIT 1")
    QuantForecastCycle selectLatest(@Param("symbol") String symbol);

    @Select("SELECT * FROM quant_forecast_cycle WHERE symbol = #{symbol} ORDER BY forecast_time DESC LIMIT #{limit}")
    List<QuantForecastCycle> selectRecent(@Param("symbol") String symbol, @Param("limit") int limit);

    @Select("SELECT c.* FROM quant_forecast_cycle c " +
            "WHERE c.symbol = #{symbol} " +
            "AND c.forecast_time < NOW() - INTERVAL '35 minutes' " +
            "AND NOT EXISTS (SELECT 1 FROM quant_forecast_verification v WHERE v.cycle_id = c.cycle_id) " +
            "ORDER BY c.forecast_time DESC LIMIT #{limit}")
    List<QuantForecastCycle> selectUnverified(@Param("symbol") String symbol, @Param("limit") int limit);

    @Select("SELECT * FROM quant_forecast_cycle " +
            "WHERE symbol = #{symbol} AND forecast_time >= #{from} AND forecast_time < #{to} " +
            "ORDER BY forecast_time ASC")
    List<QuantForecastCycle> selectBySymbolAndTimeRange(@Param("symbol") String symbol,
                                                        @Param("from") java.time.LocalDateTime from,
                                                        @Param("to") java.time.LocalDateTime to);
}