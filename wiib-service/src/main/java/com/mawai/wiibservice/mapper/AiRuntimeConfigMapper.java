package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.AiRuntimeConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AiRuntimeConfigMapper extends BaseMapper<AiRuntimeConfig> {

    @Select("SELECT * FROM ai_runtime_config WHERE config_name = 'default' AND enabled = true ORDER BY updated_at DESC LIMIT 1")
    AiRuntimeConfig selectDefault();

    @Select("SELECT * FROM ai_runtime_config ORDER BY id")
    List<AiRuntimeConfig> selectAllConfigs();
}
