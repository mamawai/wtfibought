package com.mawai.wiibservice.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.StrategyPathStatus;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibservice.agent.risk.CircuitBreakerService;
import com.mawai.wiibservice.mapper.AiTradingDecisionMapper;
import com.mawai.wiibservice.mapper.FactorHistoryMapper;
import com.mawai.wiibservice.mapper.FuturesPositionMapper;
import com.mawai.wiibservice.mapper.QuantForecastCycleMapper;
import com.mawai.wiibservice.mapper.StrategyPathStatusMapper;
import com.mawai.wiibservice.mapper.TradeAttributionMapper;
import com.mawai.wiibservice.task.AiTradingScheduler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Tag(name = "Sprint C Admin看板")
@RestController
@RequestMapping("/api/admin/sprint-c-dashboard")
@RequiredArgsConstructor
public class SprintCDashboardController {

    private static final List<String> STRATEGY_PATHS = List.of("BREAKOUT", "MR", "LEGACY_TREND");

    private final AiTradingScheduler aiTradingScheduler;
    private final FuturesPositionMapper futuresPositionMapper;
    private final TradeAttributionMapper tradeAttributionMapper;
    private final StrategyPathStatusMapper strategyPathStatusMapper;
    private final AiTradingDecisionMapper aiTradingDecisionMapper;
    private final FactorHistoryMapper factorHistoryMapper;
    private final QuantForecastCycleMapper quantForecastCycleMapper;
    private final CircuitBreakerService circuitBreakerService;

    private void checkAdmin() {
        long userId = StpUtil.getLoginIdAsLong();
        if (userId != 1L) {
            throw new RuntimeException("无权限");
        }
    }

    @GetMapping
    @Operation(summary = "Sprint C闭环与风控看板")
    public Result<Map<String, Object>> dashboard(@RequestParam(defaultValue = "7") int days) {
        checkAdmin();
        int windowDays = normalizeDays(days);
        Long aiUserId = aiTradingScheduler.getAiUserId();
        if (aiUserId == null || aiUserId == 0L) {
            return Result.fail("AI交易员未初始化");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from = now.minusDays(windowDays);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generatedAt", now);
        data.put("days", windowDays);
        data.put("from", from);
        data.put("to", now);
        data.put("account", buildAccount(aiUserId));
        data.put("pathStats", buildPathStats(aiUserId, from));
        data.put("shadow5of7", buildShadowStats());
        data.put("factorIrRanking", quantForecastCycleMapper.selectAgentIrRanking(windowDays, 12));
        data.put("externalFactorCoverage", buildExternalFactorCoverage(from, now, windowDays));
        data.put("llmVariance", quantForecastCycleMapper.selectLlmVarianceSummary(windowDays));
        return Result.ok(data);
    }

    private Map<String, Object> buildAccount(Long aiUserId) {
        Long todayOpenCount = futuresPositionMapper.selectCount(new LambdaQueryWrapper<FuturesPosition>()
                .eq(FuturesPosition::getUserId, aiUserId)
                .ge(FuturesPosition::getCreatedAt, LocalDate.now().atStartOfDay()));

        Map<String, Object> account = new LinkedHashMap<>();
        account.put("aiUserId", aiUserId);
        account.put("todayOpenCount", todayOpenCount != null ? todayOpenCount : 0);
        account.put("cumulative", tradeAttributionMapper.selectOverallStatsByUser(aiUserId));
        account.put("breaker", circuitBreakerService.status(aiUserId));
        return account;
    }

    private List<Map<String, Object>> buildPathStats(Long aiUserId, LocalDateTime from) {
        Map<String, Map<String, Object>> statsByPath = tradeAttributionMapper.selectPathStatsByUserSince(aiUserId, from)
                .stream()
                .collect(Collectors.toMap(row -> String.valueOf(row.get("path")), row -> row));
        Map<String, StrategyPathStatus> statusByPath = strategyPathStatusMapper.selectList(
                        new LambdaQueryWrapper<StrategyPathStatus>().in(StrategyPathStatus::getPath, STRATEGY_PATHS))
                .stream()
                .collect(Collectors.toMap(StrategyPathStatus::getPath, status -> status));

        List<Map<String, Object>> result = new ArrayList<>(STRATEGY_PATHS.size());
        for (String path : STRATEGY_PATHS) {
            StrategyPathStatus status = statusByPath.get(path);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", path);
            row.put("samples", 0);
            row.put("wins", 0);
            row.put("winRate", 0);
            row.put("ev", 0);
            row.put("totalPnl", 0);
            row.put("avgHoldingMinutes", 0);
            Map<String, Object> stats = statsByPath.get(path);
            if (stats != null) {
                row.putAll(stats);
            }
            row.put("enabled", status == null || !Boolean.FALSE.equals(status.getEnabled()));
            row.put("disabledReason", status != null ? status.getDisabledReason() : null);
            row.put("disabledAt", status != null ? status.getDisabledAt() : null);
            row.put("consecutiveLossCount", status != null && status.getConsecutiveLossCount() != null
                    ? status.getConsecutiveLossCount() : 0);
            result.add(row);
        }
        return result;
    }

    private Map<String, Object> buildShadowStats() {
        Map<String, Object> stats = new LinkedHashMap<>(aiTradingDecisionMapper.selectShadow5of7Stats());
        stats.put("hypotheticalWinRate", null);
        stats.put("verificationStatus", "NO_EXIT_VERIFICATION");
        return stats;
    }

    private List<Map<String, Object>> buildExternalFactorCoverage(LocalDateTime from, LocalDateTime to, int days) {
        Map<String, Map<String, Object>> actual = factorHistoryMapper.selectCoverage(from, to)
                .stream()
                .collect(Collectors.toMap(row -> factorKey(row.get("factorName"), row.get("symbol")), row -> row));

        List<Map<String, Object>> result = new ArrayList<>();
        for (FactorSpec spec : factorSpecs()) {
            for (String symbol : spec.symbols()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("factorName", spec.factorName());
                row.put("symbol", symbol);
                row.put("frequency", spec.frequency());
                row.put("expectedSamples", spec.expectedPerDay() * days);

                Map<String, Object> hit = actual.get(factorKey(spec.factorName(), symbol));
                int samples = hit != null ? asInt(hit.get("samples")) : 0;
                row.put("samples", samples);
                row.put("completeness", completeness(samples, spec.expectedPerDay() * days));
                row.put("latestObservedAt", hit != null ? hit.get("latestObservedAt") : null);
                row.put("latestAgeHours", hit != null ? hit.get("latestAgeHours") : null);
                row.put("latestValue", hit != null ? hit.get("latestValue") : null);
                result.add(row);
            }
        }
        return result;
    }

    private List<FactorSpec> factorSpecs() {
        return List.of(
                new FactorSpec("IV_PERCENTILE", "1h", 24, QuantConstants.WATCH_SYMBOLS),
                new FactorSpec("OI_PERCENTILE", "1h", 24, QuantConstants.WATCH_SYMBOLS),
                new FactorSpec("STABLECOIN_SUPPLY_DELTA", "1d", 1, QuantConstants.WATCH_SYMBOLS),
                new FactorSpec("BTC_ETF_FLOW", "1d", 1, List.of("BTCUSDT")),
                new FactorSpec("CROSS_MARKET_RISK", "5m", 288, QuantConstants.WATCH_SYMBOLS)
        );
    }

    private double completeness(int samples, int expected) {
        if (expected <= 0) return 0.0;
        double value = Math.min(1.0, (double) samples / expected);
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private String factorKey(Object factorName, Object symbol) {
        return factorName + "|" + symbol;
    }

    private int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private int normalizeDays(int days) {
        if (days < 1) return 7;
        if (days > 30) return 30;
        return days;
    }

    private record FactorSpec(String factorName, String frequency, int expectedPerDay, List<String> symbols) {}
}
