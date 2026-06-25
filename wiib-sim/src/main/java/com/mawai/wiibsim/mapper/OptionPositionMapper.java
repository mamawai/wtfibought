package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.OptionPosition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface OptionPositionMapper extends BaseMapper<OptionPosition> {

    @Update("UPDATE option_position SET quantity = quantity + #{delta}, " +
            "avg_cost = #{avgCost}, updated_at = NOW() " +
            "WHERE id = #{positionId} AND quantity + #{delta} >= 0")
    int atomicAddQuantity(@Param("positionId") Long positionId,
                          @Param("delta") int delta,
                          @Param("avgCost") BigDecimal avgCost);

    @Update("UPDATE option_position SET frozen_quantity = frozen_quantity + #{delta}, updated_at = NOW() " +
            "WHERE id = #{positionId} AND frozen_quantity + #{delta} >= 0")
    int atomicAddFrozen(@Param("positionId") Long positionId, @Param("delta") int delta);

    @Update("UPDATE option_position SET quantity = quantity + #{delta}, " +
            "frozen_quantity = frozen_quantity - #{delta}, updated_at = NOW() " +
            "WHERE id = #{positionId} AND frozen_quantity >= #{delta}")
    int atomicUnfreezeAndReduce(@Param("positionId") Long positionId, @Param("delta") int delta);
}
