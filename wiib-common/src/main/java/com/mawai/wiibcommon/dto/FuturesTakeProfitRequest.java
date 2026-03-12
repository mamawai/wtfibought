package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class FuturesTakeProfitRequest {
    private Long positionId;
    private List<TakeProfitItem> takeProfits;

    @Data
    public static class TakeProfitItem {
        private BigDecimal price;
        private BigDecimal quantity;
    }
}
