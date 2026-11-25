package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.llm.BoundedRuns;
import com.mawai.wiibagent.llm.LlmClient;
import com.mawai.wiibagent.llm.ModelCallLimiter;
import com.mawai.wiibagent.llm.ReactLoop;
import com.mawai.wiibagent.llm.ToolCallTraceHook;
import com.mawai.wiibagent.llm.UsageTrackingChatModel;
import com.mawai.wiibagent.llm.WebSearchTool;
import com.mawai.wiibagent.toolkit.CrossAssetToolkit;
import com.mawai.wiibagent.toolkit.DerivativesToolkit;
import com.mawai.wiibagent.toolkit.IndicatorToolkit;
import com.mawai.wiibagent.toolkit.MarketToolkit;
import com.mawai.wiibagent.toolkit.NewsToolkit;
import com.mawai.wiibagent.toolkit.WhaleToolkit;
import com.mawai.wiibagent.trader.trade.TradeActions;
import com.mawai.wiibagent.trader.trade.TradeTools;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.enums.AgentLang;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 一轮的会话：挂工具、建循环、限时跑完，过程逐帧推给现场。
 * 无论成败，动作轨迹和用量都记到决策行上：超时作废的那一轮，已执行的开平仓和烧掉的 token 都是真的。
 */
@Component
@RequiredArgsConstructor
class WakeSession {

    private final IndicatorToolkit indicatorToolkit;
    private final MarketToolkit marketToolkit;
    private final DerivativesToolkit derivativesToolkit;
    private final WhaleToolkit whaleToolkit;
    private final NewsToolkit newsToolkit;
    private final CrossAssetToolkit crossAssetToolkit;
    private final LocalizedToolCallbacks localizedTools;
    private final PromptCatalog prompts;
    /** 限时跑会话：到点或关停叫停都走同一条收尾路 */
    private final BoundedRuns runs;

    /** 超时打断会话后最多等它收尾多久，盖过 sim 一次下单连重试（最坏约 22 秒）；测试里调短 */
    Duration stopWait = Duration.ofSeconds(30);

    /** 会话跑完的结果：决策正文，和模型被调了几次 */
    record Outcome(String reasoning, int modelCalls) {
    }

    /**
     * 跑一轮会话。超时抛 TimeoutException，关停叫停抛它的子类 {@link BoundedRuns.CutException}，
     * 本轮作废（已发出的订单不回滚——sim 是事实源）。
     *
     * @param model     带用量统计的模型，每轮新建
     * @param webSearch 这一轮挂不挂联网搜索
     * @param deadline  本轮截止的墙钟时刻
     * @param decision  动作轨迹和用量记到它上面
     * @param inbox     每次调模型前问一次：这段时间插进来的话
     */
    Outcome run(UsageTrackingChatModel model, boolean webSearch, String systemPrompt, WakeFlows.Flow flow,
                TradeTools tradeTools, AgentLang lang, long deadline, AiTraderDecision decision, TraderLiveHub.Run run,
                Supplier<List<Message>> inbox) throws Exception {
        String instruction = flow.opening();
        // 全量工具轨迹（含数据工具）：收集器在本方法手里，超时 cancel 也保得住已发生的记录
        ToolCallTraceHook trace = new ToolCallTraceHook();
        // 不强制 tool_choice："先读事实、用工具求证"写在开场白的问题里
        // 开了联网搜索的多挂一个 web_search：搜索单独发一次请求，用量记在同一轮账上
        LlmClient client = LlmClient.builder().model(model).systemPrompt(systemPrompt)
                .tools(wakeTools(lang, tradeTools, webSearch
                        ? new WebSearchTool(model, prompts.get(lang, "trader.wake.searchInstruction")) : null))
                .build();
        // 被保险丝拦下的调用回的那句占位
        String notExecuted = prompts.get(lang, "llm.callLimit.notExecuted");
        ReactLoop loop = ReactLoop.builder().client(client)
                .streaming(true)      // 模型文本逐字推给现场
                .limiter(new ModelCallLimiter(TraderWakeupRunner.MAX_MODEL_CALLS, notExecuted,
                        prompts.get(lang, "llm.callLimit.lastCall")))
                .trace(trace)
                // 数据工具同批并行，批里有动作工具就整批串行
                .serialTools(TradeTools.SERIAL_TOOLS)
                .stages(exhausted -> flow.next(exhausted, tradeTools::accountSnapshot))
                .build();
        run.prompt(systemPrompt, instruction);

        // 模型已经答过几次
        AtomicInteger replies = new AtomicInteger();

        // 停止信号交给循环：到点或叫停时完成它，在途模型流不再往下烧
        BoundedRuns.Work<Outcome> session = stop -> {
            run.callStart();
            ReactLoop.Result result = loop.run(List.of(new UserMessage(instruction)), null, stop, new ReactLoop.Listener() {
                @Override
                public void chunk(ChatResponse frame) {
                    String chunk = frame.getResults().getFirst().getOutput().getText();
                    if (chunk != null && !chunk.isEmpty()) {
                        run.token(chunk);
                    }
                }

                @Override
                public void message(Message m) {
                    // 不按来源分支：保险丝补的占位回执也走这条路
                    if (m instanceof AssistantMessage assistant) {
                        replies.incrementAndGet();
                        flow.replied(assistant);
                        run.callEnd(assistant.getText(), assistant.getToolCalls());
                    } else if (m instanceof ToolResponseMessage responses) {
                        for (ToolResponseMessage.ToolResponse r : responses.getResponses()) {
                            // 回的是保险丝那句占位：这次调用没执行
                            if (notExecuted.equals(r.responseData())) {
                                run.toolSkipped(r.id(), r.name(), r.responseData());
                            } else {
                                run.toolResult(r.id(), r.name(), r.responseData());
                            }
                        }
                        run.callStart();
                    } else if (m instanceof UserMessage stage) {
                        // 新一段的开场白、中途插进来的话：现场分界
                        run.stage(stage.getText());
                        run.callStart();
                    }
                }

                /** 下一次调用已经是最后一次、它要的工具不会执行了：这时到来的事它处理不了，不取，留给这一轮结束后另起一轮 */
                @Override
                public List<Message> inbox() {
                    return replies.get() >= TraderWakeupRunner.MAX_MODEL_CALLS - 1 ? List.of() : inbox.get();
                }
            });
            return new Outcome(finalReasoning(prompts, lang, result.messages(), flow.finalStageOnly()), result.modelCalls());
        };
        try {
            // 返回或抛出之前已经等会话线程停下（最多 stopWait）：超时那一刻正在跑的交易调用，这时已把自己的结果记完
            return runs.run("wake traderId=" + decision.getTraderId(), session, deadline, stopWait);
        } finally {
            // 无论成败，动作轨迹都要留：超时/异常时已执行的开平仓是真实发生的
            List<ObjectNode> actions = mergeActions(trace.calls(), tradeTools.actions());
            decision.setActionsJson(MAPPER.writeValueAsString(actions));
            decision.setToolCalls(actions.size());
            // 用量同理落在 finally：超时作废的那一轮，token 也是真烧掉了，不能不记
            UsageTrackingChatModel.UsageSnapshot usage = model.snapshot();
            decision.setModelCalls(usage.modelCalls());
            decision.setPromptTokens(usage.promptTokens());
            decision.setCompletionTokens(usage.completionTokens());
            decision.setTotalTokens(usage.totalTokens());
            decision.setCachedTokens(usage.cachedTokens());
        }
    }

    /**
     * 唤醒挂的整套工具。工具描述按语言取自词表 {@code tool.<工具名>}（词表没这条就用注解原描述）。
     * <p>
     * 包私有：单测直接调它，验的才是建循环时真正挂上去的那批工具，而不是测试里另抄一份清单。
     * <p>
     * 快讯工具的语言在这里烤进实例（英文取译文，缺译文回落中文原文）——取哪门语言的新闻不是模型的选择。
     *
     * @param webSearch 联网搜索工具，没开联网搜索时为 null，不挂
     */
    List<ToolCallback> wakeTools(AgentLang lang, TradeTools tradeTools, WebSearchTool webSearch) {
        List<Object> toolkits = new ArrayList<>(List.of(tradeTools, indicatorToolkit, marketToolkit, derivativesToolkit,
                whaleToolkit, crossAssetToolkit, newsToolkit.boundTo(lang)));
        if (webSearch != null) {
            toolkits.add(webSearch);
        }
        return localizedTools.of(lang, toolkits.toArray());
    }

    /** 决策正文：只取最后一段的走法，前面几段的过程留在现场；其余走法保留各段说明。 */
    private static String finalReasoning(PromptCatalog prompts, AgentLang lang, List<Message> messages, boolean finalStageOnly) {
        List<String> parts = new ArrayList<>();
        String last = null;
        for (Message m : messages) {
            if (m instanceof UserMessage) {
                // 开场白和每段的开场白都是 user 消息：段落边界
                if (last != null && !finalStageOnly) {
                    parts.add(last);
                }
                last = null;
            } else if (m instanceof AssistantMessage assistant
                    && assistant.getText() != null && !assistant.getText().isBlank()) {
                last = assistant.getText();
            }
        }
        if (last != null) {
            parts.add(last);
        }
        return parts.isEmpty() ? prompts.get(lang, "trader.wake.noReasoning") : String.join("\n\n", parts);
    }

    /**
     * 合并轨迹：顺序骨架来自轨迹收集器的全量记录（含数据工具）；交易工具用 TradeTools 的
     * 富记录（结果/拒因）替换轻量占位，两边按调用 id（callId）对。
     * 改账户的工具只要进了方法就会留富记录，没留的就是没执行（参数绑不上、被保险丝拦下），占位标成 not_attempted。
     * 极端中断时轨迹记录缺失，富记录兜底补尾。
     */
    static List<ObjectNode> mergeActions(List<ObjectNode> traced, List<ObjectNode> tradeActions) {
        Map<String, ObjectNode> rich = new LinkedHashMap<>();
        tradeActions.forEach(a -> rich.put(a.path("callId").asString(), a));
        List<ObjectNode> merged = new ArrayList<>();
        for (ObjectNode t : traced) {
            ObjectNode own = rich.remove(t.path("callId").asString());
            if (own != null) {
                merged.add(own);
            } else {
                merged.add(TradeTools.SERIAL_TOOLS.contains(t.path("tool").asString())
                        ? t.put("status", TradeActions.NOT_ATTEMPTED) : t);
            }
        }
        merged.addAll(rich.values());
        return merged;
    }
}
