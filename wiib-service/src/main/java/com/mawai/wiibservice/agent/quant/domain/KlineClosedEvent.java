package com.mawai.wiibservice.agent.quant.domain;

public record KlineClosedEvent(Object source, String symbol, String interval, long closeTime) {
}
