package com.mawai.wiibservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.TradeAttribution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface TradeAttributionMapper extends BaseMapper<TradeAttribution> {

    /** 查询用户某个时间点之后的已平仓归因记录，供 L1 日亏损计算使用。 */
    @Select("""
            SELECT ta.*
            FROM trade_attribution ta
            JOIN futures_position fp ON fp.id = ta.position_id
            WHERE fp.user_id = #{userId}
              AND ta.exit_time >= #{from}
              AND ta.pnl IS NOT NULL
            ORDER BY ta.exit_time DESC
            """)
    List<TradeAttribution> selectByUserSince(@Param("userId") Long userId,
                                             @Param("from") LocalDateTime from);

    /** 查询用户最近 N 笔归因记录，供 L2 连亏熔断使用。 */
    @Select("""
            SELECT ta.*
            FROM trade_attribution ta
            JOIN futures_position fp ON fp.id = ta.position_id
            WHERE fp.user_id = #{userId}
              AND ta.pnl IS NOT NULL
            ORDER BY ta.exit_time DESC
            LIMIT #{limit}
            """)
    List<TradeAttribution> selectLatestByUser(@Param("userId") Long userId,
                                              @Param("limit") int limit);

    /** Admin 看板用：按策略路径统计近 N 天样本、胜率、EV 和平均持仓。 */
    @Select("""
            SELECT
                ta.strategy_path AS "path",
                COUNT(*)::int AS "samples",
                COUNT(*) FILTER (WHERE ta.pnl > 0)::int AS "wins",
                ROUND(COUNT(*) FILTER (WHERE ta.pnl > 0)::numeric / NULLIF(COUNT(*), 0), 4) AS "winRate",
                ROUND(AVG(ta.pnl), 4) AS "ev",
                ROUND(SUM(ta.pnl), 4) AS "totalPnl",
                ROUND(AVG(ta.holding_minutes), 2) AS "avgHoldingMinutes"
            FROM trade_attribution ta
            JOIN futures_position fp ON fp.id = ta.position_id
            WHERE fp.user_id = #{userId}
              AND ta.exit_time >= #{from}
              AND ta.pnl IS NOT NULL
            GROUP BY ta.strategy_path
            ORDER BY ta.strategy_path
            """)
    List<Map<String, Object>> selectPathStatsByUserSince(@Param("userId") Long userId,
                                                         @Param("from") LocalDateTime from);

    /** Admin 看板用：统计用户全部归因记录的累计胜率、平均盈亏和总盈亏。 */
    @Select("""
            SELECT
                COUNT(*)::int AS "samples",
                COUNT(*) FILTER (WHERE ta.pnl > 0)::int AS "wins",
                ROUND(COUNT(*) FILTER (WHERE ta.pnl > 0)::numeric / NULLIF(COUNT(*), 0), 4) AS "winRate",
                ROUND(AVG(ta.pnl), 4) AS "avgPnl",
                ROUND(SUM(ta.pnl), 4) AS "totalPnl",
                ROUND(AVG(ta.holding_minutes), 2) AS "avgHoldingMinutes"
            FROM trade_attribution ta
            JOIN futures_position fp ON fp.id = ta.position_id
            WHERE fp.user_id = #{userId}
              AND ta.pnl IS NOT NULL
            """)
    Map<String, Object> selectOverallStatsByUser(@Param("userId") Long userId);
}
