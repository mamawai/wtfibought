package com.mawai.wiibservice.agent.quant.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.mawai.wiibservice.agent.quant.domain.MacroContext;
import com.mawai.wiibservice.agent.quant.service.MacroContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class MacroContextNode implements NodeAction {

    private final MacroContextService macroContextService;

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String symbol = (String) state.value("target_symbol").orElse("BTCUSDT");
        // graph线程只读缓存；缺失/过期时由service异步刷新，避免重周期被90天历史重算阻塞。
        MacroContext context = macroContextService.get(symbol);
        log.info("[Q2.5] macro_context symbol={} stale={} flags={} risk={}",
                symbol, context.stale(), context.qualityFlags(), context.toRiskHint());
        return Map.of("macro_context", context);
    }
}
