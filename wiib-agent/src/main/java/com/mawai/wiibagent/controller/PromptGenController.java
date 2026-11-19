package com.mawai.wiibagent.controller;

import com.mawai.wiibagent.chat.gate.ChatConcurrencyGate;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.llm.SseChannel;
import com.mawai.wiibagent.promptgen.PromptGenService;
import com.mawai.wiibagent.promptgen.PromptGenView;
import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.entity.PromptGenSession;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 提示词生成（写主人的交易指令）。/start、/reply 走 SSE，事件 status / text / tool_calls / draft / version / done / error；
 * 题卡不走事件，一轮结束页面拉 /session 拿。名额与工作台共用（每用户同时一轮）。
 * 页面断开不停：这一轮在后台跑完照常落库，刷新回来看 /session 的 running；要停走 /cancel。
 */
@Slf4j
@Tag(name = "提示词生成")
@RestController
@RequestMapping("/api/ai/prompt-gen")
@RequiredArgsConstructor
public class PromptGenController {

    private final PromptGenService service;
    private final ChatConcurrencyGate concurrencyGate;
    private final PromptCatalog prompts;
    private final UserLangResolver userLangResolver;
    /** 与工作台共用的虚拟线程执行器 */
    private final ExecutorService streamExecutor;

    /**
     * @param traderId   为哪个 trader 写（页面地址里的 ?trader），不带=没有 trader；不归这个人的当没带
     * @param outputLang 指令正文语言 zh/en
     */
    public record StartRequest(long endpointId, Long traderId, String outputLang) {
    }

    /** text 与 skip 二选一：skip=跳过当前这题，回答时不带（缺了就是 false） */
    public record ReplyRequest(String text, Boolean skip) {
    }

    public record RollbackRequest(int no) {
    }

    @GetMapping("/session")
    @Operation(summary = "当前对话（从历史推出的页面状态）+ 最近 3 版")
    public Result<PromptGenView> session(@CurrentUserId long userId) {
        return Result.ok(service.view(userId));
    }

    @PostMapping(value = "/start", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "开新对话（覆盖旧对话，版本保留）并跑第一轮")
    public SseEmitter start(@CurrentUserId long userId, @RequestBody StartRequest req, HttpServletResponse response) {
        SseChannel.noProxyBuffering(response);
        AgentLang outputLang = AgentLang.find(req.outputLang())
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_ERROR));
        ChatModel model = service.model(userId, req.endpointId());
        // 旧对话在这一轮的任务里才覆盖，那时名额已经在手
        concurrencyGate.acquireOrThrow(userId, ChatConcurrencyGate.Owner.PROMPT_GEN);
        return stream(userId, channel -> service.start(channel, userId, model, req.endpointId(), req.traderId(), outputLang));
    }

    @PostMapping(value = "/reply", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "回答题卡、跳过当前题、或直接说要改哪里")
    public SseEmitter reply(@CurrentUserId long userId, @RequestBody ReplyRequest req, HttpServletResponse response) {
        SseChannel.noProxyBuffering(response);
        boolean skip = Boolean.TRUE.equals(req.skip());
        if (!skip && (req.text() == null || req.text().isBlank())) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        if (!skip && req.text().length() > ChatWorkbenchController.MAX_MESSAGE_CHARS) {
            throw new BizException(ErrorCode.CHAT_MESSAGE_TOO_LONG);
        }
        // 先占名额再读对话：读早了，上一轮收尾写进去的东西会被这一轮整份覆盖掉
        concurrencyGate.acquireOrThrow(userId, ChatConcurrencyGate.Owner.PROMPT_GEN);
        PromptGenSession session;
        ChatModel model;
        List<Message> history;
        try {
            session = service.session(userId);
            if (session == null) {
                throw new BizException(ErrorCode.PROMPT_GEN_NO_SESSION);
            }
            model = service.model(userId, session.getEndpointId());
            history = service.replyHistory(userId, session, req.text(), skip);
        } catch (RuntimeException e) {
            concurrencyGate.release(userId);
            throw e;
        }
        return stream(userId, channel -> service.reply(channel, userId, model, session, history));
    }

    @PostMapping("/cancel")
    @Operation(summary = "叫停在跑的这一轮（跑到下一个检查点收尾，已经说的、读的照常落库）；没在跑返回 false")
    public Result<Boolean> cancel(@CurrentUserId long userId) {
        return Result.ok(service.cancel(userId));
    }

    @PostMapping("/rollback")
    @Operation(summary = "用某一版当时的对话替换当前对话，接着从那里聊")
    public Result<PromptGenView> rollback(@CurrentUserId long userId, @RequestBody RollbackRequest req) {
        // 占着名额换对话：有一轮在跑时换了，那一轮收尾会把它覆盖回去
        concurrencyGate.acquireOrThrow(userId, ChatConcurrencyGate.Owner.PROMPT_GEN);
        try {
            return Result.ok(service.rollback(userId, req.no()));
        } finally {
            concurrencyGate.release(userId);
        }
    }

    /** 建流并把这一轮丢给执行器；名额在任务的 finally 里还，提交失败就地还 */
    private SseEmitter stream(long userId, Consumer<SseChannel> round) {
        SseEmitter emitter = new SseEmitter(600_000L);
        SseChannel channel = new SseChannel(emitter);
        // 断开只是不再往页面推，这一轮接着跑
        emitter.onCompletion(channel::markClosed);
        emitter.onTimeout(() -> {
            channel.markClosed();
            emitter.complete();
        });
        emitter.onError(_ -> channel.markClosed());
        try {
            streamExecutor.execute(() -> {
                try {
                    round.accept(channel);
                } catch (Throwable e) {
                    log.error("[PromptGen] 任务异常退出 userId={}", userId, e);
                    // 那边只兜 Exception，Error 穿到这里时通道还开着：不收口前端要挂到超时
                    if (!channel.isClosed()) {
                        channel.send("error", MAPPER.createObjectNode()
                                .put("message", prompts.get(userLangResolver.of(userId), "llm.error.fallback")));
                        channel.complete();
                    }
                } finally {
                    concurrencyGate.release(userId);
                }
            });
        } catch (Throwable e) {
            concurrencyGate.release(userId);
            throw e;
        }
        return emitter;
    }
}
