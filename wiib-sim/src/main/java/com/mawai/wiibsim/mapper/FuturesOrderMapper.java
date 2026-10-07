package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.FuturesOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface FuturesOrderMapper extends BaseMapper<FuturesOrder> {

    /** CAS状态转换 */
    @Update("UPDATE futures_order SET status = #{newStatus}, updated_at = NOW() " +
            "WHERE id = #{orderId} AND status = #{oldStatus}")
    int casUpdateStatus(@Param("orderId") Long orderId,
                        @Param("oldStatus") String oldStatus,
                        @Param("newStatus") String newStatus);

    /** CAS更新为FILLED；positionId 仅限价开仓成交时传（下单时仓位未生成，成交回填才能让仓位聚合到开仓手续费） */
    @Update("UPDATE futures_order SET " +
            "status = 'FILLED', " +
            "position_id = COALESCE(#{positionId}, position_id), " +
            "filled_price = #{filledPrice}, " +
            "filled_amount = #{filledAmount}, " +
            "commission = #{commission}, " +
            "margin_amount = #{marginAmount}, " +
            "realized_pnl = #{realizedPnl}, " +
            "updated_at = NOW() " +
            "WHERE id = #{orderId} AND status = 'PROCESSING'")
    int casUpdateToFilled(@Param("orderId") Long orderId,
                          @Param("positionId") Long positionId,
                          @Param("filledPrice") BigDecimal filledPrice,
                          @Param("filledAmount") BigDecimal filledAmount,
                          @Param("commission") BigDecimal commission,
                          @Param("marginAmount") BigDecimal marginAmount,
                          @Param("realizedPnl") BigDecimal realizedPnl);

    /** 仓位已实现盈亏：开/加仓单只贡献-fee，平仓单贡献 pnl-fee；只算已成交状态（强平单也是成交），资金费不在此表另行累计。
     *  在场持仓和已平仓位都用它。ID 集合走 = ANY(数组)，同 CommentMapper.selectChildPreviews 的取舍 */
    @Select("""
            SELECT position_id, COALESCE(SUM(COALESCE(realized_pnl, 0) - COALESCE(commission, 0)), 0) AS amount
            FROM futures_order
            WHERE status IN ('FILLED', 'STOP_LOSS', 'TAKE_PROFIT', 'LIQUIDATED')
              AND position_id = ANY(#{positionIds, typeHandler=org.apache.ibatis.type.ArrayTypeHandler})
            GROUP BY position_id
            """)
    List<Map<String, Object>> sumRealizedPnlByPositionIds(@Param("positionIds") Long[] positionIds);

    /** CAS标记为TRIGGERED */
    @Update("UPDATE futures_order SET status = 'TRIGGERED', filled_price = #{triggerPrice}, updated_at = NOW() " +
            "WHERE id = #{orderId} AND status = 'PENDING'")
    int casUpdateToTriggered(@Param("orderId") Long orderId, @Param("triggerPrice") BigDecimal triggerPrice);

    /** 抢占TRIGGERED订单处理权，避免补偿任务与实时触发重复成交 */
    @Update("UPDATE futures_order SET status = 'PROCESSING', updated_at = NOW() " +
            "WHERE id = #{orderId} AND status = 'TRIGGERED'")
    int casMarkProcessing(@Param("orderId") Long orderId);

    // 全仓挂单占用聚合已并入 FuturesPositionMapper.selectCrossSnapshot（三查合一），此处不再单列

    /** 用户所有已平仓单的已实现盈亏（已扣手续费） */
    @Select("SELECT COALESCE(SUM(realized_pnl - COALESCE(commission, 0)), 0) FROM futures_order " +
            "WHERE user_id = #{userId} AND realized_pnl IS NOT NULL")
    BigDecimal sumRealizedPnl(@Param("userId") Long userId);

    /** 分符号已实现盈亏（已扣手续费）：crypto 合约与大宗商品合约分开归集，四分类用 */
    @Select("SELECT symbol, COALESCE(SUM(realized_pnl - COALESCE(commission, 0)), 0) AS amount " +
            "FROM futures_order WHERE user_id = #{userId} AND realized_pnl IS NOT NULL GROUP BY symbol")
    List<Map<String, Object>> sumRealizedPnlBySymbol(@Param("userId") Long userId);

    /** 排行榜交易盈利：所有最终成交单都扣手续费，开仓/加仓单realized_pnl为空只贡献-fee */
    @Select("""
            SELECT user_id,
                   COALESCE(SUM(COALESCE(realized_pnl, 0) - COALESCE(commission, 0)), 0) AS amount
            FROM futures_order
            WHERE status IN ('FILLED', 'STOP_LOSS', 'TAKE_PROFIT', 'LIQUIDATED')
            GROUP BY user_id
            """)
    List<Map<String, Object>> sumNetPnlAfterCommissionAll();

    @Select("SELECT COUNT(*) FROM futures_order WHERE user_id = #{userId} AND status = 'FILLED'")
    long countFilledOrders(@Param("userId") Long userId);

    /** 分符号成交单数：行为分析按品类（crypto/大宗/tradfi）归桶用，归桶在 Java 侧按符号集做 */
    @Select("SELECT symbol, COUNT(*) AS cnt FROM futures_order " +
            "WHERE user_id = #{userId} AND status = 'FILLED' GROUP BY symbol")
    List<Map<String, Object>> countFilledOrdersBySymbol(@Param("userId") Long userId);

    @Update("UPDATE futures_order SET status = 'CANCELLED', updated_at = NOW() " +
            "WHERE user_id = #{userId} AND status IN ('PENDING', 'TRIGGERED', 'PROCESSING')")
    int cancelOpenOrdersByUserId(@Param("userId") Long userId);

    @Select("SELECT COALESCE(AVG(leverage), 0) FROM futures_order " +
            "WHERE user_id = #{userId} AND status = 'FILLED' AND leverage IS NOT NULL")
    BigDecimal selectAvgLeverage(@Param("userId") Long userId);

    @Select("""
            SELECT CASE
                     WHEN long_count > short_count THEN 'LONG'
                     WHEN short_count > long_count THEN 'SHORT'
                     ELSE 'BOTH'
                   END
            FROM (
                SELECT
                    SUM(CASE WHEN order_side IN ('OPEN_LONG', 'INCREASE_LONG') THEN 1 ELSE 0 END) AS long_count,
                    SUM(CASE WHEN order_side IN ('OPEN_SHORT', 'INCREASE_SHORT') THEN 1 ELSE 0 END) AS short_count
                FROM futures_order
                WHERE user_id = #{userId} AND status = 'FILLED'
            ) t
            """)
    String selectDirectionPreference(@Param("userId") Long userId);
}
