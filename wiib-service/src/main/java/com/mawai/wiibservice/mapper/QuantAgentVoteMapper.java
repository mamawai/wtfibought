package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.QuantAgentVote;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QuantAgentVoteMapper extends BaseMapper<QuantAgentVote> {

    @Select("SELECT * FROM quant_agent_vote WHERE cycle_id = #{cycleId}")
    List<QuantAgentVote> selectByCycleId(@Param("cycleId") String cycleId);
}
