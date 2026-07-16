package com.mawai.wiibquant.agent.chat;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolretry.ToolRetryInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.mawai.wiibquant.agent.config.AiAgentRuntime;
import com.mawai.wiibquant.agent.config.AiAgentRuntimeManager;
import com.mawai.wiibquant.agent.config.AiRuntimeRefreshedEvent;
import com.mawai.wiibquant.agent.llm.ResilientModelInterceptor;
import com.mawai.wiibquant.agent.toolkit.MarketToolkit;
import com.mawai.wiibquant.agent.toolkit.NewsToolkit;
import com.mawai.wiibquant.agent.toolkit.QuantForecastToolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 研判工作台对话 agent 工厂（P4）：SupervisorAgent(深模型) 动态调度 3 个专家 ReactAgent(浅模型)。
 * <ul>
 *   <li>深浅分层：Supervisor 用 quant 深模型做意图理解与裁决，子 agent 用 quant-light 干工具活</li>
 *   <li>checkpoint：整图挂 PostgresSaver（threadId=sessionId），断连续聊 + P5 HITL 中断恢复的地基</li>
 *   <li>韧性：ResilientModelInterceptor（流式兼容的退避重试，supervisor 额外挂 chat 模型兜底）+ ToolRetry。
 *       框架自带的 ModelRetry/ModelFallback 会把流式响应的 Flux 强转 Message 必炸（1.1.2.0 bug），不可用；
 *       预算：ModelCallLimitHook 单次运行封顶 + SummarizationHook 长对话压缩</li>
 * </ul>
 * 模型是构建期绑定的，监听 {@link AiRuntimeRefreshedEvent} 重建缓存实现热更新。
 */
@Slf4j
@Component
public class ChatAgentFactory {

    private final AiAgentRuntimeManager runtimeManager;
    private final MarketToolkit marketToolkit;
    private final QuantForecastToolkit quantForecastToolkit;
    private final NewsToolkit newsToolkit;
    private final DeepAnalysisToolkit deepAnalysisToolkit;
    private final BaseCheckpointSaver checkpointSaver;
    private final int runModelCallLimit;

    private volatile CompiledGraph cached;

    public ChatAgentFactory(AiAgentRuntimeManager runtimeManager,
                            MarketToolkit marketToolkit,
                            QuantForecastToolkit quantForecastToolkit,
                            NewsToolkit newsToolkit,
                            DeepAnalysisToolkit deepAnalysisToolkit,
                            BaseCheckpointSaver checkpointSaver,
                            @Value("${quant.workbench.run-model-call-limit:12}") int runModelCallLimit) {
        this.runtimeManager = runtimeManager;
        this.marketToolkit = marketToolkit;
        this.quantForecastToolkit = quantForecastToolkit;
        this.newsToolkit = newsToolkit;
        this.deepAnalysisToolkit = deepAnalysisToolkit;
        this.checkpointSaver = checkpointSaver;
        this.runModelCallLimit = runModelCallLimit;
    }

    /** 对话图单例（编译含 PostgresSaver），模型刷新事件后重建。 */
    public CompiledGraph chatGraph() throws Exception {
        CompiledGraph graph = cached;
        if (graph != null) return graph;
        synchronized (this) {
            if (cached == null) {
                cached = build();
                log.info("对话工作台图已构建（Supervisor + 3 专家 agent + PostgresSaver）");
            }
            return cached;
        }
    }

    @EventListener(AiRuntimeRefreshedEvent.class)
    public void onRuntimeRefreshed() {
        synchronized (this) {
            cached = null;
        }
        log.info("模型配置刷新，对话图缓存已失效待重建");
    }

    private CompiledGraph build() throws Exception {
        AiAgentRuntime runtime = runtimeManager.current();
        var deep = runtime.quantChatModel();
        var light = runtime.quantLightChatModel();
        var fallback = runtime.chatChatModel();

        ResilientModelInterceptor retry = ResilientModelInterceptor.builder()
                .maxAttempts(3).initialDelay(500).maxDelay(4000)
                .build();
        ToolRetryInterceptor toolRetry = ToolRetryInterceptor.builder().build();

        ReactAgent marketAgent = ReactAgent.builder()
                .name("market_agent")
                .description("实时市场状态专家：行情快照(价格/资金费率/持仓/清算/盘口)、期权IV、市场脆弱度评分")
                .model(light)
                .methodTools(marketToolkit)
                .instruction("""
                        你是市场状态专家。用工具获取真实数据回答，所有结论必须引用工具返回的具体数字；
                        数据不可用(available=false)时如实告知，绝不编造。回答精炼中文。""")
                .interceptors(List.of(retry, toolRetry))
                .build();

        ReactAgent quantAgent = ReactAgent.builder()
                .name("quant_agent")
                .description("量化预测专家：波动率预测(H6/H12/H24)、市场regime、以及本系统预测战绩记分卡(QLIKE vs 基准/命中率)")
                .model(light)
                .methodTools(quantForecastToolkit)
                .instruction("""
                        你是量化预测专家。工具给的是本系统的 vol/regime 预测与实盘验证战绩。
                        本系统验证过的能力是波动幅度与风险预测；方向预测无验证优势。被问涨跌方向时
                        不要生硬拒绝——给双向波动情景 + 风险提示（幅度、regime、脆弱度），说明方向确定性低的原因。
                        被问"预测准不准"时调 scorecard 用真实战绩回答（QLIKE 越低越好，improvement>0=跑赢基准）。
                        回答精炼中文，引用具体数字。""")
                .interceptors(List.of(retry, toolRetry))
                .build();

        ReactAgent newsAgent = ReactAgent.builder()
                .name("news_agent")
                .description("加密新闻专家：获取最近重要加密快讯列表")
                .model(light)
                .methodTools(newsToolkit)
                .instruction("""
                        你是加密新闻专家。用 news_search 拿最近重要快讯列表（已是完整内容）；
                        输出"事件+可能影响"的精炼中文摘要，标注消息源，不评价真伪不给投资建议。""")
                .interceptors(List.of(retry, toolRetry))
                .build();

        // 派发协议是硬约束：SupervisorAgent 带自定义 mainAgent 时框架不注入路由提示词，
        // 完全靠本 instruction 让模型输出 JSON 数组（MainAgentNodeAction 解析失败即 FINISH=不派发）
        ReactAgent supervisorMain = ReactAgent.builder()
                .name("workbench_supervisor")
                .model(deep)
                .methodTools(deepAnalysisToolkit)
                .instruction("""
                        你是加密货币研判工作台的总调度，负责把用户问题派发给专家 agent，再汇总成最终回答。

                        派发协议（严格遵守）：
                        - 需要专家数据时，只输出一个 JSON 数组（要调用的专家名），不要输出任何其他文字。
                          例：["market_agent"] 或 ["market_agent","news_agent"]
                        - 可用专家：market_agent=实时行情/持仓/清算/期权/脆弱度；
                          quant_agent=波动率预测/regime/预测战绩；news_agent=加密新闻快讯
                        - 涉及行情、预测、新闻的问题必须先派发拿真实数据，不要凭记忆回答
                        - 专家结果已在对话里、足够回答时，输出最终精炼中文回答（此时不要再输出 JSON 数组）
                        - 用户明确要"深度研判/全面分析"时 → 调 run_deep_analysis 工具（昂贵，需用户确认：
                          返回 PENDING_APPROVAL 时告知用户确认卡片已弹出，等确认后你会被再次唤起执行）

                        回答原则：
                        1. 结论必须可追溯到专家给的数据，不编造
                        2. 被问涨跌方向时不要生硬拒绝：本系统验证过的能力是波动与风险预测（方向预测无验证优势），
                           给"双向情景 + 当前风险画像 + 仓位/止损等风控参考"，并说明方向确定性低的原因
                        3. 信号矛盾时大方说"看不清"，这是专业而不是失职""")
                .hooks(
                        SummarizationHook.builder()
                                .model(light)
                                .maxTokensBeforeSummary(6000)
                                .messagesToKeep(6)
                                .build(),
                        ModelCallLimitHook.builder()
                                .runLimit(runModelCallLimit)
                                .exitBehavior(ModelCallLimitHook.ExitBehavior.END)
                                .build())
                .interceptors(List.of(ResilientModelInterceptor.builder()
                        .maxAttempts(3).initialDelay(500).maxDelay(4000)
                        .fallbackModel(fallback)
                        .build()))
                .build();

        SupervisorAgent supervisor = SupervisorAgent.builder()
                .name("quant_workbench")
                .mainAgent(supervisorMain)
                .subAgents(List.of(marketAgent, quantAgent, newsAgent))
                .build();

        // 自己掌控编译：整图挂 checkpoint saver（threadId=sessionId 断点续聊；P5 HITL interrupt/resume 同一地基）
        return supervisor.getGraph().compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(checkpointSaver).build())
                .build());
    }
}
