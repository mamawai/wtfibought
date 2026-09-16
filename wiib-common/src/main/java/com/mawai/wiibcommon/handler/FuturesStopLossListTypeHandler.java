package com.mawai.wiibcommon.handler;

import com.mawai.wiibcommon.entity.FuturesStopLoss;
import tools.jackson.core.type.TypeReference;

import java.util.List;

public class FuturesStopLossListTypeHandler extends AbstractJsonbTypeHandler<List<FuturesStopLoss>> {

    public FuturesStopLossListTypeHandler() {
        super(new TypeReference<>() {
        });
    }
}
