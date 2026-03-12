package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class FuturesOpenRequest {
    private String symbol;
    private String side; // LONG/SHORT
    private BigDecimal quantity;
    private Integer leverage;
    private String orderType; // MARKET/LIMIT
    private BigDecimal limitPrice; // 限价时必填
    private List<StopLoss> stopLosses;
    private List<TakeProfit> takeProfits;

    @Data
    public static class StopLoss {
        private BigDecimal price;
        private BigDecimal quantity;
    }

    @Data
    public static class TakeProfit {
        private BigDecimal price;
        private BigDecimal quantity;
    }
}
