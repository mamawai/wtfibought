package com.mawai.wiibagent.chat;

import com.mawai.wiibcommon.enums.AgentLang;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HITL 闸门的核心契约：判断必须发生在同时看得到 sessionId
 * 和工具名/参数(tool_call) 的这一层。工具方法体两样都看不到——那正是
 * "卡片说 BTC、实际能跑 ETH"这个洞的根源。
 */
class ApprovalGateTest {

    private final ApprovalRegistry registry = new ApprovalRegistry();
    private final ApprovalGate gate = new ApprovalGate(registry, ChatTestEndpoints.PROMPTS, AgentLang.ZH);

    private static final String SESSION = "wb-1-abc";

    /** 模型这一拍的回复：只带一个 tool_call，闸门看到的就是它 */
    private static AssistantMessage toolCallOf(String toolName, String argsJson) {
        return AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", toolName, argsJson)))
                .build();
    }

    /** 未授权时不许烧深模型：直接拦下并回一条 PENDING_APPROVAL，让模型转述给用户 */
    @Test
    void 未授权时短路并登记待确认() {
        Optional<ToolResponseMessage> out = gate.intercept(SESSION,
                toolCallOf("run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}"));

        // 有回执 = 这批工具一个都不跑，模型下一拍看到的是这条
        assertThat(out).isPresent();
        assertThat(out.orElseThrow().getResponses()).singleElement()
                .satisfies(r -> assertThat(r.responseData()).contains("PENDING_APPROVAL"));
        assertThat(registry.peekPending(SESSION)).isPresent()
                .get().satisfies(p -> {
                    assertThat(p.toolName()).isEqualTo("run_deep_analysis");
                    assertThat(p.symbol()).isEqualTo("BTCUSDT");
                });
    }

    /** 批准 BTC 之后模型改口要 ETH：key 对不上，必须重新弹卡而不是放行 */
    @Test
    void 批准的标的之外不放行() {
        gate.intercept(SESSION, toolCallOf("run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}"));
        registry.approve(SESSION, registry.peekPending(SESSION).orElseThrow().requestId());

        Optional<ToolResponseMessage> out = gate.intercept(SESSION,
                toolCallOf("run_deep_analysis", "{\"symbol\":\"ETHUSDT\"}"));

        assertThat(out).isPresent();
        assertThat(out.orElseThrow().getResponses()).singleElement()
                .satisfies(r -> assertThat(r.responseData()).contains("PENDING_APPROVAL"));
    }

    /**
     * symbol 是模型填的，可能是 btc / BTCUSDT / btcusdt。
     * 不归一化就会出现"用户点了同意却弹第二次卡"——比不修更糟。
     * <p>
     * 弹卡填 {@code btc}、续跑轮改口写 {@code btcusdt}：后缀写法和大小写全变了，
     * 仍须命中同一条授权（{@code BTCUSDT} 那种原样写法由"未授权时短路并登记待确认"覆盖）
     */
    @Test
    void 标的归一化后能对上授权() {
        gate.intercept(SESSION, toolCallOf("run_deep_analysis", "{\"symbol\":\"btc\"}"));
        assertThat(registry.peekPending(SESSION).orElseThrow().symbol()).isEqualTo("BTCUSDT");
        registry.approve(SESSION, registry.peekPending(SESSION).orElseThrow().requestId());

        Optional<ToolResponseMessage> out = gate.intercept(SESSION,
                toolCallOf("run_deep_analysis", "{\"symbol\":\"btcusdt\"}"));

        assertThat(out).isEmpty();   // 空 = 放行，循环自己去执行
    }

    /**
     * 白名单外的标的必须保留自己的键。归一化要收拢写法，但兜底一旦塌成 BTCUSDT，
     * "批了 BTC"的那条授权就会把一个 SOL 请求直接放行——比不归一化更危险
     */
    @Test
    void 白名单外的标的不蹭已有授权() {
        gate.intercept(SESSION, toolCallOf("run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}"));
        registry.approve(SESSION, registry.peekPending(SESSION).orElseThrow().requestId());

        Optional<ToolResponseMessage> out = gate.intercept(SESSION,
                toolCallOf("run_deep_analysis", "{\"symbol\":\"SOLUSDT\"}"));

        assertThat(out).isPresent();
        assertThat(registry.peekPending(SESSION).orElseThrow().symbol()).isEqualTo("SOLUSDT");
    }

    /** 用户拒绝后再问同样的问题，应该告诉模型"用户拒了"，而不是又弹一次卡 */
    @Test
    void 拒绝后回执说明原因不再弹卡() {
        gate.intercept(SESSION, toolCallOf("run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}"));
        registry.reject(SESSION, registry.peekPending(SESSION).orElseThrow().requestId());

        Optional<ToolResponseMessage> out = gate.intercept(SESSION,
                toolCallOf("run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}"));

        assertThat(out).isPresent();
        assertThat(out.orElseThrow().getResponses()).singleElement()
                .satisfies(r -> assertThat(r.responseData()).contains("拒绝"));
        assertThat(registry.peekPending(SESSION)).isEmpty(); // 没有再登记新的待确认
    }

    /**
     * 又要弹卡说明上一条授权已经用不上了（模型改口换了 symbol）。留着它，
     * route() 十分钟内会一直"跳过专家派发直通汇总"，用户每问一句都拿不到真数据——
     * 静默降级，日志里一个字都不会提
     */
    @Test
    void 重新弹卡前丢弃用不上的旧授权() {
        gate.intercept(SESSION, toolCallOf("run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}"));
        registry.approve(SESSION, registry.peekPending(SESSION).orElseThrow().requestId());

        // 模型改口要 ETH：授权对不上 → 重新弹卡，此时 BTC 那条授权必须被丢掉
        gate.intercept(SESSION, toolCallOf("run_deep_analysis", "{\"symbol\":\"ETHUSDT\"}"));

        assertThat(registry.hasApproval(SESSION)).isFalse();
    }

    /**
     * 拒的是 BTC，模型改口问 ETH——那条拒绝管不着 ETH，必须照常弹新卡。
     * 不比对是哪件事就会出现"问什么都被上一次的拒绝挡回去"
     */
    @Test
    void 拒绝标记只挡被拒的那件事() {
        gate.intercept(SESSION, toolCallOf("run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}"));
        registry.reject(SESSION, registry.peekPending(SESSION).orElseThrow().requestId());

        Optional<ToolResponseMessage> out = gate.intercept(SESSION,
                toolCallOf("run_deep_analysis", "{\"symbol\":\"ETHUSDT\"}"));

        assertThat(out.orElseThrow().getResponses()).singleElement()
                .satisfies(r -> assertThat(r.responseData()).contains("PENDING_APPROVAL"));
        assertThat(registry.peekPending(SESSION)).isPresent()
                .get().satisfies(p -> assertThat(p.symbol()).isEqualTo("ETHUSDT"));
    }

    /**
     * 短路时同批每个 tool_call 都得配一条回执。少回一条就留下孤儿 tool_call，
     * 这段历史落进会话上下文表之后续聊重放，上游直接 400，会话只能删掉重开
     */
    @Test
    void 短路时同批每个toolcall都配回执() {
        AssistantMessage reply = AssistantMessage.builder().content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("c1", "function",
                                "run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}"),
                        new AssistantMessage.ToolCall("c2", "function",
                                "market_snapshot", "{\"symbol\":\"BTCUSDT\"}")))
                .build();

        Optional<ToolResponseMessage> out = gate.intercept(SESSION, reply);

        assertThat(out.orElseThrow().getResponses())
                .extracting(ToolResponseMessage.ToolResponse::id)
                .containsExactly("c1", "c2");
    }

    // ===== 授权的粒度：闸门只管深研判一个工具了，区分维度全落在标的上 =====

    /**
     * 未授权就得拦下并把标的登记进待批。标的写法的归一化（btc / BTCUSDT / BTCUSDC 折成同一个）
     * 由"批了一个标的不放行另一个"那条覆盖，这里只钉拦截与登记。
     */
    @Test
    void 未授权时拦下并登记待批() {
        String symbol = "BTCUSDT";

        Optional<ToolResponseMessage> out = gate.intercept(SESSION,
                toolCallOf("run_deep_analysis", "{\"symbol\":\"" + symbol + "\"}"));

        assertThat(out).isPresent();
        assertThat(out.orElseThrow().getResponses()).singleElement()
                .satisfies(r -> assertThat(r.responseData()).contains("PENDING_APPROVAL"));
        assertThat(registry.peekPending(SESSION)).isPresent()
                .get().satisfies(p -> assertThat(p.symbol()).isEqualTo(symbol));
    }

    /** 批准之后就得真放行，否则用户点了同意还是执行不了 */
    @Test
    void 批准后同一标的放行() {
        String args = "{\"symbol\":\"BTCUSDT\"}";
        gate.intercept(SESSION, toolCallOf("run_deep_analysis", args));
        registry.approve(SESSION, registry.peekPending(SESSION).orElseThrow().requestId());

        Optional<ToolResponseMessage> out = gate.intercept(SESSION, toolCallOf("run_deep_analysis", args));

        assertThat(out).isEmpty();
    }

    /**
     * 授权是"这一件事"的票，不是"十分钟内的通用票"：批了 btc 不能顺手把 eth 也放行——
     * 用户为一次研判点的头，模型能拿去连烧几次，这是最贵的那种错。
     * <p>
     * 和"批准的标的之外不放行"分工：那边走 BTCUSDT/ETHUSDT 全称，这边走 btc/eth 短写法。
     * 隔离判断的两侧都跑过一遍归一化才算钉住——写法可以收拢，标的不行
     */
    @Test
    void 批了一个标的不放行另一个() {
        gate.intercept(SESSION, toolCallOf("run_deep_analysis", "{\"symbol\":\"btc\"}"));
        registry.approve(SESSION, registry.peekPending(SESSION).orElseThrow().requestId());

        Optional<ToolResponseMessage> out = gate.intercept(SESSION,
                toolCallOf("run_deep_analysis", "{\"symbol\":\"eth\"}"));

        assertThat(out).isPresent();
        assertThat(out.orElseThrow().getResponses()).singleElement()
                .satisfies(r -> assertThat(r.responseData()).contains("PENDING_APPROVAL"));
        assertThat(registry.peekPending(SESSION).orElseThrow().symbol()).isEqualTo("ETHUSDT");
    }

    /**
     * 卡片上的代价说明得写清"贵在哪"：用户是在为看不见的东西点头，笼统一句"这很贵"等于没说。
     * 回执里也要带同一句，模型才转述得出来
     */
    @Test
    void 确认卡写清贵在哪() {
        Optional<ToolResponseMessage> out = gate.intercept(SESSION,
                toolCallOf("run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}"));

        assertThat(registry.peekPending(SESSION).orElseThrow().reason())
                .contains("3 次深模型调用").contains("Judge");
        assertThat(out.orElseThrow().getResponses()).singleElement()
                .satisfies(r -> assertThat(r.responseData())
                        .contains("深度研判").contains("3 次深模型调用"));
    }

    /**
     * 一批里同时来了两个受管辖的调用（模型一口气要研判 BTC 和 ETH）：只有被登记的那个
     * 拿到 PENDING_APPROVAL 说明，另一个必须是"未执行"。按工具名认领的话两个都会拿到
     * 同一份说明——名字还一模一样——用户批的是 BTC，模型会以为 ETH 也批了。
     */
    @Test
    void 同批两个受管辖调用只有正主拿到说明() {
        AssistantMessage reply = AssistantMessage.builder().content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("c1", "function",
                                "run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}"),
                        new AssistantMessage.ToolCall("c2", "function",
                                "run_deep_analysis", "{\"symbol\":\"ETHUSDT\"}")))
                .build();

        Optional<ToolResponseMessage> out = gate.intercept(SESSION, reply);

        assertThat(out.orElseThrow().getResponses()).satisfiesExactly(
                first -> assertThat(first.responseData()).contains("PENDING_APPROVAL"),
                second -> assertThat(second.responseData()).isEqualTo("未执行：本轮存在待确认的贵操作。"));
        assertThat(registry.peekPending(SESSION).orElseThrow().symbol()).isEqualTo("BTCUSDT");
    }

    /**
     * trader 那三个工具都不进闸门：留言只写一行字，唤醒和点播复盘现在只弹一张表单卡、
     * 执行权在用户点击上——再拦一道就成了"先批准打开表单、再填表单"两道确认。
     */
    @ParameterizedTest
    @ValueSource(strings = {"leave_note_to_trader", "wake_trader", "review_trader_now"})
    void trader动作不进闸门原样放行(String tool) {
        Optional<ToolResponseMessage> out = gate.intercept(SESSION,
                toolCallOf(tool, "{\"note\":\"仓位轻点\"}"));

        assertThat(out).isEmpty();
        assertThat(registry.peekPending(SESSION)).isEmpty();
    }

    /** 非贵操作的工具（专家那些）不该被闸门碰，原样放行 */
    @Test
    void 其他工具原样放行() {
        Optional<ToolResponseMessage> out = gate.intercept(SESSION,
                toolCallOf("market_snapshot", "{\"symbol\":\"BTCUSDT\"}"));

        assertThat(out).isEmpty();
    }
}
