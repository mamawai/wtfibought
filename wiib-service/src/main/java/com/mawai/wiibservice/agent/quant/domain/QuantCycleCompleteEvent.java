package com.mawai.wiibservice.agent.quant.domain;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class QuantCycleCompleteEvent extends ApplicationEvent {

    private final String symbol;
    private final String cycleType; // 当前主链路使用 "research"，保留字段兼容旧事件消费者。

    public QuantCycleCompleteEvent(Object source, String symbol, String cycleType) {
        super(source);
        this.symbol = symbol;
        this.cycleType = cycleType;
    }

}
