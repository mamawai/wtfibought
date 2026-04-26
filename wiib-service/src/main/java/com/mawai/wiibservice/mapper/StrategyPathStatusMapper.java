package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.StrategyPathStatus;
import org.apache.ibatis.annotations.Mapper;

/** strategy_path_status 的基础 CRUD Mapper；禁用/启用状态通过 path 主键持久化。 */
@Mapper
public interface StrategyPathStatusMapper extends BaseMapper<StrategyPathStatus> {
}
