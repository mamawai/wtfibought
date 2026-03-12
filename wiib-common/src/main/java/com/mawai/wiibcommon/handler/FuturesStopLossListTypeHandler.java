package com.mawai.wiibcommon.handler;

import com.alibaba.fastjson2.TypeReference;
import com.mawai.wiibcommon.entity.FuturesStopLoss;

import java.util.List;

public class FuturesStopLossListTypeHandler extends AbstractJsonbTypeHandler<List<FuturesStopLoss>> {

    public FuturesStopLossListTypeHandler() {
        super(new TypeReference<>() {
        });
    }
}
