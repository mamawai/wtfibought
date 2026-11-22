package com.mawai.wiibagent.promptgen;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.llm.LlmClient;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
import com.mawai.wiibcommon.enums.AgentLang;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.mawai.wiibagent.promptgen.PromptGenFacts.NOTES_MARK;
import static com.mawai.wiibagent.trader.TraderService.MAX_CUSTOM_PROMPT;
import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 生成器 agent 自己的三个工具，每轮新建、绑在这一轮的现场上。
 * 读 trader 数据的另外五个直接用 {@code TraderQueryToolkit}。
 */
public class PromptGenToolkit {

    public static final String ASK_USER = "ask_user";
    public static final String WRITE_DRAFT = "write_draft";
    /** 两版之间最多问几题 */
    public static final int MAX_ASKS = 10;

    private static final Pattern NOTES_LINE =
            Pattern.compile("^[ \\t]*" + Pattern.quote(NOTES_MARK) + "[ \\t]*$", Pattern.MULTILINE);

    private final PromptGenRun run;
    /** 写稿人：同一个模型、不挂工具、系统提示词是写稿规则 */
    private final LlmClient writer;
    private final PlayStatsAssembler playStats;
    private final PromptCatalog prompts;

    /** 写稿人的输出拆开后 */
    record Draft(String body, String notes) {
    }

    PromptGenToolkit(PromptGenRun run, LlmClient writer, PlayStatsAssembler playStats,
                     PromptCatalog prompts) {
        this.run = run;
        this.writer = writer;
        this.playStats = playStats;
        this.prompts = prompts;
    }

    // 本类工具的描述都在词表 tool.<工具名>（见 LocalizedToolCallbacks）

    @Tool(name = "trader_play_stats")
    public String traderPlayStats() {
        if (run.trader() == null) {
            return MAPPER.writeValueAsString(MAPPER.createObjectNode().put("hasTrader", false));
        }
        String block = playStats.assembleWithReplay(run.trader(), run.lang());
        return block == null ? prompts.get(run.lang(), "promptgen.noPlayStats") : block;
    }

    @Tool(name = ASK_USER)
    public String askUser(
            @ToolParam(description = "Short English slug for what this question settles; required items must use their fixed keys")
            String key,
            @ToolParam(description = "The question, in the conversation language")
            String question,
            @ToolParam(description = "The option you recommend, phrased so it can go straight into the instruction")
            String recommended,
            @ToolParam(description = "Why you recommend it: cite the data you read (numbers and sample size) or say what you based it on")
            String reason,
            @ToolParam(description = "2-4 other options, mutually exclusive with the recommended one and with each other")
            List<String> options) {
        if (run.asked()) {
            return askRefused(prompts.get(run.lang(), "promptgen.askOnePerTurn"));
        }
        List<PromptGenHistory.Step> steps = PromptGenHistory.steps(run.messages());
        // 还缺的必问项不受题数上限管：不然问满了又写不了稿，对话就卡死了
        boolean missingRequired = PromptGenHistory.missing(steps, run.required(steps))
                .contains(PromptGenHistory.key(key));
        if (!missingRequired && PromptGenHistory.asksSinceDraft(steps) >= MAX_ASKS) {
            return askRefused(prompts.get(run.lang(), "promptgen.askLimit", Map.of("max", MAX_ASKS)));
        }
        // 题卡内容就是这次调用的参数，这一轮收工后页面从历史里读
        run.markAsked();
        return MAPPER.writeValueAsString(MAPPER.createObjectNode().put("shown", true)
                .put("message", prompts.get(run.lang(), "promptgen.askShown")));
    }

    @Tool(name = WRITE_DRAFT)
    public String writeDraft(
            @ToolParam(description = "What the writer needs beyond the Q&A log (attached automatically): data findings with numbers, how you read the answers, and for a revision exactly what to change")
            String brief,
            @ToolParam(description = "true when you have nothing left to ask and this is the final version")
            boolean finalVersion) {
        AgentLang lang = run.lang();
        // 题卡已经推出去了：先等用户回答，不然这道题在历史里永远配不上回答
        if (run.asked()) {
            return notWritten(prompts.get(lang, "promptgen.draftAfterAsk"));
        }
        List<PromptGenHistory.Step> steps = PromptGenHistory.steps(run.messages());
        List<String> missing = PromptGenHistory.missing(steps, run.required(steps));
        if (!missing.isEmpty()) {
            return notWritten(prompts.get(lang, "promptgen.requiredMissing", Map.of("keys", String.join(", ", missing))));
        }
        if (run.staged() != null) {
            return notWritten(prompts.get(lang, "promptgen.draftOnce"));
        }

        PromptGenHistory.Drafted last = PromptGenHistory.lastDraft(steps);
        String transcript = PromptGenHistory.transcript(steps, prompts.get(lang, "promptgen.userNote"));
        UserMessage input = new UserMessage(prompts.get(lang, "promptgen.writerInput", Map.of(
                "qa", transcript.isEmpty() ? prompts.get(lang, "promptgen.noQa") : transcript,
                "brief", Objects.requireNonNullElse(brief, ""),
                "previous", last == null ? prompts.get(lang, "promptgen.noPrevious") : last.body())));

        run.status("writing");
        String raw = write(List.of(input));
        Draft draft = raw == null ? null : split(raw);
        if (draft != null && draft.body().length() > MAX_CUSTOM_PROMPT) {
            // 超长只重写一次；再发一次 writing，页面据此把首稿清掉
            run.status("writing");
            raw = write(List.of(input, AssistantMessage.builder().content(raw).build(),
                    new UserMessage(prompts.get(lang, "promptgen.shrink",
                            Map.of("max", MAX_CUSTOM_PROMPT, "mark", NOTES_MARK)))));
            draft = raw == null ? null : split(raw);
        }
        if (draft == null) {
            return notWritten(prompts.get(lang, "promptgen.writeCancelled"));
        }
        if (draft.body().isBlank()) {
            return notWritten(prompts.get(lang, "promptgen.emptyDraft"));
        }
        if (draft.body().length() > MAX_CUSTOM_PROMPT) {
            draft = new Draft(truncate(draft.body(), MAX_CUSTOM_PROMPT), (draft.notes() + "\n"
                    + prompts.get(lang, "promptgen.truncatedNote", Map.of("max", MAX_CUSTOM_PROMPT))).strip());
        }

        PromptGenHistory.Drafted drafted = new PromptGenHistory.Drafted(
                run.store().nextNo(run.userId()), draft.body(), draft.notes(), finalVersion);
        run.stage(drafted);
        return MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("no", drafted.no())
                .put("finalVersion", finalVersion)
                .put("body", drafted.body())
                .put("notes", drafted.notes()));
    }

    /** 流式写稿，逐段推给页面；用户点了停止返回 null */
    private String write(List<Message> messages) {
        StringBuilder out = new StringBuilder();
        try (Stream<ChatResponse> frames = writer.streamingExecute(messages, run.cancel()).toStream()) {
            Iterator<ChatResponse> iterator = frames.iterator();
            while (!run.cancelled() && iterator.hasNext()) {
                ChatResponse frame = iterator.next();
                if (frame.getResults().isEmpty()) {
                    continue;
                }
                String text = frame.getResults().getFirst().getOutput().getText();
                if (text != null && !text.isEmpty()) {
                    out.append(text);
                    run.emit("draft", MAPPER.createObjectNode().put("text", text));
                }
            }
        }
        return run.cancelled() ? null : out.toString();
    }

    /** 正文 + 单独一行分界 + notes；没写分界就全算正文 */
    static Draft split(String raw) {
        String[] parts = NOTES_LINE.split(raw.replace("\r\n", "\n").strip(), 2);
        return new Draft(parts[0].strip(), parts.length > 1 ? parts[1].strip() : "");
    }

    /** 截到 max 个 UTF-16 单元，不把代理对切成半个 */
    static String truncate(String text, int max) {
        return text.substring(0, Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max);
    }

    private static String askRefused(String message) {
        return MAPPER.writeValueAsString(MAPPER.createObjectNode().put("shown", false).put("message", message));
    }

    private static String notWritten(String message) {
        return MAPPER.writeValueAsString(MAPPER.createObjectNode().put("written", false).put("message", message));
    }
}
