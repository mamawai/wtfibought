package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.VideoPokerGame;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface VideoPokerGameMapper extends BaseMapper<VideoPokerGame> {

    @Select("SELECT COALESCE(SUM(payout - bet_amount), 0) FROM video_poker_game WHERE user_id = #{userId} AND status IN ('SETTLED', 'FORFEITED')")
    BigDecimal sumNetProfit(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM video_poker_game WHERE user_id = #{userId} AND status IN ('SETTLED', 'FORFEITED')")
    int countSettledGames(@Param("userId") Long userId);

    /**
     * 发牌完等着 draw 的那一局（同一用户至多一条，靠 bet 前置校验 + 用户锁保证）。
     * deck 非空是硬条件：没牌堆就补不了牌，那种行接着打只会 NPE，当它不存在让用户能开新局。
     */
    @Select("SELECT * FROM video_poker_game WHERE user_id = #{userId} AND status = 'DEALING' " +
            "AND deck IS NOT NULL LIMIT 1")
    VideoPokerGame selectDealing(@Param("userId") Long userId);

    /** 破产清算/恢复：发完牌没 draw 的局作废(FORFEITED)，本金不退 */
    @Update("UPDATE video_poker_game SET status = 'FORFEITED', updated_at = NOW() " +
            "WHERE user_id = #{userId} AND status = 'DEALING'")
    int forfeitDealingByUserId(@Param("userId") Long userId);
}
