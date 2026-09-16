package com.mawai.wiibcommon.handler;

import com.mawai.wiibcommon.entity.FuturesTakeProfit;
import tools.jackson.core.type.TypeReference;

import java.util.List;

public class FuturesTakeProfitListTypeHandler extends AbstractJsonbTypeHandler<List<FuturesTakeProfit>> {

    public FuturesTakeProfitListTypeHandler() {
        super(new TypeReference<>() {
        });
    }
}
