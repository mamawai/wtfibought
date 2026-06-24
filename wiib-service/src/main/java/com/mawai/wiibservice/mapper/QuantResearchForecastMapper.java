package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.QuantResearchForecast;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QuantResearchForecastMapper extends BaseMapper<QuantResearchForecast> {

    @Select("SELECT * FROM quant_research_forecast WHERE cycle_id = #{cycleId}")
    List<QuantResearchForecast> selectByCycleId(@Param("cycleId") String cycleId);
}
