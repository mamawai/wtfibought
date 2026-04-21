package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.QuantForecastCycle;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface QuantForecastCycleMapper extends BaseMapper<QuantForecastCycle> {

    @Select("SELECT * FROM quant_forecast_cycle WHERE symbol = #{symbol} ORDER BY forecast_time DESC LIMIT 1")
    QuantForecastCycle selectLatest(@Param("symbol") String symbol);

    /**
     * AI-Trader 专用：只读最新重周期 cycle。
     * 轻周期也会写 cycle 表（cycleId 以 light- 前缀），但交易决策应基于含 LLM 的重周期，
     * 轻周期的影响已通过 UPDATE 父重周期 forecast/signal 反映。
     */
    @Select("SELECT * FROM quant_forecast_cycle WHERE symbol = #{symbol} AND cycle_id NOT LIKE 'light-%' " +
            "ORDER BY forecast_time DESC LIMIT 1")
    QuantForecastCycle selectLatestHeavy(@Param("symbol") String symbol);

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

    /**
     * 轻周期修正父重周期各 horizon forecast 之后，基于新 forecast 重算 overallDecision/riskStatus 回写。
     * AI-Trader 会读这两个字段，不同步回写会出现 FLAT 锁死或方向不一致。
     */
    @Update("UPDATE quant_forecast_cycle SET overall_decision = #{overallDecision}, risk_status = #{riskStatus} " +
            "WHERE cycle_id = #{cycleId}")
    int updateDecisionAndRisk(@Param("cycleId") String cycleId,
                              @Param("overallDecision") String overallDecision,
                              @Param("riskStatus") String riskStatus);
}
