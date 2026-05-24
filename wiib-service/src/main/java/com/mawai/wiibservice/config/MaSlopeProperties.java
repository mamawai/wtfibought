package com.mawai.wiibservice.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MaSlope 策略专属配置。
 */
@Data
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "trading.entry.ma-slope")
public class MaSlopeProperties {

    /** 第一版只放 BTC/ETH，新增币种先回测再加入。 */
    private List<String> enabledSymbols = new ArrayList<>(List.of("BTCUSDT", "ETHUSDT"));

    public void setEnabledSymbols(List<String> enabledSymbols) {
        List<String> normalized = enabledSymbols == null ? List.of() : enabledSymbols.stream()
                .filter(symbol -> symbol != null && !symbol.isBlank())
                .map(symbol -> symbol.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            log.warn("[MaSlope] enabled-symbols 为空，策略即使启用也不会产生候选");
        }
        this.enabledSymbols = new ArrayList<>(normalized);
    }
}
