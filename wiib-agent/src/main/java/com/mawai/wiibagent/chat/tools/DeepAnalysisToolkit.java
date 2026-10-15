package com.mawai.wiibagent.chat.tools;

import com.mawai.wiibcommon.entity.QuantDeepAnalysis;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.analysis.DeepAnalysisService;
import com.mawai.wiibagent.chat.gate.ApprovalGate;
import com.mawai.wiibagent.chat.run.ChildRun;
import com.mawai.wiibagent.chat.run.ChildRunRegistry;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.llm.UsageTrackingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 深研判工具（仅对话轨）：贵操作——Bull∥Bear + Judge 共 3 次深模型调用
 * （新闻上下文是缓存拼接，零 LLM）。
 * <p>
 * <b>这里没有 HITL 判断</b>：授权闸门在 {@link ApprovalGate}——
 * 只有那一层同时看得到 sessionId 和本次 tool_call 的参数。
 * <p>
 * 闸门放行后不在工具调用里同步跑几分钟：起一个子运行（{@link ChildRunRegistry}）立刻返回 RUNNING，
 * 研判结论作为报告回灌会话，主 agent 被唤醒补答，期间用户不被锁住。
 * 子运行的账单独记（每次现包一个 {@link UsageTrackingChatModel}，用量只打日志）：它跨轮活着，记进主轮的账本会串到下一轮。
 * <p>
 * 模型建叶子时构造注入（同配置的用户共享同一个叶子和同一个模型实例）；
 * sessionId 是请求级的，从执行工具时交进来的 ToolContext 里取（{@link ToolRunContext#sessionId}）。
 */
@Slf4j
public class DeepAnalysisToolkit {

    /** Bull∥Bear 并行用。不能用 supplyAsync 默认的 commonPool：容器里就一两个线程，被两路 LLM 占满会拖死 Redis 建连 */
    private static final ExecutorService DEBATE_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /** 裸深模型，不是主轮那个记账的装饰器 */
    private final ChatModel base;
    private final DeepAnalysisService deepAnalysisService;
    private final ChildRunRegistry childRuns;
    private final PromptCatalog prompts;
    /** 子运行按用户计上限，归属建叶子时烤死 */
    private final long userId;
    /** 辩论/裁决的提示词与推给用户的阶段进度都按它取；工具是建叶子时造的，语言跟着叶子走 */
    private final AgentLang lang;

    public DeepAnalysisToolkit(ChatModel model, DeepAnalysisService deepAnalysisService, ChildRunRegistry childRuns,
                               PromptCatalog prompts, long userId, AgentLang lang) {
        this.base = model;
        this.deepAnalysisService = deepAnalysisService;
        this.childRuns = childRuns;
        this.prompts = prompts;
        this.userId = userId;
        this.lang = lang;
    }

    @Tool(name = "run_deep_analysis", description = """
            Run a full deep market analysis (Bull vs Bear adversarial debate + Judge verdict) for a symbol.
            ONLY call when the user EXPLICITLY asks with words like "深度研判"/"全面分析"; for ordinary
            trend/outlook questions answer from your tools directly - never call this uninvited.
            EXPENSIVE: costs 3 deep-model LLM calls. Requires user approval per session.
            If the result status is PENDING_APPROVAL, tell the user approval is needed and why - the UI
            will show a confirmation card; after they approve, call this tool again to execute.
            Once approved it runs in the background: status RUNNING means it has started. Answer what you
            can now and tell the user the verdict follows; do not call it again or wait for it - you will
            be woken with the report (narrative, bull/range/bear scenario distribution, invalidation
            condition, noDirection flag) when it is done.""")
    public String runDeepAnalysis(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol,
                                  ToolContext context) {
        // 归一化必须与闸门同一套：闸门按它算授权键、也按它写确认卡的 symbol。
        // 这里另算一套的话，模型填 btc 时卡片写 BTCUSDT、工具却拿 BTC 去取数，取不到任何数据
        String normalized = ApprovalGate.approvalSymbol(symbol);
        String sessionId = ToolRunContext.sessionId(context);
        ChildRunRegistry.Spawn spawn = childRuns.spawn(
                new ChildRunRegistry.Spec(sessionId, userId, ChildRun.Kind.DEEP_ANALYSIS,
                        prompts.get(lang, "chat.deepAnalysis.label", Map.of("symbol", normalized)), null, lang),
                run -> execute(run, normalized));
        ObjectNode out = MAPPER.createObjectNode();
        if (!spawn.started()) {
            out.put("status", "REFUSED");
            out.put("message", spawn.refusal());
            return MAPPER.writeValueAsString(out);
        }
        log.info("[DeepTool] 深研判转后台 session={} symbol={} run=#{}", sessionId, normalized, spawn.run().id());
        out.put("status", "RUNNING");
        out.put("runId", spawn.run().id());
        out.put("message", prompts.get(lang, "chat.childRun.started"));
        return MAPPER.writeValueAsString(out);
    }

    /** 子运行主体：Bull∥Bear 并行 + Judge 裁决，阶段进度挂在过程轨的子运行条目上；返回回灌主 agent 的报告（JSON） */
    String execute(ChildRun run, String symbol) {
        long closeTime = System.currentTimeMillis();
        UsageTrackingChatModel model = new UsageTrackingChatModel(base);   // 这一次研判自己的账
        progress(run, prompts.get(lang, "chat.deepAnalysis.progress.start", Map.of("symbol", symbol)));
        String newsContext = deepAnalysisService.buildNewsContext(lang);
        // Bull∥Bear 虚拟线程并行（对话场景无图结构，服务级并行等价）
        CompletableFuture<String> bullF = CompletableFuture.supplyAsync(() -> {
            String r = deepAnalysisService.bullArgue(model, symbol, newsContext, lang);
            progress(run, prompts.get(lang, "chat.deepAnalysis.progress.bullDone"));
            return r;
        }, DEBATE_EXECUTOR);
        CompletableFuture<String> bearF = CompletableFuture.supplyAsync(() -> {
            String r = deepAnalysisService.bearArgue(model, symbol, newsContext, lang);
            progress(run, prompts.get(lang, "chat.deepAnalysis.progress.bearDone"));
            return r;
        }, DEBATE_EXECUTOR);
        String bull = bullF.join();
        String bear = bearF.join();
        if (run.cancelled()) {
            // 停止/重新生成/删会话/超时：报告不会回灌了，裁决那次别烧
            log.info("[DeepTool] #{} 已取消，不裁决 symbol={} usage={}", run.id(), symbol, model.snapshot());
            return null;
        }
        progress(run, prompts.get(lang, "chat.deepAnalysis.progress.judging"));
        QuantDeepAnalysis analysis = deepAnalysisService.judge(model, symbol, closeTime, "chat",
                newsContext, bull, bear, lang);
        log.info("[DeepTool] #{} 研判跑完 symbol={} usage={}", run.id(), symbol, model.snapshot());
        if (analysis == null) {
            ObjectNode out = MAPPER.createObjectNode();
            out.put("status", "FAILED");
            out.put("message", prompts.get(lang, "chat.deepAnalysis.judgeFailed"));
            return MAPPER.writeValueAsString(out);
        }
        deepAnalysisService.persist(analysis);
        progress(run, prompts.get(lang, "chat.deepAnalysis.progress.judged"));

        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "OK");
        out.put("symbol", symbol);
        out.put("narrative", analysis.getNarrative());
        out.set("scenarios", MAPPER.readTree(analysis.getScenariosJson()));
        out.put("noDirection", analysis.getNoDirection());
        out.put("invalidation", analysis.getInvalidation());
        out.put("judgeReasoning", analysis.getJudgeReasoning());
        return MAPPER.writeValueAsString(out);
    }

    private void progress(ChildRun run, String text) {
        childRuns.phase(run, text);
    }
}
