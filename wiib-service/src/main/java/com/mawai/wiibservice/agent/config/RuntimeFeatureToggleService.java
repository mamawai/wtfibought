package com.mawai.wiibservice.agent.config;

import com.alibaba.fastjson2.JSON;
import com.mawai.wiibcommon.entity.AiRuntimeToggle;
import com.mawai.wiibservice.agent.risk.CircuitBreakerService;
import com.mawai.wiibservice.agent.quant.node.DebateJudgeNode;
import com.mawai.wiibservice.agent.quant.service.FactorWeightOverrideService;
import com.mawai.wiibservice.agent.trading.DeterministicTradingExecutor;
import com.mawai.wiibservice.mapper.AiRuntimeToggleMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
@Service
@DependsOn("factorWeightOverrideService")
public class RuntimeFeatureToggleService {

    public static final String QUANT_DEBATE_JUDGE_ENABLED = "quant.debate_judge.enabled";
    public static final String QUANT_DEBATE_JUDGE_SHADOW_ENABLED = "quant.debate_judge.shadow_enabled";
    public static final String QUANT_FACTOR_WEIGHT_OVERRIDE_ENABLED = "quant.factor_weight_override.enabled";

    public static final String TRADING_LOW_VOL_ENABLED = "trading.low_vol.enabled";
    public static final String TRADING_PLAYBOOK_EXIT_ENABLED = "trading.playbook_exit.enabled";

    public static final String CIRCUIT_BREAKER_ENABLED = "trading.circuit_breaker.enabled";
    public static final String CIRCUIT_BREAKER_L1_DAILY_NET_LOSS_PCT = "trading.circuit_breaker.l1_daily_net_loss_pct";
    public static final String CIRCUIT_BREAKER_L2_LOSS_STREAK = "trading.circuit_breaker.l2_loss_streak";
    public static final String CIRCUIT_BREAKER_L2_COOLDOWN_HOURS = "trading.circuit_breaker.l2_cooldown_hours";
    public static final String CIRCUIT_BREAKER_L3_DRAWDOWN_PCT = "trading.circuit_breaker.l3_drawdown_pct";

    private final AiRuntimeToggleMapper toggleMapper;
    private final Map<String, ToggleBinding<?>> knownToggles;
    private final Map<String, Object> currentValues = new ConcurrentHashMap<>();

    public RuntimeFeatureToggleService(AiRuntimeToggleMapper toggleMapper) {
        this.toggleMapper = toggleMapper;
        this.knownToggles = buildKnownToggles();
        knownToggles.forEach((key, binding) -> currentValues.put(key, binding.defaultValue()));
    }

    @PostConstruct
    public void loadPersistedToggles() {
        try {
            int loaded = 0;
            for (AiRuntimeToggle row : toggleMapper.selectList(null)) {
                ToggleBinding<?> binding = knownToggles.get(row.getToggleKey());
                if (binding == null) {
                    continue;
                }
                Object value = parseValue(row.getValueJson(), binding.type());
                currentValues.put(row.getToggleKey(), value);
                applyBinding(binding, value);
                loaded++;
            }
            log.info("[RuntimeToggle] 已加载持久化开关 count={}", loaded);
        } catch (RuntimeException e) {
            log.warn("[RuntimeToggle] 加载持久化开关失败，沿用代码默认值: {}", e.getMessage());
        }
    }

    public <T> T get(String key, Class<T> type, T defaultValue) {
        return convertValue(currentValues.getOrDefault(key, defaultValue), type);
    }

    public void set(String key, Object value, long operator, String reason) {
        ToggleBinding<?> binding = knownToggles.get(key);
        if (binding == null) {
            throw new IllegalArgumentException("未知运行时开关: " + key);
        }
        Object normalized = convertValue(value, binding.type());

        AiRuntimeToggle row = toggleMapper.selectById(key);
        boolean insert = row == null;
        if (insert) {
            row = new AiRuntimeToggle();
            row.setToggleKey(key);
        }
        row.setValueJson(JSON.toJSONString(normalized));
        row.setUpdatedBy(operator);
        row.setUpdatedAt(LocalDateTime.now());
        row.setReason(reason);

        if (insert) {
            toggleMapper.insert(row);
        } else {
            toggleMapper.updateById(row);
        }
        currentValues.put(key, normalized);
        applyBinding(binding, normalized);
        log.info("[RuntimeToggle] key={} value={} operator={} reason={}", key, normalized, operator, reason);
    }

    public RuntimeToggleSnapshot snapshot() {
        return new RuntimeToggleSnapshot(
                get(QUANT_DEBATE_JUDGE_ENABLED, Boolean.class, DebateJudgeNode.ENABLED),
                get(QUANT_DEBATE_JUDGE_SHADOW_ENABLED, Boolean.class, DebateJudgeNode.SHADOW_ENABLED),
                get(QUANT_FACTOR_WEIGHT_OVERRIDE_ENABLED, Boolean.class,
                        FactorWeightOverrideService.FACTOR_WEIGHT_OVERRIDE_ENABLED),
                new RuntimeToggleSnapshot.TradingToggles(
                        get(TRADING_LOW_VOL_ENABLED, Boolean.class,
                                DeterministicTradingExecutor.LOW_VOL_TRADING_ENABLED),
                        get(TRADING_PLAYBOOK_EXIT_ENABLED, Boolean.class,
                                DeterministicTradingExecutor.PLAYBOOK_EXIT_ENABLED)
                ),
                new RuntimeToggleSnapshot.CircuitBreakerToggles(
                        get(CIRCUIT_BREAKER_ENABLED, Boolean.class, CircuitBreakerService.ENABLED),
                        get(CIRCUIT_BREAKER_L1_DAILY_NET_LOSS_PCT, Double.class,
                                CircuitBreakerService.L1_DAILY_NET_LOSS_PCT),
                        get(CIRCUIT_BREAKER_L2_LOSS_STREAK, Integer.class,
                                CircuitBreakerService.L2_LOSS_STREAK),
                        get(CIRCUIT_BREAKER_L2_COOLDOWN_HOURS, Integer.class,
                                CircuitBreakerService.L2_COOLDOWN_HOURS),
                        get(CIRCUIT_BREAKER_L3_DRAWDOWN_PCT, Double.class,
                                CircuitBreakerService.L3_DRAWDOWN_PCT)
                )
        );
    }

    private Map<String, ToggleBinding<?>> buildKnownToggles() {
        Map<String, ToggleBinding<?>> map = new HashMap<>();
        bind(map, QUANT_DEBATE_JUDGE_ENABLED, Boolean.class, DebateJudgeNode.ENABLED,
                v -> DebateJudgeNode.ENABLED = v);
        bind(map, QUANT_DEBATE_JUDGE_SHADOW_ENABLED, Boolean.class, DebateJudgeNode.SHADOW_ENABLED,
                v -> DebateJudgeNode.SHADOW_ENABLED = v);
        bind(map, QUANT_FACTOR_WEIGHT_OVERRIDE_ENABLED, Boolean.class,
                FactorWeightOverrideService.FACTOR_WEIGHT_OVERRIDE_ENABLED,
                v -> FactorWeightOverrideService.FACTOR_WEIGHT_OVERRIDE_ENABLED = v);

        bind(map, TRADING_LOW_VOL_ENABLED, Boolean.class, DeterministicTradingExecutor.LOW_VOL_TRADING_ENABLED,
                v -> DeterministicTradingExecutor.LOW_VOL_TRADING_ENABLED = v);
        bind(map, TRADING_PLAYBOOK_EXIT_ENABLED, Boolean.class, DeterministicTradingExecutor.PLAYBOOK_EXIT_ENABLED,
                v -> DeterministicTradingExecutor.PLAYBOOK_EXIT_ENABLED = v);

        bind(map, CIRCUIT_BREAKER_ENABLED, Boolean.class, CircuitBreakerService.ENABLED,
                v -> CircuitBreakerService.ENABLED = v);
        bind(map, CIRCUIT_BREAKER_L1_DAILY_NET_LOSS_PCT, Double.class,
                CircuitBreakerService.L1_DAILY_NET_LOSS_PCT,
                v -> CircuitBreakerService.L1_DAILY_NET_LOSS_PCT = v);
        bind(map, CIRCUIT_BREAKER_L2_LOSS_STREAK, Integer.class, CircuitBreakerService.L2_LOSS_STREAK,
                v -> CircuitBreakerService.L2_LOSS_STREAK = v);
        bind(map, CIRCUIT_BREAKER_L2_COOLDOWN_HOURS, Integer.class, CircuitBreakerService.L2_COOLDOWN_HOURS,
                v -> CircuitBreakerService.L2_COOLDOWN_HOURS = v);
        bind(map, CIRCUIT_BREAKER_L3_DRAWDOWN_PCT, Double.class, CircuitBreakerService.L3_DRAWDOWN_PCT,
                v -> CircuitBreakerService.L3_DRAWDOWN_PCT = v);
        return Map.copyOf(map);
    }

    private static <T> void bind(Map<String, ToggleBinding<?>> map, String key, Class<T> type,
                                 T defaultValue, Consumer<T> writer) {
        map.put(key, new ToggleBinding<>(type, defaultValue, writer));
    }

    private static <T> void applyBinding(ToggleBinding<T> binding, Object value) {
        binding.writer().accept(convertValue(value, binding.type()));
    }

    private static <T> T parseValue(String valueJson, Class<T> type) {
        Object parsed = JSON.parse(valueJson);
        return convertValue(parsed, type);
    }

    private static <T> T convertValue(Object value, Class<T> type) {
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        if (value instanceof Number number) {
            if (type == Integer.class) {
                return type.cast(number.intValue());
            }
            if (type == Double.class) {
                return type.cast(number.doubleValue());
            }
        }
        throw new IllegalArgumentException("运行时开关类型不匹配: expected=" + type.getSimpleName()
                + " actual=" + (value != null ? value.getClass().getSimpleName() : "null"));
    }

    private record ToggleBinding<T>(
            Class<T> type,
            T defaultValue,
            Consumer<T> writer
    ) {}
}
