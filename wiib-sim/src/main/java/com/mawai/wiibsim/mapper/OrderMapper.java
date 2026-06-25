package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 原子更新订单状态（CAS操作）
     * 只有当订单当前状态为expectedStatus时才会更新为newStatus
     *
     * @param orderId        订单ID
     * @param expectedStatus 期望的当前状态
     * @param newStatus      要更新的新状态
     * @return 影响行数（0表示状态已被其他操作改变）
     */
    @Update("UPDATE orders SET status = #{newStatus}, updated_at = NOW() " +
            "WHERE id = #{orderId} AND status = #{expectedStatus}")
    int casUpdateStatus(@Param("orderId") Long orderId,
            @Param("expectedStatus") String expectedStatus,
            @Param("newStatus") String newStatus);

    /**
     * 原子更新限价单为已触发状态（CAS操作）
     * 同时记录触发价格和触发时间
     *
     * @param orderId      订单ID
     * @param triggerPrice 触发价格
     * @return 影响行数（0表示状态已被其他操作改变）
     */
    @Update("UPDATE orders SET status = 'TRIGGERED', trigger_price = #{triggerPrice}, " +
            "triggered_at = NOW(), updated_at = NOW() " +
            "WHERE id = #{orderId} AND status = 'PENDING'")
    int casUpdateToTriggered(@Param("orderId") Long orderId,
            @Param("triggerPrice") BigDecimal triggerPrice);

    /**
     * 原子更新限价单为已成交状态（CAS操作）
     * 同时更新成交价格、成交金额、手续费等信息
     *
     * @param orderId      订单ID
     * @param filledPrice  成交价格
     * @param filledAmount 成交金额
     * @param commission   手续费
     * @return 影响行数（0表示状态已被其他操作改变）
     */
    @Update("UPDATE orders SET status = 'FILLED', filled_price = #{filledPrice}, " +
            "filled_amount = #{filledAmount}, commission = #{commission}, updated_at = NOW() " +
            "WHERE id = #{orderId} AND status = 'TRIGGERED'")
    int casUpdateToFilled(@Param("orderId") Long orderId,
            @Param("filledPrice") BigDecimal filledPrice,
            @Param("filledAmount") BigDecimal filledAmount,
            @Param("commission") BigDecimal commission);

    /** 取消用户所有未完结订单（爆仓/恢复兜底用） */
    @Update("UPDATE orders SET status = 'CANCELLED', updated_at = NOW() " +
            "WHERE user_id = #{userId} AND status IN ('PENDING', 'TRIGGERED')")
    int cancelOpenOrdersByUserId(@Param("userId") Long userId);

    /** 用户所有已成交买入的总额（买入成本，含手续费） */
    @Select("SELECT COALESCE(SUM(filled_amount + COALESCE(commission, 0)), 0) FROM orders " +
            "WHERE user_id = #{userId} AND order_side = 'BUY' AND status = 'FILLED'")
    BigDecimal sumBuyFilledAmount(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM orders WHERE user_id = #{userId} AND status = 'FILLED'")
    long countFilledOrders(@Param("userId") Long userId);

    @Select("""
            SELECT CASE
                     WHEN buy_count > sell_count THEN 'BUY_HEAVY'
                     WHEN sell_count > buy_count THEN 'SELL_HEAVY'
                     ELSE 'BALANCED'
                   END
            FROM (
                SELECT
                    SUM(CASE WHEN order_side = 'BUY' THEN 1 ELSE 0 END) AS buy_count,
                    SUM(CASE WHEN order_side = 'SELL' THEN 1 ELSE 0 END) AS sell_count
                FROM orders
                WHERE user_id = #{userId} AND status = 'FILLED'
            ) t
            """)
    String selectSidePreference(@Param("userId") Long userId);

    @Select("""
            SELECT CASE
                     WHEN market_count > limit_count THEN 'MARKET_HEAVY'
                     WHEN limit_count > market_count THEN 'LIMIT_HEAVY'
                     ELSE 'BALANCED'
                   END
            FROM (
                SELECT
                    SUM(CASE WHEN order_type = 'MARKET' THEN 1 ELSE 0 END) AS market_count,
                    SUM(CASE WHEN order_type = 'LIMIT' THEN 1 ELSE 0 END) AS limit_count
                FROM orders
                WHERE user_id = #{userId} AND status = 'FILLED'
            ) t
            """)
    String selectOrderTypePreference(@Param("userId") Long userId);
}
