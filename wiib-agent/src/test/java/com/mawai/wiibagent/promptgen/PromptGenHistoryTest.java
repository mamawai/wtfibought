package com.mawai.wiibagent.promptgen;

import com.mawai.wiibcommon.entity.PromptGenSession;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

/** 页面状态、题数、必问项、写稿用的问答记录都从历史推：题和答配对、被拒的不算、开场指令不算 */
class PromptGenHistoryTest {

    private static final Set<String> REQUIRED = Set.of("direction", "holding", "style");

    private int seq;

    @Test
    void shownQuestionWaitsForAnswerThenPairsWithIt() {
        List<Message> history = new ArrayList<>(List.of(new UserMessage("开场"),
                AssistantMessage.builder().content("我先看看你的交易记录").build()));
        history.addAll(ask("Direction"));

        PromptGenHistory.Ask pending = PromptGenHistory.pending(PromptGenHistory.steps(history));
        assertThat(pending.key()).isEqualTo("direction");
        assertThat(pending.recommended()).isEqualTo("只做多");
        assertThat(pending.options()).containsExactly("多空都做", "只做空");

        history.add(new UserMessage("只做多"));
        List<PromptGenHistory.Step> steps = PromptGenHistory.steps(history);

        assertThat(PromptGenHistory.pending(steps)).isNull();
        assertThat(steps).hasSize(2).first().isEqualTo(new PromptGenHistory.AgentText("我先看看你的交易记录"));
        assertThat(PromptGenHistory.missing(steps, REQUIRED)).containsExactlyInAnyOrder("holding", "style");
        assertThat(PromptGenHistory.transcript(steps, "用户补充：")).isEqualTo("- [direction] 方向？ → 只做多");
    }

    /** 读数据成了过程步：同一批里排在题前面，用户的回答照样配给题 */
    @Test
    void readsInTheSameBatchGoBeforeTheQuestion() {
        List<Message> history = new ArrayList<>(List.of(new UserMessage("开场")));
        history.add(AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("a", "function", "ask_user", "{\"key\":\"style\",\"question\":\"风格？\"}"),
                new AssistantMessage.ToolCall("b", "function", "trader_trades", "{\"limit\":20}"))).build());
        history.add(ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("a", "ask_user", MAPPER.writeValueAsString("{\"shown\":true}")),
                new ToolResponseMessage.ToolResponse("b", "trader_trades", MAPPER.writeValueAsString("[]")))).build());
        history.add(new UserMessage("均衡"));

        List<PromptGenHistory.Step> steps = PromptGenHistory.steps(history);

        assertThat(steps).hasSize(2);
        assertThat(steps.getFirst()).isInstanceOfSatisfying(PromptGenHistory.Tool.class, tool -> {
            assertThat(tool.name()).isEqualTo("trader_trades");
            assertThat(tool.args().path("limit").asInt()).isEqualTo(20);
        });
        assertThat(steps.getLast()).isInstanceOfSatisfying(PromptGenHistory.Ask.class,
                ask -> assertThat(ask.answer()).isEqualTo("均衡"));
    }

    /** 网关没给参数的读数据照样认（参数当空对象）；保险丝拦下没执行的不算看过 */
    @Test
    void readWithoutArgumentsCountsButBlockedReadDoesNot() {
        List<Message> history = new ArrayList<>(List.of(new UserMessage("开场")));
        history.addAll(callWithRawResponse("trader_overview", null, MAPPER.writeValueAsString("{}")));
        history.addAll(callWithRawResponse("trader_trades", "{}", "未执行"));

        List<PromptGenHistory.Step> steps = PromptGenHistory.steps(history);

        assertThat(steps).singleElement().isInstanceOfSatisfying(PromptGenHistory.Tool.class, tool -> {
            assertThat(tool.name()).isEqualTo("trader_overview");
            assertThat(tool.args().isEmpty()).isTrue();
        });
    }

    /** 被拒的题、保险丝的占位回执（不是 JSON）都不算问过 */
    @Test
    void refusedOrPlaceholderAsksAreNotCounted() {
        List<Message> history = new ArrayList<>(List.of(new UserMessage("开场")));
        history.addAll(call("ask_user", "{\"key\":\"style\"}", "{\"shown\":false,\"message\":\"满了\"}"));
        history.addAll(callWithRawResponse("ask_user", "{\"key\":\"style\"}", "未执行"));

        List<PromptGenHistory.Step> steps = PromptGenHistory.steps(history);

        assertThat(steps).isEmpty();
        assertThat(PromptGenHistory.asksSinceDraft(steps)).isZero();
    }

    @Test
    void draftResetsAskCountAndFinalDraftIsDoneUntilUserSpeaks() {
        List<Message> history = new ArrayList<>(List.of(new UserMessage("开场")));
        history.addAll(ask("direction"));
        history.add(new UserMessage("只做多"));
        history.addAll(call("write_draft", "{\"brief\":\"-\",\"finalVersion\":true}",
                "{\"no\":7,\"finalVersion\":true,\"body\":\"正文\",\"notes\":\"说明\"}"));
        // 交了最终版又读了一次数据：用户没开口，照样算写完
        history.addAll(call("trader_plans", "{}", "[]"));
        history.add(AssistantMessage.builder().content("可以复制使用了").build());

        List<PromptGenHistory.Step> steps = PromptGenHistory.steps(history);
        assertThat(PromptGenHistory.asksSinceDraft(steps)).isZero();
        assertThat(PromptGenHistory.lastDraft(steps)).isEqualTo(new PromptGenHistory.Drafted(7, "正文", "说明", true));
        assertThat(PromptGenHistory.done(steps)).isTrue();

        // 交了最终版之后用户又提修改：不算完，这句是用户直接说的话
        history.add(new UserMessage("止损再宽一点"));
        steps = PromptGenHistory.steps(history);
        assertThat(PromptGenHistory.done(steps)).isFalse();
        assertThat(steps.getLast()).isEqualTo(new PromptGenHistory.UserText("止损再宽一点"));
        assertThat(PromptGenHistory.transcript(steps, "用户补充："))
                .isEqualTo("- [direction] 方向？ → 只做多\n- 用户补充：止损再宽一点");
    }

    /** 一轮倒数第二次调用时，保险丝在最后一条回执后面拼一句预算提示：那一版照样认得出 */
    @Test
    void budgetNoticeAppendedToResultDoesNotHideTheDraft() {
        List<Message> history = new ArrayList<>(List.of(new UserMessage("开场")));
        history.addAll(callWithRawResponse("write_draft", "{}",
                MAPPER.writeValueAsString("{\"no\":5,\"finalVersion\":false,\"body\":\"正文\",\"notes\":\"\"}")
                        + "\n\n预算已用完"));

        assertThat(PromptGenHistory.lastDraft(PromptGenHistory.steps(history)).no()).isEqualTo(5);
    }

    /** 有的网关不同轮复用同一个 id：按紧跟的那条回执配，前一轮答过的题不被后一轮同 id 的拒绝盖掉 */
    @Test
    void reusedCallIdAcrossTurnsPairsByPosition() {
        List<Message> history = new ArrayList<>(List.of(new UserMessage("开场")));
        history.addAll(pair("same", "ask_user", "{\"key\":\"style\",\"question\":\"风格？\"}",
                MAPPER.writeValueAsString("{\"shown\":true}")));
        history.add(new UserMessage("均衡"));
        history.addAll(pair("same", "ask_user", "{\"key\":\"holding\"}",
                MAPPER.writeValueAsString("{\"shown\":false}")));

        List<PromptGenHistory.Step> steps = PromptGenHistory.steps(history);
        assertThat(PromptGenHistory.missing(steps, REQUIRED)).containsExactlyInAnyOrder("direction", "holding");
    }

    /** 题卡能不能跳：还没答的必问项不能跳；答过的必问项再追问一次可以跳 */
    @Test
    void onlyUnansweredRequiredQuestionCannotBeSkipped() {
        List<Message> history = new ArrayList<>(List.of(new UserMessage("开场")));
        history.addAll(ask("direction"));
        assertThat(pendingRequired(history)).isTrue();

        history.add(new UserMessage("只做多"));
        history.addAll(ask("direction"));
        assertThat(pendingRequired(history)).isFalse();
    }

    @Test
    void trimDanglingDropsOnlyTheUnansweredToolCallAndKeepsWhatWasSaid() {
        List<Message> answered = new ArrayList<>(List.of(new UserMessage("开场")));
        answered.addAll(ask("direction"));
        assertThat(PromptGenHistory.trimDangling(answered)).hasSize(3);

        List<Message> dangling = new ArrayList<>(answered);
        dangling.add(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("x", "function", "write_draft", "{}"))).build());
        assertThat(PromptGenHistory.trimDangling(dangling)).isEqualTo(answered);

        // 点停止时那条里已经说出来的话留下
        List<Message> spoken = new ArrayList<>(answered);
        spoken.add(AssistantMessage.builder().content("我先看看你的持仓")
                .toolCalls(List.of(new AssistantMessage.ToolCall("y", "function", "trader_positions", "{}"))).build());
        assertThat(PromptGenHistory.trimDangling(spoken).getLast()).isInstanceOfSatisfying(AssistantMessage.class, reply -> {
            assertThat(reply.getText()).isEqualTo("我先看看你的持仓");
            assertThat(reply.hasToolCalls()).isFalse();
        });
    }

    private static boolean pendingRequired(List<Message> history) {
        return PromptGenView.of(new PromptGenSession(), null, PromptGenHistory.steps(history), REQUIRED, false, null,
                List.of()).question().required();
    }

    /** 一道展示成功的方向题：调用 + 回执 */
    private List<Message> ask(String key) {
        String args = MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("key", key).put("question", "方向？").put("recommended", "只做多").put("reason", "近 20 笔多单胜率高")
                .set("options", MAPPER.createArrayNode().add("多空都做").add("只做空")));
        return call("ask_user", args, "{\"shown\":true}");
    }

    /** 一次工具调用 + 回执；result 照框架的样子再编码成 JSON 字符串 */
    private List<Message> call(String name, String args, String result) {
        return callWithRawResponse(name, args, MAPPER.writeValueAsString(result));
    }

    private List<Message> callWithRawResponse(String name, String args, String responseData) {
        return pair("c" + seq++, name, args, responseData);
    }

    private static List<Message> pair(String id, String name, String args, String responseData) {
        return List.of(
                AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, args))).build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(id, name, responseData))).build());
    }
}
