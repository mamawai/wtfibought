package com.mawai.wiibquant.agent.quant.domain;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class QuantCycleCompleteEvent extends ApplicationEvent {

    private final String symbol;
    private final String cycleType; // 兼容旧交易事件入口；research 不作为交易触发信号。

    public QuantCycleCompleteEvent(Object source, String symbol, String cycleType) {
        super(source);
        this.symbol = symbol;
        this.cycleType = cycleType;
    }

}
