package com.mawai.wiibquant.strategy.execution;

import com.mawai.wiibcommon.handover.DeliveryGate;
import com.mawai.wiibcommon.handover.Intake;
import com.mawai.wiibcommon.handover.Start;
import com.mawai.wiibcommon.market.MarketStreamChannels;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
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
 * 驱动 quant 侧虚拟触价单（STOP）盘中触发。取 futures 最新价（miniTicker，约1s粒度），
 * 触发后下的市价单在 sim 按盘口成交。进活的口：开口是订阅，关口是退订、等正在处理的 tick 处理完。
 */
@Slf4j
@Component
@Order(30)
public class ExecutionPriceConsumer implements MessageListener, Intake {

    private final RedisMessageListenerContainer listenerContainer;
    private final StrategyExecutionPort executionPort;
    /** 价格回调的闸 */
    private final DeliveryGate gate = new DeliveryGate();

    public ExecutionPriceConsumer(RedisMessageListenerContainer listenerContainer,
                                  StrategyExecutionPort executionPort) {
        this.listenerContainer = listenerContainer;
        this.executionPort = executionPort;
    }

    @Override
    public void open(Start how) {
        gate.open();
        listenerContainer.addMessageListener(this, new ChannelTopic(MarketStreamChannels.PRICE));
        log.info("[ExecPrice] 订阅 {} 启动(type=futures)", MarketStreamChannels.PRICE);
    }

    @Override
    public void close() {
        listenerContainer.removeMessageListener(this);
        gate.close();
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        gate.pass(() -> onTick(message));
    }

    private void onTick(Message message) {
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
