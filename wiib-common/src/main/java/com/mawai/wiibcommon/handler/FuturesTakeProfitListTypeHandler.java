package com.mawai.wiibcommon.handler;

import com.alibaba.fastjson2.TypeReference;
import com.mawai.wiibcommon.entity.FuturesTakeProfit;

import java.util.List;

public class FuturesTakeProfitListTypeHandler extends AbstractJsonbTypeHandler<List<FuturesTakeProfit>> {

    public FuturesTakeProfitListTypeHandler() {
        super(new TypeReference<>() {
        });
    }
}
