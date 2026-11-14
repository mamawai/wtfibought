package com.mawai.wiibagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AiTraderPlanMapper extends BaseMapper<AiTraderPlan> {
    @Select("SELECT * FROM ai_trader_plan WHERE id = #{id} FOR UPDATE")
    AiTraderPlan selectByIdForUpdate(Long id);
}
