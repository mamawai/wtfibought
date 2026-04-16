package com.mawai.wiibservice.agent.quant.domain;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class QuantCycleCompleteEvent extends ApplicationEvent {

    private final String symbol;
    private final String cycleType; // "heavy" or "light"

    public QuantCycleCompleteEvent(Object source, String symbol, String cycleType) {
        super(source);
        this.symbol = symbol;
        this.cycleType = cycleType;
    }

}
