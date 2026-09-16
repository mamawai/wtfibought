package com.mawai.wiibquant.strategy.execution;

import com.mawai.wiibcommon.market.MarketStreamChannels;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Locale;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 执行层行情订阅：{@link MarketStreamChannels#PRICE} 的 futures tick → 执行端口 onPriceTick，
 * 驱动 quant 侧虚拟触价单（STOP）盘中触发。取 futures 价与 sim 撮合同源（约1s粒度），
 * 触发时的市价单在 sim 按同一价格源成交，决策价≈成交价。
 */
@Slf4j
@Component
public class ExecutionPriceConsumer implements MessageListener {

    private final RedisMessageListenerContainer listenerContainer;
    private final StrategyExecutionPort executionPort;

    public ExecutionPriceConsumer(RedisMessageListenerContainer listenerContainer,
                                  StrategyExecutionPort executionPort) {
        this.listenerContainer = listenerContainer;
        this.executionPort = executionPort;
    }

    @PostConstruct
    public void init() {
        listenerContainer.addMessageListener(this, new ChannelTopic(MarketStreamChannels.PRICE));
        log.info("[ExecPrice] 订阅 {} 启动(type=futures)", MarketStreamChannels.PRICE);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode obj = MAPPER.readTree(message.getBody());
            if (!"futures".equals(obj.path("type").asString(null))) return;
            String symbol = obj.path("symbol").asString(null);
            String price = obj.path("price").asString(null);
            if (symbol == null || price == null) return;
            executionPort.onPriceTick(symbol.trim().toUpperCase(Locale.ROOT), new BigDecimal(price));
        } catch (Exception e) {
            // 单条解析失败只丢本 tick，电平式触发下一 tick 补上
            log.debug("[ExecPrice] tick解析失败: {}", e.toString());
        }
    }
}
