package com.mawai.wiibservice.agent.quant.domain;

import com.mawai.wiibcommon.market.KlineBar;

public record KlineClosedEvent(Object source, String symbol, String interval, long closeTime, KlineBar bar) {

    public KlineClosedEvent(Object source, String symbol, String interval, long closeTime) {
        this(source, symbol, interval, closeTime, null);
    }
}
