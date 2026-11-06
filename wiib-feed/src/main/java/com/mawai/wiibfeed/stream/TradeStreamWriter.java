package com.mawai.wiibfeed.stream;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.mawai.wiibcommon.market.MarketStreamChannels.*;

/** 逐笔成交写 Redis Stream：每币一条，按到达顺序 XADD；key 和字段见 MarketStreamChannels */
@Component
@RequiredArgsConstructor
public class TradeStreamWriter {

    /** 写的时候顺带近似裁到这么多条（XADD MAXLEN ~），BTC 约 50 分钟 */
    private static final long MAXLEN = 30_000L;

    private final StringRedisTemplate redisTemplate;

    /** 写失败往外抛，由 WsConnection 的 consumer 记 warn */
    public void append(String keyPrefix, String symbol, String price, String qty, boolean buyerMaker,
                       long tradeTime, long receivedAt) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put(TRADE_TS, Long.toString(tradeTime));
        f.put(TRADE_PRICE, price);
        f.put(TRADE_QTY, qty);
        f.put(TRADE_BUYER_MAKER, buyerMaker ? "1" : "0");
        f.put(TRADE_RECEIVED_AT, Long.toString(receivedAt));
        redisTemplate.opsForStream().add(StreamRecords.newRecord().in(keyPrefix + symbol).ofMap(f),
                XAddOptions.maxlen(MAXLEN).approximateTrimming(true));
    }
}
