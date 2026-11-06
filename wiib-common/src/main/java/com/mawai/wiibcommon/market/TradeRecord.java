package com.mawai.wiibcommon.market;

import java.math.BigDecimal;
import java.util.Map;

import static com.mawai.wiibcommon.market.MarketStreamChannels.*;

/** 成交 Stream 里的一笔：价、成交时间（交易所毫秒）、feed 收到它的时刻。字段见 MarketStreamChannels.TRADE_* */
public record TradeRecord(BigDecimal price, long ts, long receivedAt) {

    public static TradeRecord of(Map<?, ?> fields) {
        long ts = Long.parseLong(String.valueOf(fields.get(TRADE_TS)));
        Object rt = fields.get(TRADE_RECEIVED_AT);
        // 老 feed 写的记录没有 rt，按成交时间算
        return new TradeRecord(new BigDecimal(String.valueOf(fields.get(TRADE_PRICE))), ts,
                rt == null ? ts : Long.parseLong(String.valueOf(rt)));
    }
}
