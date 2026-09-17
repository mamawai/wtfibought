package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.llm.LlmErrorMessages;
import com.mawai.wiibagent.llm.UsageTrackingChatModel;
import com.mawai.wiibagent.trader.TraderModelFactory;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.openai.errors.OpenAIInvalidDataException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * reviewer 复盘回路（自学习，看自己不看同侪；向同侪学习见 LearningRunner）：
 * 单次模型调用（无工具，素材由代码组装齐）→ 两段固定格式解析 →
 * 【本期复盘】进 REVIEW 决策行公开上时间线、【记忆更新】全文覆盖 ai_trader.memory。
 * 与 trader 只经 DB 解耦：这里写 memory，trader 每次唤醒只读注入，互相没有直接调用。
 * 失败语义：ERROR 行留痕、不动 memory、不计连败——复盘失败没有资金风险，不值得暂停机制。
 * <p>
 * 提示词与素材段名全在 {@link PromptCatalog} 的 {@code reviewer.*}，按 trader 主人的语言取；
 * 两段分隔符同样跟语言走，{@link #parse} 认的就是本轮提示词刚要求的那一套。
 * <p>
 * 系统提示词（{@code reviewer.system}）的设计意图，加语言时逐条对照着写：
 * 身份先于指令——给自己写交易日志的交易员，不是评价者，教训写给明天的自己；
 * 防自夸三件套——战绩数字只许复述、先找错误再找亮点、教训条数上限。
 * <p>
 * 学习宗旨：<b>复盘决策过程，不复盘单次运气；对错以主人的交易指令为尺子；每条经验带证据与样本数；恐惧与贪婪交给代码闸门。</b>
 * 针对的两个真实病：看到亏损就不敢开仓（负向偏置——所以教训强制二分类、该做没做与做错同罪、
 * 保守度自检）；把学到的当铁律盲信（确证幻觉——所以记忆分已验证/假设两栏、纪律本身可被证伪淘汰）。
 * 下期纪律不得与主人指令相抵触：它会写进 memory 每轮注入，否则复盘会和主人的指令对着干。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewRunner {

    /**
     * 复盘没有"下一根K线"的截止压力、又跑在停工窗口内，所以给足，与唤醒同档。
     * 它是全系统单次负载最重的调用：输入最大、一次性输出 3000+ 字、无工具分摊——
     * 预算给窄了，模型还在写就被判超时，这一轮素材白烧还留一条 ERROR。
     * 只是天花板不是配额：跑得快就早结束，停工窗口跟着早关。
     */
    static final int REVIEW_TIMEOUT_SECONDS = 600;
    /** REVIEW 行的 interval 标记复盘节奏（wake_time=日线边界），与 trader 唤醒档位无关 */
    static final String REVIEW_INTERVAL_CODE = "1d";
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ReviewMaterialAssembler assembler;
    private final TraderModelFactory modelFactory;
    private final AiTraderMapper traderMapper;
    private final AiTraderDecisionMapper decisionMapper;
    private final PromptCatalog prompts;
    private final UserLangResolver userLangResolver;

    /** 超时注入点：测试把默认预算缩短 */
    int timeoutSeconds = REVIEW_TIMEOUT_SECONDS;

    /**
     * 点播入口的准入预检：review() 无素材时是静默跳过的，而点播是异步跑的，
     * 跳过的话用户等半天再去时间线上扑空。烧不烧钱这件事必须当场答复。
     */
    public boolean hasMaterial(AiTrader trader, long toMs) {
        AiTraderDecision last = assembler.lastReview(trader.getId(), trader.getRoundNo());
        return assembler.hasNewMaterial(trader.getId(), trader.getRoundNo(),
                last == null ? 0 : last.getWakeTime(), toMs);
    }

    /** 日线边界复盘入口：素材窗口=(上次成功REVIEW, 本边界]；无新交易素材静默跳过不白烧钱。 */
    public void review(AiTrader trader, long boundaryMs) {
        AiTraderDecision last = assembler.lastReview(trader.getId(), trader.getRoundNo());
        long fromMs = last == null ? 0 : last.getWakeTime();
        if (!assembler.hasNewMaterial(trader.getId(), trader.getRoundNo(), fromMs, boundaryMs)) {
            log.info("[Review] 无新交易素材跳过 traderId={} boundary={}", trader.getId(), boundaryMs);
            return;
        }
        long start = System.currentTimeMillis();
        // 语言查一次用到底：素材段名、系统提示、分隔符必须是同一门，混着来模型立刻跟着混
        AgentLang lang = userLangResolver.of(trader.getUserId());
        String memoryMark = prompts.get(lang, "reviewer.mark.memory");
        AiTraderDecision d = new AiTraderDecision();
        d.setTraderId(trader.getId());
        d.setRoundNo(trader.getRoundNo());
        d.setWakeTime(boundaryMs);
        d.setIntervalCode(REVIEW_INTERVAL_CODE);
        d.setKind(AiTraderDecision.KIND_REVIEW);
        d.setToolCalls(0);
        try {
            // 观望门控（口径8）：纯观望且各币平静的窗口不烧钱，留 SKIPPED 说明缘由；
            // 大动的纯观望窗口不拦——那正是要复盘"错失"的素材。
            // 放 try 里：门控要查 sim 与本地K线，抖一下也得落 ERROR 行（review.started 承诺"不会没有下文"）
            if (assembler.quietHoldWindow(trader, fromMs, boundaryMs)) {
                d.setStatus(AiTraderDecision.STATUS_SKIPPED);
                d.setError(prompts.get(lang, "reviewer.error.quietSkip",
                        Map.of("pct", ReviewMaterialAssembler.QUIET_AMPLITUDE_PCT.toPlainString())));
                decisionMapper.insert(d);
                log.info("[Review] 纯观望且市场平静跳过 traderId={} boundary={}", trader.getId(), boundaryMs);
                return;
            }
            ReviewMaterialAssembler.ReviewMaterial material =
                    assembler.assemble(trader, fromMs, boundaryMs, lang);
            UsageTrackingChatModel model = new UsageTrackingChatModel(modelFactory.modelFor(trader));
            String output = callWithTimeout(model,
                    userPrompt(trader, material, fromMs, boundaryMs, last, lang), lang, d);
            if (output == null || output.isBlank()) {
                // 空输出直接落 ERROR 行：这句本来就是给用户看的，不走下面的异常归类
                d.setStatus(AiTraderDecision.STATUS_ERROR);
                d.setError(prompts.get(lang, "reviewer.error.emptyOutput"));
                d.setLatencyMs((int) (System.currentTimeMillis() - start));
                decisionMapper.insert(d);
                log.warn("[Review] 模型输出为空 traderId={} boundary={}", trader.getId(), boundaryMs);
                return;
            }
            Parsed parsed = parse(output, memoryMark);
            d.setStatus(AiTraderDecision.STATUS_OK);
            d.setReasoning(parsed.review());
            // 学习快照随 REVIEW 行存档：memory 是滚动覆盖的，历史版本只活在这一列（学习演进史）
            d.setMemoryAfter(parsed.memory());
            d.setLatencyMs((int) (System.currentTimeMillis() - start));
            decisionMapper.insert(d);
            if (parsed.memory() != null) {
                // 覆盖写 + 列级更新：trader 只读本列，learning 是唯一写方
                traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                        .eq(AiTrader::getId, trader.getId())
                        .set(AiTrader::getMemory, parsed.memory())
                        .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
                log.info("[Review] 复盘完成 traderId={} 已了结{}笔 memory={}字",
                        trader.getId(), material.closedTrades(), parsed.memory().length());
            } else {
                // 降级安全：一次格式失守不许污染记忆——REVIEW 行照存，memory 不动
                log.warn("[Review] 输出缺{}分隔符，REVIEW照存、memory不动 traderId={}",
                        memoryMark, trader.getId());
            }
        } catch (Exception e) {
            Throwable t = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            // ERROR 行公开上时间线：只存归类文案（上游原文可能带网关 URL/key），原文进日志
            String msg = t instanceof TimeoutException
                    ? prompts.get(lang, "reviewer.error.timeout", Map.of("seconds", timeoutSeconds))
                    : LlmErrorMessages.classify(t, prompts, lang);
            d.setStatus(AiTraderDecision.STATUS_ERROR);
            d.setError(msg);
            d.setLatencyMs((int) (System.currentTimeMillis() - start));
            decisionMapper.insert(d);
            // 不计连败不暂停：复盘失败没有资金风险，明天素材还在
            log.warn("[Review] 复盘失败 traderId={} boundary={} msg={}", trader.getId(), boundaryMs, msg, t);
        }
    }

    /** 虚拟线程承载超时；用量落 finally——超时作废的调用 token 也真烧了，不能不记。 */
    private String callWithTimeout(UsageTrackingChatModel model, String user, AgentLang lang,
                                   AiTraderDecision d) throws Exception {
        // 提示词里那句"≤N"是笔记篇幅的唯一约束（落库不裁），取值见 NoteBudget
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(prompts.get(lang, "reviewer.system",
                        Map.of("maxChars", NoteBudget.maxChars(lang)))), new UserMessage(user)));
        FutureTask<String> task = new FutureTask<>(() -> {
            ChatResponse resp;
            try {
                resp = model.call(prompt);
            } catch (OpenAIInvalidDataException e) {
                // SDK 重试盲区（读响应中断）单次补救，与 ResilientChatService.callPrimary 同款
                log.warn("[Review] 响应读取中断（SDK不重试此类失败），单次重试: {}", e.toString());
                resp = model.call(prompt);
            }
            return resp.getResult() == null ? "" : resp.getResult().getOutput().getText();
        });
        Thread.startVirtualThread(task);
        try {
            return task.get(timeoutSeconds, TimeUnit.SECONDS);
        } finally {
            task.cancel(true);
            UsageTrackingChatModel.UsageSnapshot usage = model.snapshot();
            d.setModelCalls(usage.modelCalls());
            d.setPromptTokens(usage.promptTokens());
            d.setCompletionTokens(usage.completionTokens());
            d.setTotalTokens(usage.totalTokens());
        }
    }

    record Parsed(String review, String memory) {
    }

    /**
     * 两段解析：按最后一个记忆更新标记切分。缺分隔符 → REVIEW 照存、memory 返回 null 不动
     * （降级安全）；复盘段意外为空时整篇当复盘存——公开留痕优先。两段都原样返回，
     * 记忆段的篇幅靠提示词里那句"≤N"（{@link NoteBudget}）约束，不在这裁。
     * <p>
     * 标记<b>只认本轮提示词那一门语言</b>的那条：提示词刚让它用英文标记，它交回中文标记就是没照格式
     * 走，按格式失守降级才对。这里若两门都认，"英文提示词却输出中文"这种真失守会被悄悄放过。
     */
    static Parsed parse(String output, String memoryMark) {
        int idx = output.lastIndexOf(memoryMark);
        if (idx < 0) {
            return new Parsed(output.strip(), null);
        }
        String review = output.substring(0, idx).strip();
        String memory = output.substring(idx + memoryMark.length()).strip();
        if (memory.isEmpty()) {
            return new Parsed(review.isEmpty() ? output.strip() : review, null);
        }
        return new Parsed(review.isEmpty() ? output.strip() : review, memory);
    }

    /**
     * 复盘的用户消息：主人的交易指令（评判尺子）+ 四块硬事实 + 上一期复盘 + 记忆笔记 + 收尾指令。
     * <p>
     * 只回注上一期全文（不是全部历史）：每篇复盘都已经把它的上一篇吸收进去了，所以
     * 给最近这一篇＝给了全部历史的滚动浓缩。把每期都堆进来只会越喂越长，模型抓不住重点。
     */
    // 包私有非 private：输出语言硬收尾那条钉子（PromptMarkParsingTest）要拿成文验它在末尾
    String userPrompt(AiTrader trader, ReviewMaterialAssembler.ReviewMaterial m,
                              long fromMs, long toMs, AiTraderDecision lastReview, AgentLang lang) {
        String from = fromMs == 0 ? prompts.get(lang, "reviewer.label.windowStart")
                : TIME_FMT.format(Instant.ofEpochMilli(fromMs));
        StringBuilder sb = new StringBuilder();
        sb.append(prompts.get(lang, "reviewer.label.window", Map.of(
                "from", from, "to", TIME_FMT.format(Instant.ofEpochMilli(toMs))))).append("\n\n");
        // 主人的交易指令是评判尺子，排在硬事实之前；主人亲笔原样注入不翻译
        sb.append(prompts.get(lang, "reviewer.label.ownerInstructions")).append('\n');
        sb.append(trader.getCustomPrompt() == null || trader.getCustomPrompt().isBlank()
                ? prompts.get(lang, "reviewer.label.ownerInstructionsEmpty") : trader.getCustomPrompt()).append("\n\n");
        sb.append(m.statsBlock()).append('\n');
        sb.append(m.tradesBlock()).append('\n');
        sb.append(m.timelineBlock()).append('\n');
        sb.append(m.pricePathBlock()).append('\n');
        if (lastReview != null && lastReview.getReasoning() != null && !lastReview.getReasoning().isBlank()) {
            sb.append(prompts.get(lang, "reviewer.label.lastReview", Map.of(
                            "time", TIME_FMT.format(Instant.ofEpochMilli(lastReview.getWakeTime())))))
                    .append('\n').append(lastReview.getReasoning()).append("\n\n");
        }
        sb.append(prompts.get(lang, "reviewer.label.memoryNotes")).append('\n');
        // 笔记是写入时那门语言落库的，切了语言旧笔记仍是旧语言——提示词里已明说"照读照用、输出用当前语言"
        sb.append(trader.getMemory() == null || trader.getMemory().isBlank()
                ? prompts.get(lang, "reviewer.label.memoryEmpty") : trader.getMemory()).append('\n');
        sb.append('\n').append(prompts.get(lang, "reviewer.label.closing"));
        // 输出语言硬收尾：用户消息最末一行，排在素材/上一期复盘/记忆笔记之后——它们可能是另一门语言
        sb.append('\n').append(prompts.get(lang, "reviewer.label.outputLanguage"));
        return sb.toString();
    }
}
