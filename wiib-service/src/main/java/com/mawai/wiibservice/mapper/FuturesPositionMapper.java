package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface FuturesPositionMapper extends BaseMapper<FuturesPosition> {

    /** 原子追加保证金 */
    @Update("UPDATE futures_position SET margin = margin + #{amount}, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN'")
    int atomicAddMargin(@Param("positionId") Long positionId, @Param("amount") BigDecimal amount);

    /** 原子扣除资金费率（足够扣） */
    @Update("UPDATE futures_position SET margin = margin - #{fee}, funding_fee_total = funding_fee_total + #{fee}, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN' AND margin >= #{fee}")
    int atomicDeductFundingFee(@Param("positionId") Long positionId, @Param("fee") BigDecimal fee);

    /** 原子扣除资金费率（不够扣，扣光） */
    @Update("UPDATE futures_position SET funding_fee_total = funding_fee_total + margin, margin = 0, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN' AND margin > 0")
    int atomicDeductFundingFeePartial(@Param("positionId") Long positionId);

    /** 原子部分平仓 */
    @Update("UPDATE futures_position SET quantity = quantity - #{qty}, margin = margin - #{marginPart}, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN' AND quantity >= #{qty} AND margin >= #{marginPart}")
    int atomicPartialClose(@Param("positionId") Long positionId,
                           @Param("qty") BigDecimal qty,
                           @Param("marginPart") BigDecimal marginPart);

    /** CAS关闭仓位 */
    @Update("UPDATE futures_position SET status = #{newStatus}, closed_price = #{closedPrice}, closed_pnl = #{closedPnl}, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN'")
    int casClosePosition(@Param("positionId") Long positionId,
                         @Param("newStatus") String newStatus,
                         @Param("closedPrice") BigDecimal closedPrice,
                         @Param("closedPnl") BigDecimal closedPnl);

    /** 查询用户所有OPEN仓位的保证金总和 */
    @Select("SELECT COALESCE(SUM(margin), 0) FROM futures_position WHERE user_id = #{userId} AND status = 'OPEN'")
    BigDecimal sumOpenMargin(@Param("userId") Long userId);

    /** 仅累加资金费率记录(从余额扣费时用，不动margin) */
    @Update("UPDATE futures_position SET funding_fee_total = funding_fee_total + #{fee}, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN'")
    int atomicAddFundingFeeTotal(@Param("positionId") Long positionId, @Param("fee") BigDecimal fee);

    /** 原子加仓：更新均价、加数量、加保证金 */
    @Update("UPDATE futures_position SET entry_price = #{newEntryPrice}, quantity = quantity + #{addQty}, " +
            "margin = margin + #{addMargin}, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN'")
    int atomicIncreasePosition(@Param("positionId") Long positionId,
                               @Param("newEntryPrice") BigDecimal newEntryPrice,
                               @Param("addQty") BigDecimal addQty,
                               @Param("addMargin") BigDecimal addMargin);

    @Update("UPDATE futures_position SET stop_losses = #{stopLosses,jdbcType=OTHER,typeHandler=com.mawai.wiibcommon.handler.FuturesStopLossListTypeHandler}::jsonb, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN'")
    int updateStopLosses(@Param("positionId") Long positionId, @Param("stopLosses") List<FuturesStopLoss> stopLosses);

    @Update("UPDATE futures_position SET take_profits = #{takeProfits,jdbcType=OTHER,typeHandler=com.mawai.wiibcommon.handler.FuturesTakeProfitListTypeHandler}::jsonb, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN'")
    int updateTakeProfits(@Param("positionId") Long positionId, @Param("takeProfits") List<FuturesTakeProfit> takeProfits);
}
