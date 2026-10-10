package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.llm.LlmErrorMessages;
import com.mawai.wiibagent.llm.UsageTrackingChatModel;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.trader.TraderModelFactory;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTraderExperience;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.openai.errors.OpenAIInvalidDataException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * reviewer 复盘回路：代码备料（战绩 / 论点战绩 / 案例事实表 / 行情 / 账本）→ 一次模型调用写案例分析 + 经验更新表
 * → 解析落账（{@link ExperienceLedger}）→ 账本渲染写回 ai_trader.memory → 有待复审的条目再调一次模型复审。
 * REVIEW 决策行公开上时间线：正文 + 更新表 + 复审结论；memory_after 存账本渲染快照。
 * <p>
 * 没有素材（窗口内无了结、无开仓动作）静默跳过，窗口并入下次。旧的自由文本记忆第一次跑时压成账本行（一次迁移调用）。
 * 迁移 / 复盘 / 复审三次调用共用一份总预算。
 * 失败语义：ERROR 行留痕、账本不动、不计连败；一行更新都解析不出 → 正文照存、账本不动。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewRunner {

    /** 整篇复盘的总预算：迁移 / 复盘 / 复审三次调用各用剩余的部分 */
    static final int REVIEW_TIMEOUT_SECONDS = 600;
    /** REVIEW 行的 interval 标记复盘节奏（wake_time=日线边界），与 trader 唤醒档位无关 */
    static final String REVIEW_INTERVAL_CODE = "1d";
    /** 迁移时最多压出几条 */
    static final int MIGRATE_MAX = 10;
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ReviewMaterialAssembler assembler;
    private final TraderModelFactory modelFactory;
    private final AiTraderDecisionMapper decisionMapper;
    private final PromptCatalog prompts;
    private final UserLangResolver userLangResolver;
    private final ExperienceLedger ledger;
    private final ExperienceOpParser parser;
    private final SimTradeClient simTradeClient;

    /** 总预算注入点：测试把默认预算缩短 */
    int timeoutSeconds = REVIEW_TIMEOUT_SECONDS;

    /** 点播与动作面板的准入预检：有没有素材要当场答复；查不动就当没有 */
    public boolean hasMaterial(AiTrader trader, long toMs) {
        try {
            AiTraderDecision last = assembler.lastReview(trader.getId(), trader.getRoundNo());
            return assembler.hasNewMaterial(trader, last == null ? 0 : last.getWakeTime(), toMs);
        } catch (Exception e) {
            log.warn("[Review] 素材预检失败 traderId={} msg={}", trader.getId(), e.toString());
            return false;
        }
    }

    /** 日线边界复盘入口：素材窗口=(上次成功 REVIEW, 本边界]；无素材静默跳过 */
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
            AiTraderDecision last = assembler.lastReview(trader.getId(), trader.getRoundNo());
            long fromMs = last == null ? 0 : last.getWakeTime();
            if (!assembler.hasNewMaterial(trader, fromMs, boundaryMs)) {
                log.info("[Review] 无新交易素材跳过 traderId={} boundary={}", trader.getId(), boundaryMs);
                return;
            }
            UsageTrackingChatModel model = new UsageTrackingChatModel(modelFactory.modelFor(trader));
            if (needsMigration(trader)) {
                // 迁出 0 条不动 memory，下次复盘再迁
                if (migrate(trader, lang, model, d, deadline) > 0) {
                    ledger.writeMemory(trader, lang);
                } else {
                    log.warn("[Review] 旧记忆一条都没压出来 traderId={}", trader.getId());
                }
            }
            ReviewMaterialAssembler.ReviewMaterial material = assembler.assemble(trader, fromMs, boundaryMs, lang);
            String output = call(model, prompts.get(lang, "reviewer.system"),
                    userPrompt(trader, material, fromMs, boundaryMs, lang), d, deadline);
            if (output == null || output.isBlank()) {
                d.setStatus(AiTraderDecision.STATUS_ERROR);
                d.setError(prompts.get(lang, "reviewer.error.emptyOutput"));
                d.setLatencyMs((int) (System.currentTimeMillis() - start));
                decisionMapper.insert(d);
                log.warn("[Review] 模型输出为空 traderId={} boundary={}", trader.getId(), boundaryMs);
                return;
            }
            List<ExperienceLedger.Op> ops = parser.parse(output, lang);
            d.setStatus(AiTraderDecision.STATUS_OK);
            d.setReasoning(output.strip());
            d.setLatencyMs((int) (System.currentTimeMillis() - start));
            if (ops.isEmpty()) {
                // 一行都解析不出：正文照存留痕，账本一个字不动
                d.setError(prompts.get(lang, "reviewer.error.noOps"));
                decisionMapper.insert(d);
                log.warn("[Review] 经验更新解析为空，账本不动 traderId={}", trader.getId());
                return;
            }
            // 先落行拿 id
            decisionMapper.insert(d);
            ExperienceLedger.Applied applied = ledger.apply(trader.getId(), trader.getRoundNo(), d.getId(), ops, lang,
                    material.positionIdsByPlay());
            log.info("[Review] 复盘落账 traderId={} 已了结{}笔 生效{}条 丢弃{}条",
                    trader.getId(), material.closedTrades(), applied.applied(), applied.dropped());
            writeMemory(trader, d, lang);

            List<AiTraderExperience> flagged = ledger.flagged(trader.getId(), trader.getRoundNo());
            if (!flagged.isEmpty()) {
                audit(trader, d, flagged, model, lang, start, deadline);
            }
        } catch (Exception e) {
            Throwable t = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            // ERROR 行公开上时间线：只存归类文案（上游原文可能带网关 URL/key），原文进日志
            String msg = t instanceof TimeoutException
                    ? prompts.get(lang, "reviewer.error.timeout", Map.of("seconds", timeoutSeconds))
                    : LlmErrorMessages.classify(t, prompts, lang);
            if (d.getId() != null) {
                // 复盘行已落、账本已记：出在后面的渲染或复审，不改写成 ERROR
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

    /**
     * 待复审的条目再调一次模型，四选一落账；复审失败只记错误文案，旗留到下期。
     * 拆分先入子条、满两条才退役父条。复审改了账本就重新渲染记忆；结论段追加在复盘正文末尾。
     */
    private void audit(AiTrader trader, AiTraderDecision d, List<AiTraderExperience> flagged,
                       UsageTrackingChatModel model, AgentLang lang, long start, long deadline) {
        // 已平仓位拉一次，各条复审素材共用
        Map<Long, FuturesPositionDTO> positions = new HashMap<>();
        for (FuturesPositionDTO p : simTradeClient.getClosedPositions(trader.getSimUserId(), ReviewMaterialAssembler.CLOSED_FETCH_LIMIT)) {
            positions.put(p.getId(), p);
        }
        StringBuilder user = new StringBuilder(prompts.get(lang, "reviewer.audit.opening")).append("\n\n");
        for (AiTraderExperience e : flagged) {
            user.append(assembler.auditBlock(trader, e, lang, positions)).append('\n');
        }
        user.append(prompts.get(lang, "reviewer.label.outputLanguage"));
        String output;
        try {
            output = call(model, prompts.get(lang, "reviewer.audit.system"), user.toString(), d, deadline);
        } catch (Exception e) {
            d.setError(prompts.get(lang, "reviewer.error.auditFailed"));
            d.setLatencyMs((int) (System.currentTimeMillis() - start));
            decisionMapper.updateById(d);
            log.warn("[Review] 复审调用失败 traderId={} msg={}", trader.getId(), e.toString());
            return;
        }
        ExperienceOpParser.AuditParsed parsed = parser.parseAudit(output == null ? "" : output, lang);
        int applied = 0;
        for (ExperienceLedger.Audit a : parsed.audits()) {
            boolean ok;
            if (ExperienceLedger.AUDIT_SPLIT.equals(a.kind())) {
                List<ExperienceLedger.Add> children = parsed.adds().stream()
                        .filter(x -> Objects.equals(x.sourceId(), a.id())).toList();
                ok = ledger.split(trader.getId(), trader.getRoundNo(), d.getId(), a, children, lang);
                if (!ok) {
                    log.info("[Review] 拆分未生效，旗留到下期 traderId={} id={} 子条{}条", trader.getId(), a.id(), children.size());
                }
            } else {
                ok = ledger.applyAudit(trader.getId(), trader.getRoundNo(), d.getId(), a, lang);
            }
            if (ok) {
                applied++;
            }
        }
        d.setReasoning(d.getReasoning() + "\n\n" + prompts.get(lang, "reviewer.label.auditAppendix") + "\n"
                + (output == null ? "" : output.strip()));
        d.setLatencyMs((int) (System.currentTimeMillis() - start));
        decisionMapper.updateById(d);
        if (applied > 0) {
            writeMemory(trader, d, lang);
        }
        log.info("[Review] 复审完成 traderId={} 举旗{}条 生效{}条", trader.getId(), flagged.size(), applied);
    }

    /** 账本渲染文本写回 ai_trader.memory，并按列存进 REVIEW 行的 memory_after */
    private void writeMemory(AiTrader trader, AiTraderDecision d, AgentLang lang) {
        String rendered = ledger.writeMemory(trader, lang);
        d.setMemoryAfter(rendered);
        decisionMapper.update(null, new LambdaUpdateWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getId, d.getId())
                .set(AiTraderDecision::getMemoryAfter, rendered));
    }

    /** 账本空着、memory 非空且不是账本渲染出来的：旧的自由文本记忆要先压成账本行 */
    private boolean needsMigration(AiTrader trader) {
        String memory = trader.getMemory();
        return memory != null && !memory.isBlank() && !ledger.isRendered(memory)
                && ledger.active(trader.getId(), trader.getRoundNo()).isEmpty()
                && ledger.retired(trader.getId(), trader.getRoundNo()).isEmpty();
    }

    /** 旧的自由文本记忆压成账本行，返回生效条数；压出来的条目没有复盘行可挂，created_review_id 为空 */
    private int migrate(AiTrader trader, AgentLang lang, UsageTrackingChatModel model,
                        AiTraderDecision d, long deadline) throws Exception {
        StringBuilder user = new StringBuilder();
        user.append(prompts.get(lang, "reviewer.label.ownerInstructions")).append('\n')
                .append(trader.getCustomPrompt() == null || trader.getCustomPrompt().isBlank()
                        ? prompts.get(lang, "reviewer.label.ownerInstructionsEmpty") : trader.getCustomPrompt())
                .append("\n\n")
                .append(prompts.get(lang, "reviewer.migrate.opening")).append('\n').append(trader.getMemory()).append("\n\n")
                .append(prompts.get(lang, "reviewer.migrate.closing")).append('\n')
                .append(prompts.get(lang, "reviewer.label.outputLanguage"));
        String output = call(model, prompts.get(lang, "reviewer.migrate.system", Map.of("max", MIGRATE_MAX)),
                user.toString(), d, deadline);
        List<ExperienceLedger.Op> ops = parser.parseOps(output == null ? "" : output, lang);
        ExperienceLedger.Applied applied = ledger.apply(trader.getId(), trader.getRoundNo(), null, ops, lang, Map.of());
        log.info("[Review] 旧记忆迁移 traderId={} 生效{}条 丢弃{}条", trader.getId(), applied.applied(), applied.dropped());
        return applied.applied();
    }

    /** 虚拟线程承载超时，预算＝总截止时刻剩下的秒数；用量落 finally——超时作废的调用 token 也真烧了。同一 model 实例多次调用用量累加 */
    private String call(UsageTrackingChatModel model, String system, String user, AiTraderDecision d,
                        long deadline) throws Exception {
        Prompt prompt = new Prompt(List.of(new SystemMessage(system), new UserMessage(user)));
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
            return task.get(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        } finally {
            task.cancel(true);
            UsageTrackingChatModel.UsageSnapshot usage = model.snapshot();
            d.setModelCalls(usage.modelCalls());
            d.setPromptTokens(usage.promptTokens());
            d.setCompletionTokens(usage.completionTokens());
            d.setTotalTokens(usage.totalTokens());
        }
    }

    /** 复盘的用户消息：主人指令 + 战绩 + 论点战绩 + 案例素材 + 行情 + 账本 + 收尾 */
    // 包私有非 private：输出语言硬收尾那条钉子（PromptMarkParsingTest）要拿成文验它在末尾
    String userPrompt(AiTrader trader, ReviewMaterialAssembler.ReviewMaterial m, long fromMs, long toMs, AgentLang lang) {
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
        if (!m.playStatsBlock().isBlank()) {
            sb.append(m.playStatsBlock()).append('\n');
        }
        sb.append(m.casesBlock()).append('\n');
        sb.append(m.marketBlock()).append('\n');
        sb.append(m.ledgerBlock()).append('\n');
        sb.append(prompts.get(lang, "reviewer.label.closing"));
        // 输出语言硬收尾：用户消息最末一行，排在素材与账本之后——它们可能是另一门语言
        sb.append('\n').append(prompts.get(lang, "reviewer.label.outputLanguage"));
        return sb.toString();
    }
}
