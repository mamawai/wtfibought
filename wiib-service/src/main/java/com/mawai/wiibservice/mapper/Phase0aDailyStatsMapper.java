package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.Phase0aDailyStats;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Mapper
public interface Phase0aDailyStatsMapper extends BaseMapper<Phase0aDailyStats> {

    @Select("SELECT MAX(stat_date) FROM phase0a_daily_stats")
    LocalDate selectMaxStatDate();

    /** ai_trading_decision 当日决策聚合。别名加双引号保留 camelCase（PG 未引用标识符默认小写化）。 */
    @Select("SELECT " +
            "COUNT(*) FILTER (WHERE action LIKE 'OPEN%') AS \"totalOpens\", " +
            "COUNT(*) FILTER (WHERE action LIKE 'OPEN%' AND symbol = 'BTCUSDT') AS \"opensBtc\", " +
            "COUNT(*) FILTER (WHERE action LIKE 'OPEN%' AND symbol = 'ETHUSDT') AS \"opensEth\", " +
            "COUNT(*) FILTER (WHERE action LIKE 'CLOSE%') AS \"totalCloses\", " +
            "COUNT(*) FILTER (WHERE reasoning LIKE '%日亏%' OR reasoning LIKE '%DAILY_LOSS%') AS \"dailyLossBlocks\" " +
            "FROM ai_trading_decision " +
            "WHERE created_at >= #{dayStart} AND created_at < #{dayEnd}")
    Map<String, Object> aggregateDecisionCounts(@Param("dayStart") java.time.LocalDateTime dayStart,
                                                @Param("dayEnd") java.time.LocalDateTime dayEnd);

    /** ai_trading_decision 当日 balance_after 轨迹。 */
    @Select("SELECT " +
            "MIN(balance_after) AS \"equityLow\", " +
            "MAX(balance_after) AS \"equityHigh\", " +
            "(SELECT balance_after FROM ai_trading_decision " +
            "  WHERE created_at >= #{dayStart} AND created_at < #{dayEnd} AND balance_after IS NOT NULL " +
            "  ORDER BY created_at ASC LIMIT 1) AS \"equityStart\", " +
            "(SELECT balance_after FROM ai_trading_decision " +
            "  WHERE created_at >= #{dayStart} AND created_at < #{dayEnd} AND balance_after IS NOT NULL " +
            "  ORDER BY created_at DESC LIMIT 1) AS \"equityEnd\" " +
            "FROM ai_trading_decision " +
            "WHERE created_at >= #{dayStart} AND created_at < #{dayEnd} AND balance_after IS NOT NULL")
    Map<String, Object> aggregateEquityTrajectory(@Param("dayStart") java.time.LocalDateTime dayStart,
                                                  @Param("dayEnd") java.time.LocalDateTime dayEnd);

    /** futures_position 当日平仓聚合（按 updated_at 判断平仓时间）。 */
    @Select("SELECT " +
            "COUNT(*) AS \"positionsClosed\", " +
            "COUNT(*) FILTER (WHERE closed_pnl > 0) AS \"wins\", " +
            "COUNT(*) FILTER (WHERE closed_pnl < 0) AS \"losses\", " +
            "COALESCE(SUM(closed_pnl), 0) AS \"realizedPnl\", " +
            "AVG(closed_pnl) FILTER (WHERE closed_pnl > 0) AS \"avgWin\", " +
            "AVG(closed_pnl) FILTER (WHERE closed_pnl < 0) AS \"avgLoss\", " +
            "AVG(EXTRACT(EPOCH FROM (updated_at - created_at)) / 60.0) AS \"avgHoldingMinutes\" " +
            "FROM futures_position " +
            "WHERE user_id = #{userId} AND status = 'CLOSED' " +
            "  AND updated_at >= #{dayStart} AND updated_at < #{dayEnd}")
    Map<String, Object> aggregateClosedPositions(@Param("userId") Long userId,
                                                 @Param("dayStart") java.time.LocalDateTime dayStart,
                                                 @Param("dayEnd") java.time.LocalDateTime dayEnd);

    /** 按 memo（策略标签）聚合当日平仓表现。 */
    @Select("SELECT " +
            "COALESCE(memo, 'UNKNOWN') AS \"strategy\", " +
            "COUNT(*) AS \"count\", " +
            "COUNT(*) FILTER (WHERE closed_pnl > 0) AS \"wins\", " +
            "COUNT(*) FILTER (WHERE closed_pnl < 0) AS \"losses\", " +
            "COALESCE(SUM(closed_pnl), 0) AS \"pnl\" " +
            "FROM futures_position " +
            "WHERE user_id = #{userId} AND status = 'CLOSED' " +
            "  AND updated_at >= #{dayStart} AND updated_at < #{dayEnd} " +
            "GROUP BY COALESCE(memo, 'UNKNOWN')")
    List<Map<String, Object>> aggregateStrategyBreakdown(@Param("userId") Long userId,
                                                         @Param("dayStart") java.time.LocalDateTime dayStart,
                                                         @Param("dayEnd") java.time.LocalDateTime dayEnd);
}
