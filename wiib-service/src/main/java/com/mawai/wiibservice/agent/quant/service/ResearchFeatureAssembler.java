package com.mawai.wiibservice.agent.quant.service;

import com.mawai.wiibcommon.entity.FactorHistory;
import com.mawai.wiibservice.agent.research.forecast.ResearchFeatures;
import com.mawai.wiibservice.agent.research.kline.KlineBar;
import com.mawai.wiibservice.agent.research.series.MarketSeriesPoint;
import com.mawai.wiibservice.agent.research.series.MarketSeriesStore;
import com.mawai.wiibservice.agent.research.series.SeriesAligner;
import com.mawai.wiibservice.agent.research.series.SeriesCode;
import com.mawai.wiibservice.mapper.FactorHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Live 路径 ResearchFeatures 装配器：从 factor_history 读取外生因子（funding/FGI/ETF/稳定币），
 * 按 point-in-time 对齐到决策 K线 closeTime，缺数据写中性值+quality flag。
 *
 * <p>与回测路径 {@code ResearchEvalService.assemblePoints()} 的关键区别：
 * live 直接读 {@code factor_history}，再用 {@link SeriesAligner#asOfPoint(List, long)}
 * 按 {@code observed_at <= decisionTime} 做 point-in-time floor join。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchFeatureAssembler {

    /** 资金费中性=0：无持仓成本偏置 */
    static final BigDecimal NEUTRAL_FUNDING = BigDecimal.ZERO;
    /** 恐惧贪婪中性=50：不恐不贪 */
    static final BigDecimal NEUTRAL_FEAR_GREED = BigDecimal.valueOf(50);
    /** ETF/稳定币缺口中性=0：无净流入/供给变化信号 */
    static final BigDecimal NEUTRAL_EXTERNAL = BigDecimal.ZERO;

    /** funding 8h 一次，查 3 天足够容忍短断档，也避免拿太旧持仓成本。 */
    private static final Duration FUNDING_LOOKBACK = Duration.ofDays(3);
    /** FNG 日级且更新稳定，查 7 天即可区分 missing 与短暂延迟。 */
    private static final Duration FNG_LOOKBACK = Duration.ofDays(7);
    /** ETF/稳定币日级但源更杂，查 10 天容忍周末/源站延迟，stale 仍单独打标。 */
    private static final Duration EXTERNAL_FLOW_LOOKBACK = Duration.ofDays(10);
    /** 日级因子超过此阈值视为过期（2天未更新），标记 STALE */
    private static final Duration DAILY_STALE_THRESHOLD = Duration.ofDays(2);
    /** 资金费超过此阈值视为过期（16h=2个funding周期+缓冲），标记 STALE */
    private static final Duration FUNDING_STALE_THRESHOLD = Duration.ofHours(16);

    private final FactorHistoryMapper factorHistoryMapper;

    /**
     * 完整装配结果：features + 数据质量标记。
     * qualityFlags 会被上游合并到 {@code MacroContext.qualityFlags}，供下游节点（RiskGate/DebateJudge）感知数据可信度。
     */
    public record AssemblyResult(ResearchFeatures features, List<String> qualityFlags) {
        public AssemblyResult {
            qualityFlags = qualityFlags == null ? List.of() : List.copyOf(qualityFlags);
        }
    }

    /**
     * 按最新 5m bar 的 closeTime 对齐所有外生因子，返回带质量标记的 ResearchFeatures。
     *
     * @param symbol  交易对（BTCUSDT/ETHUSDT）
     * @param bars5m  决策点可用的全部 5m K线（至少 1 根）
     */
    public AssemblyResult assemble(String symbol, List<KlineBar> bars5m) {
        if (bars5m == null || bars5m.isEmpty()) {
            return new AssemblyResult(
                    ResearchFeatures.ofBars(List.of()),
                    List.of("FEATURE_BARS_EMPTY"));
        }

        long decisionTime = bars5m.getLast().closeTime();
        List<String> qualityFlags = new ArrayList<>();

        double fundingRate = alignFunding(symbol, decisionTime, qualityFlags);
        int fearGreed = alignFearGreed(decisionTime, qualityFlags);
        double etfFlow = alignEtfFlow(symbol, decisionTime, qualityFlags);
        double stablecoinDelta = alignStablecoinDelta(symbol, decisionTime, qualityFlags);

        if (!qualityFlags.isEmpty()) {
            log.info("[FeatureAssembler] symbol={} decisionTime={} flags={} funding={} fng={} etf={} stable={}",
                    symbol, decisionTime, qualityFlags, fundingRate, fearGreed, etfFlow, stablecoinDelta);
        }

        return new AssemblyResult(
                new ResearchFeatures(bars5m, fundingRate, fearGreed, etfFlow, stablecoinDelta),
                List.copyOf(qualityFlags));
    }

    // ---- 各因子对齐逻辑 ----

    private double alignFunding(String symbol, long decisionTime, List<String> flags) {
        AlignedFactor aligned = align(symbol, SeriesCode.FUNDING.factorName(), decisionTime,
                FUNDING_LOOKBACK, NEUTRAL_FUNDING);
        if (aligned.missing()) {
            flags.add("FUNDING_MISSING");
            return NEUTRAL_FUNDING.doubleValue();
        }
        if (decisionTime - aligned.observedMs() > FUNDING_STALE_THRESHOLD.toMillis()) {
            flags.add("FUNDING_STALE");
        }
        return aligned.value().doubleValue();
    }

    private int alignFearGreed(long decisionTime, List<String> flags) {
        AlignedFactor aligned = align(MarketSeriesStore.GLOBAL, SeriesCode.FEAR_GREED.factorName(), decisionTime,
                FNG_LOOKBACK, NEUTRAL_FEAR_GREED);
        if (aligned.missing()) {
            flags.add("FNG_MISSING");
            return NEUTRAL_FEAR_GREED.intValue();
        }
        if (decisionTime - aligned.observedMs() > DAILY_STALE_THRESHOLD.toMillis()) {
            flags.add("FNG_STALE");
        }
        return aligned.value().intValue();
    }

    private double alignEtfFlow(String symbol, long decisionTime, List<String> flags) {
        AlignedFactor aligned = align(symbol, SeriesCode.ETF_FLOW.factorName(), decisionTime,
                EXTERNAL_FLOW_LOOKBACK, NEUTRAL_EXTERNAL);
        if (aligned.missing()) {
            flags.add("ETF_MISSING");
            return NEUTRAL_EXTERNAL.doubleValue();
        }
        if (decisionTime - aligned.observedMs() > DAILY_STALE_THRESHOLD.toMillis()) {
            flags.add("ETF_STALE");
        }
        return aligned.value().doubleValue();
    }

    private double alignStablecoinDelta(String symbol, long decisionTime, List<String> flags) {
        AlignedFactor aligned = align(symbol, SeriesCode.STABLECOIN_DELTA.factorName(), decisionTime,
                EXTERNAL_FLOW_LOOKBACK, NEUTRAL_EXTERNAL);
        if (aligned.missing()) {
            flags.add("STABLECOIN_MISSING");
            return NEUTRAL_EXTERNAL.doubleValue();
        }
        if (decisionTime - aligned.observedMs() > DAILY_STALE_THRESHOLD.toMillis()) {
            flags.add("STABLECOIN_STALE");
        }
        return aligned.value().doubleValue();
    }

    private AlignedFactor align(String symbol, String factorName, long decisionTime,
                                Duration lookback, BigDecimal neutralDefault) {
        // selectRange 是 observed_at < to；+1ns 让同一毫秒的观测点合法进入 as-of。
        LocalDateTime to = LocalDateTime.ofInstant(Instant.ofEpochMilli(decisionTime), ZoneOffset.UTC)
                .plusNanos(1);
        LocalDateTime from = to.minus(lookback);
        List<FactorHistory> rows = factorHistoryMapper.selectRange(symbol, factorName, from, to);
        if (rows == null || rows.isEmpty()) {
            return AlignedFactor.missing(neutralDefault);
        }

        List<MarketSeriesPoint> series = rows.stream()
                .filter(row -> row.getObservedAt() != null && row.getFactorValue() != null)
                .map(row -> new MarketSeriesPoint(toEpochMs(row.getObservedAt()), row.getFactorValue()))
                .toList();
        if (series.isEmpty()) {
            return AlignedFactor.missing(neutralDefault);
        }

        MarketSeriesPoint point = SeriesAligner.asOfPoint(series, decisionTime);
        return point == null ? AlignedFactor.missing(neutralDefault) : AlignedFactor.observed(point);
    }

    /** UTC LocalDateTime → epoch ms（与 MarketSeriesStore.toMs 同口径） */
    private static long toEpochMs(LocalDateTime ldt) {
        if (ldt == null) return Instant.EPOCH.toEpochMilli();
        return ldt.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private record AlignedFactor(BigDecimal value, long observedMs, boolean missing) {
        private static AlignedFactor missing(BigDecimal neutralDefault) {
            return new AlignedFactor(neutralDefault, Instant.EPOCH.toEpochMilli(), true);
        }

        private static AlignedFactor observed(MarketSeriesPoint point) {
            return new AlignedFactor(point.value(), point.ts(), false);
        }
    }
}
