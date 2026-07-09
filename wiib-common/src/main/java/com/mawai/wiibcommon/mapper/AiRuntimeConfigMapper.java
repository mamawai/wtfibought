package com.mawai.wiibcommon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.AiRuntimeConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** LLM 配置（一条=一个具体LLM：key+baseUrl+model）。quant/sim 共享，Admin 页统一管理 */
@Mapper
public interface AiRuntimeConfigMapper extends BaseMapper<AiRuntimeConfig> {

    @Select("SELECT * FROM ai_runtime_config ORDER BY id")
    List<AiRuntimeConfig> selectAllConfigs();
}
