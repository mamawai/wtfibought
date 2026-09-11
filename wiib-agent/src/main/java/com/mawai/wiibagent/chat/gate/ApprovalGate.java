package com.mawai.wiibagent.chat.gate;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.llm.ReactLoop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 贵操作的 HITL 闸门，{@link ReactLoop} 执行工具前先问它。
 * <p>
 * <b>管辖范围：只有深度研判</b>。弹表单卡的三个 trader 工具不受管辖——执行权本来就归用户
 * 在卡上点击，再批准一次等于让用户确认两遍，第一道毫无信息量。
 * <p>
 * 判断做在这一层，这么写为了在执行之前拿到 sessionId 和 tool_call 的 name/arguments。
 * <p>
 * 拒绝标记跨轮活着（用户点拒绝发生在两轮之间）且一次性——被读走就没了，改主意重新问不被挡。
 */
@Slf4j
public class ApprovalGate implements ReactLoop.ToolGate {

    /** Bull∥Bear + Judge 三次深模型调用 */
    public static final String DEEP_ANALYSIS_TOOL = "run_deep_analysis";

    /**
     * 受管辖的贵操作。判据是"模型点一下就把钱烧掉/把仓位动了"——
     * 只有深研判还满足：它一被调用就是三次深模型调用，中间没有人插得上手。
     * trader 那几个工具现在只弹表单，真正的执行扳机在用户手指上，不需要再多一道闸。
     */
    static final Set<String> GUARDED_TOOLS = Set.of(DEEP_ANALYSIS_TOOL);

    private final ApprovalRegistry registry;
    private final PromptCatalog prompts;
    /** 确认卡与回执的语言：闸门是建叶子时挂上去的，语言跟着叶子走（见 ChatAgentFactory.leafKey） */
    private final AgentLang lang;

    public ApprovalGate(ApprovalRegistry registry, PromptCatalog prompts, AgentLang lang) {
        this.registry = registry;
        this.prompts = prompts;
        this.lang = lang;
    }

    /** 确认卡与回执上的操作名 */
    private String label() {
        return prompts.get(lang, "chat.hitl.label");
    }

    /** 卡片上给用户看的代价说明——用户要为"贵在哪"点头，笼统说一句"这很贵"等于没说 */
    private String reason() {
        return prompts.get(lang, "chat.hitl.reason");
    }

    /**
     * 空 = 放行，循环自己去执行这批工具；非空 = 这批工具不跑，拿这条回执回模型。
     */
    @Override
    public Optional<ToolResponseMessage> intercept(String sessionId, AssistantMessage reply) {
        Optional<AssistantMessage.ToolCall> guarded = guardedCall(reply);
        if (sessionId == null || guarded.isEmpty()) {
            return Optional.empty();
        }
        AssistantMessage.ToolCall call = guarded.get();
        String symbol = normalizedSymbol(call);

        // 用户上一轮拒绝过同一件事：如实告诉模型，别再弹一次卡（标记是一次性的，
        // 用户改主意重新问时不该还被挡着）
        Optional<ApprovalRegistry.PendingRequest> rejected = registry.consumeRejected(sessionId);
        if (rejected.isPresent() && sameRequest(rejected.get(), call.name(), symbol)) {
            log.info("[HITL] 用户已拒绝，回执告知模型 session={} tool={} symbol={}",
                    sessionId, call.name(), symbol);
            return Optional.of(pairedReply(reply, call.id(),
                    prompts.get(lang, "chat.hitl.rejectedReply", Map.of("label", label()))));
        }

        if (registry.consumeApproval(sessionId, call.name(), symbol)) {
            log.info("[HITL] 授权命中，放行 session={} tool={} symbol={}", sessionId, call.name(), symbol);
            return Optional.empty();
        }

        // 又要弹卡 = 上一条授权已经用不上了（模型改口换了 symbol）。留着它，
        // ChatTurnRunner 会在 TTL 内一直跳过专家派发，用户之后每问一句都拿不到真数据，
        // 且没有任何日志说明原因
        registry.discardApprovals(sessionId);
        registry.requestApproval(sessionId, call.name(), symbol, reason());
        log.info("[HITL] 未授权，登记待确认 session={} tool={} symbol={}", sessionId, call.name(), symbol);
        JSONObject out = new JSONObject();
        out.put("status", "PENDING_APPROVAL");
        out.put("message", prompts.get(lang, "chat.hitl.pendingMessage",
                Map.of("label", label(), "reason", reason())));
        return Optional.of(pairedReply(reply, call.id(), out.toJSONString()));
    }

    /** 本批 tool_call 里受管辖的那个（一批里最多处理一个贵操作，其余的连同它一起等下一轮）。 */
    private static Optional<AssistantMessage.ToolCall> guardedCall(AssistantMessage reply) {
        return reply.getToolCalls().stream()
                .filter(c -> GUARDED_TOOLS.contains(c.name()))
                .findFirst();
    }

    /**
     * 标的必须归一化再参与授权键：模型可能填 btc / BTCUSDT / btcusdt，
     * 用原文组键会让"用户点了同意却弹第二次卡"，比不修更糟。
     */
    private static String normalizedSymbol(AssistantMessage.ToolCall call) {
        try {
            return approvalSymbol(JSON.parseObject(call.arguments()).getString("symbol"));
        } catch (Exception e) {
            // 参数解析不了也要有个确定的键，否则授权永远对不上
            return approvalSymbol(null);
        }
    }

    /**
     * 授权键专用归一：trim+大写之后把 USDT/USDC 后缀收掉再统一补 USDT，
     * 于是 btc / BTCUSDT / btcusdt 落到同一个键。
     * <p>
     * <b>{@link DeepAnalysisToolkit} 执行时共用这一个方法</b>，不是"顺手复用"：闸门用它算授权键、
     * 也用它写进确认卡的 symbol，工具要是另算一套（比如只 trim+大写），模型填 {@code btc} 时
     * 卡片写着 BTCUSDT、工具却拿 {@code BTC} 去打 Binance，一条数据都取不到。
     * <p>
     * 没直接用 {@code QuantConstants} 的两个现成方法，各有原因：<br>
     * {@code normalizeSymbolLenient} 只 trim+大写，{@code btc} 归成 {@code BTC} 对不上
     * {@code BTCUSDT}，正是这里要防的那件事；<br>
     * {@code normalizeSymbol} 会对白名单外的标的抛错，而且它在算后缀时无条件
     * {@code substring(0, len-4)}，传 {@code BTC} 这种三字母当场
     * StringIndexOutOfBounds（实测）——闸门在工具执行前，抛出去就是整轮对话失败。
     * <p>
     * 白名单外的标的（{@code SOL} 之类）保留自己的键、<b>不塌成 BTCUSDT</b>：
     * 塌了会让"批了 BTC"的授权把一个 SOL 请求放行进去，比不归一化更危险。
     */
    public static String approvalSymbol(String raw) {
        String upper = QuantConstants.normalizeSymbolLenient(raw);
        if (upper.endsWith("USDT") || upper.endsWith("USDC")) {
            upper = upper.substring(0, upper.length() - 4);
        }
        return upper.isBlank() ? "BTCUSDT" : upper + "USDT";
    }

    private static boolean sameRequest(ApprovalRegistry.PendingRequest req, String toolName, String symbol) {
        return req.toolName().equals(toolName) && req.symbol().equals(symbol);
    }

    /**
     * 拦下时必须给**这一批**每个 tool_call 都配对回执。只回一条 = 留下孤儿 tool_call，
     * 这段历史被 {@link ChatContextStore} 持久化后，续聊重放时上游直接 400，会话只能删掉重开。
     * <p>
     * 按 id 而不是按工具名认领正主：一批里可能同时来两个受管辖的调用（比如模型一口气研判
     * BTC 和 ETH），按名字匹配会把只针对其中一个的说明同时发给两个——工具名一样，
     * 用户批的是 BTC，模型会以为 ETH 也批了。
     */
    private ToolResponseMessage pairedReply(AssistantMessage reply, String guardedCallId, String body) {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall c : reply.getToolCalls()) {
            responses.add(new ToolResponseMessage.ToolResponse(c.id(), c.name(),
                    c.id().equals(guardedCallId) ? body : prompts.get(lang, "chat.hitl.notExecuted")));
        }
        return ToolResponseMessage.builder().responses(responses).build();
    }
}
