package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.QuantHorizonForecast;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface QuantHorizonForecastMapper extends BaseMapper<QuantHorizonForecast> {

    @Select("SELECT * FROM quant_horizon_forecast WHERE cycle_id = #{cycleId}")
    List<QuantHorizonForecast> selectByCycleId(@Param("cycleId") String cycleId);

    /**
     * 轻周期修正父重周期 forecast 的方向/置信度（不动 weightedScore 等其他字段，
     * 保留原始快照供 VerificationService 验证路径不受影响）。
     */
    @Update("UPDATE quant_horizon_forecast SET direction = #{direction}, confidence = #{confidence} " +
            "WHERE cycle_id = #{cycleId} AND horizon = #{horizon}")
    int updateDirectionAndConfidence(@Param("cycleId") String cycleId,
                                     @Param("horizon") String horizon,
                                     @Param("direction") String direction,
                                     @Param("confidence") BigDecimal confidence);
}
