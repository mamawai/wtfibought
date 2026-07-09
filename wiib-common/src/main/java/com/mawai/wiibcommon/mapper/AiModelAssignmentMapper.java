package com.mawai.wiibcommon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.AiModelAssignment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 功能位→LLM配置指针。quant 负责种子/管理，sim 只读自己的功能位 */
@Mapper
public interface AiModelAssignmentMapper extends BaseMapper<AiModelAssignment> {

    @Select("SELECT * FROM ai_model_assignment ORDER BY id")
    List<AiModelAssignment> selectAll();

    @Select("SELECT * FROM ai_model_assignment WHERE function_name = #{functionName}")
    AiModelAssignment selectByFunction(String functionName);
}
