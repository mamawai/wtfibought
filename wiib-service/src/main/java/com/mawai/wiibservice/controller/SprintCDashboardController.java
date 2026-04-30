package com.mawai.wiibservice.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.StrategyPathStatus;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibservice.agent.risk.CircuitBreakerService;
import com.mawai.wiibservice.mapper.FactorHistoryMapper;
import com.mawai.wiibservice.mapper.FuturesPositionMapper;
import com.mawai.wiibservice.mapper.QuantForecastCycleMapper;
import com.mawai.wiibservice.mapper.StrategyPathStatusMapper;
import com.mawai.wiibservice.mapper.TradeAttributionMapper;
import com.mawai.wiibservice.task.AiTradingScheduler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final FactorHistoryMapper factorHistoryMapper;
    private final QuantForecastCycleMapper quantForecastCycleMapper;
    private final CircuitBreakerService circuitBreakerService;

    /** Admin 接口只允许 1 号用户访问，沿用现有后台权限约定。 */
    private void checkAdmin() {
        long userId = StpUtil.getLoginIdAsLong();
        if (userId != 1L) {
            throw new RuntimeException("无权限");
        }
    }

    /** 聚合 Sprint C 看板所需的账户、路径、影子、外部因子与 LLM 方差数据。 */
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
        data.put("factorIrRanking", quantForecastCycleMapper.selectAgentIrRanking(windowDays, 12));
        data.put("externalFactorCoverage", buildExternalFactorCoverage(from, now, windowDays));
        data.put("llmVariance", quantForecastCycleMapper.selectLlmVarianceSummary(windowDays));
        return Result.ok(data);
    }

    @PostMapping("/path-status")
    @Operation(summary = "手动启用/禁用策略路径")
    public Result<Void> setPathStatus(@RequestBody PathStatusRequest req) {
        checkAdmin();
        if (req == null || req.getPath() == null || req.getPath().isBlank()) {
            return Result.fail("path不能为空");
        }
        String path = req.getPath().trim().toUpperCase();
        if (!STRATEGY_PATHS.contains(path)) {
            return Result.fail("path只允许 BREAKOUT / MR / LEGACY_TREND");
        }
        if (req.getEnabled() == null) {
            return Result.fail("enabled不能为空");
        }

        StrategyPathStatus status = strategyPathStatusMapper.selectById(path);
        if (status == null) {
            status = new StrategyPathStatus();
            status.setPath(path);
        }
        status.setEnabled(req.getEnabled());
        status.setUpdatedAt(LocalDateTime.now());
        if (Boolean.TRUE.equals(req.getEnabled())) {
            // 人工恢复要清掉自动禁用痕迹，避免看板继续显示旧连亏状态。
            status.setDisabledReason(null);
            status.setDisabledAt(null);
            status.setConsecutiveLossCount(0);
        } else {
            String reason = req.getReason() != null && !req.getReason().isBlank()
                    ? req.getReason().trim()
                    : "人工禁用";
            status.setDisabledReason(reason);
            status.setDisabledAt(LocalDateTime.now());
            if (status.getConsecutiveLossCount() == null) {
                status.setConsecutiveLossCount(0);
            }
        }

        if (strategyPathStatusMapper.selectById(path) == null) {
            strategyPathStatusMapper.insert(status);
        } else {
            strategyPathStatusMapper.updateById(status);
        }
        return Result.ok(null);
    }

    /** 构建账户级指标：今日开仓、累计归因表现和当前熔断状态。 */
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

    /** 构建三条实盘路径的近 N 天统计，并拼上启停状态。 */
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

    @Data
    public static class PathStatusRequest {
        private String path;
        private Boolean enabled;
        private String reason;
    }

    /** 按预期频率计算外部因子完整率，帮助判断 B2 shadow 数据是否够用。 */
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

    /** 看板展示的外部因子清单和期望采样频率。 */
    private List<FactorSpec> factorSpecs() {
        return List.of(
                new FactorSpec("IV_PERCENTILE", "1h", 24, QuantConstants.WATCH_SYMBOLS),
                new FactorSpec("OI_PERCENTILE", "1h", 24, QuantConstants.WATCH_SYMBOLS),
                new FactorSpec("STABLECOIN_SUPPLY_DELTA", "1d", 1, QuantConstants.WATCH_SYMBOLS),
                new FactorSpec("BTC_ETF_FLOW", "1d", 1, List.of("BTCUSDT")),
                new FactorSpec("CROSS_MARKET_RISK", "5m", 288, QuantConstants.WATCH_SYMBOLS)
        );
    }

    /** 计算完整率并限制到 0-1，避免超采样时显示超过 100%。 */
    private double completeness(int samples, int expected) {
        if (expected <= 0) return 0.0;
        double value = Math.min(1.0, (double) samples / expected);
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    /** 统一 Map key，避免 factorName 和 symbol 拼接冲突。 */
    private String factorKey(Object factorName, Object symbol) {
        return factorName + "|" + symbol;
    }

    /** SQL 聚合结果转 int，null 或非数字按 0 处理。 */
    private int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    /** 看板窗口限制在 1-30 天，避免误传大窗口拖慢后台。 */
    private int normalizeDays(int days) {
        if (days < 1) return 7;
        return Math.min(days, 30);
    }

    private record FactorSpec(String factorName, String frequency, int expectedPerDay, List<String> symbols) {}
}
