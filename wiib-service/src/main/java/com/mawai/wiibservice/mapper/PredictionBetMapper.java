package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.PredictionBet;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface PredictionBetMapper extends BaseMapper<PredictionBet> {

    @Select("SELECT COALESCE(SUM(COALESCE(payout, 0) - cost), 0) FROM prediction_bet WHERE user_id = #{userId} AND status IN ('WON', 'LOST', 'DRAW', 'SOLD')")
    BigDecimal sumRealizedProfit(@Param("userId") Long userId);

    /** CAS卖出: 仅ACTIVE→SOLD，返回affected行数防并发 */
    @Update("UPDATE prediction_bet SET status = 'SOLD', payout = #{payout}, updated_at = NOW() " +
            "WHERE id = #{id} AND status = 'ACTIVE'")
    int casSell(@Param("id") Long id, @Param("payout") BigDecimal payout);

    /** 赢方结算: payout = contracts（每张合约值$1） */
    @Update("UPDATE prediction_bet SET status = 'WON', payout = contracts, updated_at = NOW() " +
            "WHERE round_id = #{roundId} AND side = #{side} AND status = 'ACTIVE'")
    int settleWon(@Param("roundId") Long roundId, @Param("side") String side);

    /** 输方结算: payout = 0 */
    @Update("UPDATE prediction_bet SET status = 'LOST', payout = 0, updated_at = NOW() " +
            "WHERE round_id = #{roundId} AND side = #{side} AND status = 'ACTIVE'")
    int settleLost(@Param("roundId") Long roundId, @Param("side") String side);

    /** 平局结算: payout = cost（退本金） */
    @Update("UPDATE prediction_bet SET status = 'DRAW', payout = cost, updated_at = NOW() " +
            "WHERE round_id = #{roundId} AND status = 'ACTIVE'")
    int settleDraw(@Param("roundId") Long roundId);
}
