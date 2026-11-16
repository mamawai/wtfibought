package com.mawai.wiibagent.trader.wakeup;

import org.springframework.data.redis.connection.Message;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Locale;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/** feed 价格频道上的一条标记价 tick，两个哨兵都从这里读 */
record MarkPriceTick(String symbol, BigDecimal price) {

    /** 不是标记价消息或缺字段返回 null；消息体坏了直接抛，调用方丢掉这一条 */
    static MarkPriceTick parse(Message message) {
        JsonNode obj = MAPPER.readTree(message.getBody());
        if (!"markprice".equals(obj.path("type").asString(null))) {
            return null;
        }
        String symbol = obj.path("symbol").asString(null);
        String price = obj.path("price").asString(null);
        if (symbol == null || price == null) {
            return null;
        }
        return new MarkPriceTick(symbol.trim().toUpperCase(Locale.ROOT), new BigDecimal(price));
    }
}
