package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.MinesGame;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface MinesGameMapper extends BaseMapper<MinesGame> {

    @Select("SELECT COALESCE(SUM(COALESCE(payout, 0) - bet_amount), 0) FROM mines_game WHERE user_id = #{userId} AND status IN ('CASHED_OUT', 'EXPLODED')")
    BigDecimal sumNetProfit(@Param("userId") Long userId);
}
