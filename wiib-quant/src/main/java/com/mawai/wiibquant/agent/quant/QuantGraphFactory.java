package com.mawai.wiibquant.agent.quant;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.mawai.wiibquant.agent.quant.domain.LlmCallMode;
import com.mawai.wiibquant.agent.quant.memory.MemoryService;
import com.mawai.wiibquant.agent.quant.service.MacroContextService;
import com.mawai.wiibcommon.enums.KlineInterval;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibquant.config.DeribitClient;
import com.mawai.wiibcommon.market.DepthStreamCache;
import com.mawai.wiibcommon.market.ForceOrderService;
import com.mawai.wiibcommon.market.OrderFlowAggregator;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class QuantGraphFactory {

    private final BinanceRestClient binanceRestClient;
    private final MemoryService memoryService;
    private final ForceOrderService forceOrderService;
    private final OrderFlowAggregator orderFlowAggregator;
    private final DepthStreamCache depthStreamCache;
    private final DeribitClient deribitClient;
    private final MacroContextService macroContextService;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    // 主决策周期：原属 sim 的 TradingConfig，quant 拆出后只需这一个字段，直接读 yml trading.decision-interval（默认 M5）
    private final KlineInterval decisionInterval;

    public QuantGraphFactory(BinanceRestClient binanceRestClient,
                             MemoryService memoryService,
                             ForceOrderService forceOrderService,
                             OrderFlowAggregator orderFlowAggregator,
                             DepthStreamCache depthStreamCache,
                             DeribitClient deribitClient,
                             MacroContextService macroContextService,
                             ObjectProvider<MeterRegistry> meterRegistryProvider,
                             @Value("${trading.decision-interval:M5}") KlineInterval decisionInterval) {
        this.binanceRestClient = binanceRestClient;
        this.memoryService = memoryService;
        this.forceOrderService = forceOrderService;
        this.orderFlowAggregator = orderFlowAggregator;
        this.depthStreamCache = depthStreamCache;
        this.deribitClient = deribitClient;
        this.macroContextService = macroContextService;
        this.meterRegistryProvider = meterRegistryProvider;
        this.decisionInterval = decisionInterval;
    }

    public CompiledGraph create(ChatModel chatModel) throws Exception {
        // 当前深/浅指向同一模型，未来可配置不同模型
        ChatClient.Builder deepClient = ChatClient.builder(chatModel);
        ChatClient.Builder shallowClient = ChatClient.builder(chatModel);
        return QuantForecastWorkflow.build(deepClient, shallowClient, binanceRestClient, memoryService,
                forceOrderService, orderFlowAggregator, depthStreamCache, deribitClient,
                macroContextService, LlmCallMode.STREAMING, LlmCallMode.STREAMING, meterRegistryProvider.getIfAvailable(),
                decisionInterval);
    }
}
