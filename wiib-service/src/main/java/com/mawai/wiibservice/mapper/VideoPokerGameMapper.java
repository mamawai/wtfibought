package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.VideoPokerGame;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

@Mapper
public interface VideoPokerGameMapper extends BaseMapper<VideoPokerGame> {

    @Select("SELECT COALESCE(SUM(COALESCE(payout, 0) - bet_amount), 0) FROM video_poker_game WHERE user_id = #{userId} AND status = 'SETTLED'")
    BigDecimal sumNetProfit(@Param("userId") Long userId);
}
