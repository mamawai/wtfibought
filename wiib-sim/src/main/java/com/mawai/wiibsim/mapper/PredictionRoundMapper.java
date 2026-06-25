package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.PredictionRound;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface PredictionRoundMapper extends BaseMapper<PredictionRound> {

    @Insert("INSERT INTO prediction_round (window_start, start_price, status, created_at, updated_at) " +
            "VALUES (#{windowStart}, #{startPrice}, 'OPEN', NOW(), NOW()) " +
            "ON CONFLICT (window_start) DO NOTHING")
    int insertIfAbsent(@Param("windowStart") long windowStart,
                       @Param("startPrice") BigDecimal startPrice);

    @Update("UPDATE prediction_round SET status = 'SETTLED', end_price = #{endPrice}, " +
            "outcome = #{outcome}, updated_at = NOW() " +
            "WHERE id = #{id} AND status = 'LOCKED'")
    int casSettleRound(@Param("id") Long id,
                       @Param("endPrice") BigDecimal endPrice,
                       @Param("outcome") String outcome);

    @Update("UPDATE prediction_round SET start_price = #{startPrice}, updated_at = NOW() " +
            "WHERE window_start = #{windowStart} AND status = 'OPEN'")
    int updateStartPrice(@Param("windowStart") long windowStart,
                         @Param("startPrice") BigDecimal startPrice);

    @Update("UPDATE prediction_round SET status = 'LOCKED', updated_at = NOW() " +
            "WHERE window_start = #{windowStart} AND status = 'OPEN'")
    int casLockRound(@Param("windowStart") long windowStart);
}
