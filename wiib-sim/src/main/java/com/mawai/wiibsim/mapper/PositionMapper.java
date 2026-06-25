package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.Position;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;

@Mapper
public interface PositionMapper extends BaseMapper<Position> {

    /** 原子减少可用数量 */
    @Update("UPDATE position SET quantity = quantity - #{quantity}, updated_at = NOW() " +
            "WHERE user_id = #{userId} AND stock_id = #{stockId} AND quantity >= #{quantity}")
    int atomicReduceQuantity(@Param("userId") Long userId, @Param("stockId") Long stockId, @Param("quantity") int quantity);

    /** 原子冻结：可用减少，冻结增加 */
    @Update("UPDATE position SET quantity = quantity - #{quantity}, frozen_quantity = frozen_quantity + #{quantity}, updated_at = NOW() " +
            "WHERE user_id = #{userId} AND stock_id = #{stockId} AND quantity >= #{quantity}")
    int atomicFreezePosition(@Param("userId") Long userId, @Param("stockId") Long stockId, @Param("quantity") int quantity);

    /** 原子解冻：冻结减少，可用增加 */
    @Update("UPDATE position SET quantity = quantity + #{quantity}, frozen_quantity = frozen_quantity - #{quantity}, updated_at = NOW() " +
            "WHERE user_id = #{userId} AND stock_id = #{stockId} AND frozen_quantity >= #{quantity}")
    int atomicUnfreezePosition(@Param("userId") Long userId, @Param("stockId") Long stockId, @Param("quantity") int quantity);

    /** 原子扣除冻结数量 */
    @Update("UPDATE position SET frozen_quantity = frozen_quantity - #{quantity}, updated_at = NOW() " +
            "WHERE user_id = #{userId} AND stock_id = #{stockId} AND frozen_quantity >= #{quantity}")
    int atomicDeductFrozenPosition(@Param("userId") Long userId, @Param("stockId") Long stockId, @Param("quantity") int quantity);

    /**
     * 删除空持仓（可用+冻结都为0）
     */
    @Delete("DELETE FROM position WHERE user_id = #{userId} AND stock_id = #{stockId} AND quantity = 0 AND frozen_quantity = 0")
    void deleteEmptyPosition(@Param("userId") Long userId, @Param("stockId") Long stockId);

    /**
     * UPSERT: 新建或更新持仓（加权平均成本 + 累加折扣）
     */
    @Insert("INSERT INTO position (user_id, stock_id, quantity, frozen_quantity, avg_cost, total_discount, created_at, updated_at) " +
            "VALUES (#{userId}, #{stockId}, #{quantity}, 0, #{price}, #{discount}, NOW(), NOW()) " +
            "ON CONFLICT (user_id, stock_id) DO UPDATE SET " +
            "avg_cost = (position.avg_cost * position.quantity + #{price} * #{quantity}) / (position.quantity + #{quantity}), " +
            "quantity = position.quantity + #{quantity}, " +
            "total_discount = position.total_discount + #{discount}, " +
            "updated_at = NOW()")
    void upsertPosition(@Param("userId") Long userId, @Param("stockId") Long stockId,
                       @Param("quantity") int quantity, @Param("price") BigDecimal price,
                       @Param("discount") BigDecimal discount);

    /** 删除用户全部持仓（爆仓/恢复兜底用） */
    @Delete("DELETE FROM position WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);
}
