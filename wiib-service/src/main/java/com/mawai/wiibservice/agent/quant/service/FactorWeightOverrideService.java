package com.mawai.wiibservice.agent.quant.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibservice.agent.quant.domain.MarketRegime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * B1-3 静态调权：只乘 agent+horizon+regime 权重，不改 Agent 内部子因子。
 */
@Slf4j
@Service
public class FactorWeightOverrideService {

    /** B1-3 静态调权总开关。默认 false，回放通过前不要实盘开启。 */
    public static volatile boolean FACTOR_WEIGHT_OVERRIDE_ENABLED = false;

    private final boolean enabled;
    private final Resource configResource;
    private volatile Map<String, Double> multipliers = Map.of();

    public FactorWeightOverrideService(
            @Value("${factor.weight_override.enabled:false}") boolean enabled,
            @Value("${factor.weight_override.config:classpath:factor_weight_override.json}") Resource configResource) {
        this.enabled = enabled;
        this.configResource = configResource;
    }

    @PostConstruct
    public void load() {
        FACTOR_WEIGHT_OVERRIDE_ENABLED = enabled;
        if (configResource == null || !configResource.exists()) {
            log.info("[Q4.weightOverride] config不存在 enabled={}", enabled);
            return;
        }
        try {
            String json = new String(configResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            JSONObject root = JSON.parseObject(json);
            JSONArray rules = root.getJSONArray("rules");
            Map<String, Double> loaded = new HashMap<>();
            if (rules != null) {
                for (Object item : rules) {
                    JSONObject rule = (JSONObject) item;
                    String agent = rule.getString("agent");
                    String horizon = rule.getString("horizon");
                    String regime = rule.getString("regime");
                    Double multiplier = rule.getDouble("multiplier");
                    if (agent == null || horizon == null || regime == null || multiplier == null) {
                        throw new IllegalArgumentException("factor_weight_override rule字段不完整: " + rule);
                    }
                    loaded.put(key(agent, horizon, regime), multiplier);
                }
            }
            multipliers = Map.copyOf(loaded);
            log.info("[Q4.weightOverride] loaded enabled={} rules={}", enabled, multipliers.size());
        } catch (Exception e) {
            if (enabled) {
                throw new IllegalStateException("factor_weight_override加载失败", e);
            }
            log.warn("[Q4.weightOverride] config加载失败，因开关关闭忽略: {}", e.getMessage());
        }
    }

    public boolean isEnabled() {
        return FACTOR_WEIGHT_OVERRIDE_ENABLED;
    }

    public double apply(String agent, String horizon, MarketRegime regime, double baseWeight) {
        return apply(agent, horizon, regime, baseWeight, FACTOR_WEIGHT_OVERRIDE_ENABLED);
    }

    public double apply(String agent, String horizon, MarketRegime regime, double baseWeight, boolean overrideEnabled) {
        if (!overrideEnabled || regime == null) {
            return baseWeight;
        }
        Double multiplier = multipliers.get(key(agent, horizon, regime.name()));
        return multiplier == null ? baseWeight : baseWeight * multiplier;
    }

    public double multiplier(String agent, String horizon, MarketRegime regime) {
        if (!FACTOR_WEIGHT_OVERRIDE_ENABLED || regime == null) {
            return 1.0;
        }
        return multipliers.getOrDefault(key(agent, horizon, regime.name()), 1.0);
    }

    private static String key(String agent, String horizon, String regime) {
        return agent + "|" + horizon + "|" + regime;
    }
}
