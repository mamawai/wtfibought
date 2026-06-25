package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.CryptoPosition;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;

@Mapper
public interface CryptoPositionMapper extends BaseMapper<CryptoPosition> {

    @Insert("INSERT INTO crypto_position (user_id, symbol, quantity, frozen_quantity, avg_cost, total_discount, created_at, updated_at) " +
            "VALUES (#{userId}, #{symbol}, #{quantity}, 0, #{price}, #{discount}, NOW(), NOW()) " +
            "ON CONFLICT (user_id, symbol) DO UPDATE SET " +
            "avg_cost = (crypto_position.avg_cost * crypto_position.quantity + #{price} * #{quantity}) / (crypto_position.quantity + #{quantity}), " +
            "quantity = crypto_position.quantity + #{quantity}, " +
            "total_discount = crypto_position.total_discount + #{discount}, " +
            "updated_at = NOW()")
    void upsertPosition(@Param("userId") Long userId, @Param("symbol") String symbol,
                        @Param("quantity") BigDecimal quantity, @Param("price") BigDecimal price,
                        @Param("discount") BigDecimal discount);

    @Update("UPDATE crypto_position SET quantity = quantity - #{quantity}, updated_at = NOW() " +
            "WHERE user_id = #{userId} AND symbol = #{symbol} AND quantity >= #{quantity}")
    int atomicReduceQuantity(@Param("userId") Long userId, @Param("symbol") String symbol, @Param("quantity") BigDecimal quantity);

    @Update("UPDATE crypto_position SET quantity = quantity - #{quantity}, frozen_quantity = frozen_quantity + #{quantity}, updated_at = NOW() " +
            "WHERE user_id = #{userId} AND symbol = #{symbol} AND quantity >= #{quantity}")
    int atomicFreezePosition(@Param("userId") Long userId, @Param("symbol") String symbol, @Param("quantity") BigDecimal quantity);

    @Update("UPDATE crypto_position SET quantity = quantity + #{quantity}, frozen_quantity = frozen_quantity - #{quantity}, updated_at = NOW() " +
            "WHERE user_id = #{userId} AND symbol = #{symbol} AND frozen_quantity >= #{quantity}")
    int atomicUnfreezePosition(@Param("userId") Long userId, @Param("symbol") String symbol, @Param("quantity") BigDecimal quantity);

    @Update("UPDATE crypto_position SET frozen_quantity = frozen_quantity - #{quantity}, updated_at = NOW() " +
            "WHERE user_id = #{userId} AND symbol = #{symbol} AND frozen_quantity >= #{quantity}")
    int atomicDeductFrozenPosition(@Param("userId") Long userId, @Param("symbol") String symbol, @Param("quantity") BigDecimal quantity);

    @Delete("DELETE FROM crypto_position WHERE user_id = #{userId} AND symbol = #{symbol} AND quantity = 0 AND frozen_quantity = 0")
    void deleteEmptyPosition(@Param("userId") Long userId, @Param("symbol") String symbol);

    @Delete("DELETE FROM crypto_position WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);
}
