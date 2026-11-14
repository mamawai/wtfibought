package com.mawai.wiibcommon.dto;

import lombok.Data;
import java.util.List;

/** 挂单保护：不传的列表不动，空列表清掉。 */
@Data
public class FuturesOrderProtectionRequest {
    private List<FuturesOpenRequest.StopLoss> stopLosses;
    private List<FuturesOpenRequest.TakeProfit> takeProfits;
}
