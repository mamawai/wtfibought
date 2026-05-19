package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.CryptoOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface CryptoOrderMapper extends BaseMapper<CryptoOrder> {

    @Select("SELECT COALESCE(SUM(filled_amount - commission), 0) FROM crypto_order " +
            "WHERE user_id = #{userId} AND status = 'SETTLING'")
    BigDecimal sumSettlingAmount(@Param("userId") Long userId);

    @Select("SELECT user_id, COALESCE(SUM(filled_amount - commission), 0) AS amount " +
            "FROM crypto_order WHERE status = 'SETTLING' GROUP BY user_id")
    List<Map<String, Object>> sumAllSettlingAmounts();

    @Update("UPDATE crypto_order SET status = #{newStatus}, updated_at = NOW() " +
            "WHERE id = #{orderId} AND status = #{expectedStatus}")
    int casUpdateStatus(@Param("orderId") Long orderId,
                        @Param("expectedStatus") String expectedStatus,
                        @Param("newStatus") String newStatus);

    @Update("UPDATE crypto_order SET status = 'TRIGGERED', trigger_price = #{triggerPrice}, " +
            "triggered_at = NOW(), updated_at = NOW() " +
            "WHERE id = #{orderId} AND status = 'PENDING'")
    int casUpdateToTriggered(@Param("orderId") Long orderId,
                             @Param("triggerPrice") BigDecimal triggerPrice);

    @Update("UPDATE crypto_order SET status = 'FILLED', filled_price = #{filledPrice}, " +
            "filled_amount = #{filledAmount}, commission = #{commission}, updated_at = NOW() " +
            "WHERE id = #{orderId} AND status = 'TRIGGERED'")
    int casUpdateToFilled(@Param("orderId") Long orderId,
                          @Param("filledPrice") BigDecimal filledPrice,
                          @Param("filledAmount") BigDecimal filledAmount,
                          @Param("commission") BigDecimal commission);

    @Update("UPDATE crypto_order SET status = 'SETTLING', filled_price = #{filledPrice}, " +
            "filled_amount = #{filledAmount}, commission = #{commission}, updated_at = NOW() " +
            "WHERE id = #{orderId} AND status = 'TRIGGERED'")
    int casUpdateToSettling(@Param("orderId") Long orderId,
                            @Param("filledPrice") BigDecimal filledPrice,
                            @Param("filledAmount") BigDecimal filledAmount,
                            @Param("commission") BigDecimal commission);

    @Update("UPDATE crypto_order SET status = 'CANCELLED', updated_at = NOW() " +
            "WHERE user_id = #{userId} AND status IN ('PENDING', 'TRIGGERED')")
    int cancelOpenOrdersByUserId(@Param("userId") Long userId);

    /** 用户所有已成交买入的总额（成本，含手续费） */
    @Select("SELECT COALESCE(SUM(filled_amount + COALESCE(commission, 0)), 0) FROM crypto_order " +
            "WHERE user_id = #{userId} AND order_side = 'BUY' AND status = 'FILLED'")
    BigDecimal sumBuyFilledAmount(@Param("userId") Long userId);

    /** 用户所有已成交卖出的总额（收入，已扣手续费） */
    @Select("SELECT COALESCE(SUM(filled_amount - COALESCE(commission, 0)), 0) FROM crypto_order " +
            "WHERE user_id = #{userId} AND order_side = 'SELL' AND status = 'FILLED'")
    BigDecimal sumSellFilledAmount(@Param("userId") Long userId);

    /** 全部用户的现货买入总成本（含手续费），用于排行榜批量聚合 */
    @Select("SELECT user_id, COALESCE(SUM(filled_amount + COALESCE(commission, 0)), 0) AS amount " +
            "FROM crypto_order WHERE order_side = 'BUY' AND status = 'FILLED' GROUP BY user_id")
    List<Map<String, Object>> sumBuyFilledAmountAll();

    /** 全部用户的现货卖出净收入（已扣手续费），用于排行榜批量聚合 */
    @Select("SELECT user_id, COALESCE(SUM(filled_amount - COALESCE(commission, 0)), 0) AS amount " +
            "FROM crypto_order WHERE order_side = 'SELL' AND status = 'FILLED' GROUP BY user_id")
    List<Map<String, Object>> sumSellFilledAmountAll();

    /** 排行榜硬实力：卖出已成交或待到账都算已脱手现金流，避免SETTLING窗口少算 */
    @Select("SELECT user_id, COALESCE(SUM(filled_amount - COALESCE(commission, 0)), 0) AS amount " +
            "FROM crypto_order WHERE order_side = 'SELL' AND status IN ('FILLED', 'SETTLING') GROUP BY user_id")
    List<Map<String, Object>> sumSellFilledOrSettlingAmountAll();

    /** 排行榜硬实力：从历史买单反推优惠券节省金额，避免持仓清零后折扣记录丢失 */
    @Select("""
            SELECT user_id,
                   COALESCE(SUM(
                       CASE
                           WHEN discount_percent IS NOT NULL
                                AND filled_price IS NOT NULL
                                AND filled_amount IS NOT NULL
                           THEN ROUND(filled_price * quantity, 2) - filled_amount
                           ELSE 0
                       END
                   ), 0) AS amount
            FROM crypto_order
            WHERE order_side = 'BUY' AND status = 'FILLED'
            GROUP BY user_id
            """)
    List<Map<String, Object>> sumBuyDiscountAll();

    @Select("SELECT * FROM crypto_order WHERE user_id = #{userId} AND status = 'FILLED' " +
            "ORDER BY updated_at DESC LIMIT #{limit}")
    List<CryptoOrder> selectRecentOrders(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("""
            SELECT COALESCE(AVG(leverage), 0)
            FROM crypto_order
            WHERE user_id = #{userId}
              AND status = 'FILLED'
              AND leverage IS NOT NULL
            """)
    BigDecimal selectAvgLeverage(@Param("userId") Long userId);
}
