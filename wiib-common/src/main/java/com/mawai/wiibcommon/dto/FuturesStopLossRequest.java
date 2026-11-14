package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class FuturesStopLossRequest {
    private Long positionId;
    private List<StopLossItem> stopLosses;

    @Data
    public static class StopLossItem {
        private BigDecimal price;
        private BigDecimal quantity; // 不传就是锁内的整仓现量
    }
}
