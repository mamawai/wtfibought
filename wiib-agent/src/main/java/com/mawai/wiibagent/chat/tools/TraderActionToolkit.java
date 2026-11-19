package com.mawai.wiibagent.chat.tools;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.chat.gate.WorkbenchRunRegistry;
import com.mawai.wiibagent.i18n.PromptCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 对 trader 动手的三个入口，挂在主 agent 上（与 {@link DeepAnalysisToolkit} 并列）。
 * <p>
 * <b>它们只开表单，不执行</b>：每个工具都只往 SSE 推一张待填的卡，用户在卡上按下按钮才真动手，
 * 执行走 REST 打到 TraderActionService。所以模型没有能力唤醒、复盘或落库留言——
 * 这正是这层存在的意义，误触最多是多弹一张卡。
 * 带了 trader（名字）也只是让卡上先高亮那个，对哪个 trader 动手仍由用户在卡上选。
 * <p>
 * 与 {@link TraderQueryToolkit} 并列挂在主 agent 上：查归那边、动手归这边。
 * <p>
 * 会话号从执行工具时交进来的 ToolContext 里取（{@link ToolRunContext#sessionId}）：
 * ChatTurnRunner 把它传给 ReactLoop，循环执行工具时放进 ToolContext。
 * <p>
 * userId 建叶子时烤死，理由见 {@link TraderQueryToolkit}。
 */
@Slf4j
public class TraderActionToolkit {

    /** 三个工具共用的 trader 参数说明 */
    private static final String TRADER_PARAM = "Name of the user's trader this is meant for, as listed by trader_list. "
            + "The form highlights it; the user still picks the trader it runs on";

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

    // 本类工具的描述都在词表 tool.<工具名>（见 LocalizedToolCallbacks）
    @Tool(name = "wake_trader")
    public String wakeTrader(@ToolParam(required = false, description = TRADER_PARAM) String trader,
                             ToolContext context) {
        return openForm(ToolRunContext.sessionId(context), "wake", named(MAPPER.createObjectNode(), trader),
                "chat.form.wake");
    }

    @Tool(name = "review_trader_now")
    public String reviewTraderNow(@ToolParam(required = false, description = TRADER_PARAM) String trader,
                                  ToolContext context) {
        return openForm(ToolRunContext.sessionId(context), "review", named(MAPPER.createObjectNode(), trader),
                "chat.form.review");
    }

    @Tool(name = "leave_note_to_trader")
    public String leaveNoteToTrader(
            @ToolParam(description =
                    "The note drafted in the user's own words, <=500 chars, e.g. 'CPI print tonight, keep size light'")
            String note,
            @ToolParam(required = false, description =
                    "How many upcoming wake-ups should carry this note, 1-24. Omit for 1 (a one-off remark).")
            Integer rounds,
            @ToolParam(required = false, description = TRADER_PARAM) String trader,
            ToolContext context) {
        ObjectNode prefill = MAPPER.createObjectNode().put("note", note);
        if (rounds != null) {
            prefill.put("rounds", rounds);
        }
        return openForm(ToolRunContext.sessionId(context), "note", named(prefill, trader), "chat.form.note");
    }

    /** 点了名就放进预填，卡上先高亮那个 */
    private static ObjectNode named(ObjectNode prefill, String trader) {
        if (trader != null && !trader.isBlank()) {
            prefill.put("trader", trader.trim());
        }
        return prefill;
    }

    /**
     * 推一张待填的卡。推不出去要如实回报——断连后没有 SSE 通道，
     * 这时候答"表单已打开"就是一句用户永远兑现不了的话，而它还会落进对话历史。
     */
    private String openForm(String sessionId, String formType, ObjectNode prefill, String labelKey) {
        boolean sent = sessionId != null && runRegistry.publishForm(sessionId, formType, prefill);
        log.info("[TraderAction] 打开{}表单 userId={} session={} sent={}", formType, userId, sessionId, sent);
        Map<String, Object> vars = Map.of("label", prompts.get(lang, labelKey));
        return sent
                ? outcome(true, prompts.get(lang, "chat.form.opened", vars))
                : outcome(false, prompts.get(lang, "chat.form.failed", vars));
    }

    private static String outcome(boolean ok, String message) {
        return MAPPER.writeValueAsString(MAPPER.createObjectNode().put("ok", ok).put("message", message));

    }
}
