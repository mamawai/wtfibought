package com.mawai.wiibquant.agent.config;

import com.alibaba.fastjson2.JSON;
import com.mawai.wiibcommon.entity.AiRuntimeToggle;
import com.mawai.wiibquant.mapper.AiRuntimeToggleMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 运行时开关服务：DB 持久化的功能开关框架。
 *
 * <p>原 factor_weight_override 开关已随"调权死回路"一并移除，当前无注册开关；
 * 保留本框架供后续开关接入。snapshot() 的 debate/macro 为只读诊断恒真。</p>
 */
@Slf4j
@Service
public class RuntimeFeatureToggleService {

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
                true   // macro_risk 强制启用（只读诊断）
        );
    }

    private Map<String, ToggleBinding<?>> buildKnownToggles() {
        // 当前无注册开关（factor_weight_override 已移除）；保留空 map 供后续接入。
        return Map.of();
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
