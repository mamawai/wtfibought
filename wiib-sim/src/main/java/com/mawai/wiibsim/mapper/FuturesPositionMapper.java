package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.FuturesStopLoss;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import com.mawai.wiibsim.dto.CrossSnapshotRow;
import com.mawai.wiibsim.dto.PositionFillDTO;
import com.mawai.wiibsim.dto.PositionHistoryDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface FuturesPositionMapper extends BaseMapper<FuturesPosition> {

    /** 调杠杆：杠杆与保证金一起改（逐仓=划扣额，全仓=占用额） */
    @Update("UPDATE futures_position SET leverage = #{leverage}, margin = #{margin}, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN'")
    int updateLeverageAndMargin(@Param("positionId") Long positionId,
                                @Param("leverage") int leverage,
                                @Param("margin") BigDecimal margin);

    /** 原子追加保证金 */
    @Update("UPDATE futures_position SET margin = margin + #{amount}, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN'")
    int atomicAddMargin(@Param("positionId") Long positionId, @Param("amount") BigDecimal amount);

    /** 原子减少保证金 */
    @Update("UPDATE futures_position SET margin = margin - #{amount}, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN' AND margin >= #{amount}")
    int atomicReduceMargin(@Param("positionId") Long positionId, @Param("amount") BigDecimal amount);

    /**
     * 原子扣除资金费率（足够扣），返回扣后保证金；null=没改成（保证金不够或仓位已关）。
     * <p>
     * 这两条按 UserMapper 类注释里那套 @Select + UPDATE...RETURNING 范式写：账本要的是
     * 变动后余额，影响行数不够用。顺带把调用方原来那句 pos.getMargin().subtract(fee) 的
     * 无锁重算干掉了——那是扣款前读的快照，并发追加/减少保证金后算出来的强平价是脏的。
     * margin 是 NOT NULL 列，所以 null 只可能是"没匹配到行"，无二义性。
     * flushCache 必须开：挂 @Select 但实为 UPDATE，同事务内同参第二次调用会被一级缓存挡掉、SQL 不发 DB。
     */
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("UPDATE futures_position SET margin = margin - #{fee}, funding_fee_total = funding_fee_total + #{fee}, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN' AND margin >= #{fee} " +
            "RETURNING margin")
    BigDecimal atomicDeductFundingFee(@Param("positionId") Long positionId, @Param("fee") BigDecimal fee);

    /** 原子扣除资金费率（不够扣，扣光），返回扣后保证金（恒为 0）；null=没改成 */
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("UPDATE futures_position SET funding_fee_total = funding_fee_total + margin, margin = 0, updated_at = NOW() " +
            "WHERE id = #{positionId} AND status = 'OPEN' AND margin > 0 " +
            "RETURNING margin")
    BigDecimal atomicDeductFundingFeePartial(@Param("positionId") Long positionId);

    /**
     * 加行锁读当前保证金，专给上面那条"扣光"用：它是整体覆写（SET margin = 0），
     * 扣款额只能是覆写前那一刻的保证金，而 RETURNING 只拿得到新值（恒为 0）。
     * 取锁之后并发的保证金增减都在锁上排队，读到的就是这条 UPDATE 真正抹掉的金额。
     * null=仓位不在或已关（那么紧跟的 UPDATE 也必然一行不改）。
     * <p>
     * 只取 margin 一列而不是 SELECT * 进实体：实体的 stop_losses/take_profits 是 JSONB，
     * 靠 MP resultMap 里的 typeHandler 才映射得上，而注解 @Select 走的是自动映射、用不到那份 resultMap。
     * <p>
     * 必须禁缓存，理由同 UserMapper.selectByIdForUpdate：它的价值在<b>取锁</b>，
     * 而锁是"把 SQL 发给 DB"的副作用；被一级缓存挡掉就是锁没取到而调用方以为拿着锁。
     */
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT margin FROM futures_position WHERE id = #{positionId} AND status = 'OPEN' FOR UPDATE")
    BigDecimal selectMarginForUpdate(@Param("positionId") Long positionId);

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

    /** 排行榜交易盈利：资金费已从余额或保证金扣过，这里按仓位累计扣回 */
    @Select("SELECT user_id, SUM(funding_fee_total) AS amount " +
            "FROM futures_position GROUP BY user_id")
    List<Map<String, Object>> sumFundingFeeTotalAll();

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

    @Select("""
            SELECT COALESCE(
                AVG(CASE WHEN stop_losses IS NOT NULL AND stop_losses::text != '[]' THEN 1 ELSE 0 END),
                0
            )
            FROM futures_position
            WHERE user_id = #{userId}
            """)
    BigDecimal selectStopLossRate(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM futures_position WHERE user_id = #{userId} AND status = 'LIQUIDATED'")
    int countLiquidatedPositions(@Param("userId") Long userId);

    /**
     * 仓位历史分页：已平/已强平的仓位 + 它名下全部成交单的聚合。
     * 非聚合不可：部分平仓后仓位表的 closed_pnl/quantity 是残值，真实数字只能从订单表加出来。
     * 子查询里也带 user_id，走 idx_fo_user 只扫这个人的单。
     * 状态四选（FILLED/STOP_LOSS/TAKE_PROFIT/LIQUIDATED 是订单终态），漏一个少算一段盈亏。
     * 已实现盈亏与 ROI 在 SQL 里减完手续费和资金费一步到位。
     * symbol 可选过滤用 (? IS NULL OR p.symbol = ?)：访问路径是 user_id 索引，计划中性；
     * null 才是"不筛"，空串由 PositionHistoryService 归一。
     */
    @Select("""
            SELECT p.id, p.symbol, p.side, p.margin_mode, p.leverage, p.status, p.memo,
                   p.entry_price, p.funding_fee_total,
                   p.created_at AS opened_at,
                   p.updated_at AS closed_at,
                   COALESCE(o.closed_qty, 0)      AS closed_qty,
                   COALESCE(o.close_amount, 0)    AS close_amount,
                   COALESCE(o.commission, 0)      AS commission,
                   COALESCE(o.invested_margin, 0) AS invested_margin,
                   CASE WHEN COALESCE(o.closed_qty, 0) > 0
                        THEN ROUND(o.close_amount / o.closed_qty, 8) END AS close_avg_price,
                   COALESCE(o.net_pnl, 0) - p.funding_fee_total AS realized_pnl,
                   CASE WHEN COALESCE(o.invested_margin, 0) > 0
                        THEN ROUND((COALESCE(o.net_pnl, 0) - p.funding_fee_total)
                                   / o.invested_margin * 100, 2) END AS roi_pct
            FROM futures_position p
            LEFT JOIN (
                SELECT position_id,
                       SUM(CASE WHEN order_side LIKE 'CLOSE%' THEN quantity ELSE 0 END)                    AS closed_qty,
                       SUM(CASE WHEN order_side LIKE 'CLOSE%' THEN COALESCE(filled_amount, 0) ELSE 0 END)  AS close_amount,
                       SUM(CASE WHEN order_side LIKE 'CLOSE%' THEN 0 ELSE COALESCE(margin_amount, 0) END)  AS invested_margin,
                       SUM(COALESCE(commission, 0))                                                        AS commission,
                       SUM(COALESCE(realized_pnl, 0) - COALESCE(commission, 0))                            AS net_pnl
                FROM futures_order
                WHERE user_id = #{userId}
                  AND status IN ('FILLED', 'STOP_LOSS', 'TAKE_PROFIT', 'LIQUIDATED')
                GROUP BY position_id
            ) o ON o.position_id = p.id
            WHERE p.user_id = #{userId} AND p.status IN ('CLOSED', 'LIQUIDATED')
              AND (#{symbol, jdbcType=VARCHAR} IS NULL OR p.symbol = #{symbol, jdbcType=VARCHAR})
            ORDER BY p.updated_at DESC, p.id DESC
            """)
    IPage<PositionHistoryDTO> selectPositionHistory(IPage<PositionHistoryDTO> page,
                                                    @Param("userId") Long userId,
                                                    @Param("symbol") String symbol);

    /**
     * 上面那页仓位的成交明细，一次全取回来按仓位分组，不逐行再查（那是 N+1）。
     * <p>
     * 成交时间取 updated_at 不取 created_at：限价单挂上和真正成交是两个时刻，
     * 按下单时间排会让"先挂后成"的单插到前面去，分批平仓的顺序就乱了。
     */
    @Select("""
            SELECT position_id, id AS order_id, order_side, order_type, status,
                   quantity, filled_price AS price, filled_amount AS amount,
                   commission, realized_pnl, updated_at AS filled_at
            FROM futures_order
            WHERE status IN ('FILLED', 'STOP_LOSS', 'TAKE_PROFIT', 'LIQUIDATED')
              AND position_id = ANY(#{positionIds, typeHandler=org.apache.ibatis.type.ArrayTypeHandler})
            ORDER BY updated_at, id
            """)
    List<PositionFillDTO> selectFillsByPositionIds(@Param("positionIds") Long[] positionIds);

    @Update("UPDATE futures_position SET status = #{status}, updated_at = NOW() " +
            "WHERE user_id = #{userId} AND status = 'OPEN'")
    int closeOpenByUserId(@Param("userId") Long userId, @Param("status") String status);

    /**
     * 全仓账户快照三查合一：余额 + 全仓持仓 + 挂单占用一条 SQL 出。
     * <p>
     * 为什么合：tick 巡检（CrossLiquidationService）每用户每秒一次全打在 snapshot 上，压测（2026-08）
     * 显示每条查询服务端只要 0.02ms、成本全在连接池往返——三条查询 = 拿三次池，巡检突发把 10 连接
     * 打满时正常 API p99 从 4ms 恶化到 47ms。合一后拿池次数 3→1，巡检吞吐上限 ~2800/s → ~8000/s。
     * <p>
     * 用户不存在返回空列表；有账号无持仓返回一行 position_id 为 NULL 的行（LEFT JOIN）。
     * 挂单占用口径：全仓开/加仓限价单预留的保证金+手续费，状态含 PENDING 以及触发后还没落成仓位的
     * TRIGGERED / PROCESSING。成交事务里本单是 PROCESSING，读到的可用额度已扣掉本单预留。
     */
    @Select("""
            SELECT u.balance,
                   (SELECT COALESCE(SUM(o.frozen_amount), 0) FROM futures_order o
                     WHERE o.user_id = u.id AND o.status IN ('PENDING', 'TRIGGERED', 'PROCESSING')
                       AND o.margin_mode = 'CROSS'
                       AND o.order_side NOT LIKE 'CLOSE%') AS pending_reserved,
                   p.id AS position_id, p.symbol, p.side, p.leverage,
                   p.quantity, p.entry_price, p.margin, p.funding_fee_total
            FROM "user" u
            LEFT JOIN futures_position p
                   ON p.user_id = u.id AND p.status = 'OPEN' AND p.margin_mode = 'CROSS'
            WHERE u.id = #{userId}
            """)
    List<CrossSnapshotRow> selectCrossSnapshot(@Param("userId") Long userId);
}
