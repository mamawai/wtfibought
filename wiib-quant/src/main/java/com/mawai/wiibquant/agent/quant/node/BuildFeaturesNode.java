package com.mawai.wiibquant.agent.quant.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.mawai.wiibcommon.enums.KlineInterval;
import com.mawai.wiibcommon.market.OrderFlowAggregator;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 特征工程节点：从原始市场数据构建FeatureSnapshot。
 * 具体特征计算委托给 BuildFeaturesBuilder，节点只负责适配 StateGraph。
 */
public class BuildFeaturesNode implements NodeAction {

    private final BuildFeaturesBuilder builder;

    public BuildFeaturesNode(OrderFlowAggregator orderFlowAggregator, KlineInterval decisionInterval) {
        this.builder = new BuildFeaturesBuilder(orderFlowAggregator, decisionInterval);
    }

    public BuildFeaturesNode(OrderFlowAggregator orderFlowAggregator, Supplier<KlineInterval> decisionIntervalSupplier) {
        this.builder = new BuildFeaturesBuilder(orderFlowAggregator, decisionIntervalSupplier);
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        return builder.apply(state);
    }

    /** 独立于 StateGraph 的特征构建入口，供测试和工具复用。 */
    public Map<String, Object> buildFeatures(String symbol, Map<String, Object> rawData) {
        return builder.buildFeatures(symbol, rawData);
    }
}
