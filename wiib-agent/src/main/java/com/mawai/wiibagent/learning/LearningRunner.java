package com.mawai.wiibagent.learning;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.LocalizedToolCallbacks;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.llm.LlmErrorMessages;
import com.mawai.wiibagent.llm.ModelCallLimiter;
import com.mawai.wiibagent.llm.ReactLoop;
import com.mawai.wiibagent.llm.ResilientChatService;
import com.mawai.wiibagent.llm.ToolCallTraceHook;
import com.mawai.wiibagent.llm.UsageTrackingChatModel;
import com.mawai.wiibagent.trader.TraderModelFactory;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * learning agent 学习回路（向同侪学习，看别人不看自己；看自己的复盘见 ReviewRunner）：
 * ReactLoop + 唯一只读工具 peer_insights → 一份完整学习笔记 →
 * LEARN 决策行公开上时间线、全文覆盖 ai_trader.learning_notes。
 * <p>
 * 做成 agent 而非单次调用：看谁、看多深、值不值得学，下一步取决于上一步看到了什么，
 * 写不成固定步骤，正是 ReAct 循环的用武之地。
 * <p>
 * 失败语义：ERROR 行留痕、不动 learning_notes、不计连败——学习失败没有资金风险，不值得暂停机制。
 * <p>
 * 提示词、同侪段名、peer_insights 的工具描述全在 {@link PromptCatalog}（{@code learning.*} /
 * {@code tool.peer_insights}），按 trader 主人的语言取；三个必需段标记同样跟语言走，
 * {@link #missingMarks} 判的就是本轮提示词刚要求的那一套。
 * <p>
 * 系统提示词（{@code learning.system}）的设计意图，加语言时逐条对照着写：
 * 身份先于指令——研究同行的交易员，不是评审，学的是能用在自己身上的东西，不是给别人打分；
 * 单问题框架——只回答"别人做对了什么，其中哪些对我真的有用"。
 * <p>
 * 反照抄三件套（对应 reviewer 的防自夸三件套）：①【不学什么】必填，强制做否定判断；
 * ②每条学习必须带证据与差距数字，不许写"他的风控意识值得学习"这种没法执行的话；
 * ③引用同侪战绩必须带笔数，样本少时运气和方法长得一模一样。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LearningRunner {

    /**
     * 学习超时：ReAct 多轮（挑人 → 查 2~3 个详情 → 收束），每轮都短，300s 够用。
     * 这个数直接算进日线交接的停工窗口（≈复盘超时 + 学习超时），窗口期间调度拒绝一切唤醒——
     * 代价是跳过几根 5m K 线，每天只有一次，可接受。
     */
    static final int LEARN_TIMEOUT_SECONDS = 300;
    /** 单次学习模型调用上限（ReAct 保险丝，挡住"再看一个再看一个"烧用户的钱）：预期 2~5 次，8 留足余量 */
    static final int MAX_MODEL_CALLS = 8;
    /** LEARN 行的 interval 标记学习节奏（wake_time=日线边界），与 trader 唤醒档位无关 */
    static final String LEARN_INTERVAL_CODE = "1d";

    /**
     * 合格判定的两个必需段标记（词表 key，取值跟语言走）：缺任一就是格式失守。
     * 反照抄三件套之一：【不学什么】是必填段。只会说"值得学"的复盘等于没复盘——
     * 不做否定判断，模型就只是在抄。其余段落的条数上限不做代码硬校验，那是提示词的事。
     */
    private static final List<String> REQUIRED_MARK_KEYS =
            List.of("learning.mark.learn", "learning.mark.skip");

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final PeerInsightService peerInsightService;
    private final TraderModelFactory modelFactory;
    private final AiTraderMapper traderMapper;
    private final AiTraderDecisionMapper decisionMapper;
    private final PromptCatalog prompts;
    private final LocalizedToolCallbacks localizedTools;
    private final UserLangResolver userLangResolver;

    /** 超时注入点：测试把 300s 缩短 */
    int timeoutSeconds = LEARN_TIMEOUT_SECONDS;

    /**
     * 日线边界学习入口（调度侧已判 learning_enabled 与同侪数量，这里不重复判）。
     * boundaryMs 即 LEARN 行的 wake_time，与本轮全体复盘同一个边界。
     */
    public void learn(AiTrader trader, long boundaryMs) {
        long start = System.currentTimeMillis();
        AiTraderDecision d = new AiTraderDecision();
        d.setTraderId(trader.getId());
        d.setRoundNo(trader.getRoundNo());
        d.setWakeTime(boundaryMs);
        d.setIntervalCode(LEARN_INTERVAL_CODE);
        d.setKind(AiTraderDecision.KIND_LEARN);
        d.setToolCalls(0);
        // 语言查一次用到底：系统提示、同侪段名、工具描述、段标记必须是同一门
        AgentLang lang = userLangResolver.of(trader.getUserId());
        try {
            // 排行榜代码注入而不是让它自己查：这一步是必然发生的，白白花掉一次工具调用不值
            String leaderboard = peerInsightService.leaderboard(trader.getId(), lang);
            String output = runAgentSession(trader, boundaryMs, leaderboard, lang, d);
            List<String> missing = missingMarks(output, lang);
            d.setLatencyMs((int) (System.currentTimeMillis() - start));
            if (!missing.isEmpty()) {
                // 降级安全：一次格式失守不许污染笔记——ERROR 行存原文留痕，learning_notes 一个字不动
                d.setStatus(AiTraderDecision.STATUS_ERROR);
                d.setReasoning(output);
                d.setError(prompts.get(lang, "learning.error.missingMarks", Map.of(
                        "marks", String.join(prompts.get(lang, "learning.error.markJoin"), missing))));
                decisionMapper.insert(d);
                log.warn("[Learn] 输出缺段 traderId={} missing={}", trader.getId(), missing);
                return;
            }
            d.setStatus(AiTraderDecision.STATUS_OK);
            d.setReasoning(output);
            decisionMapper.insert(d);
            // 笔记就是这份产出的全文（不像复盘要切两段），原样落库。
            // 篇幅靠提示词里那句"≤N"约束，不在这裁：裁刀落在哪都是把模型认为最该留的那句切了
            // 覆盖写 + 列级更新：并发唤醒回路正在改同一行的其它列，整行 updateById 会把它们打回旧值
            traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                    .eq(AiTrader::getId, trader.getId())
                    .set(AiTrader::getLearningNotes, output)
                    .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
            log.info("[Learn] 学习完成 traderId={} 工具调用{}次 笔记{}字",
                    trader.getId(), d.getToolCalls(), output.length());
        } catch (Exception e) {
            Throwable t = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            // ERROR 行公开上时间线：只存归类文案（上游原文可能带网关 URL/key），原文进日志
            String msg = t instanceof TimeoutException
                    ? prompts.get(lang, "learning.error.timeout", Map.of("seconds", timeoutSeconds))
                    : LlmErrorMessages.classify(t, prompts, lang);
            // LEARN 行已落库（异常出在之后写笔记那步）：这一轮学习本身是成功的，不该改写成 ERROR；
            // 而且 MP insert 已把自增 id 回填进 d，再 insert 必撞主键、异常直接逃出学习回路——
            // 与 TraderWakeupRunner 同款坑同款防护。只留日志。
            if (d.getId() != null) {
                log.warn("[Learn] LEARN行已存但写笔记失败 traderId={} msg={}", trader.getId(), msg, t);
                return;
            }
            d.setStatus(AiTraderDecision.STATUS_ERROR);
            d.setError(msg);
            d.setLatencyMs((int) (System.currentTimeMillis() - start));
            decisionMapper.insert(d);
            // 不计连败不暂停：学习失败没有资金风险，明天同侪还在
            log.warn("[Learn] 学习失败 traderId={} boundary={} msg={}", trader.getId(), boundaryMs, msg, t);
        }
    }

    /**
     * 缺哪些必需段（两段都缺就都列出来）——error 里要分得清是没写学习正文还是没做否定判断。
     * <p>
     * 段标记<b>只认本轮提示词那一门语言</b>的那套：提示词刚让它用英文标记，它交回中文标记就是没照
     * 格式走，按格式失守降级才对。两门都认的话，"英文提示词却输出中文"这种真失守会被悄悄放过。
     */
    List<String> missingMarks(String output, AgentLang lang) {
        List<String> missing = new ArrayList<>(2);
        String text = output == null ? "" : output;
        for (String key : REQUIRED_MARK_KEYS) {
            String mark = prompts.get(lang, key);
            if (!text.contains(mark)) {
                missing.add(mark);
            }
        }
        return missing;
    }

    /** ReactLoop 会话：返回模型最终文本；工具轨迹与用量随 decision 一并写入。 */
    private String runAgentSession(AiTrader trader, long boundaryMs, String leaderboard,
                                   AgentLang lang, AiTraderDecision d) throws Exception {
        // 用量统计包在最外层：ReAct 一轮要调模型很多次。工厂里的实例是跨唤醒缓存的，
        // 装饰器必须每轮新建，否则用量会跨轮累加
        UsageTrackingChatModel model = new UsageTrackingChatModel(modelFactory.modelFor(trader));
        // "它看了谁"是 LEARN 行在公开时间线上的观赏点，全靠这个收集器记
        ToolCallTraceHook trace = new ToolCallTraceHook();
        // 提示词里那句"≤N"是笔记篇幅的唯一约束（落库不裁），取值见 NoteBudget
        ResilientChatService chat = ResilientChatService.builder().model(model)
                .systemPrompt(prompts.get(lang, "learning.system",
                        Map.of("maxChars", NoteBudget.maxChars(lang))))
                // 工具描述也得跟语言走：@Tool 的 description 是编译期常量，这一层替它换
                .tools(localizedTools.of(lang,
                        new PeerInsightToolkit(peerInsightService, trader.getId(), lang)))
                // 不强制首轮调工具：排行榜已随开场白注入，首轮该做的正是"挑谁值得深看"这步推理
                .build();
        ReactLoop loop = ReactLoop.builder().chat(chat)
                .limiter(new ModelCallLimiter(MAX_MODEL_CALLS,
                        prompts.get(lang, "llm.callLimit.notExecuted"),
                        prompts.get(lang, "llm.callLimit.lastCall")))
                .trace(trace).build();

        String instruction = userPrompt(trader, boundaryMs, leaderboard, lang);
        // 虚拟线程 + FutureTask 承载超时；阻塞跑，不需要流
        FutureTask<String> task = new FutureTask<>(() ->
                finalReasoning(loop.run(List.of(new UserMessage(instruction)), null, null, null).messages(), lang));
        Thread.startVirtualThread(task);
        try {
            return task.get(timeoutSeconds, TimeUnit.SECONDS);
        } finally {
            task.cancel(true);
            // 轨迹与用量都落 finally：超时作废的那一轮，"看了谁"和烧掉的 token 一样真实发生过
            List<JSONObject> calls = trace.calls();
            d.setActionsJson(JSON.toJSONString(calls));
            d.setToolCalls(calls.size());
            UsageTrackingChatModel.UsageSnapshot usage = model.snapshot();
            d.setModelCalls(usage.modelCalls());
            d.setPromptTokens(usage.promptTokens());
            d.setCompletionTokens(usage.completionTokens());
            d.setTotalTokens(usage.totalTokens());
        }
    }

    /**
     * 学习正文：往前找最近一条有正文的助手消息，而不是死盯最后一条。
     * 保险丝在工具边收束时，末尾是纯 tool_call 的助手消息 + 未执行占位回执，正文都是空的。
     */
    private String finalReasoning(List<Message> messages, AgentLang lang) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AssistantMessage assistant
                    && assistant.getText() != null && !assistant.getText().isBlank()) {
                return assistant.getText();
            }
        }
        return prompts.get(lang, "learning.label.noOutput");
    }

    /** 开场白：三样注入齐活（排行榜/自己的复盘笔记/上一份学习笔记），然后把挑人这一步交回给模型。 */
    // 包私有非 private：输出语言硬收尾那条钉子（PromptMarkParsingTest）要拿成文验它在末尾
    String userPrompt(AiTrader trader, long boundaryMs, String leaderboard, AgentLang lang) {
        StringBuilder sb = new StringBuilder();
        sb.append(prompts.get(lang, "learning.label.opening",
                Map.of("time", TIME_FMT.format(Instant.ofEpochMilli(boundaryMs))))).append("\n\n");
        sb.append(leaderboard).append('\n');
        // 不给自己的复盘笔记，它会去学一堆跟自己毫无关系的东西。
        // 笔记是写入时那门语言落库的，切了语言旧笔记仍是旧语言——提示词里已明说"照读照用、输出用当前语言"
        sb.append(prompts.get(lang, "learning.label.ownMemory")).append('\n')
                .append(blank(trader.getMemory())
                        ? prompts.get(lang, "learning.label.ownMemoryEmpty") : trader.getMemory().strip())
                .append("\n\n");
        sb.append(prompts.get(lang, "learning.label.lastNotes")).append('\n')
                .append(blank(trader.getLearningNotes())
                        ? prompts.get(lang, "learning.label.lastNotesEmpty")
                        : trader.getLearningNotes().strip())
                .append("\n\n");
        sb.append(prompts.get(lang, "learning.label.closing"));
        // 输出语言硬收尾：用户消息最末一行，排在同侪材料（含别人给 trader 起的名字，不翻译）
        // 与旧笔记之后——它们可能是另一门语言
        sb.append('\n').append(prompts.get(lang, "learning.label.outputLanguage"));
        return sb.toString();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
