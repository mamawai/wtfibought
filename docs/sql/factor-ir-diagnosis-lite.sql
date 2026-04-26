-- Sprint B1-2: 轻量版现有因子 IR 诊断
-- 用来替代 factor-ir-diagnosis.sql 的第 2 段重查询。
-- 只读脚本；不建表、不改数据。
--
-- 跑法：
--   1. 先按需调整 params.start_time / end_time。
--   2. 直接执行本文件整段。
--   3. 如果仍超过 1 分钟，先跑下面的索引检查：
--      SELECT tablename, indexname, indexdef
--      FROM pg_indexes
--      WHERE tablename IN ('quant_forecast_cycle', 'quant_agent_vote', 'quant_forecast_verification')
--      ORDER BY tablename, indexname;
--
-- 口径：
--   aligned_return_bps: LONG 取 actual_change_bps，SHORT 取 -actual_change_bps。
--   signed 因子: 只统计与 agent 投票方向一致的触发样本。
--   magnitude 因子: 只统计强度超过阈值的触发样本。
--
-- 这个版本刻意只保留核心字段，并且先抽样 cycle，
-- 避免旧库全量 JSON 展开和全量 join 跑太久。

WITH params AS (
    SELECT
        now() - interval '30 days' AS start_time,
        now() AS end_time,
        3000::int AS max_cycles,
        15::int AS min_samples
),
sample_cycles AS MATERIALIZED (
    SELECT
        c.cycle_id,
        c.symbol,
        c.forecast_time,
        c.snapshot_json
    FROM quant_forecast_cycle c
    JOIN params p ON true
    WHERE c.forecast_time >= p.start_time
      AND c.forecast_time < p.end_time
    ORDER BY c.forecast_time DESC
    LIMIT (SELECT max_cycles FROM params)
),
cycle_features AS MATERIALIZED (
    SELECT
        c.cycle_id,
        c.symbol,
        c.forecast_time,
        COALESCE(c.snapshot_json #>> '{regime}', 'UNKNOWN') AS regime,

        NULLIF(c.snapshot_json #>> '{priceChanges,5m}', '')::numeric AS price_change_5m,
        NULLIF(c.snapshot_json #>> '{priceChanges,15m}', '')::numeric AS price_change_15m,

        NULLIF(c.snapshot_json #>> '{spotBidAskImbalance}', '')::numeric AS spot_bid_ask_imbalance,
        NULLIF(c.snapshot_json #>> '{spotLeadLagScore}', '')::numeric AS spot_lead_lag_score,
        -NULLIF(c.snapshot_json #>> '{spotPerpBasisBps}', '')::numeric / 12.0 AS basis_contrarian_score,

        NULLIF(c.snapshot_json #>> '{bidAskImbalance}', '')::numeric AS bid_ask_imbalance,
        NULLIF(c.snapshot_json #>> '{tradeDelta}', '')::numeric AS trade_delta,
        NULLIF(c.snapshot_json #>> '{largeTradeBias}', '')::numeric AS large_trade_bias,
        NULLIF(c.snapshot_json #>> '{takerBuySellPressure}', '')::numeric AS taker_buy_sell_pressure,
        NULLIF(c.snapshot_json #>> '{topTraderBias}', '')::numeric AS top_trader_bias,
        ABS(NULLIF(c.snapshot_json #>> '{oiChangeRate}', '')::numeric) AS oi_change_abs,
        -NULLIF(c.snapshot_json #>> '{liquidationPressure}', '')::numeric AS liquidation_contrarian,

        -NULLIF(c.snapshot_json #>> '{fundingDeviation}', '')::numeric AS funding_deviation_contrarian,
        -NULLIF(c.snapshot_json #>> '{fundingRateTrend}', '')::numeric AS funding_trend_contrarian,
        -NULLIF(c.snapshot_json #>> '{lsrExtreme}', '')::numeric AS lsr_contrarian,
        (NULLIF(c.snapshot_json #>> '{fearGreedIndex}', '')::numeric - 50.0) / 50.0 AS fear_greed_centered,

        NULLIF(c.snapshot_json #>> '{indicatorsByTimeframe,5m,ma_alignment}', '')::numeric AS ma_alignment_5m,
        (NULLIF(c.snapshot_json #>> '{indicatorsByTimeframe,5m,rsi14}', '')::numeric - 50.0) / 50.0 AS rsi_center_5m,
        CASE
            WHEN c.snapshot_json #>> '{indicatorsByTimeframe,5m,macd_hist_trend}' LIKE 'rising%' THEN 1::numeric
            WHEN c.snapshot_json #>> '{indicatorsByTimeframe,5m,macd_hist_trend}' LIKE 'falling%' THEN -1::numeric
            WHEN c.snapshot_json #>> '{indicatorsByTimeframe,5m,macd_hist_trend}' IS NULL THEN NULL::numeric
            ELSE 0::numeric
        END AS macd_hist_trend_5m,
        CASE
            WHEN NULLIF(c.snapshot_json #>> '{indicatorsByTimeframe,5m,kdj_j}', '')::numeric > 80 THEN -1::numeric
            WHEN NULLIF(c.snapshot_json #>> '{indicatorsByTimeframe,5m,kdj_j}', '')::numeric < 20 THEN 1::numeric
            WHEN NULLIF(c.snapshot_json #>> '{indicatorsByTimeframe,5m,kdj_j}', '') IS NULL THEN NULL::numeric
            ELSE 0::numeric
        END AS kdj_extreme_reversal_5m,
        (NULLIF(c.snapshot_json #>> '{indicatorsByTimeframe,5m,boll_pb}', '')::numeric - 50.0) / 50.0 AS boll_pb_center_5m,
        NULLIF(c.snapshot_json #>> '{indicatorsByTimeframe,5m,volume_ratio}', '')::numeric AS volume_ratio_5m,

        NULLIF(c.snapshot_json #>> '{indicatorsByTimeframe,15m,ma_alignment}', '')::numeric AS ma_alignment_15m,
        NULLIF(c.snapshot_json #>> '{indicatorsByTimeframe,15m,adx}', '')::numeric AS adx_15m
    FROM sample_cycles c
),
base AS MATERIALIZED (
    SELECT
        f.cycle_id,
        f.symbol,
        f.forecast_time,
        f.regime,
        a.agent,
        a.horizon,
        a.direction AS vote_direction,
        a.confidence::numeric AS vote_confidence,
        CASE a.direction
            WHEN 'LONG' THEN v.actual_change_bps::numeric
            WHEN 'SHORT' THEN -v.actual_change_bps::numeric
        END AS aligned_return_bps,

        f.price_change_5m,
        f.price_change_15m,
        f.spot_bid_ask_imbalance,
        f.spot_lead_lag_score,
        f.basis_contrarian_score,
        f.bid_ask_imbalance,
        f.trade_delta,
        f.large_trade_bias,
        f.taker_buy_sell_pressure,
        f.top_trader_bias,
        f.oi_change_abs,
        f.liquidation_contrarian,
        f.funding_deviation_contrarian,
        f.funding_trend_contrarian,
        f.lsr_contrarian,
        f.fear_greed_centered,
        f.ma_alignment_5m,
        f.rsi_center_5m,
        f.macd_hist_trend_5m,
        f.kdj_extreme_reversal_5m,
        f.boll_pb_center_5m,
        f.volume_ratio_5m,
        f.ma_alignment_15m,
        f.adx_15m
    FROM cycle_features f
    JOIN quant_agent_vote a
        ON a.cycle_id = f.cycle_id
    JOIN quant_forecast_verification v
        ON v.cycle_id = a.cycle_id
       AND v.horizon = a.horizon
    WHERE v.actual_change_bps IS NOT NULL
      AND a.direction IN ('LONG', 'SHORT')
),
factor_values AS (
    SELECT
        b.*,
        fv.factor_group,
        fv.factor_name,
        fv.factor_mode,
        fv.active_threshold,
        fv.factor_value
    FROM base b
    CROSS JOIN LATERAL (
        VALUES
            ('price', 'price_change_5m', 'signed', 0.0001::numeric, b.price_change_5m),
            ('price', 'price_change_15m', 'signed', 0.0001::numeric, b.price_change_15m),

            ('spot_perp', 'spot_bid_ask_imbalance', 'signed', 0.05::numeric, b.spot_bid_ask_imbalance),
            ('spot_perp', 'spot_lead_lag_score', 'signed', 0.05::numeric, b.spot_lead_lag_score),
            ('spot_perp', 'basis_contrarian_score', 'signed', 0.05::numeric, b.basis_contrarian_score),

            ('micro', 'bid_ask_imbalance', 'signed', 0.05::numeric, b.bid_ask_imbalance),
            ('micro', 'trade_delta', 'signed', 0.05::numeric, b.trade_delta),
            ('micro', 'large_trade_bias', 'signed', 0.05::numeric, b.large_trade_bias),
            ('micro', 'taker_buy_sell_pressure', 'signed', 0.05::numeric, b.taker_buy_sell_pressure),
            ('micro', 'top_trader_bias', 'signed', 0.05::numeric, b.top_trader_bias),
            ('micro', 'oi_change_abs', 'magnitude', 0.01::numeric, b.oi_change_abs),
            ('micro', 'liquidation_contrarian', 'signed', 0.05::numeric, b.liquidation_contrarian),

            ('sentiment', 'funding_deviation_contrarian', 'signed', 0.05::numeric, b.funding_deviation_contrarian),
            ('sentiment', 'funding_trend_contrarian', 'signed', 0.05::numeric, b.funding_trend_contrarian),
            ('sentiment', 'lsr_contrarian', 'signed', 0.05::numeric, b.lsr_contrarian),
            ('sentiment', 'fear_greed_centered', 'signed', 0.10::numeric, b.fear_greed_centered),

            ('tech_5m', 'ma_alignment_5m', 'signed', 0.5::numeric, b.ma_alignment_5m),
            ('tech_5m', 'rsi_center_5m', 'signed', 0.10::numeric, b.rsi_center_5m),
            ('tech_5m', 'macd_hist_trend_5m', 'signed', 0.5::numeric, b.macd_hist_trend_5m),
            ('tech_5m', 'kdj_extreme_reversal_5m', 'signed', 0.5::numeric, b.kdj_extreme_reversal_5m),
            ('tech_5m', 'boll_pb_center_5m', 'signed', 0.10::numeric, b.boll_pb_center_5m),
            ('tech_5m', 'volume_ratio_5m', 'magnitude', 1.3::numeric, b.volume_ratio_5m),

            ('tech_15m', 'ma_alignment_15m', 'signed', 0.5::numeric, b.ma_alignment_15m),
            ('tech_15m', 'adx_15m', 'magnitude', 15.0::numeric, b.adx_15m)
    ) AS fv(factor_group, factor_name, factor_mode, active_threshold, factor_value)
    WHERE fv.factor_value IS NOT NULL
),
factor_available AS (
    SELECT
        agent,
        horizon,
        regime,
        factor_group,
        factor_name,
        factor_mode,
        COUNT(*) AS available_samples
    FROM factor_values
    GROUP BY agent, horizon, regime, factor_group, factor_name, factor_mode
),
active_factor AS (
    SELECT *
    FROM factor_values
    WHERE (
        factor_mode = 'signed'
        AND (
            (vote_direction = 'LONG' AND factor_value > active_threshold)
            OR (vote_direction = 'SHORT' AND factor_value < -active_threshold)
        )
    ) OR (
        factor_mode = 'magnitude'
        AND ABS(factor_value) >= active_threshold
    )
),
metrics AS (
    SELECT
        af.factor_group,
        af.factor_name,
        af.factor_mode,
        af.active_threshold,
        af.agent,
        af.horizon,
        af.regime,
        fa.available_samples,
        COUNT(*) AS active_samples,
        ROUND(COUNT(*)::numeric / NULLIF(fa.available_samples, 0), 4) AS active_coverage,
        ROUND(AVG(af.factor_value), 6) AS avg_factor_value,
        ROUND(AVG(af.aligned_return_bps), 4) AS mean_aligned_return_bps,
        ROUND(STDDEV_SAMP(af.aligned_return_bps), 4) AS std_aligned_return_bps,
        ROUND(AVG(af.aligned_return_bps) / NULLIF(STDDEV_SAMP(af.aligned_return_bps), 0), 4) AS ir,
        ROUND(AVG(CASE WHEN af.aligned_return_bps > 0 THEN 1 ELSE 0 END), 4) AS win_rate,
        ROUND(AVG(af.vote_confidence), 4) AS avg_confidence
    FROM active_factor af
    JOIN factor_available fa
      ON fa.agent = af.agent
     AND fa.horizon = af.horizon
     AND fa.regime = af.regime
     AND fa.factor_group = af.factor_group
     AND fa.factor_name = af.factor_name
     AND fa.factor_mode = af.factor_mode
    GROUP BY
        af.factor_group,
        af.factor_name,
        af.factor_mode,
        af.active_threshold,
        af.agent,
        af.horizon,
        af.regime,
        fa.available_samples
    HAVING COUNT(*) >= (SELECT min_samples FROM params)
)
SELECT
    factor_group,
    factor_name,
    factor_mode,
    active_threshold,
    agent,
    horizon,
    regime,
    available_samples,
    active_samples,
    active_coverage,
    avg_factor_value,
    mean_aligned_return_bps,
    std_aligned_return_bps,
    ir,
    win_rate,
    avg_confidence,
    CASE
        WHEN active_samples >= 50 AND ir >= 0.12 THEN 'STRENGTHEN_CANDIDATE'
        WHEN active_samples >= 50 AND ir <= -0.08 THEN 'DOWNWEIGHT_OR_GATE_CANDIDATE'
        ELSE 'OBSERVE'
    END AS diagnosis_hint
FROM metrics
ORDER BY ABS(ir) DESC NULLS LAST, active_samples DESC, factor_group, factor_name;
