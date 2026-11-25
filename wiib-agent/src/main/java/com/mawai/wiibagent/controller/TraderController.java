package com.mawai.wiibagent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.dto.PositionHistoryDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderExperience;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import com.mawai.wiibcommon.entity.UserLedger;
import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.learning.ExperienceLedger;
import com.mawai.wiibagent.llm.LlmEndpointService;
import com.mawai.wiibagent.llm.SseChannel;
import com.mawai.wiibquant.external.sim.SimTradeApi;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibagent.trader.TradeRecordService;
import com.mawai.wiibagent.trader.TraderActionService;
import com.mawai.wiibagent.trader.TraderModelFactory;
import com.mawai.wiibagent.trader.TraderService;
import com.mawai.wiibagent.trader.prompt.TraderPromptAssembler;
import com.mawai.wiibagent.trader.trade.TraderRiskConfig;
import com.mawai.wiibagent.trader.trade.TraderWatchStore;
import com.mawai.wiibagent.trader.wakeup.TraderLiveHub;
import com.mawai.wiibagent.trader.wakeup.TraderScheduler;
import com.mawai.wiibagent.trader.wakeup.WakeIntervals;
import com.mawai.wiibagent.trader.wakeup.WakeWindow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * AI Trader：我的 trader 管理（每人最多 3 个；创建/配置/启停/重置）+ 公开竞技场（排行/详情/决策时间线/净值曲线）。
 * 竞技场读接口登录即可看（决策日志天生公开——观赏性是产品核心）；写操作只动自己的 trader：
 * 主人操作都是 /{id}/…，不归当前用户的 id 一律当"trader 不存在"。
 * <p>
 * 唤醒现场（SSE，事件名即 {@code event:}，data 是 JSON，帧的拼装在 {@code WakeTrace}）。
 * 工作流程只给主人看——下面两个接口都拒绝非主人，公开的只到决策正文为止：
 * <ul>
 *   <li>{@code GET /{id}/live} 现场流：
 *       run_start → prompt → 每次模型调用 model_start / token… / model_end → tool_result… → run_end；
 *       中途连上按当前状态回放，空闲只有心跳</li>
 *   <li>{@code GET /{id}/decisions/{decisionId}/trace} 落库轨迹原样返回；老行没有轨迹回 null</li>
 * </ul>
 */
@Slf4j
@Tag(name = "AI Trader")
@RestController
@RequestMapping("/api/ai/trader")
@RequiredArgsConstructor
public class TraderController {

    private final TraderService traderService;
    private final TraderModelFactory modelFactory;
    private final SimTradeClient simTradeClient;
    private final TraderPromptAssembler promptAssembler;
    private final LlmEndpointService endpointService;
    private final TraderActionService actionService;
    private final TradeRecordService tradeRecordService;
    private final UserLangResolver userLangResolver;
    private final MessageCatalog messages;
    private final TraderLiveHub liveHub;
    private final ExperienceLedger ledger;
    private final TraderScheduler scheduler;
    private final TraderWatchStore watchStore;

    // ========== 我的 trader ==========

    /**
     * 公开视图：任何登录用户可见的字段（model 是它当前用的端点的模型名，现解析）。key/baseUrl/自定义提示词绝不进公开视图。
     * wakeWindow 进公开视图：观众要看得懂"1h 档为什么半天不出一条决策"，null=全天
     */
    public record TraderPublicView(long id, String name, String model, String status, String pausedReason,
                                   String symbols, String intervalCode, int roundNo,
                                   BigDecimal equity, BigDecimal pnlPct, boolean mine, String wakeWindow) {
    }

    /**
     * 主人视图：公开视图 + 配置回显。llmEndpointId=这个 trader 选的端点，null=跟随默认端点；wakeWindow null=全天；
     * learnedRestrictionHold=账本里限制型规律能否作为观望理由；webSearchEnabled=联网搜索开关，端点也开了才真搜；
     * equityDropPct=权益回落警报的比例%，null=没启用
     */
    public record TraderOwnerView(TraderPublicView pub, Long llmEndpointId,
                                  String customPrompt,
                                  TraderSpec spec, boolean alertEnabled, BigDecimal alertThresholdMult,
                                  BigDecimal equityDropPct,
                                  boolean reviewEnabled, boolean learnedRestrictionHold,
                                  boolean webSearchEnabled, String wakeWindow) {
    }

    /** 仓位规格：配置回显与提示词预览共用一个形状，前端改一处两边同步。 */
    public record TraderSpec(int leverageMin, int leverageMax, BigDecimal marginPctMax,
                             boolean allowMultiPosition, boolean allowHedge) {
        TraderRiskConfig toConfig() {
            return new TraderRiskConfig(leverageMin, leverageMax, marginPctMax, allowMultiPosition, allowHedge);
        }

        static TraderSpec of(AiTrader t) {
            TraderRiskConfig c = TraderRiskConfig.of(t);
            return new TraderSpec(c.leverageMin(), c.leverageMax(), c.marginPctMax(),
                    c.allowMultiPosition(), c.allowHedge());
        }
    }

    @GetMapping("/mine")
    @Operation(summary = "我名下的全部trader（按id升序，可能为空；含配置回显）")
    public Result<List<TraderOwnerView>> mine(@CurrentUserId long userId) {
        return Result.ok(traderService.listMine(userId).stream().map(t -> ownerView(t, userId)).toList());
    }

    private TraderOwnerView ownerView(AiTrader t, long userId) {
        return new TraderOwnerView(publicView(t, userId, modelName(t)),
                t.getLlmEndpointId(),
                t.getCustomPrompt(), TraderSpec.of(t),
                !Boolean.FALSE.equals(t.getAlertEnabled()),
                t.getAlertThresholdMult() == null ? BigDecimal.ONE : t.getAlertThresholdMult(),
                t.getEquityDropPct(),
                !Boolean.FALSE.equals(t.getReviewEnabled()),
                Boolean.TRUE.equals(t.getLearnedRestrictionHold()),
                Boolean.TRUE.equals(t.getWebSearchEnabled()),
                t.getWakeWindow());
    }

    /** 提示词预览的入参：规格项太多，走 POST 带 body 比堆十个 query 参数干净。 */
    public record PromptPreviewRequest(String intervalCode, String symbols, TraderSpec spec, String wakeWindow) {
    }

    @PostMapping("/prompt-template")
    @Operation(summary = "平台系统提示词预览（与唤醒组装同一份文本）")
    public Result<String> promptTemplate(@CurrentUserId long userId, @RequestBody PromptPreviewRequest req) {
        String interval = req.intervalCode() == null || req.intervalCode().isBlank() ? WakeIntervals.DEFAULT : req.intervalCode();
        String symbols = req.symbols() == null || req.symbols().isBlank() ? "BTCUSDT" : req.symbols();
        TraderRiskConfig cfg = req.spec() == null
                ? TraderRiskConfig.of(new AiTrader()) : req.spec().toConfig();
        String windowText;
        try {
            WakeWindow w = WakeWindow.parse(req.wakeWindow());
            windowText = w == null ? null : w.text();
        } catch (IllegalArgumentException e) {
            windowText = null; // 预览只是看文本，时段还没填对就按全天预览，保存时才真校验
        }
        // 预览也按当前用户语言出：MyTrader 页展示的就是这份文本，英文用户不该看到中文模板。
        // 收尾格式块一并带上：它是系统强制的，预览里不露面用户就不知道自己被强制了什么
        AgentLang lang = userLangResolver.of(userId);
        return Result.ok(promptAssembler.templatePreview(lang, interval, symbols, cfg, windowText));
    }

    /** 按用户的 agent 语言取，与模板预览同一门，不跟界面语言 */
    @GetMapping("/default-instructions")
    @Operation(summary = "平台默认交易指令（新建 trader 时预填进指令框的那段）")
    public Result<String> defaultInstructions(@CurrentUserId long userId) {
        return Result.ok(promptAssembler.defaultInstructions(userLangResolver.of(userId)));
    }

    /** llmEndpointId：端点库里的一条，空=跟随用户默认端点；equityDropPct：权益回落警报的比例%，空=不启用 */
    public record UpsertRequest(String name, String symbols, String intervalCode, String customPrompt,
                                Long llmEndpointId, TraderSpec spec,
                                Boolean alertEnabled, BigDecimal alertThresholdMult, BigDecimal equityDropPct,
                                Boolean reviewEnabled, Boolean learnedRestrictionHold,
                                Boolean webSearchEnabled, String wakeWindow) {
        TraderService.UpsertReq toReq() {
            TraderSpec s = spec;
            return new TraderService.UpsertReq(name, symbols, intervalCode, customPrompt,
                    llmEndpointId,
                    s == null ? null : s.leverageMin(), s == null ? null : s.leverageMax(),
                    s == null ? null : s.marginPctMax(),
                    s == null ? null : s.allowMultiPosition(), s == null ? null : s.allowHedge(),
                    alertEnabled, alertThresholdMult, equityDropPct, reviewEnabled, learnedRestrictionHold,
                    webSearchEnabled, wakeWindow);
        }
    }

    @PostMapping
    @Operation(summary = "创建trader（每人最多3个、名下不重名；连通性校验→入库→开sim子账户注资10000），返回新trader的id")
    public Result<Long> create(@CurrentUserId long userId, @RequestBody UpsertRequest req) {
        TraderService.CreateResult r = traderService.create(userId, req.toReq());
        return r.error() == null ? Result.ok(r.id()) : Result.fail(r.error());
    }

    @PutMapping("/{id}/config")
    @Operation(summary = "改配置（提示词改完下一根K线生效）")
    public Result<Void> updateConfig(@CurrentUserId long userId, @PathVariable long id, @RequestBody UpsertRequest req) {
        String err = traderService.updateConfig(userId, id, req.toReq());
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    @PostMapping("/{id}/start")
    @Operation(summary = "启动")
    public Result<Void> start(@CurrentUserId long userId, @PathVariable long id) {
        String err = traderService.start(userId, id);
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    @PostMapping("/{id}/pause")
    @Operation(summary = "暂停")
    public Result<Void> pause(@CurrentUserId long userId, @PathVariable long id) {
        String err = traderService.pause(userId, id);
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    /** carryNotes 空=true（带入笔记到新局）；false 只清生效版本，历届存档保留 */
    public record ResetRequest(Boolean carryNotes) {
    }

    @PostMapping("/{id}/reset")
    @Operation(summary = "重置开新局（旧账户挂单全撤、仓位全平，round+1，新子账户注资10000，历史留档；唤醒或复盘中拒；carryNotes=false不带入经验账本）")
    public Result<Void> reset(@CurrentUserId long userId, @PathVariable long id,
                              @RequestBody(required = false) ResetRequest req) {
        boolean carry = req == null || req.carryNotes() == null || req.carryNotes();
        String err = traderService.reset(userId, id, carry);
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    /**
     * confirmName 走 query 不走 body：DELETE 带 body 规范允许但一路上的代理不一定转发，
     * 而这就是一个短名字，没有藏进 body 的必要。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除我的一个 trader（决策/计划/每局sim子账户全物理删除，竞技场里彻底消失，名额空出来可再建；需名字确认，唤醒中拒）")
    public Result<Void> delete(@CurrentUserId long userId, @PathVariable long id,
                               @RequestParam(required = false) String confirmName) {
        String err = traderService.delete(userId, id, confirmName);
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    /** stale 开关请求体。包装 Boolean：缺字段是格式错误要拒，不能被 Jackson 静默填成 false 当"取消忽略"执行 */
    public record PlanStaleRequest(Boolean stale) {
    }

    @PostMapping("/{id}/plan/{planId}/stale")
    @Operation(summary = "标记/取消忽略一笔已了结交易（仅本人、仅CLOSED；忽略后AI统计与复盘不再参考，公开记录不变）")
    public Result<Void> setPlanStale(@CurrentUserId long userId, @PathVariable long id, @PathVariable long planId,
                                     @RequestBody PlanStaleRequest req) {
        if (req.stale() == null) {
            return Result.fail(messages.get("trader.plan.staleRequired"));
        }
        String err = traderService.setPlanStale(userId, id, planId, req.stale());
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    // ========== 经验账本（主人管理） ==========

    /**
     * 主人页账本一条：条目本身 + 代码按名下单子算的账（主人标了不算数的单子不算）。
     * shown＝trader 现在看得到它：现役的规律，或破满 2 次的现役执行缺口（按开仓那一轮数，同一轮开的几笔算一次）
     */
    public record ExperienceView(long id, int roundNo, String playType, String kind, String slot, String direction,
                                 String text, String evidence, String status, boolean pinned, String retireReason,
                                 Long sourceId, int citedOpen, int citedHold, int segmentNo, int idleReviews,
                                 LocalDateTime createdAt, boolean shown, ExperienceLedger.Tally tally) {
    }

    @GetMapping("/{id}/experiences")
    @Operation(summary = "我的 trader 的经验账本（仅主人；本局全部条目：现役、发现、退役），每条带代码按名下单子算的账")
    public Result<List<ExperienceView>> experiences(@CurrentUserId long userId, @PathVariable long id) {
        AiTrader t = traderService.owned(userId, id);
        if (t == null) {
            return Result.fail(messages.get("trader.notFound"));
        }
        List<AiTraderExperience> all = ledger.all(t.getId(), t.getRoundNo());
        Map<Long, ExperienceLedger.Tally> tallies = ledger.tallies(all);
        return Result.ok(all.stream().map(e -> experienceView(e, tallies.get(e.getId()))).toList());
    }

    private static ExperienceView experienceView(AiTraderExperience e, ExperienceLedger.Tally tally) {
        return new ExperienceView(e.getId(), e.getRoundNo(), e.getPlayType(), e.getKind(), e.getSlot(), e.getDirection(),
                e.getText(), e.getEvidence(), e.getStatus(), e.getPinned(), e.getRetireReason(),
                e.getSourceId(), e.getCitedOpen(), e.getCitedHold(), e.getSegmentNo(), e.getIdleReviews(), e.getCreatedAt(),
                AiTraderExperience.STATUS_ACTIVE.equals(e.getStatus()) && ExperienceLedger.shown(e, tally), tally);
    }

    /** 包装 Boolean：缺字段是格式错误要拒 */
    public record PinRequest(Boolean pinned) {
    }

    public record RetireRequest(String reason) {
    }

    @PostMapping("/{id}/experience/{eid}/pin")
    @Operation(summary = "钉住/取消钉住一条经验（钉住的不会被代码自动降级或退役，复盘也删不掉）")
    public Result<Void> pinExperience(@CurrentUserId long userId, @PathVariable long id, @PathVariable long eid,
                                      @RequestBody PinRequest req) {
        if (req.pinned() == null) {
            return Result.fail(messages.get("trader.experience.pinnedRequired"));
        }
        return experienceOp(userId, id, t -> ledger.pin(t.getId(), eid, req.pinned()));
    }

    @PostMapping("/{id}/experience/{eid}/retire")
    @Operation(summary = "主人退役一条经验（留在退役名单，不再注入；理由会给复盘看）")
    public Result<Void> retireExperience(@CurrentUserId long userId, @PathVariable long id, @PathVariable long eid,
                                         @RequestBody RetireRequest req) {
        if (req.reason() == null || req.reason().isBlank()) {
            return Result.fail(messages.get("trader.experience.reasonRequired"));
        }
        return experienceOp(userId, id, t -> ledger.retireByOwner(t.getId(), eid, req.reason()));
    }

    @PostMapping("/{id}/experience/{eid}/revive")
    @Operation(summary = "把退役的经验恢复为现役")
    public Result<Void> reviveExperience(@CurrentUserId long userId, @PathVariable long id, @PathVariable long eid) {
        return experienceOp(userId, id, t -> ledger.revive(t.getId(), eid));
    }

    @DeleteMapping("/{id}/experience/{eid}")
    @Operation(summary = "物理删除一条经验及其名下单子")
    public Result<Void> deleteExperience(@CurrentUserId long userId, @PathVariable long id, @PathVariable long eid) {
        return experienceOp(userId, id, t -> ledger.delete(t.getId(), eid));
    }

    /** 主人改了账本就重渲染 memory，trader 下一轮读到的是改后的 */
    private Result<Void> experienceOp(long userId, long traderId, Predicate<AiTrader> op) {
        AiTrader t = traderService.owned(userId, traderId);
        if (t == null) {
            return Result.fail(messages.get("trader.notFound"));
        }
        if (!op.test(t)) {
            return Result.fail(messages.get("trader.experience.notFound"));
        }
        ledger.writeMemory(t, userLangResolver.of(userId));
        return Result.ok(null);
    }

    // ========== 动作面板（留言 / 手动唤醒 / 点播复盘） ==========
    // 三个动作的执行入口只有这里。对话轨的工具只把表单推给用户看，模型碰不到执行那一步。
    // 一律 Result.ok(ActionResult)：业务结果（被拦下、无素材跳过）要显示在卡片里，
    // 走 Result.fail 会被前端当成接口报错弹 toast。

    @GetMapping("/{id}/action-panel")
    @Operation(summary = "动作面板状态：上次/下次唤醒、留言剩余轮次、可否唤醒与复盘")
    public Result<TraderActionService.ActionPanel> actionPanel(@CurrentUserId long userId, @PathVariable long id) {
        TraderActionService.ActionPanel panel = actionService.panel(userId, id);
        return panel == null ? Result.fail(messages.get("trader.notFound")) : Result.ok(panel);
    }

    @PostMapping("/{id}/note")
    @Operation(summary = "给 trader 留言（覆盖未读的那条，按轮次逐轮注入）")
    public Result<TraderActionService.ActionResult> saveNote(@CurrentUserId long userId, @PathVariable long id,
                                                             @RequestBody NoteRequest req) {
        return Result.ok(actionService.saveNote(userId, id, req.note(), req.rounds()));
    }

    @DeleteMapping("/{id}/note")
    @Operation(summary = "撤回还没被读走的留言")
    public Result<TraderActionService.ActionResult> clearNote(@CurrentUserId long userId, @PathVariable long id) {
        return Result.ok(actionService.clearNote(userId, id));
    }

    @PostMapping("/{id}/wake")
    @Operation(summary = "手动唤醒（真实执行一次决策，可能开/平仓）")
    public Result<TraderActionService.ActionResult> wake(@CurrentUserId long userId, @PathVariable long id) {
        return Result.ok(actionService.wake(userId, id));
    }

    @PostMapping("/{id}/review")
    @Operation(summary = "点播复盘（异步跑；无新素材则跳过且不消耗模型调用）")
    public Result<TraderActionService.ActionResult> review(@CurrentUserId long userId, @PathVariable long id) {
        return Result.ok(actionService.review(userId, id));
    }

    @PostMapping("/{id}/debrief/{positionId}")
    @Operation(summary = "立即复盘一笔已平仓的单子（异步跑；复盘成功过的、没绑计划的、忽略了的不做）")
    public Result<TraderActionService.ActionResult> debrief(@CurrentUserId long userId, @PathVariable long id,
                                                             @PathVariable long positionId) {
        return Result.ok(actionService.debrief(userId, id, positionId));
    }

    /** rounds 空=1 轮（等同"念一次就清"）；越界由 service 钳到 1~24 */
    public record NoteRequest(String note, Integer rounds) {
    }

    // ========== 公开竞技场 ==========

    @GetMapping("/arena")
    @Operation(summary = "竞技场列表（全部trader按收益率排序）")
    public Result<List<TraderPublicView>> arena() {
        long viewer = viewerId();
        List<AiTrader> all = traderService.all();
        // 模型名按 trader 批量解析，不在循环里逐个查
        Map<Long, UserLlmEndpoint> endpoints = endpointService.resolveForTraders(all);
        return Result.ok(all.stream()
                .map(t -> publicView(t, viewer, endpoints.containsKey(t.getId()) ? endpoints.get(t.getId()).getModel() : null))
                .sorted((a, b) -> b.pnlPct().compareTo(a.pnlPct()))
                .toList());
    }

    /**
     * 详情视图：公开视图 + 实时持仓/挂单 + 本局存活计划 + 经验账本。
     * memory=账本渲染文本，trader 每次唤醒真正读到的东西；lastReviewAt 是最近一次复盘写 memory 的时刻（ms），没有=null。
     * 公开口径与时间线一致：REVIEW 行本就带 memoryAfter 快照。
     * nextWakeAt=下次例行唤醒时刻，不在运行中为 null；watches=本局还在盯的条件（参数本就在时间线的 set_watch 动作里公开）。
     */
    public record TraderDetailView(TraderPublicView trader,
                                   List<FuturesPositionDTO> positions,
                                   List<FuturesOrderResponse> pendingOrders,
                                   List<AiTraderPlan> plans,
                                   String memory, Long lastReviewAt,
                                   Long nextWakeAt, List<AiTraderWatch> watches) {
    }

    @GetMapping("/{id}")
    @Operation(summary = "trader详情（当前持仓/挂单实时现查 + 各持仓的交易计划 + 经验账本）")
    public Result<TraderDetailView> detail(@PathVariable long id) {
        long viewer = viewerId();
        AiTrader t = traderService.byId(id);
        if (t == null) {
            return Result.fail(ErrorCode.SYSTEM_ERROR.getCode(), messages.get("trader.notFound"));
        }
        List<FuturesPositionDTO> positions = List.of();
        List<FuturesOrderResponse> pending = List.of();
        List<AiTraderPlan> plans = traderService.plans(t);
        try {
            positions = simTradeClient.getAllPositions(t.getSimUserId());
            pending = simTradeClient.getPendingOrders(t.getSimUserId(), null);
            // 计划各档是唤醒开头对账时记的，两次唤醒之间成交或撤掉的按订单的最新状态给
            traderService.overlayEntries(t, plans, pending);
        } catch (Exception e) {
            log.warn("[Trader] 详情持仓查询失败 traderId={} msg={}", id, e.getMessage());
        }
        Long nextWakeAt = AiTrader.STATUS_RUNNING.equals(t.getStatus()) ? scheduler.nextRoutineWakeAt(t) : null;
        return Result.ok(new TraderDetailView(publicView(t, viewer, modelName(t)), positions, pending,
                plans, t.getMemory(), traderService.latestReviewTime(id),
                nextWakeAt, watchStore.armed(id, t.getRoundNo())));
    }

    @GetMapping("/{id}/decisions")
    @Operation(summary = "决策时间线（倒序分页，before传上一页最旧wakeTime；from/to 为 wakeTime 区间 [from,to)；wakeOnly=true 只要真醒来的那几种行，复盘和预案执行不要）")
    public Result<List<AiTraderDecision>> decisions(@PathVariable long id,
                                                    @RequestParam(defaultValue = "50") int limit,
                                                    @RequestParam(required = false) Long before,
                                                    @RequestParam(required = false) Integer round,
                                                    @RequestParam(required = false) Long from,
                                                    @RequestParam(required = false) Long to,
                                                    @RequestParam(defaultValue = "false") boolean wakeOnly) {
        return Result.ok(traderService.decisions(id, limit, before, round, from, to,
                wakeOnly ? AiTraderDecision.WAKE_KINDS : null));
    }

    // ========== 唤醒现场 ==========

    @GetMapping(value = "/{id}/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "trader 唤醒现场流（只有主人能连；中途连上回放当前状态）")
    public SseEmitter live(@PathVariable long id, HttpServletResponse response) {
        SseChannel.noProxyBuffering(response);
        AiTrader t = traderService.byId(id);
        if (t == null) {
            // 抛成 JSON 而不是开流：前端 getSse 见到 JSON 就按接口报错处理
            throw new BizException(messages.get("trader.notFound"));
        }
        if (t.getUserId() != StpUtil.getLoginIdAsLong()) {
            throw new BizException(messages.get("trader.notOwner"));
        }
        return liveHub.subscribeTrader(id);
    }

    @GetMapping("/{id}/decisions/{decisionId}/trace")
    @Operation(summary = "一条决策的过程轨迹（trace_json 原样；只给主人，别人与没有轨迹一样回 null）")
    public Result<JsonNode> decisionTrace(@PathVariable long id, @PathVariable long decisionId) {
        AiTraderDecision d = traderService.trace(decisionId);
        // 非主人回 null 不报错：轨迹入口本就藏在主人才见得到的按钮后面，报错反倒把"有这东西"讲出去了
        if (d == null || d.getTraderId() != id
                || traderService.byId(id).getUserId() != StpUtil.getLoginIdAsLong()) {
            return Result.ok(null);
        }
        return Result.ok(MAPPER.readTree(d.getTraceJson()));
    }

    @GetMapping("/{id}/token-usage")
    @Operation(summary = "决策token合计（三参数语义同 /decisions：round缺省=当前局，[from,to) 为 wakeTime 区间）")
    public Result<Long> tokenUsage(@PathVariable long id,
                                   @RequestParam(required = false) Integer round,
                                   @RequestParam(required = false) Long from,
                                   @RequestParam(required = false) Long to) {
        return Result.ok(traderService.sumTokens(id, round, from, to));
    }

    @GetMapping("/{id}/trades")
    @Operation(summary = "已了结交易（当前局；论点→结局配对 + 开/平仓那一轮的决策全文）")
    public Result<List<TradeRecordService.TradeRecord>> trades(@PathVariable long id) {
        AiTrader t = traderService.byId(id);
        if (t == null) {
            return Result.fail(ErrorCode.SYSTEM_ERROR.getCode(), messages.get("trader.notFound"));
        }
        try {
            return Result.ok(tradeRecordService.closedTrades(t));
        } catch (Exception e) {
            // 与 detail 同口径：sim 抖一下不该让整页红，空列表 + warn
            log.warn("[Trader] 已了结交易查询失败 traderId={} msg={}", id, e.getMessage());
            return Result.ok(List.of());
        }
    }

    /**
     * 当前局子账户的资金流水，公开：子账户登不了站，账单只能从这里看。
     * 查不动就报错，不回空表——空表是"翻到底了"的意思
     */
    @GetMapping("/{id}/ledger")
    @Operation(summary = "资金账单（当前局子账户的资金流水；游标翻页，id 倒序，beforeId 不传取最新一页，"
            + "返回空数组即到底；limit 默认 50、封顶 100）")
    public Result<List<UserLedger>> ledger(@PathVariable long id,
                                           @RequestParam(required = false) Long beforeId,
                                           @RequestParam(defaultValue = "50") int limit) {
        AiTrader t = traderService.byId(id);
        if (t == null) {
            return Result.fail(ErrorCode.SYSTEM_ERROR.getCode(), messages.get("trader.notFound"));
        }
        return Result.ok(simTradeClient.getLedger(t.getSimUserId(), beforeId, limit));
    }

    /** 当前局子账户已平仓位的历史，公开，和资金账单一样只能从这里看 */
    @GetMapping("/{id}/position-history")
    @Operation(summary = "合约持仓历史（当前局子账户已平的仓位，一行一笔完整仓位，带每一次成交；pageSize 默认 20、封顶 100）")
    public Result<SimTradeApi.Paged<PositionHistoryDTO>> positionHistory(@PathVariable long id,
                                                                         @RequestParam(defaultValue = "1") int pageNum,
                                                                         @RequestParam(defaultValue = "20") int pageSize) {
        AiTrader t = traderService.byId(id);
        if (t == null) {
            return Result.fail(ErrorCode.SYSTEM_ERROR.getCode(), messages.get("trader.notFound"));
        }
        return Result.ok(simTradeClient.getPositionHistory(t.getSimUserId(), pageNum, pageSize));
    }

    /** at=这一行落库的时刻(ms)，权益就是那一刻取的 */
    public record EquityPoint(long at, BigDecimal equity) {
    }

    @GetMapping("/{id}/equity-curve")
    @Operation(summary = "净值曲线（round缺省=当前局）")
    public Result<List<EquityPoint>> equityCurve(@PathVariable long id,
                                                 @RequestParam(required = false) Integer round) {
        AiTrader t = traderService.byId(id);
        if (t == null) {
            return Result.fail(ErrorCode.SYSTEM_ERROR.getCode(), messages.get("trader.notFound"));
        }
        return Result.ok(traderService.equityCurve(t, round).stream()
                .map(d -> new EquityPoint(TradePairing.msOf(d.getCreatedAt()), d.getEquity()))
                .toList());
    }

    /** 竞技场看客是谁：游客给 -1，publicView 里 mine 永远对不上 */
    private static long viewerId() {
        return StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : -1L;
    }

    /** 单个 trader 当前端点的模型名；用户把端点删光了给 null（前端显示"未配置"） */
    private String modelName(AiTrader t) {
        UserLlmEndpoint e = modelFactory.endpointFor(t);
        return e == null ? null : e.getModel();
    }

    private TraderPublicView publicView(AiTrader t, long viewerUserId, String model) {
        BigDecimal equity = traderService.latestEquity(t);
        return new TraderPublicView(t.getId(), t.getName(), model, t.getStatus(), t.getPausedReason(),
                t.getSymbols(), t.getIntervalCode(), t.getRoundNo(),
                equity.setScale(2, RoundingMode.HALF_UP), TraderService.pnlPct(equity),
                t.getUserId() == viewerUserId, t.getWakeWindow());
    }
}
