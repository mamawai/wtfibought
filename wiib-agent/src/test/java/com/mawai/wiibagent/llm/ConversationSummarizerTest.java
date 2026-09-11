package com.mawai.wiibagent.llm;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationSummarizerTest {

    private static final PromptCatalog PROMPTS = new PromptCatalog();

    private final ChatModel summaryModel = mock(ChatModel.class);

    private ConversationSummarizer summarizer(int thresholdTokens, int messagesToKeep) {
        return new ConversationSummarizer(summaryModel, thresholdTokens, messagesToKeep,
                PROMPTS, AgentLang.ZH);
    }

    private void stubSummary(String text) {
        when(summaryModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }

    // ===== token 估算：中英文分别校准 =====

    @Test
    void estimatesChineseAtRoughlyOneTokenPerChar() {
        // 20 个汉字 ≈ 20 token；框架的 charCount/4 只会算出 5
        int tokens = ConversationSummarizer.estimateTokens(
                List.of(new UserMessage("一二三四五六七八九十一二三四五六七八九十")));

        assertThat(tokens).isEqualTo(20);
    }

    @Test
    void estimatesEnglishAtRoughlyFourCharsPerToken() {
        int tokens = ConversationSummarizer.estimateTokens(new ArrayList<>(List.of(
                new UserMessage("abcdefgh")))); // 8 字符 → 2 token

        assertThat(tokens).isEqualTo(2);
    }

    // ===== 触发条件 =====

    @Test
    void skipsWhenUnderThreshold() {
        Optional<List<Message>> compressed = summarizer(10_000, 6)
                .compress(List.of(new UserMessage("短对话")));

        assertThat(compressed).isEmpty();
        verify(summaryModel, never()).call(any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    @Test
    void keepsFirstUserMessageAndRecentOnes() {
        stubSummary("摘要内容");
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("最初的诉求：帮我盯BTC"));
        for (int i = 0; i < 20; i++) {
            messages.add(new AssistantMessage("这是一段很长的助手回复用来撑高token计数" + i));
        }

        List<Message> compressed = summarizer(50, 3).compress(messages).orElseThrow();

        assertThat(compressed).hasSize(1 + 1 + 3); // 首条用户消息 + 摘要 + 最近3条
        assertThat(compressed.get(0)).isInstanceOf(UserMessage.class);
        assertThat(compressed.get(0).getText()).contains("最初的诉求");
        assertThat(compressed.get(1)).isInstanceOf(SystemMessage.class);
        assertThat(compressed.get(1).getText()).contains("摘要内容");
        assertThat(compressed.get(compressed.size() - 1).getText()).endsWith("19");
    }

    // ===== 核心正确性：切点不能拆散工具调用配对 =====

    @Test
    void cutoffNeverSeparatesToolCallFromItsResponse() {
        stubSummary("摘要");
        // 构造：理想切点(size-2)恰好落在 toolCall 与 toolResponse 之间，压缩器必须往前挪
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("查一下行情"));
        for (int i = 0; i < 8; i++) {
            messages.add(new AssistantMessage("填充消息拉高token" + i));
        }
        messages.add(AssistantMessage.builder().content("要调工具了")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "getSnapshot", "{}")))
                .build());
        messages.add(ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call_1", "getSnapshot", "{\"price\":95000}"))).build());

        List<Message> compressed = summarizer(50, 2).compress(messages).orElseThrow();

        // 配对的两条要么都在保留区、要么都被压缩，不能只剩一半
        boolean hasCall = compressed.stream().anyMatch(m -> m instanceof AssistantMessage a
                && a.getToolCalls().stream().anyMatch(tc -> "call_1".equals(tc.id())));
        boolean hasResponse = compressed.stream().anyMatch(m -> m instanceof ToolResponseMessage t
                && t.getResponses().stream().anyMatch(r -> "call_1".equals(r.id())));
        assertThat(hasCall).isEqualTo(hasResponse);
    }

    // ===== 摘要输入必须含工具结果内容 =====

    /**
     * ToolResponseMessage.getText() 恒为空串（构造时传的就是 ""），真内容在 responseData()；
     * AssistantMessage 的 toolCalls().arguments() 同样不进文本。而阈值估算恰恰把这两样都算进去了——
     * 压缩是被工具结果的体积撑触发的，扔掉的却正是工具结果，行情数字和研判结论全没。
     */
    @Test
    void summaryInputCarriesToolResultPayload() {
        stubSummary("摘要");
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("BTC 现在怎么样"));
        messages.add(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "market_snapshot",
                        "{\"symbol\":\"BTCUSDT\"}"))).build());
        messages.add(ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call_1", "market_snapshot",
                        "{\"markPrice\":95000,\"fundingRate\":0.0001}"))).build());
        for (int i = 0; i < 10; i++) {
            messages.add(new AssistantMessage("闲聊填充把体积撑过阈值" + i));
        }

        summarizer(50, 2).compress(messages);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(summaryModel).call(prompt.capture());
        String input = prompt.getValue().getInstructions().getFirst().getText();
        assertThat(input).contains("95000").contains("market_snapshot").contains("BTCUSDT");
    }

    /** 超长工具回包要截断：摘要输入本身不能反被深研判回包撑爆 */
    @Test
    void oversizedToolPayloadIsTruncatedInSummaryInput() {
        String huge = "行情".repeat(5000);
        String text = summarizer(999_999, 6).textOf(ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call_1", "deep_analysis", huge))).build());

        assertThat(text).contains("deep_analysis").contains("截断");
        assertThat(text.length()).isLessThan(huge.length() / 2);
    }

    // ===== 摘要不许套摘要 =====

    /**
     * 上次压缩产出的摘要落在 index 1，下次压缩必然把它再压一遍。除首条用户消息外没有原文锚点，
     * 3~4 次后早期事实基本消失且无法归因。已是摘要的那条要被认出来原样保留，只压新增的原文。
     */
    @Test
    void previousSummaryIsKeptVerbatimNotRecompressed() {
        stubSummary("本次新增要点");
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("最初的诉求：帮我盯BTC"));
        messages.add(new SystemMessage("## 早前对话摘要：\n用户在 92000 附近建了多单"));
        for (int i = 0; i < 20; i++) {
            messages.add(new AssistantMessage("新一轮对话内容填充把体积撑过阈值" + i));
        }

        List<Message> compressed = summarizer(50, 3).compress(messages).orElseThrow();

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(summaryModel).call(prompt.capture());
        // 老摘要不进这次的压缩输入——二次压缩正是早期事实消失的原因
        assertThat(prompt.getValue().getInstructions().getFirst().getText()).doesNotContain("92000");
        // 但它原样活在新的摘要消息里，与本次新增分段可辨
        assertThat(compressed).hasSize(1 + 1 + 3);
        assertThat(compressed.get(1)).isInstanceOf(SystemMessage.class);
        assertThat(compressed.get(1).getText()).contains("92000").contains("本次新增要点");
    }

    // ===== 真实历史形状 × 保留条数：切点不许拆散配对（必现，毫秒级，不烧钱） =====

    /**
     * 形状取自 {@code ConversationSummarizerRealRunTest} 历次真跑的日志（2026-08-09~10，同一套五轮剧本），
     * 记法与那边的形状日志一致，{@code #n} 是配对编号。每个形状都用生产代码推演出的压缩后条数
     * 与日志实录的 {@code 压缩 N 条 → M 条} 对过账，对不上的不收。
     * <p>
     * 与上面那条 {@link #cutoffNeverSeparatesToolCallFromItsResponse} 不重复：那条是合成形状，
     * 历史里<b>没有 index 1 的摘要消息</b>；真实形状里首条用户消息与老摘要会被 {@code compress}
     * 提到头部，切点与"被提头的两条"之间的相互作用只有这里覆盖得到。
     * <p>
     * <b>采集环境要注意</b>：这批形状是在 market 专家全线不可用（Binance 请求 451 地域封锁）
     * 时采的，所以行情专家每轮只贡献一条"数据不可用"的短消息。正常生产下专家消息更长、条数不变，
     * 形状骨架仍然成立；但别把它当"典型生产历史"的样本去推断别的结论。
     */
    private static final Map<String, String> REAL_SHAPES = new LinkedHashMap<>(Map.of(
            // run1 第3轮首次压缩的输入：理想切点 9-4=5 正好夹在 调用(4)/回执(5) 之间，守卫必须回退
            "run1-R3-首压", "用户 摘要 用户 助手 调用#1 回执#1 助手 用户 助手",
            // run1 第3轮二次压缩的输入：两组配对，一组已在头部、一组刚落在尾部
            "run1-R3-次压", "用户 摘要 调用#1 回执#1 助手 用户 助手 调用#2 回执#2",
            "run1-R4-压缩", "用户 摘要 用户 助手 调用#1 回执#1 助手 用户",
            "run1-R5-压缩", "用户 摘要 调用#1 回执#1 助手 用户 助手 用户 助手 助手",
            "run4-R3-压缩", "用户 摘要 用户 助手 调用#1 回执#1 助手 用户 助手 助手",
            // 一轮内两组配对，是全部真跑日志里最丰富的形状。理想切点 13-4=9 落在两组之间、本来就安全，
            // 日志实录 压缩 13 条 → 6 条（=keep+2，守卫没被逼出来）；但 keep=3 会切在 调用#2/回执#2
            // 之间、keep=8 会切在 调用#1/回执#1 之间，这个形状因此一个人贡献两个杀手 keep
            "run6-R4-压缩", "用户 摘要 用户 助手 调用#1 回执#1 助手 用户 助手 调用#2 回执#2 助手 用户"));

    private static Stream<Arguments> realShapesCrossKeep() {
        // keep 扫 2..8：每个形状都至少有一个 keep 让理想切点正好切断配对（跳过配对检查就红）
        return REAL_SHAPES.entrySet().stream().flatMap(shape ->
                IntStream.rangeClosed(2, 8).mapToObj(keep ->
                        Arguments.of(shape.getKey(), shape.getValue(), keep)));
    }

    @ParameterizedTest(name = "{0} keep={2}")
    @MethodSource("realShapesCrossKeep")
    void realHistoryShapesNeverLeaveAnOrphanToolResponse(String label, String spec, int keep) {
        stubSummary("摘要");
        List<Message> messages = shapeOf(spec);

        Optional<List<Message>> result = summarizer(1, keep).compress(messages);
        if (result.isEmpty()) {
            return; // 没触发压缩（历史比保留数还短 / 无新原文可压）：没产出就没有孤儿
        }

        List<Message> compressed = result.orElseThrow();
        // 只查"回执找不到调用"：compress 是严格前缀切、提头的两条带不了 toolCalls，
        // 所以反方向的孤儿构造不出来（详见真跑类 recordPairingViolation 的注释）
        assertThat(responseIds(compressed))
                .as("%s keep=%d 的切点把工具回执和它的调用切开了，压出：%s", label, keep, labelOf(compressed))
                .isSubsetOf(callIds(compressed));
    }

    /**
     * 钉死"这批 fixture 里确实存在理想切点会切断配对的组合"。
     * 没有这条，上面那组参数化用例哪天被改得再也逼不出守卫、也会一直绿着当摆设。
     * <p>
     * 判据是条数：压缩后恒为 {@code 首条用户消息 + 摘要 + (size-cutoff)}，用理想切点就是 {@code keep+2}，
     * 多出来的每一条都是守卫往前挪的步数。run1 那次真跑日志记的正是 {@code 压缩 9 条 → 7 条}。
     */
    @Test
    void realShapeActuallyForcesTheGuardToBackOff() {
        stubSummary("摘要");
        List<Message> messages = shapeOf(REAL_SHAPES.get("run1-R3-首压"));

        List<Message> compressed = summarizer(1, 4).compress(messages).orElseThrow();

        assertThat(compressed).hasSize(4 + 3); // keep+2 是理想切点；多这一条 = 守卫退了一步
        assertThat(responseIds(compressed)).isEqualTo(callIds(compressed));
    }

    /** 按形状记法造消息。{@code 调用#1 / 回执#1} 用同一个 call id 配对。 */
    private static List<Message> shapeOf(String spec) {
        List<Message> messages = new ArrayList<>();
        String[] tokens = spec.split(" ");
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            int hash = token.indexOf('#');
            String kind = hash < 0 ? token : token.substring(0, hash);
            String callId = hash < 0 ? null : "call_" + token.substring(hash + 1);
            messages.add(switch (kind) {
                case "用户" -> new UserMessage("用户提问填充内容" + i);
                case "摘要" -> new SystemMessage("## 早前对话摘要：\n── 第1段 ──\n上一轮压缩留下的要点");
                case "助手" -> new AssistantMessage("助手回复填充内容拉高体积" + i);
                case "调用" -> AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function",
                                "run_deep_analysis", "{\"symbol\":\"BTCUSDT\"}"))).build();
                case "回执" -> ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse(callId, "run_deep_analysis",
                                "{\"status\":\"PENDING_APPROVAL\"}"))).build();
                default -> throw new IllegalArgumentException("未知形状标记: " + token);
            });
        }
        return messages;
    }

    private static String labelOf(List<Message> messages) {
        return messages.stream().map(message -> switch (message) {
            case AssistantMessage assistant when !assistant.getToolCalls().isEmpty() -> "调用";
            case AssistantMessage ignored -> "助手";
            case ToolResponseMessage ignored -> "回执";
            case UserMessage ignored -> "用户";
            default -> "系统";
        }).collect(Collectors.joining("·"));
    }

    private static Set<String> callIds(List<Message> messages) {
        return messages.stream()
                .filter(AssistantMessage.class::isInstance).map(AssistantMessage.class::cast)
                .flatMap(assistant -> assistant.getToolCalls().stream())
                .map(AssistantMessage.ToolCall::id)
                .collect(Collectors.toSet());
    }

    private static Set<String> responseIds(List<Message> messages) {
        return messages.stream()
                .filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                .flatMap(toolResponse -> toolResponse.getResponses().stream())
                .map(ToolResponseMessage.ToolResponse::id)
                .collect(Collectors.toSet());
    }

    @Test
    void degradesToOriginalWhenSummaryModelFails() {
        when(summaryModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("模型挂了"));
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("诉求"));
        for (int i = 0; i < 20; i++) {
            messages.add(new AssistantMessage("很长很长的助手回复内容用来撑高计数" + i));
        }

        // 压缩失败只记日志，返回空=沿用原始对话，不该打断整轮对话
        assertThat(summarizer(50, 3).compress(messages)).isEmpty();
    }
}
