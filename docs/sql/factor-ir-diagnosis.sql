-- Sprint B1-1: 现有数据 IR 诊断
-- PostgreSQL 只读脚本；不建表、不改数据。
--
-- aligned_return_bps:
--   agent 投 LONG 时取 actual_change_bps；
--   agent 投 SHORT 时取 -actual_change_bps；
--   值越大说明该 agent 当时方向越对。
--
-- IR:
--   mean(aligned_return_bps | factor_signal) / stddev_samp(aligned_return_bps | factor_signal)
--
-- factor_mode:
--   signed    = 因子有方向，正偏多、负偏空；只统计与 agent 投票方向一致的触发样本。
--   magnitude = 因子只表示强弱/风险，不自带多空；统计因子活跃时 agent 投票表现。

-- 1) B1-3 可直接用的 agent/horizon/regime 基线 IR。
WITH params AS (
    SELECT
        now() - interval '30 days' AS start_time,
        5::int AS min_samples
),
base AS (
    SELECT
        c.cycle_id,
        c.symbol,
        c.forecast_time,
        COALESCE(c.snapshot_json #>> '{regime}', 'UNKNOWN') AS regime,
        a.agent,
        a.horizon,
        a.direction AS vote_direction,
        a.score::numeric AS vote_score,
        a.confidence::numeric AS vote_confidence,
        a.reason_codes,
        v.prediction_correct,
        v.actual_change_bps::numeric AS actual_change_bps,
        CASE a.direction
            WHEN 'LONG' THEN v.actual_change_bps::numeric
            WHEN 'SHORT' THEN -v.actual_change_bps::numeric
        END AS aligned_return_bps
    FROM quant_forecast_cycle c
    JOIN params p ON true
    JOIN quant_agent_vote a
        ON a.cycle_id = c.cycle_id
    JOIN quant_forecast_verification v
        ON v.cycle_id = a.cycle_id
       AND v.horizon = a.horizon
    WHERE c.forecast_time >= p.start_time
      AND v.actual_change_bps IS NOT NULL
      AND a.direction IN ('LONG', 'SHORT')
)
SELECT
    agent,
    horizon,
    regime,
    COUNT(*) AS samples,
    ROUND(AVG(aligned_return_bps), 4) AS mean_aligned_return_bps,
    ROUND(STDDEV_SAMP(aligned_return_bps), 4) AS std_aligned_return_bps,
    ROUND(AVG(aligned_return_bps) / NULLIF(STDDEV_SAMP(aligned_return_bps), 0), 4) AS ir,
    ROUND(AVG(CASE WHEN aligned_return_bps > 0 THEN 1 ELSE 0 END), 4) AS win_rate,
    ROUND(AVG(vote_confidence), 4) AS avg_confidence,
    ROUND(AVG(ABS(vote_score)), 4) AS avg_abs_score
FROM base
GROUP BY agent, horizon, regime
HAVING COUNT(*) >= (SELECT min_samples FROM params)
ORDER BY ir DESC NULLS LAST, samples DESC;

-- 2) snapshot_json 中现有数值因子的 IR 明细。
WITH params AS (
    SELECT
        now() - interval '30 days' AS start_time,
        5::int AS min_samples
),
base AS (
    SELECT
        c.cycle_id,
        c.symbol,
        c.forecast_time,
        c.snapshot_json,
        COALESCE(c.snapshot_json #>> '{regime}', 'UNKNOWN') AS regime,
        a.agent,
        a.horizon,
        a.direction AS vote_direction,
        a.score::numeric AS vote_score,
        a.confidence::numeric AS vote_confidence,
        v.prediction_correct,
        CASE a.direction
            WHEN 'LONG' THEN v.actual_change_bps::numeric
            WHEN 'SHORT' THEN -v.actual_change_bps::numeric
        END AS aligned_return_bps
    FROM quant_forecast_cycle c
    JOIN params p ON true
    JOIN quant_agent_vote a
        ON a.cycle_id = c.cycle_id
    JOIN quant_forecast_verification v
        ON v.cycle_id = a.cycle_id
       AND v.horizon = a.horizon
    WHERE c.forecast_time >= p.start_time
      AND v.actual_change_bps IS NOT NULL
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
            ('price', 'price_change_1m', 'signed', 0.0001::numeric, NULLIF(b.snapshot_json #>> '{priceChanges,1m}', '')::numeric),
            ('price', 'price_change_5m', 'signed', 0.0001::numeric, NULLIF(b.snapshot_json #>> '{priceChanges,5m}', '')::numeric),
            ('price', 'price_change_15m', 'signed', 0.0001::numeric, NULLIF(b.snapshot_json #>> '{priceChanges,15m}', '')::numeric),
            ('price', 'price_change_1h', 'signed', 0.0001::numeric, NULLIF(b.snapshot_json #>> '{priceChanges,1h}', '')::numeric),

            ('spot_perp', 'spot_price_change_5m', 'signed', 0.0001::numeric, NULLIF(b.snapshot_json #>> '{spotPriceChange5m}', '')::numeric),
            ('spot_perp', 'spot_bid_ask_imbalance', 'signed', 0.05::numeric, NULLIF(b.snapshot_json #>> '{spotBidAskImbalance}', '')::numeric),
            ('spot_perp', 'spot_lead_lag_score', 'signed', 0.05::numeric, NULLIF(b.snapshot_json #>> '{spotLeadLagScore}', '')::numeric),
            ('spot_perp', 'basis_contrarian_score', 'signed', 0.05::numeric, -NULLIF(b.snapshot_json #>> '{spotPerpBasisBps}', '')::numeric / 12.0),

            ('micro', 'bid_ask_imbalance', 'signed', 0.05::numeric, NULLIF(b.snapshot_json #>> '{bidAskImbalance}', '')::numeric),
            ('micro', 'trade_delta', 'signed', 0.05::numeric, NULLIF(b.snapshot_json #>> '{tradeDelta}', '')::numeric),
            ('micro', 'large_trade_bias', 'signed', 0.05::numeric, NULLIF(b.snapshot_json #>> '{largeTradeBias}', '')::numeric),
            ('micro', 'trade_intensity_high', 'magnitude', 1.5::numeric, NULLIF(b.snapshot_json #>> '{tradeIntensity}', '')::numeric),
            ('micro', 'oi_change_rate', 'magnitude', 0.01::numeric, ABS(NULLIF(b.snapshot_json #>> '{oiChangeRate}', '')::numeric)),
            ('micro', 'liquidation_contrarian', 'signed', 0.05::numeric, -NULLIF(b.snapshot_json #>> '{liquidationPressure}', '')::numeric),
            ('micro', 'liquidation_volume_usdt', 'magnitude', 500000::numeric, NULLIF(b.snapshot_json #>> '{liquidationVolumeUsdt}', '')::numeric),
            ('micro', 'top_trader_bias', 'signed', 0.05::numeric, NULLIF(b.snapshot_json #>> '{topTraderBias}', '')::numeric),
            ('micro', 'taker_buy_sell_pressure', 'signed', 0.05::numeric, NULLIF(b.snapshot_json #>> '{takerBuySellPressure}', '')::numeric),

            ('sentiment', 'funding_deviation_contrarian', 'signed', 0.05::numeric, -NULLIF(b.snapshot_json #>> '{fundingDeviation}', '')::numeric),
            ('sentiment', 'funding_trend_contrarian', 'signed', 0.05::numeric, -NULLIF(b.snapshot_json #>> '{fundingRateTrend}', '')::numeric),
            ('sentiment', 'funding_extreme_contrarian', 'signed', 0.05::numeric, -NULLIF(b.snapshot_json #>> '{fundingRateExtreme}', '')::numeric),
            ('sentiment', 'lsr_contrarian', 'signed', 0.05::numeric, -NULLIF(b.snapshot_json #>> '{lsrExtreme}', '')::numeric),
            ('sentiment', 'fear_greed_centered', 'signed', 0.10::numeric, (NULLIF(b.snapshot_json #>> '{fearGreedIndex}', '')::numeric - 50.0) / 50.0),

            ('iv', 'dvol_index', 'magnitude', 1.0::numeric, NULLIF(b.snapshot_json #>> '{dvolIndex}', '')::numeric),
            ('iv', 'atm_iv', 'magnitude', 1.0::numeric, NULLIF(b.snapshot_json #>> '{atmIv}', '')::numeric),
            ('iv', 'iv_skew_25d', 'signed', 0.50::numeric, NULLIF(b.snapshot_json #>> '{ivSkew25d}', '')::numeric),
            ('iv', 'iv_term_slope', 'signed', 0.50::numeric, NULLIF(b.snapshot_json #>> '{ivTermSlope}', '')::numeric),

            ('volatility', 'atr_1m_bps', 'magnitude', 1.0::numeric,
                NULLIF(b.snapshot_json #>> '{atr1m}', '')::numeric * 10000.0 / NULLIF(NULLIF(b.snapshot_json #>> '{lastPrice}', '')::numeric, 0)),
            ('volatility', 'atr_5m_bps', 'magnitude', 1.0::numeric,
                NULLIF(b.snapshot_json #>> '{atr5m}', '')::numeric * 10000.0 / NULLIF(NULLIF(b.snapshot_json #>> '{lastPrice}', '')::numeric, 0)),
            ('volatility', 'boll_squeeze', 'magnitude', 0.5::numeric,
                CASE WHEN b.snapshot_json #>> '{bollSqueeze}' = 'true' THEN 1::numeric ELSE 0::numeric END),
            ('volatility', 'boll_bandwidth_snapshot', 'magnitude', 0.0001::numeric, NULLIF(b.snapshot_json #>> '{bollBandwidth}', '')::numeric),

            ('tech_1m', 'ma_alignment_1m', 'signed', 0.5::numeric, NULLIF(b.snapshot_json #>> '{indicatorsByTimeframe,1m,ma_alignment}', '')::numeric),
            ('tech_1m', 'rsi_center_1m', 'signed', 0.10::numeric, (NULLIF(b.snapshot_json #>> '{indicatorsByTimeframe,1m,rsi14}', '')::numeric - 50.0) / 50.0),
            ('tech_1m', 'macd_hist_1m', 'signed', 0.00000001::numeric, NULLIF(b.snapshot_json #>> '{indicatorsByTimeframe,1m,macd_hist}', '')::numeric),
            ('tech_1m', 'volume_ratio_1m', 'magnitude', 1.3::numeric, NULLIF(b.snapshot_json #>> '{indicatorsByTimeframe,1m,volume_ratio}', '')::numeric),

            ('tech_5m', 'ma_alignment_5m', 'signed', 0.5::numeric, NULLIF(b.snapshot_json #>> '{indicatorsByTimeframe,5m,ma_alignment}', '')::numeric),
            ('tech_5m', 'rsi_center_5m', 'signed', 0.10::numeric, (NULLIF(b.snapshot_json #>> '{indicatorsByTimeframe,5m,rsi14}', '')::numeric - 50.0) / 50.0),
            ('tech_5m', 'macd_cross_5m', 'signed', 0.5::numeric,
                CASE b.snapshot_json #>> '{indicatorsByTimeframe,5m,macd_cross}'
                    WHEN 'golden' THEN 1::numeric
                    WHEN 'death' THEN -1::numeric
                    ELSE 0::numeric
                END),
            ('tech_5m', 'macd_hist_5m', 'signed', 0.00000001::numeric, NULLIF(b.snapshot_json #>> '{indicatorsByTimeframe,5m,macd_hist}', '')::numeric),
            ('tech_5m', 'macd_hist_trend_5m', 'signed', 0.5::numeric,
                CASE
                    WHEN COALESCE(b.snapshot_json #>> '{indicatorsByTimeframe,5m,macd_hist_trend}', '') LIKE 'rising%' THEN 1::numeric
                    WHEN COALESCE(b.snapshot_json #>> '{indicatorsByTimeframe,5m,macd_hist_trend}', '') LIKE 'falling%' THEN -1::numeric
                    ELSE 0::numeric
                END),
            ('tech_5m', 'kdj_cross_5m', 'signed', 0.0001::numeric,
                NULLIF(b.snapshot_json #>> '{indicatorsByTimeframe,5m,kdj_k}', '')::numeric
                - NULLIF(b.snapshot_json #>> '{indicatorsByTimeframe,5m,kdj_d}', '')::numeric),
            ('tech_5m', 'kdj_extreme_reversal_5m', 'signed', 0.5::numeric,
                CASE
                    WHEN NULLIF(b.snapshot_json #>> '{indicatorsByTimeframe,5m,kdj_j}', '')::numeric > 80 THEN -1::numeric
                    WHEN NULLIF(b.snapshot_json #>> '{indicatorsByTimeframe,5m,kdj_j}', '')::numeric < 20 THEN 1::numeric
                    ELSE 0::numeric
                END),
            ('tech_5m', 'boll_pb_center_5m', 'signed', 0.10::numeric,
                (NULLIF(b.snapshot_json #>> '{indicatorsByTimeframe,5m,boll_pb}', '')::numeric - 50.0) / 50.0),
            ('tech_5m', 'boll_bandwidth_5m', 'magnitude', 0.0001::numeric, NULLIF(b.snapshot_json #>> '{indicatorsByTimeframe,5m,boll_bandwidth}', '')::numeric),
            ('tech_5m', 'volume_ratio_5m', 'magnitude', 1.3::numeric, NULLIF(b.snapshot_json #>> '{indicatorsByTimeframe,5m,volume_ratio}', '')::numeric),
            ('tech_5m', 'close_trend_5m', 'signed', 0.5::numeric,
                CASE
                    WHEN COALESCE(b.snapshot_json #>> '{indicatorsByTimeframe,5m,close_trend}', '') IN ('rising_5', 'mostly_up') THEN 1::numeric
                    WHEN COALESCE(b.snapshot_json #>> '{indicatorsByTimeframe,5m,close_trend}', '') IN ('falling_5', 'mostly_down') THEN -1::numeric
                    ELSE 0::numeric
                END),

            ('tech_15m', 'ma_alignment_15m', 'signed', 0.5::numeric, NULLIF(b.snapshot_json #>> '{indicatorsByTimeframe,15m,ma_alignment}', '')::numeric),
            ('tech_15m', 'rsi_center_15m', 'signed', 0.10::numeric, (NULLIF(b.snapshot_json #>> '{indicatorsByTimeframe,15m,rsi14}', '')::numeric - 50.0) / 50.0),
            ('tech_15m', 'plus_minus_di_15m', 'signed', 0.05::numeric,
                (NULLIF(b.snapshot_json #>> '{indicatorsByTimeframe,15m,plus_di}', '')::numeric
                - NULLIF(b.snapshot_json #>> '{indicatorsByTimeframe,15m,minus_di}', '')::numeric) / 30.0),
            ('tech_15m', 'adx_15m', 'magnitude', 15.0::numeric, NULLIF(b.snapshot_json #>> '{indicatorsByTimeframe,15m,adx}', '')::numeric),
            ('tech_15m', 'volume_ratio_15m', 'magnitude', 1.3::numeric, NULLIF(b.snapshot_json #>> '{indicatorsByTimeframe,15m,volume_ratio}', '')::numeric),

            ('tech_1h', 'ma_alignment_1h', 'signed', 0.5::numeric, NULLIF(b.snapshot_json #>> '{indicatorsByTimeframe,1h,ma_alignment}', '')::numeric),
            ('tech_1h', 'plus_minus_di_1h', 'signed', 0.05::numeric,
                (NULLIF(b.snapshot_json #>> '{indicatorsByTimeframe,1h,plus_di}', '')::numeric
                - NULLIF(b.snapshot_json #>> '{indicatorsByTimeframe,1h,minus_di}', '')::numeric) / 30.0),
            ('tech_1h', 'adx_1h', 'magnitude', 15.0::numeric, NULLIF(b.snapshot_json #>> '{indicatorsByTimeframe,1h,adx}', '')::numeric)
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
)
SELECT
    af.factor_group,
    af.factor_name,
    af.factor_mode,
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
    af.agent,
    af.horizon,
    af.regime,
    fa.available_samples
HAVING COUNT(*) >= (SELECT min_samples FROM params)
ORDER BY ir DESC NULLS LAST, active_samples DESC, af.factor_group, af.factor_name;

-- 3) reason_codes 触发 IR：用于 B1-2 报告交叉核对，不参与自动调权。
WITH params AS (
    SELECT
        now() - interval '30 days' AS start_time,
        5::int AS min_samples
),
base AS (
    SELECT
        c.cycle_id,
        COALESCE(c.snapshot_json #>> '{regime}', 'UNKNOWN') AS regime,
        a.agent,
        a.horizon,
        a.direction AS vote_direction,
        a.confidence::numeric AS vote_confidence,
        a.reason_codes,
        CASE a.direction
            WHEN 'LONG' THEN v.actual_change_bps::numeric
            WHEN 'SHORT' THEN -v.actual_change_bps::numeric
        END AS aligned_return_bps
    FROM quant_forecast_cycle c
    JOIN params p ON true
    JOIN quant_agent_vote a
        ON a.cycle_id = c.cycle_id
    JOIN quant_forecast_verification v
        ON v.cycle_id = a.cycle_id
       AND v.horizon = a.horizon
    WHERE c.forecast_time >= p.start_time
      AND v.actual_change_bps IS NOT NULL
      AND a.direction IN ('LONG', 'SHORT')
),
reason_signals AS (
    SELECT
        b.*,
        TRIM(rc.reason_code) AS reason_code
    FROM base b
    CROSS JOIN LATERAL regexp_split_to_table(COALESCE(b.reason_codes, ''), ',') AS rc(reason_code)
    WHERE TRIM(rc.reason_code) <> ''
)
SELECT
    reason_code,
    agent,
    horizon,
    regime,
    COUNT(*) AS samples,
    ROUND(AVG(aligned_return_bps), 4) AS mean_aligned_return_bps,
    ROUND(STDDEV_SAMP(aligned_return_bps), 4) AS std_aligned_return_bps,
    ROUND(AVG(aligned_return_bps) / NULLIF(STDDEV_SAMP(aligned_return_bps), 0), 4) AS ir,
    ROUND(AVG(CASE WHEN aligned_return_bps > 0 THEN 1 ELSE 0 END), 4) AS win_rate,
    ROUND(AVG(vote_confidence), 4) AS avg_confidence
FROM reason_signals
GROUP BY reason_code, agent, horizon, regime
HAVING COUNT(*) >= (SELECT min_samples FROM params)
ORDER BY ir DESC NULLS LAST, samples DESC, reason_code;
