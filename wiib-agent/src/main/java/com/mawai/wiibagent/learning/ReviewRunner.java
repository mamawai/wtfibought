package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.llm.LlmClient;
import com.mawai.wiibagent.llm.LlmErrorMessages;
import com.mawai.wiibagent.llm.UsageTrackingChatModel;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.toolkit.BeijingTime;
import com.mawai.wiibagent.trader.TraderModelFactory;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderExperience;
import com.mawai.wiibcommon.enums.AgentLang;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 统一复盘回路：代码备料（战绩 / 论点战绩 / 本批单子的事实表和单笔复盘正文 / 行情 / 布防 / 账本）
 * → 一次模型调用写跨单子的分析 + 经验更新表（新增、发现、删除）
 * → 落账（{@link ExperienceLedger}：各笔单笔复盘的归类行和这篇补的合并后按仓位编号记到经验名下，连同本篇的新增、发现、删除）
 * → 给本批单子盖上这篇复盘的编号 → 代码结账（转正、降级、闲置退役）→ 账本渲染写回 ai_trader.memory。
 * REVIEW 决策行公开上时间线：正文 + 更新表 + 代码记账段；memory_after 存账本渲染快照。
 * <p>
 * 收做完单笔复盘、没进过统一复盘、平仓不晚于窗口右端的单子；行情、布防、撤单、权益的窗口整体晚 24 小时
 * （见 {@link #WINDOW_LAG_MS}）。归类来自单笔复盘；某笔做单笔复盘之后账本才新增的条目，由这篇给那一笔补归类，
 * 这篇输出里别的编号归类行不认。
 * 没有素材（没有可收的单子、窗口内也无开仓动作）静默跳过，窗口并入下次。
 * 失败语义：ERROR 行留痕、账本不动、本批单子不盖编号下次还在、不计连败；
 * 经验更新一行都认不出、也没写「本期无更新」也算失败，只是正文照存。
 * 只写了「本期无更新」的算成功，后面照常落行、落账、结账、渲染。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewRunner {

    /** 一篇复盘最多跑多久 */
    static final int REVIEW_TIMEOUT_SECONDS = 600;
    /** REVIEW 行的 interval 标记复盘节奏（wake_time=日线边界），与 trader 唤醒档位无关 */
    static final String REVIEW_INTERVAL_CODE = "1d";
    /** 行情、布防、撤单、权益的窗口右端比复盘时刻早多久：和单子等单笔复盘的时间一样，窗口才对得上这批单子 */
    static final long WINDOW_LAG_MS = DebriefRunner.WAIT_MS;

    private final ReviewMaterialAssembler assembler;
    private final TraderModelFactory modelFactory;
    private final AiTraderDecisionMapper decisionMapper;
    private final PromptCatalog prompts;
    private final UserLangResolver userLangResolver;
    private final ExperienceLedger ledger;
    private final ExperienceOpParser parser;
    private final TraderPlanStore planStore;

    /** 时限注入点：测试把它调短 */
    int timeoutSeconds = REVIEW_TIMEOUT_SECONDS;

    /** 点播与动作面板的准入预检：有没有素材要当场答复；查不动就当没有 */
    public boolean hasMaterial(AiTrader trader, long nowMs) {
        try {
            AiTraderDecision last = assembler.lastReview(trader.getId(), trader.getRoundNo());
            return assembler.hasNewMaterial(trader, windowFrom(last), nowMs - WINDOW_LAG_MS);
        } catch (Exception e) {
            log.warn("[Review] 素材预检失败 traderId={} msg={}", trader.getId(), e.toString());
            return false;
        }
    }

    /** 窗口左端接上一篇的右端；本局首篇从头 */
    private static long windowFrom(AiTraderDecision last) {
        return last == null ? 0 : last.getWakeTime() - WINDOW_LAG_MS;
    }

    /** 统一复盘入口，boundaryMs 是这篇的时刻（08:00 边界，或点播的那一刻）；无素材静默跳过 */
    public void review(AiTrader trader, long boundaryMs) {
        long start = System.currentTimeMillis();
        long deadline = start + timeoutSeconds * 1000L;
        // 语言查一次用到底：素材段名、系统提示、行首固定词必须是同一门
        AgentLang lang = userLangResolver.of(trader.getUserId());
        AiTraderDecision d = new AiTraderDecision();
        d.setTraderId(trader.getId());
        d.setRoundNo(trader.getRoundNo());
        d.setWakeTime(boundaryMs);
        d.setIntervalCode(REVIEW_INTERVAL_CODE);
        d.setKind(AiTraderDecision.KIND_REVIEW);
        d.setToolCalls(0);
        try {
            long fromMs = windowFrom(assembler.lastReview(trader.getId(), trader.getRoundNo()));
            long toMs = boundaryMs - WINDOW_LAG_MS;
            if (!assembler.hasNewMaterial(trader, fromMs, toMs)) {
                log.info("[Review] 无新交易素材跳过 traderId={} boundary={}", trader.getId(), boundaryMs);
                return;
            }
            UsageTrackingChatModel model = new UsageTrackingChatModel(modelFactory.modelFor(trader));
            ReviewMaterialAssembler.ReviewMaterial material = assembler.assemble(trader, fromMs, toMs, lang);
            String output = call(model, prompts.get(lang, "reviewer.system"),
                    userPrompt(trader, material, fromMs, toMs, boundaryMs, lang), d, deadline);
            if (output == null || output.isBlank()) {
                d.setStatus(AiTraderDecision.STATUS_ERROR);
                d.setError(prompts.get(lang, "reviewer.error.emptyOutput"));
                d.setLatencyMs((int) (System.currentTimeMillis() - start));
                decisionMapper.insert(d);
                log.warn("[Review] 模型输出为空 traderId={} boundary={}", trader.getId(), boundaryMs);
                return;
            }
            ExperienceOpParser.ReviewParsed parsed = parser.parse(output, lang);
            // 编号归类行只收补给"单笔复盘时还没有的条目"的那几对，别的不认
            Map<Long, List<ExperienceLedger.Classify>> gapLines = material.gapLines(parsed.ops());
            List<ExperienceLedger.Op> written = parsed.ops().stream()
                    .filter(op -> !(op instanceof ExperienceLedger.Classify)).toList();
            d.setReasoning(output.strip());
            d.setLatencyMs((int) (System.currentTimeMillis() - start));
            if (written.isEmpty() && gapLines.isEmpty() && !parsed.noUpdates()) {
                // 一行都认不出、也没写「本期无更新」：记成 ERROR，正文照存，账本不动；本批单子没盖编号，下次复盘还在
                d.setStatus(AiTraderDecision.STATUS_ERROR);
                d.setError(prompts.get(lang, "reviewer.error.noOps"));
                decisionMapper.insert(d);
                log.warn("[Review] 经验更新解析为空，账本不动 traderId={}", trader.getId());
                return;
            }
            d.setStatus(AiTraderDecision.STATUS_OK);
            // 先落行拿 id
            decisionMapper.insert(d);
            // 归类在前（各笔单笔复盘的，加这篇补的），本篇的新增、发现、删除在后
            List<ExperienceLedger.Op> ops = new ArrayList<>(material.classifies(gapLines));
            ops.addAll(written);
            ExperienceLedger.Applied applied = ledger.apply(trader.getId(), trader.getRoundNo(), d.getId(), ops, lang,
                    material.trades());
            planStore.markReviewed(material.planIds(), d.getId());
            List<ExperienceLedger.Change> changes = ledger.settle(trader.getId(), trader.getRoundNo(), d.getId(),
                    !material.trades().isEmpty(), lang);
            log.info("[Review] 复盘落账 traderId={} 本批单子{}笔 生效{}条 丢弃{}条 代码结账{}条",
                    trader.getId(), material.planIds().size(), applied.applied(), applied.dropped(), changes.size());
            writeMemory(trader, d, lang);
            // 展示用的追加段放最后，出错不耽误前面的账本渲染
            if (!changes.isEmpty() || !applied.overCap().isEmpty()) {
                appendNotes(d, changes, applied.overCap(), lang);
            }
        } catch (Exception e) {
            Throwable t = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            // ERROR 行公开上时间线：只存归类文案（上游原文可能带网关 URL/key），原文进日志
            String msg = t instanceof TimeoutException
                    ? prompts.get(lang, "reviewer.error.timeout", Map.of("seconds", timeoutSeconds))
                    : LlmErrorMessages.classify(t, prompts, lang);
            if (d.getId() != null) {
                // 复盘行已落、账本已记：出在后面的结账或渲染，不改写成 ERROR
                log.warn("[Review] 复盘已落行，后续步骤失败 traderId={} msg={}", trader.getId(), msg, t);
                return;
            }
            d.setStatus(AiTraderDecision.STATUS_ERROR);
            d.setError(msg);
            d.setLatencyMs((int) (System.currentTimeMillis() - start));
            decisionMapper.insert(d);
            // 不计连败不暂停：复盘失败没有资金风险，明天素材还在
            log.warn("[Review] 复盘失败 traderId={} boundary={} msg={}", trader.getId(), boundaryMs, msg, t);
        }
    }

    /** 复盘正文末尾追加代码写的两段，一行一条：转正、降级、退役的结账结果；名额满了没记进账本的新增 */
    private void appendNotes(AiTraderDecision d, List<ExperienceLedger.Change> changes, List<ExperienceLedger.Add> overCap,
                             AgentLang lang) {
        StringBuilder sb = new StringBuilder(d.getReasoning());
        if (!changes.isEmpty()) {
            sb.append("\n\n").append(prompts.get(lang, "reviewer.label.settledAppendix"));
            changes.forEach(c -> sb.append("\n- ").append(changeLine(c, lang)));
        }
        if (!overCap.isEmpty()) {
            sb.append("\n\n").append(prompts.get(lang, "reviewer.label.overCapAppendix"));
            overCap.forEach(a -> sb.append("\n- ").append(a.text()));
        }
        d.setReasoning(sb.toString());
        decisionMapper.update(null, new LambdaUpdateWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getId, d.getId())
                .set(AiTraderDecision::getReasoning, d.getReasoning()));
    }

    /** 一条结账结果：转正、降级带判它那一段的账，退役带闲置篇数 */
    private String changeLine(ExperienceLedger.Change c, AgentLang lang) {
        AiTraderExperience e = c.entry();
        ExperienceLedger.Tally t = c.tally();
        return switch (c.kind()) {
            case PROMOTED -> prompts.get(lang, "reviewer.label.settledPromoted", Map.of("id", e.getId(),
                    "n", t.segmentUnder(), "days", t.segmentDays(), "net", ExperienceLedger.money(t.segmentNet()),
                    "text", e.getText()));
            case DEMOTED -> prompts.get(lang, "reviewer.label.settledDemoted", Map.of("id", e.getId(),
                    "n", t.segmentUnder(), "net", ExperienceLedger.money(t.segmentNet()), "text", e.getText()));
            case RETIRED -> prompts.get(lang, "reviewer.label.settledRetired", Map.of("id", e.getId(),
                    "n", ExperienceLedger.IDLE_RETIRE, "text", e.getText()));
        };
    }

    /** 账本渲染文本写回 ai_trader.memory，并按列存进 REVIEW 行的 memory_after */
    private void writeMemory(AiTrader trader, AiTraderDecision d, AgentLang lang) {
        String rendered = ledger.writeMemory(trader, lang);
        d.setMemoryAfter(rendered);
        decisionMapper.update(null, new LambdaUpdateWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getId, d.getId())
                .set(AiTraderDecision::getMemoryAfter, rendered));
    }

    /** 虚拟线程承载超时，等到截止时刻为止；用量落 finally——超时作废的调用 token 也真烧了 */
    private String call(UsageTrackingChatModel model, String system, String user, AiTraderDecision d,
                        long deadline) throws Exception {
        LlmClient client = LlmClient.builder().model(model).systemPrompt(system).build();
        FutureTask<String> task = new FutureTask<>(() -> {
            ChatResponse resp = client.execute(List.of(new UserMessage(user)));
            return resp.getResult() == null ? "" : resp.getResult().getOutput().getText();
        });
        Thread.startVirtualThread(task);
        try {
            return task.get(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        } finally {
            task.cancel(true);
            UsageTrackingChatModel.UsageSnapshot usage = model.snapshot();
            d.setModelCalls(usage.modelCalls());
            d.setPromptTokens(usage.promptTokens());
            d.setCompletionTokens(usage.completionTokens());
            d.setTotalTokens(usage.totalTokens());
            d.setCachedTokens(usage.cachedTokens());
        }
    }

    /** 复盘的用户消息：窗口 + 主人指令 + 战绩 + 论点战绩 + 本批单子 + 行情 + 盯盘布防 + 账本 + 收尾 */
    // 包私有非 private：输出语言硬收尾那条钉子（PromptMarkParsingTest）要拿成文验它在末尾
    String userPrompt(AiTrader trader, ReviewMaterialAssembler.ReviewMaterial m, long fromMs, long toMs, long nowMs,
                      AgentLang lang) {
        String from = fromMs == 0 ? prompts.get(lang, "reviewer.label.windowStart")
                : BeijingTime.shortFormat(fromMs);
        StringBuilder sb = new StringBuilder();
        sb.append(prompts.get(lang, "reviewer.label.window", Map.of(
                "from", from, "to", BeijingTime.shortFormat(toMs), "now", BeijingTime.shortFormat(nowMs)))).append("\n\n");
        // 主人的交易指令是目标与边界，排在硬事实之前；主人亲笔原样注入不翻译
        sb.append(prompts.get(lang, "reviewer.label.ownerInstructions")).append('\n');
        sb.append(trader.getCustomPrompt() == null || trader.getCustomPrompt().isBlank()
                ? prompts.get(lang, "reviewer.label.ownerInstructionsEmpty") : trader.getCustomPrompt()).append("\n\n");
        sb.append(m.statsBlock()).append('\n');
        if (!m.playStatsBlock().isBlank()) {
            sb.append(m.playStatsBlock()).append('\n');
        }
        sb.append(m.casesBlock()).append('\n');
        sb.append(m.marketBlock()).append('\n');
        sb.append(m.watchBlock()).append('\n');
        sb.append(m.ledgerBlock()).append('\n');
        sb.append(prompts.get(lang, "reviewer.label.closing"));
        // 输出语言硬收尾：用户消息最末一行，排在素材与账本之后——它们可能是另一门语言
        sb.append('\n').append(prompts.get(lang, "reviewer.label.outputLanguage"));
        return sb.toString();
    }
}
