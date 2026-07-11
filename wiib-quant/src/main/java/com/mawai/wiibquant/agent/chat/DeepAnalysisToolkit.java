package com.mawai.wiibquant.agent.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.entity.QuantDeepAnalysis;
import com.mawai.wiibcommon.entity.QuantSnapshot;
import com.mawai.wiibquant.agent.analysis.DeepAnalysisService;
import com.mawai.wiibquant.mapper.QuantSnapshotMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 深研判工具（P5，仅对话轨）：贵操作——Bull∥Bear + Judge 共 3 次深模型调用（新闻上下文是缓存拼接，零 LLM）。
 * HITL 闸门：未授权时登记 pending 并返回 PENDING_APPROVAL（agent 转述给用户，Controller 随后弹确认卡）；
 * 用户确认后 agent 重调本工具，消费一次性授权后真执行。sessionId 经 ToolContext 传入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeepAnalysisToolkit {

    public static final String SESSION_CONTEXT_KEY = "workbenchSessionId";

    private final DeepAnalysisService deepAnalysisService;
    private final ApprovalRegistry approvalRegistry;
    private final QuantSnapshotMapper snapshotMapper;

    @Tool(name = "run_deep_analysis", description = """
            Run a full deep market analysis (Bull vs Bear adversarial debate + Judge verdict) for a symbol.
            EXPENSIVE: costs 3 deep-model LLM calls. Requires user approval per session.
            If the result status is PENDING_APPROVAL, tell the user approval is needed and why - the UI
            will show a confirmation card; after they approve, call this tool again to execute.
            Returns: narrative, bull/range/bear scenario distribution, invalidation condition, noDirection flag.""")
    public String runDeepAnalysis(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol,
                                  ToolContext toolContext) {
        String sessionId = sessionId(toolContext);
        String normalized = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();

        // HITL 闸门：无有效授权 → 登记 pending，返回待确认标记（不烧深模型）
        if (!approvalRegistry.consumeApproval(sessionId)) {
            approvalRegistry.requestApproval(sessionId, normalized,
                    "深度研判需 3 次深模型调用（Bull/Bear 辩论 + Judge 裁决）");
            JSONObject out = new JSONObject();
            out.put("status", "PENDING_APPROVAL");
            out.put("message", "深度研判是昂贵操作（3 次深模型调用），已向用户请求确认。请告知用户等待确认卡片，确认后你会被再次调用。");
            return out.toJSONString();
        }

        log.info("[DeepTool] 用户已授权，执行深研判 session={} symbol={}", sessionId, normalized);
        long closeTime = System.currentTimeMillis();
        // 对话轨挂靠最新快照（≤5min 前）：snapshotId 溯源 + 三腿喂 prompt，与定时轨同口径
        QuantSnapshot latest = latestSnapshot(normalized);
        Long snapshotId = latest != null ? latest.getId() : null;
        String volLegsJson = latest != null ? latest.getVolLegsJson() : null;

        // 复用定时轨同一套研判服务；Bull∥Bear 虚拟线程并行（对话场景无图结构，服务级并行等价）
        String newsContext = deepAnalysisService.buildNewsContext();
        CompletableFuture<String> bullF = CompletableFuture.supplyAsync(
                () -> deepAnalysisService.bullArgue(normalized, newsContext, volLegsJson));
        CompletableFuture<String> bearF = CompletableFuture.supplyAsync(
                () -> deepAnalysisService.bearArgue(normalized, newsContext, volLegsJson));
        QuantDeepAnalysis analysis = deepAnalysisService.judge(normalized, closeTime, snapshotId, "chat",
                newsContext, volLegsJson, bullF.join(), bearF.join());
        if (analysis == null) {
            JSONObject out = new JSONObject();
            out.put("status", "FAILED");
            out.put("message", "深研判裁决失败（LLM 异常），请稍后重试");
            return out.toJSONString();
        }
        deepAnalysisService.persist(analysis);

        JSONObject out = new JSONObject();
        out.put("status", "OK");
        out.put("narrative", analysis.getNarrative());
        out.put("scenarios", JSON.parseObject(analysis.getScenariosJson()));
        out.put("noDirection", analysis.getNoDirection());
        out.put("invalidation", analysis.getInvalidation());
        out.put("judgeReasoning", analysis.getJudgeReasoning());
        return out.toJSONString();
    }

    private QuantSnapshot latestSnapshot(String symbol) {
        return snapshotMapper.selectOne(new LambdaQueryWrapper<QuantSnapshot>()
                .eq(QuantSnapshot::getSymbol, symbol)
                .orderByDesc(QuantSnapshot::getCloseTime)
                .last("LIMIT 1"));
    }

    private String sessionId(ToolContext toolContext) {
        // 双层取值：框架若把运行时 context 桥进 ToolContext 则精确；否则退活跃会话槽（单用户场景等价）
        Object value = toolContext != null && toolContext.getContext() != null
                ? toolContext.getContext().get(SESSION_CONTEXT_KEY) : null;
        return value != null ? value.toString() : approvalRegistry.activeSession();
    }
}
