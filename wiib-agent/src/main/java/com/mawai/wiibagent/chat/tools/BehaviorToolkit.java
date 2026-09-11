package com.mawai.wiibagent.chat.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibagent.behavior.BehaviorAnalysisReport;
import com.mawai.wiibagent.behavior.BehaviorAnalysisService;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

/**
 * 用户行为分析工具（仅对话轨）：贵操作——10 个 sim 内部端点 + 一次大 prompt 的深模型调用。
 * <p>
 * <b>不挂授权闸门</b>（对比 {@link DeepAnalysisToolkit}）：深研判一调就是 3 次深模型调用、
 * 中间没人插得上手，这里只有 1 次；而且"分析我的交易习惯"本身就是用户明说的意图，
 * 再弹一张卡请他确认自己刚说过的话是纯噪音。防滥调靠两处——工具描述里写死"只在用户明说时调"，
 * 以及 {@link BehaviorAnalysisService} 那层的 30 分钟缓存与并发闸门。
 * <p>
 * <b>userId 建叶子时烤死在实例里，不做成模型可填的参数</b>：做成参数就等于允许模型分析别人的账户。
 * 口径与 {@link TraderQueryToolkit} / {@link TraderActionToolkit} 一致。
 * <p>
 * 模型用的是这个用户 BYOK 的深模型（叶子上绑的那个）——平台的 behavior 功能位已退休，
 * 这次调用记在用户自己的 key 上。
 */
@Slf4j
public class BehaviorToolkit {

    private final ChatModel model;
    private final BehaviorAnalysisService behaviorAnalysisService;
    private final WorkbenchRunRegistry runRegistry;
    /** 分析对象：建叶子时就定死，见类注释 */
    private final long userId;
    /** 报告正文与阶段进度的语言；工具是建叶子时造的，语言跟着叶子走 */
    private final AgentLang lang;

    public BehaviorToolkit(ChatModel model, BehaviorAnalysisService behaviorAnalysisService,
                           WorkbenchRunRegistry runRegistry, long userId, AgentLang lang) {
        this.model = model;
        this.behaviorAnalysisService = behaviorAnalysisService;
        this.runRegistry = runRegistry;
        this.userId = userId;
        this.lang = lang;
    }

    @Tool(name = "analyze_my_behavior", description = """
            Run a full behaviour analysis of THIS user's own account across every dimension of the
            platform (crypto spot, tokenized US equities, futures, prediction, the three games) plus
            a risk profile and targeted suggestions.
            ONLY call when the user EXPLICITLY asks about their own habits/style/risk profile, e.g.
            "analyse my behaviour" / "what kind of trader am I" - never call this uninvited.
            EXPENSIVE: 10 internal data calls plus one large deep-model call. Results are cached for
            30 minutes, so calling it twice in a row gains nothing.
            The full report is rendered as a card in the chat UI. Your job afterwards is to talk about
            it, not to recite every number - unless cardShown is false, in which case the user cannot
            see the card and you must state the key findings yourself.""")
    public String analyzeMyBehavior(ToolContext context) {
        String sessionId = ToolRunContext.sessionId(context);
        log.info("[BehaviorTool] 执行行为分析 session={} userId={}", sessionId, userId);

        Result<BehaviorAnalysisReport> result = behaviorAnalysisService.analyze(userId, lang, model,
                text -> progress(sessionId, text));
        if (result.getCode() != 0 || result.getData() == null) {
            JSONObject out = new JSONObject();
            out.put("status", "FAILED");
            out.put("message", result.getMsg());
            return out.toJSONString();
        }

        JSONObject full = JSON.parseObject(JSON.toJSONString(result.getData()));
        boolean shown = runRegistry.publishBehaviorReport(sessionId, full);

        JSONObject out = new JSONObject();
        out.put("status", "OK");
        // 如实答"卡上没上屏"：断连、补答轮、会话已结束都会走到这里，模型据此改口自己讲结论
        out.put("cardShown", shown);
        out.put("report", trimmed(full));
        return out.toJSONString();
    }

    /**
     * 回模型的裁剪版：砍掉 overview.trend（30 天逐日快照）。
     * <p>那是给卡片画资产曲线用的几十行数字，喂给模型既占上下文又不会改变任何结论——
     * 走势该说什么，overview 的总额与收益率、riskProfile 的最大回撤已经说清了。
     */
    private static JSONObject trimmed(JSONObject full) {
        JSONObject copy = JSON.parseObject(full.toJSONString());
        JSONObject overview = copy.getJSONObject("overview");
        if (overview != null) {
            overview.remove("trend");
        }
        return copy;
    }

    /** 进度是尽力而为：拿不到会话号（不在工具执行栈里）就静默跳过，不影响正确性。 */
    private void progress(String sessionId, String text) {
        if (sessionId != null) {
            runRegistry.publishProgress(sessionId, text);
        }
    }
}
