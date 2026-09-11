package com.mawai.wiibagent.chat.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.entity.QuantDeepAnalysis;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.analysis.DeepAnalysisService;
import com.mawai.wiibagent.chat.gate.ApprovalGate;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.i18n.PromptCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 深研判工具（仅对话轨）：贵操作——Bull∥Bear + Judge 共 3 次深模型调用
 * （新闻上下文是缓存拼接，零 LLM）。
 * <p>
 * <b>这里没有 HITL 判断</b>：授权闸门在 {@link ApprovalGate}——
 * 只有那一层同时看得到 sessionId 和本次 tool_call 的参数。
 * <p>
 * 模型建叶子时构造注入（同配置的用户共享同一个叶子和同一个模型实例）；
 * sessionId 是请求级的，从执行工具时交进来的 ToolContext 里取（{@link ToolRunContext#sessionId}），只用来推进度。
 */
@Slf4j
public class DeepAnalysisToolkit {

    private final ChatModel model;
    private final DeepAnalysisService deepAnalysisService;
    private final WorkbenchRunRegistry runRegistry;
    private final PromptCatalog prompts;
    /** 辩论/裁决的提示词与推给用户的阶段进度都按它取；工具是建叶子时造的，语言跟着叶子走 */
    private final AgentLang lang;

    public DeepAnalysisToolkit(ChatModel model, DeepAnalysisService deepAnalysisService,
                               WorkbenchRunRegistry runRegistry, PromptCatalog prompts, AgentLang lang) {
        this.model = model;
        this.deepAnalysisService = deepAnalysisService;
        this.runRegistry = runRegistry;
        this.prompts = prompts;
        this.lang = lang;
    }

    @Tool(name = "run_deep_analysis", description = """
            Run a full deep market analysis (Bull vs Bear adversarial debate + Judge verdict) for a symbol.
            ONLY call when the user EXPLICITLY asks with words like "深度研判"/"全面分析"; for ordinary
            trend/outlook questions answer from expert data directly - never call this uninvited.
            EXPENSIVE: costs 3 deep-model LLM calls. Requires user approval per session.
            If the result status is PENDING_APPROVAL, tell the user approval is needed and why - the UI
            will show a confirmation card; after they approve, call this tool again to execute.
            Returns: narrative, bull/range/bear scenario distribution, invalidation condition, noDirection flag.""")
    public String runDeepAnalysis(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol,
                                  ToolContext context) {
        // 归一化必须与闸门同一套：闸门按它算授权键、也按它写确认卡的 symbol。
        // 这里另算一套的话，模型填 btc 时卡片写 BTCUSDT、工具却拿 BTC 去取数，取不到任何数据
        String normalized = ApprovalGate.approvalSymbol(symbol);
        String sessionId = ToolRunContext.sessionId(context);
        log.info("[DeepTool] 执行深研判 session={} symbol={}", sessionId, normalized);

        long closeTime = System.currentTimeMillis();
        // 全程静默数分钟，按阶段推进度给 SSE，前端才知道跑到哪了
        progress(sessionId, prompts.get(lang, "chat.deepAnalysis.progress.start",
                Map.of("symbol", normalized)));
        String newsContext = deepAnalysisService.buildNewsContext(lang);
        // Bull∥Bear 虚拟线程并行（对话场景无图结构，服务级并行等价）
        CompletableFuture<String> bullF = CompletableFuture.supplyAsync(() -> {
            String r = deepAnalysisService.bullArgue(model, normalized, newsContext, lang);
            progress(sessionId, prompts.get(lang, "chat.deepAnalysis.progress.bullDone"));
            return r;
        });
        CompletableFuture<String> bearF = CompletableFuture.supplyAsync(() -> {
            String r = deepAnalysisService.bearArgue(model, normalized, newsContext, lang);
            progress(sessionId, prompts.get(lang, "chat.deepAnalysis.progress.bearDone"));
            return r;
        });
        String bull = bullF.join();
        String bear = bearF.join();
        progress(sessionId, prompts.get(lang, "chat.deepAnalysis.progress.judging"));
        QuantDeepAnalysis analysis = deepAnalysisService.judge(model, normalized, closeTime, "chat",
                newsContext, bull, bear, lang);
        if (analysis == null) {
            JSONObject out = new JSONObject();
            out.put("status", "FAILED");
            out.put("message", prompts.get(lang, "chat.deepAnalysis.judgeFailed"));
            return out.toJSONString();
        }
        deepAnalysisService.persist(analysis);
        progress(sessionId, prompts.get(lang, "chat.deepAnalysis.progress.judged"));

        JSONObject out = new JSONObject();
        out.put("status", "OK");
        out.put("narrative", analysis.getNarrative());
        out.put("scenarios", JSON.parseObject(analysis.getScenariosJson()));
        out.put("noDirection", analysis.getNoDirection());
        out.put("invalidation", analysis.getInvalidation());
        out.put("judgeReasoning", analysis.getJudgeReasoning());
        return out.toJSONString();
    }

    /** 进度是尽力而为：拿不到会话号（不在工具执行栈里）就静默跳过，不影响正确性。 */
    private void progress(String sessionId, String text) {
        if (sessionId != null) {
            runRegistry.publishProgress(sessionId, text);
        }
    }
}
