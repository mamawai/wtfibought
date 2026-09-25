package com.mawai.wiibagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.JevPredictionDecision;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface JevPredictionDecisionMapper extends BaseMapper<JevPredictionDecision> {

    /**
     * 记分汇总：回合数、下注数（含加注）、卖出次数、已结注单与胜场、盈亏、手续费、全都拿到结算的盈亏，
     * 三个概率各自的 Brier 均值（只算 UP/DOWN 已结、有 p_jev 的行，三列同一批样本；R4 起不问谁赢，没有 Brier）
     */
    @Data
    class Stats {
        private int windows;
        private int bets;
        private int sells;
        private int settledBets;
        private int wins;
        private BigDecimal pnl;
        /** 买入按成交均价、卖出按 reason 里的成交买价算，费率同 PredictionFee */
        private BigDecimal fees;
        /** 跟 pnl 同一批买入要是都不卖、拿到结算的盈亏（扣买入手续费）；作废回合照实际盈亏算 */
        private BigDecimal heldPnl;
        /** 有结果且有 p_jev 的行数，Brier 的样本量 */
        private int scored;
        private BigDecimal brierModel;
        private BigDecimal brierJev;
        private BigDecimal brierMkt;
    }

    /** 按检查点分的 Brier：越靠后三列都该越小，看 Jev 在哪一段有用 */
    @Data
    class CheckpointBrier {
        private String checkpoint;
        private int n;
        private BigDecimal brierModel;
        private BigDecimal brierJev;
        private BigDecimal brierMkt;
    }

    /** 校准桶：p_jev 五等分，每桶样本数、平均预测、实际涨的比例 */
    @Data
    class CalibrationBucket {
        private int bucket;
        private int n;
        private BigDecimal meanP;
        private BigDecimal hitRate;
    }

    @Select("""
            SELECT COUNT(DISTINCT window_start) AS windows,
                   COUNT(*) FILTER (WHERE action IN ('BUY_UP', 'BUY_DOWN')) AS bets,
                   COUNT(*) FILTER (WHERE action = 'SELL') AS sells,
                   COUNT(*) FILTER (WHERE action IN ('BUY_UP', 'BUY_DOWN') AND pnl IS NOT NULL) AS settled_bets,
                   COUNT(*) FILTER (WHERE action IN ('BUY_UP', 'BUY_DOWN') AND pnl > 0) AS wins,
                   COALESCE(SUM(pnl), 0) AS pnl,
                   COALESCE(SUM(0.07 * avg_price * (1 - avg_price) * shares) FILTER (WHERE action IN ('BUY_UP', 'BUY_DOWN')), 0)
                     + COALESCE(SUM(0.07 * sell_px * (1 - sell_px) * shares) FILTER (WHERE action = 'SELL'), 0) AS fees,
                   COALESCE(SUM(CASE WHEN outcome IN ('UP', 'DOWN')
                                     THEN shares * CASE WHEN (action = 'BUY_UP' AND outcome = 'UP') OR (action = 'BUY_DOWN' AND outcome = 'DOWN')
                                                        THEN 1 ELSE 0 END - stake - 0.07 * avg_price * (1 - avg_price) * shares
                                     ELSE pnl END)
                       FILTER (WHERE action IN ('BUY_UP', 'BUY_DOWN') AND pnl IS NOT NULL), 0) AS held_pnl,
                   COUNT(*) FILTER (WHERE outcome IN ('UP', 'DOWN') AND p_jev IS NOT NULL) AS scored,
                   AVG(POWER(p_model - CASE outcome WHEN 'UP' THEN 1 ELSE 0 END, 2))
                       FILTER (WHERE outcome IN ('UP', 'DOWN') AND p_jev IS NOT NULL AND p_model IS NOT NULL) AS brier_model,
                   AVG(POWER(p_jev - CASE outcome WHEN 'UP' THEN 1 ELSE 0 END, 2))
                       FILTER (WHERE outcome IN ('UP', 'DOWN') AND p_jev IS NOT NULL) AS brier_jev,
                   AVG(POWER(p_mkt - CASE outcome WHEN 'UP' THEN 1 ELSE 0 END, 2))
                       FILTER (WHERE outcome IN ('UP', 'DOWN') AND p_jev IS NOT NULL AND p_mkt IS NOT NULL) AS brier_mkt
            FROM (
                -- 卖出行的成交买价：reason 是 "SELL p bid 看到的[→实际的]"，有箭头取实际的
                SELECT *, CASE WHEN action = 'SELL'
                               THEN CAST(COALESCE(SUBSTRING(reason FROM '→([0-9.]+)$'), SUBSTRING(reason FROM 'bid ([0-9.]+)')) AS NUMERIC)
                          END AS sell_px
                FROM jev_prediction_decision
                WHERE run_no = #{runNo}
            ) d
            """)
    Stats selectStats(@Param("runNo") int runNo);

    @Select("""
            SELECT checkpoint, COUNT(*) AS n,
                   AVG(POWER(p_model - CASE outcome WHEN 'UP' THEN 1 ELSE 0 END, 2)) AS brier_model,
                   AVG(POWER(p_jev - CASE outcome WHEN 'UP' THEN 1 ELSE 0 END, 2)) AS brier_jev,
                   AVG(POWER(p_mkt - CASE outcome WHEN 'UP' THEN 1 ELSE 0 END, 2)) AS brier_mkt
            FROM jev_prediction_decision
            WHERE run_no = #{runNo} AND outcome IN ('UP', 'DOWN') AND p_jev IS NOT NULL AND p_model IS NOT NULL
            GROUP BY checkpoint ORDER BY CAST(SUBSTRING(checkpoint FROM 2) AS INT)
            """)
    List<CheckpointBrier> selectBrierByCheckpoint(@Param("runNo") int runNo);

    @Select("""
            SELECT width_bucket(p_jev, 0, 1, 5) AS bucket, COUNT(*) AS n, AVG(p_jev) AS mean_p,
                   AVG(CASE outcome WHEN 'UP' THEN 1.0 ELSE 0.0 END) AS hit_rate
            FROM jev_prediction_decision
            WHERE run_no = #{runNo} AND outcome IN ('UP', 'DOWN') AND p_jev IS NOT NULL
            GROUP BY bucket ORDER BY bucket
            """)
    List<CalibrationBucket> selectCalibration(@Param("runNo") int runNo);

    @Select("SELECT * FROM jev_prediction_decision WHERE run_no = #{runNo} ORDER BY decided_at DESC LIMIT #{limit}")
    List<JevPredictionDecision> selectRecent(@Param("runNo") int runNo, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM jev_prediction_decision WHERE window_start = #{windowStart} AND checkpoint = #{checkpoint}")
    int countCheckpoint(@Param("windowStart") long windowStart, @Param("checkpoint") String checkpoint);

    /** 回填要看的行：没结果的，或开过仓但盈亏还没填的；窗口在 (afterWs, beforeWs) 之间 */
    @Select("SELECT * FROM jev_prediction_decision WHERE window_start < #{beforeWs} AND window_start > #{afterWs} "
            + "AND (outcome IS NULL OR (action IN ('BUY_UP', 'BUY_DOWN') AND pnl IS NULL)) ORDER BY window_start")
    List<JevPredictionDecision> selectPendingSettle(@Param("beforeWs") long beforeWs, @Param("afterWs") long afterWs);
}
