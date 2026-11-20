package com.mawai.wiibcommon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.AiModelAssignment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 功能位→LLM配置指针。只有 agent 在用：刷新时补种子，Admin 页管理 */
@Mapper
public interface AiModelAssignmentMapper extends BaseMapper<AiModelAssignment> {

    @Select("SELECT * FROM ai_model_assignment ORDER BY id")
    List<AiModelAssignment> selectAll();

    @Select("SELECT * FROM ai_model_assignment WHERE function_name = #{functionName}")
    AiModelAssignment selectByFunction(String functionName);
}
