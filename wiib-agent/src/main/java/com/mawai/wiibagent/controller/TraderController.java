package com.mawai.wiibagent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.UserLlmBinding;
import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.llm.LlmEndpointService;
import com.mawai.wiibagent.llm.SseChannel;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.trader.TradeRecordService;
import com.mawai.wiibagent.trader.TraderActionService;
import com.mawai.wiibagent.trader.TraderService;
import com.mawai.wiibagent.trader.prompt.TraderPromptAssembler;
import com.mawai.wiibagent.trader.trade.TraderRiskConfig;
import com.mawai.wiibagent.trader.wakeup.TraderLiveHub;
import com.mawai.wiibagent.trader.wakeup.WakeTrace;
import com.mawai.wiibagent.trader.wakeup.WakeWindow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * AI Trader：我的 trader 管理（创建/配置/启停/重置）+ 公开竞技场（排行/详情/决策时间线/净值曲线）。
 * 竞技场读接口登录即可看（决策日志天生公开——观赏性是产品核心）；写操作只动自己的 trader。
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
    private final SimTradeClient simTradeClient;
    private final TraderPromptAssembler promptAssembler;
    private final LlmEndpointService endpointService;
    private final TraderActionService actionService;
    private final TradeRecordService tradeRecordService;
    private final UserLangResolver userLangResolver;
    private final MessageCatalog messages;
    private final TraderLiveHub liveHub;

    // ========== 我的 trader ==========

    /**
     * 公开视图：任何登录用户可见的字段（model 是它当前用的端点的模型名，现解析）。key/baseUrl/自定义提示词绝不进公开视图。
     * wakeWindow 进公开视图：观众要看得懂"15m 档为什么半天不出一条决策"，null=全天
     */
    public record TraderPublicView(long id, String name, String model, String status, String pausedReason,
                                   String symbols, String intervalCode, int roundNo,
                                   BigDecimal equity, BigDecimal pnlPct, boolean mine, String wakeWindow) {
    }

    /** 主人视图：公开视图 + 配置回显。llmEndpointId=显式绑定的端点，null=跟随默认端点；wakeWindow null=全天 */
    public record TraderOwnerView(TraderPublicView pub, Long llmEndpointId,
                                  String customPrompt, boolean useDefaultPrompt,
                                  TraderSpec spec, boolean alertEnabled, BigDecimal alertThresholdMult,
                                  boolean reviewEnabled, boolean learningEnabled,
                                  String wakeWindow) {
    }

    /** 仓位规格：配置回显与提示词预览共用一个形状，前端改一处两边同步。 */
    public record TraderSpec(int leverageMin, int leverageMax,
                             BigDecimal marginPctMin, BigDecimal marginPctMax,
                             boolean allowMultiPosition, boolean allowHedge) {
        TraderRiskConfig toConfig() {
            return new TraderRiskConfig(leverageMin, leverageMax, marginPctMin, marginPctMax,
                    allowMultiPosition, allowHedge);
        }

        static TraderSpec of(AiTrader t) {
            TraderRiskConfig c = TraderRiskConfig.of(t);
            return new TraderSpec(c.leverageMin(), c.leverageMax(), c.marginPctMin(), c.marginPctMax(),
                    c.allowMultiPosition(), c.allowHedge());
        }
    }

    @GetMapping("/mine")
    @Operation(summary = "我的trader（含配置回显，key只回尾4位）")
    public Result<TraderOwnerView> mine(@CurrentUserId long userId) {
        AiTrader t = traderService.mine(userId);
        if (t == null) {
            return Result.ok(null);
        }
        return Result.ok(new TraderOwnerView(publicView(t, userId, modelName(t)),
                endpointService.bindings(userId).get(UserLlmBinding.TRADER),
                t.getCustomPrompt(),
                !Boolean.FALSE.equals(t.getUseDefaultPrompt()), TraderSpec.of(t),
                !Boolean.FALSE.equals(t.getAlertEnabled()),
                t.getAlertThresholdMult() == null ? BigDecimal.ONE : t.getAlertThresholdMult(),
                !Boolean.FALSE.equals(t.getReviewEnabled()),
                !Boolean.FALSE.equals(t.getLearningEnabled()),
                t.getWakeWindow()));
    }

    /** 提示词预览的入参：规格项太多，走 POST 带 body 比堆十个 query 参数干净。 */
    public record PromptPreviewRequest(String intervalCode, String symbols, TraderSpec spec, String wakeWindow) {
    }

    @PostMapping("/prompt-template")
    @Operation(summary = "平台系统提示词预览（与唤醒组装同一份文本）")
    public Result<String> promptTemplate(@CurrentUserId long userId, @RequestBody PromptPreviewRequest req) {
        String interval = req.intervalCode() == null || req.intervalCode().isBlank() ? "15m" : req.intervalCode();
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
        // 收尾格式块一并带上：它不随模板开关走，预览里不露面用户就不知道自己被强制了什么
        AgentLang lang = userLangResolver.of(userId);
        return Result.ok(promptAssembler.platformTemplate(lang, interval, symbols, cfg, windowText)
                + "\n\n" + promptAssembler.closingFormat(lang, symbols));
    }

    /** llmEndpointId：端点库里的一条，空=跟随用户默认端点 */
    public record UpsertRequest(String name, String symbols, String intervalCode, String customPrompt,
                                Long llmEndpointId,
                                Boolean useDefaultPrompt, TraderSpec spec,
                                Boolean alertEnabled, BigDecimal alertThresholdMult,
                                Boolean reviewEnabled, Boolean learningEnabled,
                                String wakeWindow) {
        TraderService.UpsertReq toReq() {
            TraderSpec s = spec;
            return new TraderService.UpsertReq(name, symbols, intervalCode, customPrompt,
                    llmEndpointId, useDefaultPrompt,
                    s == null ? null : s.leverageMin(), s == null ? null : s.leverageMax(),
                    s == null ? null : s.marginPctMin(), s == null ? null : s.marginPctMax(),
                    s == null ? null : s.allowMultiPosition(), s == null ? null : s.allowHedge(),
                    alertEnabled, alertThresholdMult, reviewEnabled, learningEnabled, wakeWindow);
        }
    }

    @PostMapping
    @Operation(summary = "创建trader（连通性校验→key加密→开sim子账户注资10000）")
    public Result<Void> create(@CurrentUserId long userId, @RequestBody UpsertRequest req) {
        String err = traderService.create(userId, req.toReq());
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    @PutMapping("/config")
    @Operation(summary = "改配置（apiKey传空=不换；提示词改完下一根K线生效）")
    public Result<Void> updateConfig(@CurrentUserId long userId, @RequestBody UpsertRequest req) {
        String err = traderService.updateConfig(userId, req.toReq());
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    @PostMapping("/start")
    @Operation(summary = "启动")
    public Result<Void> start(@CurrentUserId long userId) {
        String err = traderService.start(userId);
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    @PostMapping("/pause")
    @Operation(summary = "暂停")
    public Result<Void> pause(@CurrentUserId long userId) {
        String err = traderService.pause(userId);
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    /** carryNotes 空=true（带入笔记到新局）；false 只清生效版本，历届存档保留 */
    public record ResetRequest(Boolean carryNotes) {
    }

    @PostMapping("/reset")
    @Operation(summary = "重置开新局（round+1，新子账户注资10000，历史留档；carryNotes=false不带入复盘/学习笔记）")
    public Result<Void> reset(@CurrentUserId long userId, @RequestBody(required = false) ResetRequest req) {
        boolean carry = req == null || req.carryNotes() == null || req.carryNotes();
        String err = traderService.reset(userId, carry);
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    /**
     * confirmName 走 query 不走 body：DELETE 带 body 规范允许但一路上的代理不一定转发，
     * 而这就是一个短名字，没有藏进 body 的必要。
     */
    @DeleteMapping
    @Operation(summary = "删除我的 trader（决策/计划/每局sim子账户/TRADER端点绑定全物理删除，竞技场里彻底消失，删完可重建；需名字确认，唤醒中拒）")
    public Result<Void> delete(@CurrentUserId long userId,
                               @RequestParam(required = false) String confirmName) {
        String err = traderService.delete(userId, confirmName);
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    /** stale 开关请求体。包装 Boolean：缺字段是格式错误要拒，不能被 Jackson 静默填成 false 当"取消忽略"执行 */
    public record PlanStaleRequest(Boolean stale) {
    }

    @PostMapping("/plan/{planId}/stale")
    @Operation(summary = "标记/取消忽略一笔已了结交易（仅本人、仅CLOSED；忽略后AI统计与复盘不再参考，公开记录不变）")
    public Result<Void> setPlanStale(@CurrentUserId long userId, @PathVariable long planId,
                                     @RequestBody PlanStaleRequest req) {
        if (req.stale() == null) {
            return Result.fail(messages.get("trader.plan.staleRequired"));
        }
        String err = traderService.setPlanStale(userId, planId, req.stale());
        return err == null ? Result.ok(null) : Result.fail(err);
    }

    // ========== 动作面板（留言 / 手动唤醒 / 点播复盘） ==========
    // 三个动作的执行入口只有这里。对话轨的工具只把表单推给用户看，模型碰不到执行那一步。
    // 一律 Result.ok(ActionResult)：业务结果（被拦下、无素材跳过）要显示在卡片里，
    // 走 Result.fail 会被前端当成接口报错弹 toast。

    @GetMapping("/action-panel")
    @Operation(summary = "动作面板状态：上次/下次唤醒、留言剩余轮次、可否唤醒与复盘")
    public Result<TraderActionService.ActionPanel> actionPanel(@CurrentUserId long userId) {
        return Result.ok(actionService.panel(userId));
    }

    @PostMapping("/note")
    @Operation(summary = "给 trader 留言（覆盖未读的那条，按轮次逐轮注入）")
    public Result<TraderActionService.ActionResult> saveNote(@CurrentUserId long userId,
                                                             @RequestBody NoteRequest req) {
        return Result.ok(actionService.saveNote(userId, req.note(), req.rounds()));
    }

    @DeleteMapping("/note")
    @Operation(summary = "撤回还没被读走的留言")
    public Result<TraderActionService.ActionResult> clearNote(@CurrentUserId long userId) {
        return Result.ok(actionService.clearNote(userId));
    }

    @PostMapping("/wake")
    @Operation(summary = "手动唤醒（真实执行一次决策，可能开/平仓）")
    public Result<TraderActionService.ActionResult> wake(@CurrentUserId long userId) {
        return Result.ok(actionService.wake(userId));
    }

    @PostMapping("/review")
    @Operation(summary = "点播复盘（异步跑；无新素材则跳过且不消耗模型调用）")
    public Result<TraderActionService.ActionResult> review(@CurrentUserId long userId) {
        return Result.ok(actionService.review(userId));
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
        // 模型名按人批量解析（两条查询），不在循环里逐个查
        Map<Long, UserLlmEndpoint> endpoints = endpointService.resolveForUsers(
                all.stream().map(AiTrader::getUserId).toList(), UserLlmBinding.TRADER);
        return Result.ok(all.stream()
                .map(t -> publicView(t, viewer, endpoints.containsKey(t.getUserId()) ? endpoints.get(t.getUserId()).getModel() : null))
                .sorted((a, b) -> b.pnlPct().compareTo(a.pnlPct()))
                .toList());
    }

    /**
     * 详情视图：公开视图 + 实时持仓/挂单 + 本局存活计划 + 两份笔记。
     * memory=reviewer 复盘沉淀的记忆笔记，learningNotes=learning agent 向同侪学的笔记，
     * 都是 trader 每次唤醒真正读到的东西；lastReviewAt / lastLearnAt 是最近一次成功写笔记的时刻（ms），没有=null。
     * 两份笔记公开与时间线口径一致：REVIEW 行本就带 memoryAfter 快照，LEARN 行的 reasoning 就是学习笔记全文。
     */
    public record TraderDetailView(TraderPublicView trader,
                                   List<FuturesPositionDTO> positions,
                                   List<FuturesOrderResponse> pendingOrders,
                                   List<AiTraderPlan> plans,
                                   String memory, String learningNotes,
                                   Long lastReviewAt, Long lastLearnAt) {
    }

    @GetMapping("/{id}")
    @Operation(summary = "trader详情（当前持仓/挂单实时现查 + 各持仓的交易计划 + 复盘/学习笔记）")
    public Result<TraderDetailView> detail(@PathVariable long id) {
        long viewer = viewerId();
        AiTrader t = traderService.byId(id);
        if (t == null) {
            return Result.fail(ErrorCode.SYSTEM_ERROR.getCode(), messages.get("trader.notFound"));
        }
        List<FuturesPositionDTO> positions = List.of();
        List<FuturesOrderResponse> pending = List.of();
        try {
            positions = simTradeClient.getAllPositions(t.getSimUserId());
            pending = simTradeClient.getPendingOrders(t.getSimUserId(), null);
        } catch (Exception e) {
            log.warn("[Trader] 详情持仓查询失败 traderId={} msg={}", id, e.getMessage());
        }
        return Result.ok(new TraderDetailView(publicView(t, viewer, modelName(t)), positions, pending,
                traderService.plans(t), t.getMemory(), t.getLearningNotes(),
                traderService.latestNoteTime(id, AiTraderDecision.KIND_REVIEW),
                traderService.latestNoteTime(id, AiTraderDecision.KIND_LEARN)));
    }

    @GetMapping("/{id}/decisions")
    @Operation(summary = "决策时间线（倒序分页，before传上一页最旧wakeTime；from/to 为 wakeTime 区间 [from,to)）")
    public Result<List<AiTraderDecision>> decisions(@PathVariable long id,
                                                    @RequestParam(defaultValue = "50") int limit,
                                                    @RequestParam(required = false) Long before,
                                                    @RequestParam(required = false) Integer round,
                                                    @RequestParam(required = false) Long from,
                                                    @RequestParam(required = false) Long to) {
        return Result.ok(traderService.decisions(id, limit, before, round, from, to));
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
    public Result<JSONObject> decisionTrace(@PathVariable long id, @PathVariable long decisionId) {
        AiTraderDecision d = traderService.trace(decisionId);
        // 非主人回 null 不报错：轨迹入口本就藏在主人才见得到的按钮后面，报错反倒把"有这东西"讲出去了
        if (d == null || d.getTraderId() != id
                || traderService.byId(id).getUserId() != StpUtil.getLoginIdAsLong()) {
            return Result.ok(null);
        }
        return Result.ok(JSON.parseObject(d.getTraceJson()));
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

    public record EquityPoint(long wakeTime, BigDecimal equity) {
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
                .map(d -> new EquityPoint(d.getWakeTime(), d.getEquity()))
                .toList());
    }

    /** 竞技场看客是谁：游客给 -1，publicView 里 mine 永远对不上 */
    private static long viewerId() {
        return StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : -1L;
    }

    /** 单个 trader 当前端点的模型名；用户把端点删光了给 null（前端显示"未配置"） */
    private String modelName(AiTrader t) {
        UserLlmEndpoint e = endpointService.resolve(t.getUserId(), UserLlmBinding.TRADER);
        return e == null ? null : e.getModel();
    }

    private TraderPublicView publicView(AiTrader t, long viewerUserId, String model) {
        BigDecimal equity = traderService.latestEquity(t);
        BigDecimal pnlPct = equity.subtract(TraderService.INITIAL_BALANCE)
                .divide(TraderService.INITIAL_BALANCE, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
        return new TraderPublicView(t.getId(), t.getName(), model, t.getStatus(), t.getPausedReason(),
                t.getSymbols(), t.getIntervalCode(), t.getRoundNo(),
                equity.setScale(2, RoundingMode.HALF_UP), pnlPct.setScale(2, RoundingMode.HALF_UP),
                t.getUserId() == viewerUserId, t.getWakeWindow());
    }
}
