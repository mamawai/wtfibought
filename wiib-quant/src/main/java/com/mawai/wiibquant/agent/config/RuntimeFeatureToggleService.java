package com.mawai.wiibquant.agent.config;

import com.alibaba.fastjson2.JSON;
import com.mawai.wiibcommon.entity.AiRuntimeToggle;
import com.mawai.wiibquant.agent.quant.service.FactorWeightOverrideService;
import com.mawai.wiibquant.mapper.AiRuntimeToggleMapper;
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

    public static final String QUANT_FACTOR_WEIGHT_OVERRIDE_ENABLED = "quant.factor_weight_override.enabled";

    private final AiRuntimeToggleMapper toggleMapper;
    private final Map<String, ToggleBinding<?>> knownToggles;
    private final Map<String, Object> currentValues = new ConcurrentHashMap<>();

    public RuntimeFeatureToggleService(AiRuntimeToggleMapper toggleMapper) {
        this.toggleMapper = toggleMapper;
        this.knownToggles = buildKnownToggles();
        knownToggles.forEach((key, binding) -> {
            currentValues.put(key, binding.defaultValue());
            applyBinding(binding, binding.defaultValue());
        });
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
                true,  // debate_judge 强制启用（只读诊断）
                get(QUANT_FACTOR_WEIGHT_OVERRIDE_ENABLED, Boolean.class,
                        FactorWeightOverrideService.FACTOR_WEIGHT_OVERRIDE_ENABLED),
                true   // macro_risk 强制启用（只读诊断）
        );
    }

    private Map<String, ToggleBinding<?>> buildKnownToggles() {
        Map<String, ToggleBinding<?>> map = new HashMap<>();
        bind(map, QUANT_FACTOR_WEIGHT_OVERRIDE_ENABLED, Boolean.class,
                FactorWeightOverrideService.FACTOR_WEIGHT_OVERRIDE_ENABLED,
                v -> FactorWeightOverrideService.FACTOR_WEIGHT_OVERRIDE_ENABLED = v);
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
