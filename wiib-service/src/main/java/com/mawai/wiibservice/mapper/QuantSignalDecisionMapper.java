package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.QuantSignalDecision;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QuantSignalDecisionMapper extends BaseMapper<QuantSignalDecision> {

    @Select("SELECT * FROM quant_signal_decision WHERE cycle_id = (SELECT cycle_id FROM quant_forecast_cycle WHERE symbol = #{symbol} ORDER BY forecast_time DESC LIMIT 1)")
    List<QuantSignalDecision> selectLatestBySymbol(@Param("symbol") String symbol);

    /**
     * AI-Trader 专用：读取最新正式 research cycle。
     * 方法名保留 Heavy 是兼容旧调用方；过滤 light 前缀只用于跳过历史遗留轻周期记录。
     */
    @Select("SELECT * FROM quant_signal_decision WHERE cycle_id = (SELECT cycle_id FROM quant_forecast_cycle " +
            "WHERE symbol = #{symbol} AND cycle_id NOT LIKE 'light-%' ORDER BY forecast_time DESC LIMIT 1)")
    List<QuantSignalDecision> selectLatestHeavyBySymbol(@Param("symbol") String symbol);

}
