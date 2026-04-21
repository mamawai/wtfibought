package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.QuantSignalDecision;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface QuantSignalDecisionMapper extends BaseMapper<QuantSignalDecision> {

    @Select("SELECT * FROM quant_signal_decision WHERE cycle_id = (SELECT cycle_id FROM quant_forecast_cycle WHERE symbol = #{symbol} ORDER BY forecast_time DESC LIMIT 1)")
    List<QuantSignalDecision> selectLatestBySymbol(@Param("symbol") String symbol);

    /**
     * AI-Trader 专用：只读最新重周期（cycle_id 不以 light- 开头）。
     * 轻周期修正父重周期 forecast 后会同步 UPDATE 父重周期对应 signal 行，
     * 所以这里读到的就是「被轻周期修正过的」最新重周期信号。
     */
    @Select("SELECT * FROM quant_signal_decision WHERE cycle_id = (SELECT cycle_id FROM quant_forecast_cycle " +
            "WHERE symbol = #{symbol} AND cycle_id NOT LIKE 'light-%' ORDER BY forecast_time DESC LIMIT 1)")
    List<QuantSignalDecision> selectLatestHeavyBySymbol(@Param("symbol") String symbol);

    /** 轻周期修正父重周期后，同步更新 signal 的 direction/confidence。 */
    @Update("UPDATE quant_signal_decision SET direction = #{direction}, confidence = #{confidence} " +
            "WHERE cycle_id = #{cycleId} AND horizon = #{horizon}")
    int updateDirectionAndConfidence(@Param("cycleId") String cycleId,
                                     @Param("horizon") String horizon,
                                     @Param("direction") String direction,
                                     @Param("confidence") BigDecimal confidence);
}
