package com.mawai.wiibquant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.QuantHorizonForecast;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QuantHorizonForecastMapper extends BaseMapper<QuantHorizonForecast> {

    @Select("SELECT * FROM quant_horizon_forecast WHERE cycle_id = #{cycleId}")
    List<QuantHorizonForecast> selectByCycleId(@Param("cycleId") String cycleId);

}
