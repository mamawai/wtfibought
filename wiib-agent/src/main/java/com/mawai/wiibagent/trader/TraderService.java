package com.mawai.wiibagent.trader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mawai.wiibcommon.config.BinanceProperties;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.entity.UserLlmBinding;
import com.mawai.wiibcommon.entity.UserLlmEndpoint;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.i18n.UserLangResolver;
import com.mawai.wiibagent.llm.LlmEndpointService;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.trader.trade.TraderPlanStore;
import com.mawai.wiibagent.trader.trade.TraderRiskConfig;
import com.mawai.wiibagent.trader.wakeup.TraderLiveHub;
import com.mawai.wiibagent.trader.wakeup.TraderScheduler;
import com.mawai.wiibagent.trader.wakeup.TraderWakeupRunner;
import com.mawai.wiibagent.trader.wakeup.WakeWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * trader 生命周期：创建（选端点→连通性校验→开sim子账户注资）→ 启停 → 重置开新局。
 * 每用户 1 个；每局一个独立 sim 子账户（账号名 ai_trader_{userId}_r{round}），历史局留档。
 * <p>
 * 模型端点不再存在 ai_trader 行里：从用户端点库（AI 页「模型配置」）里选一条绑到 TRADER 用途，
 * 不选就跟随用户默认端点；唤醒时 {@link TraderModelFactory} 现解析。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraderService {

    public static final BigDecimal INITIAL_BALANCE = new BigDecimal("10000");
    /** 轮次保留窗口：每 trader 只留最近 10 局（R10），开新局时更早的整局数据连同 sim 子账户一并清除 */
    public static final int MAX_ROUNDS_KEPT = 10;
    /** 币种上限：单轮 12 次模型调用按币摊，一个币扎实求证约 3 次，3 币正好（前端 MyTrader.MAX_SYMBOLS 同一个数） */
    public static final int MAX_SYMBOLS = 3;
    /** 唤醒档位四档（1d 已下线：一天一醒的观赏性与反馈密度都撑不起一个档位） */
    private static final Set<String> INTERVALS = Set.of("5m", "15m", "1h", "4h");

    private final AiTraderMapper traderMapper;
    private final AiTraderDecisionMapper decisionMapper;
    private final TraderModelFactory modelFactory;
    private final LlmEndpointService endpointService;
    private final SimTradeClient simTradeClient;
    private final BinanceProperties binanceProperties;
    private final TraderPlanStore planStore;
    /** 删除时拦在途唤醒、清进程内那两份按 traderId 的记账 */
    private final TraderScheduler scheduler;
    private final TraderLiveHub hub;
    /** 暂停原因落库即公开展示，跟 trader 主人的语言写入——与 TraderWakeupRunner 那几种同一口径 */
    private final PromptCatalog prompts;
    private final UserLangResolver langResolver;
    /** 校验回执是当场给用户看的话，跟当次请求的界面语言，与上面那份落库文案不是一个来源 */
    private final MessageCatalog messages;

    /** llmEndpointId：端点库里的一条；空=跟随用户默认端点。wakeWindow：唤醒时段"HH:mm-HH:mm"（北京时间），null=全天 */
    public record UpsertReq(String name, String symbols, String intervalCode, String customPrompt,
                            Long llmEndpointId,
                            Boolean useDefaultPrompt,
                            Integer leverageMin, Integer leverageMax,
                            BigDecimal marginPctMin, BigDecimal marginPctMax,
                            Boolean allowMultiPosition, Boolean allowHedge,
                            Boolean alertEnabled, BigDecimal alertThresholdMult,
                            Boolean reviewEnabled, Boolean learningEnabled,
                            String wakeWindow) {
    }

    public AiTrader mine(long userId) {
        return traderMapper.selectOne(new LambdaQueryWrapper<AiTrader>().eq(AiTrader::getUserId, userId));
    }

    public AiTrader byId(long id) {
        return traderMapper.selectById(id);
    }

    public List<AiTrader> all() {
        return traderMapper.selectList(new LambdaQueryWrapper<AiTrader>().orderByAsc(AiTrader::getId));
    }

    /** 创建：校验→选端点→连通性测试→开子账户→PAUSED 入库→绑定用途。返回错误信息或 null。 */
    public String create(long userId, UpsertReq req) {
        if (mine(userId) != null) {
            return messages.get("trader.alreadyExists");
        }
        String err = validate(req);
        if (err != null) {
            return err;
        }
        UserLlmEndpoint endpoint = pickEndpoint(userId, req.llmEndpointId());
        if (endpoint == null) {
            return messages.get("trader.noEndpoint");
        }
        String connErr = modelFactory.testConnection(endpoint);
        if (connErr != null) {
            return messages.get("trader.connectFailed", Map.of("reason", connErr));
        }
        AiTrader t = new AiTrader();
        t.setUserId(userId);
        applyConfig(t, req);
        t.setStatus(AiTrader.STATUS_PAUSED);
        t.setRoundNo(1);
        t.setConsecutiveFailures(0);
        t.setSimUserId(simTradeClient.ensureAccount(accountName(userId, 1), INITIAL_BALANCE));
        traderMapper.insert(t);
        endpointService.bind(userId, UserLlmBinding.TRADER, req.llmEndpointId());
        log.info("[Trader] 创建 traderId={} userId={} model={}", t.getId(), userId, endpoint.getModel());
        return null;
    }

    /** 改配置：换了端点（含"跟随默认"与显式之间切换到不同端点）就重测连通并逐出模型缓存。 */
    public String updateConfig(long userId, UpsertReq req) {
        AiTrader t = mine(userId);
        if (t == null) {
            return messages.get("trader.notCreated");
        }
        String err = validate(req);
        if (err != null) {
            return err;
        }
        UserLlmEndpoint endpoint = pickEndpoint(userId, req.llmEndpointId());
        if (endpoint == null) {
            return messages.get("trader.noEndpoint");
        }
        UserLlmEndpoint current = modelFactory.endpointFor(t);
        boolean modelChanged = current == null || !current.getId().equals(endpoint.getId());
        if (modelChanged) {
            String connErr = modelFactory.testConnection(endpoint);
            if (connErr != null) {
                return messages.get("trader.connectFailed", Map.of("reason", connErr));
            }
        }
        AiTrader probe = new AiTrader();
        applyConfig(probe, req);
        // 列级更新只写配置字段：整行 updateById 会把唤醒回路并发写的 status/连败计数盖回快照旧值
        // （连通性测试要出网数秒，窗口不小）——与 runner 侧"状态回写列级更新"是同一条铁律的两半
        traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                .eq(AiTrader::getId, t.getId())
                .set(AiTrader::getName, probe.getName())
                .set(AiTrader::getSymbols, probe.getSymbols())
                .set(AiTrader::getIntervalCode, probe.getIntervalCode())
                .set(AiTrader::getCustomPrompt, probe.getCustomPrompt())
                .set(AiTrader::getUseDefaultPrompt, probe.getUseDefaultPrompt())
                .set(AiTrader::getLeverageMin, probe.getLeverageMin())
                .set(AiTrader::getLeverageMax, probe.getLeverageMax())
                .set(AiTrader::getMarginPctMin, probe.getMarginPctMin())
                .set(AiTrader::getMarginPctMax, probe.getMarginPctMax())
                .set(AiTrader::getAllowMultiPosition, probe.getAllowMultiPosition())
                .set(AiTrader::getAllowHedge, probe.getAllowHedge())
                .set(AiTrader::getAlertEnabled, probe.getAlertEnabled())
                .set(AiTrader::getAlertThresholdMult, probe.getAlertThresholdMult())
                .set(AiTrader::getReviewEnabled, probe.getReviewEnabled())
                .set(AiTrader::getLearningEnabled, probe.getLearningEnabled())
                .set(AiTrader::getWakeWindow, probe.getWakeWindow())
                .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
        endpointService.bind(userId, UserLlmBinding.TRADER, req.llmEndpointId());
        if (modelChanged) {
            modelFactory.evict(t.getId());
        }
        return null;
    }


    /** 显式选的端点必须是自己的；不选（null）= 跟随默认。两头都拿不到端点 → null */
    private UserLlmEndpoint pickEndpoint(long userId, Long endpointId) {
        if (endpointId != null) {
            return endpointService.get(userId, endpointId);
        }
        return endpointService.defaultOf(userId);
    }

    public String start(long userId) {
        AiTrader t = mine(userId);
        if (t == null) {
            return messages.get("trader.notCreated");
        }
        if (AiTrader.STATUS_LIQUIDATED.equals(t.getStatus())) {
            return messages.get("trader.liquidatedRound");
        }
        traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                .eq(AiTrader::getId, t.getId())
                .set(AiTrader::getStatus, AiTrader.STATUS_RUNNING)
                .set(AiTrader::getPausedReason, null)
                .set(AiTrader::getConsecutiveFailures, 0)
                .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
        return null;
    }

    public String pause(long userId) {
        AiTrader t = mine(userId);
        if (t == null) {
            return messages.get("trader.notCreated");
        }
        traderMapper.update(null, new LambdaUpdateWrapper<AiTrader>()
                .eq(AiTrader::getId, t.getId())
                .set(AiTrader::getStatus, AiTrader.STATUS_PAUSED)
                .set(AiTrader::getPausedReason, prompts.get(langResolver.of(userId), "trader.pause.manual"))
                .set(AiTrader::getUpdatedAt, LocalDateTime.now()));
        return null;
    }

    /**
     * 标记/取消忽略一笔已归档计划：只有主人能标；LIVE 是在场纪律不许藏，只有 CLOSED 可标。
     * stale 只作用于教材层（论点战绩统计、复盘素材），权益/排行榜/同侪学习照常。
     */
    public String setPlanStale(long userId, long planId, boolean stale) {
        AiTrader t = mine(userId);
        if (t == null) {
            return messages.get("trader.notCreated");
        }
        AiTraderPlan p = planStore.byId(planId);
        if (p == null || !Objects.equals(p.getTraderId(), t.getId())) {
            return messages.get("trader.plan.notFound");
        }
        if (!AiTraderPlan.STATUS_CLOSED.equals(p.getStatus())) {
            return messages.get("trader.plan.notClosed");
        }
        planStore.setStale(planId, stale);
        return null;
    }

    /**
     * 重置开新局：round+1、新 sim 子账户注资、PAUSED 待手动启动；旧账户与决策历史留档。
     * carryNotes=false 时连两份笔记的生效版本一并清空——历届存档在 REVIEW 行 memory_after /
     * LEARN 行 reasoning 里，永远查得到，清的只是"下局还注不注入"。
     */
    public String reset(long userId, boolean carryNotes) {
        AiTrader t = mine(userId);
        if (t == null) {
            return messages.get("trader.notCreated");
        }
        int newRound = t.getRoundNo() + 1;
        Long simUserId = simTradeClient.ensureAccount(accountName(userId, newRound), INITIAL_BALANCE);
        // 本局存活计划随重置归档（不删）：论点/失效条件/修订史是公开凭证，也是reviewer的复盘原料
        planStore.archiveRound(t.getId(), t.getRoundNo(), System.currentTimeMillis());
        LambdaUpdateWrapper<AiTrader> upd = new LambdaUpdateWrapper<AiTrader>()
                .eq(AiTrader::getId, t.getId())
                .set(AiTrader::getRoundNo, newRound)
                .set(AiTrader::getSimUserId, simUserId)
                .set(AiTrader::getStatus, AiTrader.STATUS_PAUSED)
                .set(AiTrader::getPausedReason, null)
                .set(AiTrader::getConsecutiveFailures, 0)
                .set(AiTrader::getOwnerNote, null)
                .set(AiTrader::getOwnerNoteRounds, 0)
                .set(AiTrader::getUpdatedAt, LocalDateTime.now());
        if (!carryNotes) {
            upd.set(AiTrader::getMemory, null).set(AiTrader::getLearningNotes, null);
        }
        traderMapper.update(null, upd);
        purgeExpiredRounds(t.getId(), userId, newRound);
        log.info("[Trader] 重置开新局 traderId={} round={}", t.getId(), newRound);
        return null;
    }

    /**
     * 删 trader：决策/计划两表全轮次、每一局的 sim 子账户、TRADER 用途绑定一并物理删除，
     * 竞技场里这个人彻底消失，删完可以立刻重建。端点本身不动——那是全站 BYOK 配置，不归 trader。
     * <p>
     * 两道闸：名字要原样打一遍；在途唤醒时拒——那一轮跑完要写 decision 行、要调 sim 交易工具，
     * 中途删了就是留一行永远查不到的孤儿加一串报错。
     */
    public String delete(long userId, String confirmName) {
        AiTrader t = mine(userId);
        if (t == null) {
            return messages.get("trader.notCreated");
        }
        if (!t.getName().equals(confirmName)) {
            return messages.get("trader.delete.nameMismatch");
        }
        if (scheduler.isBusy(t.getId())) {
            return messages.get("trader.delete.busy");
        }
        // 本地先清干净：ai_trader 行没了调度器就找不到它，之后销 sim 账户不会跟在途交易撞上
        decisionMapper.delete(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, t.getId()));
        planStore.purgeAll(t.getId());
        traderMapper.deleteById(t.getId());
        endpointService.bind(userId, UserLlmBinding.TRADER, null);
        modelFactory.evict(t.getId());
        scheduler.forget(t.getId());
        hub.forget(t.getId());
        // 每局一个子账户，从 r1 扫一遍：出保留窗口的那些 purgeExpiredRounds 早清过，
        // sim 侧销户幂等删不存在的不报错，顺带把历史漏删的孤儿一并收走。
        // 单局失败不阻断——本地已经删净，剩下的孤儿账户无业务引用，同 purgeExpiredRounds 口径
        for (int round = 1; round <= t.getRoundNo(); round++) {
            try {
                simTradeClient.deleteAccount(accountName(userId, round));
            } catch (Exception e) {
                log.warn("[Trader] 删除时 sim 子账户销户失败 traderId={} round={}", t.getId(), round, e);
            }
        }
        log.info("[Trader] 删除 traderId={} userId={} rounds={}", t.getId(), userId, t.getRoundNo());
        return null;
    }

    /**
     * 清保留窗口（{@link #MAX_ROUNDS_KEPT}）外的旧局：决策/请求/计划三表按 roundNo ≤ 界外号
     * 整段删（顺带自愈历史漏删），sim 子账户只删刚出窗那一局。
     * sim 删失败不阻断开新局（新局账户已就绪），留 warn——遗留的孤儿账户无业务引用，无害。
     */
    private void purgeExpiredRounds(long traderId, long userId, int newRound) {
        int expired = newRound - MAX_ROUNDS_KEPT;
        if (expired < 1) {
            return;
        }
        decisionMapper.delete(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, traderId)
                .le(AiTraderDecision::getRoundNo, expired));
        planStore.purgeRounds(traderId, expired);
        try {
            simTradeClient.deleteAccount(accountName(userId, expired));
        } catch (Exception e) {
            log.warn("[Trader] 过期轮次 sim 子账户删除失败 traderId={} round={}", traderId, expired, e);
        }
        log.info("[Trader] 清理过期轮次 traderId={} round<={}", traderId, expired);
    }

    /** 该 trader 最新权益（最近一条带 equity 的决策行；开局无决策时=初始资金）。 */
    public BigDecimal latestEquity(AiTrader t) {
        AiTraderDecision d = decisionMapper.selectOne(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .isNotNull(AiTraderDecision::getEquity)
                .orderByDesc(AiTraderDecision::getWakeTime)
                .last("LIMIT 1"));
        return d != null ? d.getEquity() : INITIAL_BALANCE;
    }

    /**
     * 决策时间线。必须按局过滤：局与局之间是两个互不相干的 sim 子账户（各自注资 10000），
     * 混排会出现"曲线上没有的决策"，权益数字也在两条基线之间跳。round 传空=当前局。
     * from/to 是 wakeTime 区间 [from, to)，竞技场按天翻看用；与 before 分页可叠加。
     */
    public List<AiTraderDecision> decisions(long traderId, int limit, Long before, Integer round,
                                            Long from, Long to) {
        AiTrader t = traderMapper.selectById(traderId);
        if (t == null) {
            return List.of();
        }
        LambdaQueryWrapper<AiTraderDecision> q = new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, traderId)
                .eq(AiTraderDecision::getRoundNo, round != null ? round : t.getRoundNo())
                .orderByDesc(AiTraderDecision::getWakeTime)
                .last("LIMIT " + Math.clamp(limit, 1, 100));
        if (before != null) {
            q.lt(AiTraderDecision::getWakeTime, before);
        }
        if (from != null) {
            q.ge(AiTraderDecision::getWakeTime, from);
        }
        if (to != null) {
            q.lt(AiTraderDecision::getWakeTime, to);
        }
        List<AiTraderDecision> page = decisionMapper.selectList(q);
        if (!page.isEmpty()) {
            // 有没有过程可看：trace_json 不背进列表，页内 id 再查一次哪些非空
            Set<Long> withTrace = decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                            .select(AiTraderDecision::getId)
                            .in(AiTraderDecision::getId, page.stream().map(AiTraderDecision::getId).toList())
                            .isNotNull(AiTraderDecision::getTraceJson))
                    .stream().map(AiTraderDecision::getId).collect(Collectors.toSet());
            page.forEach(d -> d.setHasTrace(withTrace.contains(d.getId())));
        }
        return page;
    }

    /** 一条决策的过程轨迹（只带 id/traderId/traceJson）；没有轨迹或行不存在=null */
    public AiTraderDecision trace(long decisionId) {
        return decisionMapper.selectOne(new LambdaQueryWrapper<AiTraderDecision>()
                .select(AiTraderDecision::getId, AiTraderDecision::getTraderId, AiTraderDecision::getTraceJson)
                .eq(AiTraderDecision::getId, decisionId)
                .isNotNull(AiTraderDecision::getTraceJson));
    }

    /**
     * 决策 token 合计，三个参数的语义与 {@link #decisions} 一致：round 缺省=当前局，[from, to) 是 wakeTime 区间。
     * 页面翻到哪天就统计哪天，切到哪局就统计哪局。
     * <p>整段都没有 usage（BYOK 网关不一定回）时 SUM 本身就是 null，原样返回让前端显示"—"，不补 0。
     */
    public Long sumTokens(long traderId, Integer round, Long from, Long to) {
        AiTrader t = traderMapper.selectById(traderId);
        if (t == null) {
            return null;
        }
        QueryWrapper<AiTraderDecision> q = new QueryWrapper<AiTraderDecision>()
                .select("SUM(total_tokens) AS total")
                .eq("trader_id", traderId)
                .eq("round_no", round != null ? round : t.getRoundNo());
        if (from != null) {
            q.ge("wake_time", from);
        }
        if (to != null) {
            q.lt("wake_time", to);
        }
        List<Map<String, Object>> rows = decisionMapper.selectMaps(q);
        // SUM 无行可加时回一行、列值 NULL，MyBatis 把整行全 null 的行映射成 null 元素，首行得先判空
        Map<String, Object> first = rows.isEmpty() ? null : rows.getFirst();
        // PG 的 SUM(bigint) 回 numeric，JDBC 给的是 BigDecimal，按 Number 收
        Object v = first == null ? null : first.get("total");
        return v instanceof Number n ? n.longValue() : null;
    }

    /**
     * 最近一条写了笔记的 REVIEW / LEARN 行的 wakeTime，没有=null。
     * REVIEW 以 memoryAfter 非空为准（缺分隔符的复盘 OK 行照存但笔记没动）；LEARN 的 OK 行本身就是笔记全文。
     * 不按局过滤：memory / learningNotes 是跨局累积的，"最近一次复盘/学习"也就跨局看。
     */
    public Long latestNoteTime(long traderId, String kind) {
        LambdaQueryWrapper<AiTraderDecision> q = new LambdaQueryWrapper<AiTraderDecision>()
                .select(AiTraderDecision::getWakeTime)
                .eq(AiTraderDecision::getTraderId, traderId)
                .eq(AiTraderDecision::getKind, kind)
                .eq(AiTraderDecision::getStatus, AiTraderDecision.STATUS_OK)
                .orderByDesc(AiTraderDecision::getWakeTime)
                .last("LIMIT 1");
        if (AiTraderDecision.KIND_REVIEW.equals(kind)) {
            q.isNotNull(AiTraderDecision::getMemoryAfter);
        }
        AiTraderDecision d = decisionMapper.selectOne(q);
        return d == null ? null : d.getWakeTime();
    }

    /** 当前局的持仓交易计划（竞技场详情随持仓一并展示）。 */
    public List<AiTraderPlan> plans(AiTrader t) {
        return planStore.list(t.getId(), t.getRoundNo());
    }

    /** 净值曲线：(wakeTime, equity) 升序；round 缺省=当前局。 */
    public List<AiTraderDecision> equityCurve(AiTrader t, Integer round) {
        return decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                .select(AiTraderDecision::getWakeTime, AiTraderDecision::getEquity)
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, round != null ? round : t.getRoundNo())
                .isNotNull(AiTraderDecision::getEquity)
                .orderByAsc(AiTraderDecision::getWakeTime));
    }

    private String validate(UpsertReq req) {
        if (req.name() == null || req.name().isBlank() || req.name().length() > 32) {
            return messages.get("trader.config.nameRequired");
        }
        if (req.intervalCode() == null || !INTERVALS.contains(req.intervalCode())) {
            return messages.get("trader.config.badInterval");
        }
        WakeWindow window;
        try {
            window = WakeWindow.parse(req.wakeWindow());
        } catch (IllegalArgumentException e) {
            // parse 抛的是词表 key（见 WakeWindow.parse），成文在这一处
            return messages.get(e.getMessage());
        }
        // 4h 档选 21:00-23:00 这种时段里一根本档K线都不收盘 = 永远不醒，拦在入口
        if (window != null) {
            long intervalMs = TraderScheduler.INTERVAL_MS.get(req.intervalCode());
            long now = System.currentTimeMillis();
            if (window.nextBoundaryFrom(now - Math.floorMod(now, intervalMs), intervalMs) < 0) {
                return messages.get("trader.config.windowNeverWakes", Map.of("interval", req.intervalCode()));
            }
        }
        String spec = validateSpec(req);
        if (spec != null) {
            return spec;
        }
        List<String> whitelist = binanceProperties.getSymbols();
        Set<String> symbols = parseSymbols(req.symbols());
        if (symbols.isEmpty()) {
            return messages.get("trader.config.symbolRequired");
        }
        // 存量 4~5 币的 trader 不动，下次改配置才被要求裁到 3
        if (symbols.size() > MAX_SYMBOLS) {
            return messages.get("trader.config.tooManySymbols", Map.of("max", MAX_SYMBOLS));
        }
        if (whitelist == null || !new HashSet<>(whitelist).containsAll(symbols)) {
            return messages.get("trader.config.symbolNotAllowed", Map.of("whitelist", whitelist));
        }
        if (req.customPrompt() != null && req.customPrompt().length() > 4000) {
            return messages.get("trader.config.promptTooLong");
        }
        // 退出平台模板后自定义就是唯一指令来源，空着=模型裸奔
        if (Boolean.FALSE.equals(req.useDefaultPrompt())
                && (req.customPrompt() == null || req.customPrompt().isBlank())) {
            return messages.get("trader.config.customPromptRequired");
        }
        return null;
    }

    /**
     * 仓位规格校验：区间本身要成立，边界不能离谱。
     * 杠杆上界只卡到 125——实际可用还受 sim 按名义价值分档限制，超档由 sim 拒并把原因回传给模型，
     * 这里不重复实现一套分档表（agent 进程读不到 sim 的 bracket registry）。
     */
    private String validateSpec(UpsertReq req) {
        int lmin = req.leverageMin() == null ? TraderRiskConfig.DEF_LEV_MIN : req.leverageMin();
        int lmax = req.leverageMax() == null ? TraderRiskConfig.DEF_LEV_MAX : req.leverageMax();
        if (lmin < 1 || lmax > TraderRiskConfig.LEVERAGE_HARD_MAX) {
            return messages.get("trader.config.leverageRange", Map.of("max", TraderRiskConfig.LEVERAGE_HARD_MAX));
        }
        if (lmin > lmax) {
            return messages.get("trader.config.leverageInverted");
        }
        BigDecimal mmin = req.marginPctMin() == null ? TraderRiskConfig.DEF_MARGIN_MIN : req.marginPctMin();
        BigDecimal mmax = req.marginPctMax() == null ? TraderRiskConfig.DEF_MARGIN_MAX : req.marginPctMax();
        if (mmin.compareTo(TraderRiskConfig.MARGIN_PCT_HARD_MIN) < 0
                || mmax.compareTo(TraderRiskConfig.MARGIN_PCT_HARD_MAX) > 0) {
            return messages.get("trader.config.marginRange");
        }
        if (mmin.compareTo(mmax) > 0) {
            return messages.get("trader.config.marginInverted");
        }
        // 双开天然要占两个仓位，单仓模式下勾它是自相矛盾的配置，直接拦在入口
        if (Boolean.FALSE.equals(req.allowMultiPosition()) && Boolean.TRUE.equals(req.allowHedge())) {
            return messages.get("trader.config.hedgeNeedsTwoSlots");
        }
        // 警报阈值只能调高：系数<1 等于把每币基准（平台下限）调低
        if (req.alertThresholdMult() != null && req.alertThresholdMult().compareTo(BigDecimal.ONE) < 0) {
            return messages.get("trader.config.alertMultTooLow");
        }
        return null;
    }

    private static void applyConfig(AiTrader t, UpsertReq req) {
        t.setName(req.name().trim());
        t.setSymbols(String.join(",", parseSymbols(req.symbols())));
        t.setIntervalCode(req.intervalCode());
        t.setCustomPrompt(req.customPrompt());
        t.setUseDefaultPrompt(req.useDefaultPrompt() == null || req.useDefaultPrompt());
        t.setLeverageMin(req.leverageMin() == null ? TraderRiskConfig.DEF_LEV_MIN : req.leverageMin());
        t.setLeverageMax(req.leverageMax() == null ? TraderRiskConfig.DEF_LEV_MAX : req.leverageMax());
        t.setMarginPctMin(req.marginPctMin() == null ? TraderRiskConfig.DEF_MARGIN_MIN : req.marginPctMin());
        t.setMarginPctMax(req.marginPctMax() == null ? TraderRiskConfig.DEF_MARGIN_MAX : req.marginPctMax());
        t.setAllowMultiPosition(!Boolean.FALSE.equals(req.allowMultiPosition()));
        // 单仓+双开这个自相矛盾的组合，validateSpec 已经在入口拒掉了，这里不必再判一次
        t.setAllowHedge(Boolean.TRUE.equals(req.allowHedge()));
        t.setAlertEnabled(!Boolean.FALSE.equals(req.alertEnabled()));
        t.setAlertThresholdMult(req.alertThresholdMult() == null ? BigDecimal.ONE : req.alertThresholdMult());
        t.setReviewEnabled(!Boolean.FALSE.equals(req.reviewEnabled()));
        t.setLearningEnabled(!Boolean.FALSE.equals(req.learningEnabled()));
        // 存归一化文本（validate 已 parse 过），全天存 null
        WakeWindow window = WakeWindow.parse(req.wakeWindow());
        t.setWakeWindow(window == null ? null : window.text());
    }

    private static Set<String> parseSymbols(String symbols) {
        if (symbols == null || symbols.isBlank()) {
            return Set.of();
        }
        return new HashSet<>(Arrays.stream(symbols.split(","))
                .map(s -> s.trim().toUpperCase()).filter(s -> !s.isEmpty()).toList());
    }

    private static String accountName(long userId, int round) {
        return "ai_trader_" + userId + "_r" + round;
    }
}
