package com.mawai.wiibservice.agent.quant;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.mawai.wiibservice.agent.quant.domain.LlmCallMode;
import com.mawai.wiibservice.agent.quant.memory.MemoryService;
import com.mawai.wiibservice.agent.quant.service.FactorWeightOverrideService;
import com.mawai.wiibservice.config.BinanceRestClient;
import com.mawai.wiibservice.config.DeribitClient;
import com.mawai.wiibservice.service.DepthStreamCache;
import com.mawai.wiibservice.service.ForceOrderService;
import com.mawai.wiibservice.service.OrderFlowAggregator;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class QuantGraphFactory {

    private final BinanceRestClient binanceRestClient;
    private final MemoryService memoryService;
    private final ForceOrderService forceOrderService;
    private final OrderFlowAggregator orderFlowAggregator;
    private final DepthStreamCache depthStreamCache;
    private final DeribitClient deribitClient;
    private final FactorWeightOverrideService weightOverrideService;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public QuantGraphFactory(BinanceRestClient binanceRestClient,
                             MemoryService memoryService,
                             ForceOrderService forceOrderService,
                             OrderFlowAggregator orderFlowAggregator,
                             DepthStreamCache depthStreamCache,
                             DeribitClient deribitClient,
                             FactorWeightOverrideService weightOverrideService,
                             ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.binanceRestClient = binanceRestClient;
        this.memoryService = memoryService;
        this.forceOrderService = forceOrderService;
        this.orderFlowAggregator = orderFlowAggregator;
        this.depthStreamCache = depthStreamCache;
        this.deribitClient = deribitClient;
        this.weightOverrideService = weightOverrideService;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    public CompiledGraph create(ChatModel chatModel) throws Exception {
        // 当前深/浅指向同一模型，未来可配置不同模型
        ChatClient.Builder deepClient = ChatClient.builder(chatModel);
        ChatClient.Builder shallowClient = ChatClient.builder(chatModel);
        return QuantForecastWorkflow.build(deepClient, shallowClient, binanceRestClient, memoryService,
                forceOrderService, orderFlowAggregator, depthStreamCache, deribitClient, weightOverrideService,
                LlmCallMode.STREAMING, LlmCallMode.STREAMING, meterRegistryProvider.getIfAvailable());
    }
}
