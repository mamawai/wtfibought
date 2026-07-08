package com.mawai.wiibquant.agent.mcp;

import com.mawai.wiibquant.agent.toolkit.MarketToolkit;
import com.mawai.wiibquant.agent.toolkit.QuantForecastToolkit;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP server 工具注册（P6）："一份能力三处消费"的第三处——对话 agent / 定时轨之外，
 * 把只读量化工具暴露给任意 MCP 客户端（Claude Desktop / Cursor 等直连本服务 SSE 端点）。
 * 刻意只注册只读六件：market_snapshot / option_iv / fragility / vol_forecast / market_regime / scorecard；
 * NewsToolkit（外部抓取成本）与 run_deep_analysis（贵操作，HITL 管辖）不对外。
 */
@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider quantMcpTools(MarketToolkit marketToolkit,
                                              QuantForecastToolkit quantForecastToolkit) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(marketToolkit, quantForecastToolkit)
                .build();
    }
}
