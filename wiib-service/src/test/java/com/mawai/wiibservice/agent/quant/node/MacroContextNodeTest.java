package com.mawai.wiibservice.agent.quant.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.mawai.wiibservice.agent.quant.domain.MacroContext;
import com.mawai.wiibservice.agent.quant.service.MacroContextService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MacroContextNodeTest {

    @Test
    void readsMacroContextWithoutSynchronousRefresh() {
        TrackingMacroContextService service = new TrackingMacroContextService();
        MacroContextNode node = new MacroContextNode(service);

        Map<String, Object> result = node.apply(new OverAllState(Map.of("target_symbol", "BTCUSDT")));

        assertThat(service.refreshIfStaleCalled).isFalse();
        assertThat(service.requestedSymbol).isEqualTo("BTCUSDT");
        assertThat(result.get("macro_context")).isSameAs(service.context);
    }

    private static final class TrackingMacroContextService extends MacroContextService {
        private final MacroContext context = MacroContext.neutral("BTCUSDT");
        private boolean refreshIfStaleCalled;
        private String requestedSymbol;

        private TrackingMacroContextService() {
            super(null);
        }

        @Override
        public MacroContext get(String symbol) {
            requestedSymbol = symbol;
            return context;
        }

        @Override
        public void refreshIfStale(String symbol) {
            refreshIfStaleCalled = true;
        }
    }
}
