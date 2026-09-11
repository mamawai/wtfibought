package com.mawai.wiibagent.chat.tools;

import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.i18n.PromptCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;

/**
 * 对 trader 动手的三个入口，挂在 summarizer 叶子上（与 {@link DeepAnalysisToolkit} 并列）。
 * <p>
 * <b>它们只开表单，不执行</b>：每个工具都只往 SSE 推一张待填的卡，用户在卡上按下按钮才真动手，
 * 执行走 REST 打到 TraderActionService。所以模型没有能力唤醒、复盘或落库留言——
 * 这正是这层存在的意义，误触最多是多弹一张卡。
 * <p>
 * 挂在汇总者不挂 trader 专家：查询归专家，动手归汇总者——收尾动作由写最终回答的那个人做。
 * <p>
 * 会话号从执行工具时交进来的 ToolContext 里取（{@link ToolRunContext#sessionId}）：
 * ChatTurnRunner 把它传给 ReactLoop，循环执行工具时放进 ToolContext。
 * <p>
 * userId 建叶子时烤死，理由见 {@link TraderQueryToolkit}。
 */
@Slf4j
public class TraderActionToolkit {

    private final WorkbenchRunRegistry runRegistry;
    private final long userId;
    private final PromptCatalog prompts;
    /** 回执那句话会被模型原样转述给用户，所以跟语言走；工具是建叶子时造的，语言跟着叶子走 */
    private final AgentLang lang;

    public TraderActionToolkit(WorkbenchRunRegistry runRegistry, long userId,
                               PromptCatalog prompts, AgentLang lang) {
        this.runRegistry = runRegistry;
        this.userId = userId;
        this.prompts = prompts;
        this.lang = lang;
    }

    @Tool(name = "wake_trader", description = """
            Open the "wake trader now" form for the user. IMPORTANT: this does NOT wake the trader.
            It only shows a card with when it last woke, when it wakes next, and a button the user presses themselves.
            Waking is EXPENSIVE AND REAL (burns their model budget, MAY OPEN OR CLOSE POSITIONS) - that is exactly
            why the trigger belongs to the user, not to you.
            Never say the trader has been woken; say the form is open and waiting for them to confirm.""")
    public String wakeTrader(ToolContext context) {
        return openForm(ToolRunContext.sessionId(context), "wake", null, "chat.form.wake");
    }

    @Tool(name = "review_trader_now", description = """
            Open the "run retrospective now" form for the user. IMPORTANT: this does NOT run the retrospective.
            It only shows a card with the last retrospective time, whether there is new material, and a button
            the user presses themselves. Running it costs one deep-model call.
            Never say the retrospective has run or claim to know what it says; say the form is open.""")
    public String reviewTraderNow(ToolContext context) {
        return openForm(ToolRunContext.sessionId(context), "review", null, "chat.form.review");
    }

    @Tool(name = "leave_note_to_trader", description = """
            Open the "leave a note" form, pre-filled with the note you drafted from the user's own words.
            IMPORTANT: this does NOT save the note - the user reviews your draft, sets how many wake-ups it
            should last, and presses save themselves.
            A note is a passing remark carried by the next N wake-ups then erased; standing rules belong in the
            trader's custom prompt on its config page, not here.
            Never say the note has been saved; say the form is open with your draft in it.""")
    public String leaveNoteToTrader(
            @ToolParam(description =
                    "The note drafted in the user's own words, <=500 chars, e.g. 'CPI print tonight, keep size light'")
            String note,
            @ToolParam(required = false, description =
                    "How many upcoming wake-ups should carry this note, 1-24. Omit for 1 (a one-off remark).")
            Integer rounds,
            ToolContext context) {
        JSONObject prefill = new JSONObject().fluentPut("note", note);
        if (rounds != null) {
            prefill.put("rounds", rounds);
        }
        return openForm(ToolRunContext.sessionId(context), "note", prefill, "chat.form.note");
    }

    /**
     * 推一张待填的卡。推不出去要如实回报——补答轮与断连后都没有 SSE 通道，
     * 这时候答"表单已打开"就是一句用户永远兑现不了的话，而它还会落进对话历史。
     */
    private String openForm(String sessionId, String formType, JSONObject prefill, String labelKey) {
        boolean sent = sessionId != null && runRegistry.publishForm(sessionId, formType, prefill);
        log.info("[TraderAction] 打开{}表单 userId={} session={} sent={}", formType, userId, sessionId, sent);
        Map<String, Object> vars = Map.of("label", prompts.get(lang, labelKey));
        return sent
                ? outcome(true, prompts.get(lang, "chat.form.opened", vars))
                : outcome(false, prompts.get(lang, "chat.form.failed", vars));
    }

    private static String outcome(boolean ok, String message) {
        return new JSONObject().fluentPut("ok", ok).fluentPut("message", message).toJSONString();
    }
}
