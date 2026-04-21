package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.QuantForecastAdjustment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface QuantForecastAdjustmentMapper extends BaseMapper<QuantForecastAdjustment> {

    @Select("<script>SELECT * FROM quant_forecast_adjustment WHERE heavy_cycle_id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "ORDER BY created_at ASC</script>")
    List<QuantForecastAdjustment> selectByHeavyCycleIds(@Param("ids") Collection<String> ids);
}
