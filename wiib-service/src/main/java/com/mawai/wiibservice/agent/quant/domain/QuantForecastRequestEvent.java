package com.mawai.wiibservice.agent.quant.domain;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class QuantForecastRequestEvent extends ApplicationEvent {

    private final String symbol;
    private final long closeTime;
    private final String requestSource;
    private final boolean force;

    public QuantForecastRequestEvent(Object sourceObject, String symbol, long closeTime, String requestSource, boolean force) {
        super(sourceObject);
        this.symbol = symbol;
        this.closeTime = closeTime;
        this.requestSource = requestSource == null || requestSource.isBlank() ? "request" : requestSource;
        this.force = force;
    }
}
