package com.mawai.wiibagent.learning;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.market.KlineBar;
import com.mawai.wiibcommon.market.KlineHistoryStore;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibquant.market.indicator.KlineStructureCalculator;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.trader.DecisionText;
import com.mawai.wiibagent.trader.TradePairing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 复盘素材组装（纯代码，可单测）：战绩表/配对表/时间线摘编/价格路径四块硬事实。
 * 事实裁定归代码、模型只解读——战绩数字只许复述，代码错一位就是复盘造假。
 * 素材窗口 = (上次成功REVIEW的wake_time, 本日线边界]；无REVIEW则本局开始（round过滤天然覆盖）。
 * <p>
 * 段标签全在 {@link PromptCatalog} 的 {@code reviewer.label.*}，按 reviewer 本轮的语言取。
 * 仓位⟵配对⟶计划与了结方式判定在 {@link TradePairing}，决策正文的结论块定位与 stale 剔除在
 * {@link DecisionText}——竞技场/同侪/统计共用同一套，各写一套迟早口径对不上。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewMaterialAssembler {

    /** 初始资金，与 TraderService.INITIAL_BALANCE 同一口径 */
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("10000");
    /** 时间线条目上限：5m 档一天 288 轮全文注入烧不起；有动作的行优先保全，早段观望被省略 */
    static final int MAX_TIMELINE_ENTRIES = 80;
    /** 动作行结论字数上限 */
    static final int ACTION_MAX_CHARS = 300;
    /** 「等待」段的正则按语言现编（标签跟着 trader 提示词走），编一次缓存住——一天几百行不必每行重编 */
    private final Map<AgentLang, Pattern> waitPatterns = new ConcurrentHashMap<>();
    /** 价格路径回看上限(小时)：窗口通常一天，首篇复盘 fromMs=0 时靠它兜住 */
    private static final int MAX_PATH_HOURS = 48;
    /** 已平仓位拉取上限：窗口通常一天，远超一天可能的成交笔数 */
    static final int CLOSED_FETCH_LIMIT = 200;
    /** 只进摘编的交易动作工具（get_account 是查户口不是动作） */
    private static final Set<String> ACTION_TOOLS = Set.of(
            "open_position", "close_position", "set_stop_loss",
            "set_take_profit", "cancel_order", "write_plan");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final AiTraderDecisionMapper decisionMapper;
    private final AiTraderPlanMapper planMapper;
    private final SimTradeClient simTradeClient;
    private final KlineHistoryStore historyStore;
    private final PromptCatalog prompts;
    private final DecisionText decisionText;

    /** 四块素材文本 + 已了结笔数（调用方日志用） */
    public record ReviewMaterial(String statsBlock, String tradesBlock,
                                 String timelineBlock, String pricePathBlock, int closedTrades) {
    }

    /**
     * 上一期成功的复盘行；无 → null（本局首篇，素材窗口从本局开始算）。
     * 一次查询两用：wake_time 定素材窗口起点，reasoning 全文回注给本期承接检验——
     * 上期立的"下期纪律"必须有人管，不然每期各写各的，闭环是断的。
     */
    public AiTraderDecision lastReview(long traderId, int roundNo) {
        return decisionMapper.selectOne(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, traderId)
                .eq(AiTraderDecision::getRoundNo, roundNo)
                .eq(AiTraderDecision::getKind, AiTraderDecision.KIND_REVIEW)
                .eq(AiTraderDecision::getStatus, AiTraderDecision.STATUS_OK)
                .orderByDesc(AiTraderDecision::getWakeTime)
                .last("LIMIT 1"));
    }

    /**
     * 窗口内有无新交易素材（TRADE/ALERT/MANUAL 的 OK 行）——无素材跳过复盘，不白烧钱。
     * 白名单不是黑名单：LEARN 行每天必有一条，用 ne(REVIEW) 排除的话它会天天充当"新素材"，
     * 无交易的日子复盘再也跳不过去
     */
    public boolean hasNewMaterial(long traderId, int roundNo, long fromMs, long toMs) {
        Long n = decisionMapper.selectCount(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, traderId)
                .eq(AiTraderDecision::getRoundNo, roundNo)
                .in(AiTraderDecision::getKind, AiTraderDecision.KIND_TRADE,
                        AiTraderDecision.KIND_ALERT, AiTraderDecision.KIND_MANUAL)
                .eq(AiTraderDecision::getStatus, AiTraderDecision.STATUS_OK)
                .gt(AiTraderDecision::getWakeTime, fromMs)
                .le(AiTraderDecision::getWakeTime, toMs));
        return n != null && n > 0;
    }

    /** 观望门控阈值（口径8，常量起步不做每档配置）：窗口内各币振幅全部低于此百分比才算"平静" */
    static final BigDecimal QUIET_AMPLITUDE_PCT = new BigDecimal("2");

    /**
     * 纯观望且各币平静（口径8）：窗口内无已了结交易、无开仓动作，且各币窗口振幅
     * （(最高-最低)/开盘）全部低于 {@link #QUIET_AMPLITUDE_PCT}——这样的窗口跳过复盘不烧钱。
     * 开仓动作按 actionsJson 粗筛 open_position 字样：被拒的开仓尝试也算动过手，宁可多复盘不漏评。
     * 任一币窗口内无K线 → 不算平静（数据缺口时照常复盘）。振幅回看与价格路径同上限 48h。
     */
    public boolean quietHoldWindow(AiTrader trader, long fromMs, long toMs) {
        List<FuturesPositionDTO> closed = inWindow(
                simTradeClient.getClosedPositions(trader.getSimUserId(), CLOSED_FETCH_LIMIT), fromMs, toMs);
        if (!closed.isEmpty()) {
            return false;
        }
        Long opens = decisionMapper.selectCount(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, trader.getId())
                .eq(AiTraderDecision::getRoundNo, trader.getRoundNo())
                .in(AiTraderDecision::getKind, AiTraderDecision.KIND_TRADE,
                        AiTraderDecision.KIND_ALERT, AiTraderDecision.KIND_MANUAL)
                .gt(AiTraderDecision::getWakeTime, fromMs)
                .le(AiTraderDecision::getWakeTime, toMs)
                .like(AiTraderDecision::getActionsJson, "open_position"));
        if (opens != null && opens > 0) {
            return false;
        }
        long effectiveFrom = Math.max(fromMs, toMs - MAX_PATH_HOURS * 3_600_000L);
        for (String symbol : trader.getSymbols().split(",")) {
            symbol = symbol.trim();
            if (symbol.isEmpty()) {
                continue;
            }
            List<KlineBar> bars = historyStore.load(symbol, KlineHistoryStore.DEFAULT_INTERVAL,
                    effectiveFrom, toMs);
            if (bars.isEmpty()) {
                return false;
            }
            BigDecimal high = bars.stream().map(KlineBar::high).max(BigDecimal::compareTo).orElseThrow();
            BigDecimal low = bars.stream().map(KlineBar::low).min(BigDecimal::compareTo).orElseThrow();
            BigDecimal open = bars.getFirst().open();
            if (open.signum() <= 0 || high.subtract(low).multiply(BigDecimal.valueOf(100))
                    .divide(open, 2, RoundingMode.HALF_UP).compareTo(QUIET_AMPLITUDE_PCT) >= 0) {
                return false;
            }
        }
        return true;
    }

    public ReviewMaterial assemble(AiTrader trader, long fromMs, long toMs, AgentLang lang) {
        // 已平仓位是"最近N条"，取满上限就说明可能被截断——战绩表得把这件事说出来，
        // 不能一边宣称"硬事实、禁止自行计算"一边给不完整的数字
        List<FuturesPositionDTO> fetched = simTradeClient.getClosedPositions(trader.getSimUserId(), CLOSED_FETCH_LIMIT);
        boolean maybeTruncated = fetched.size() >= CLOSED_FETCH_LIMIT;
        List<FuturesPositionDTO> closed = inWindow(fetched, fromMs, toMs);
        // 配对一次、配对表与战绩表共用；stale 过滤在配对之后——被忽略的计划仍占配对位，先滤后配会错配
        List<AiTraderPlan> plans = planMapper.selectList(new LambdaQueryWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getTraderId, trader.getId())
                .eq(AiTraderPlan::getRoundNo, trader.getRoundNo()));
        Map<FuturesPositionDTO, AiTraderPlan> planByPos = TradePairing.pairAll(closed, plans);
        // 主人标记忽略的交易从复盘教材整体消失（配对表 + 战绩表的了结统计行）；权益线来自决策行序列，不动
        List<FuturesPositionDTO> visible = closed.stream()
                .filter(p -> !isStale(planByPos.get(p)))
                .toList();
        String stats = statsBlock(trader, fromMs, toMs, visible, maybeTruncated, lang);
        String trades = tradesBlock(visible, planByPos, plans, fromMs, toMs, lang);
        String timeline = timelineBlock(trader, fromMs, toMs, plans, lang);
        String pricePath = pricePathBlock(trader, fromMs, toMs, lang);
        return new ReviewMaterial(stats, trades, timeline, pricePath, visible.size());
    }

    private static boolean isStale(AiTraderPlan p) {
        return p != null && Boolean.TRUE.equals(p.getStale());
    }

    // ==================== 战绩表 ====================

    private String statsBlock(AiTrader t, long fromMs, long toMs, List<FuturesPositionDTO> closed,
                              boolean maybeTruncated, AgentLang lang) {
        // 起始权益 = 窗口起点前最后一条带权益的决策行；开局首次复盘无前值 → 初始资金
        AiTraderDecision prior = decisionMapper.selectOne(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .isNotNull(AiTraderDecision::getEquity)
                .le(AiTraderDecision::getWakeTime, fromMs)
                .orderByDesc(AiTraderDecision::getWakeTime)
                .last("LIMIT 1"));
        BigDecimal start = prior != null ? prior.getEquity() : INITIAL_BALANCE;
        List<AiTraderDecision> series = decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                .select(AiTraderDecision::getWakeTime, AiTraderDecision::getEquity)
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .isNotNull(AiTraderDecision::getEquity)
                .gt(AiTraderDecision::getWakeTime, fromMs)
                .le(AiTraderDecision::getWakeTime, toMs)
                .orderByAsc(AiTraderDecision::getWakeTime));
        BigDecimal end = series.isEmpty() ? start : series.getLast().getEquity();

        BigDecimal returnPct = start.signum() > 0
                ? end.subtract(start).multiply(BigDecimal.valueOf(100)).divide(start, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal peak = start;
        BigDecimal maxDd = BigDecimal.ZERO;
        for (AiTraderDecision d : series) {
            BigDecimal e = d.getEquity();
            if (e.compareTo(peak) > 0) {
                peak = e;
            } else if (peak.signum() > 0) {
                BigDecimal dd = peak.subtract(e).multiply(BigDecimal.valueOf(100))
                        .divide(peak, 2, RoundingMode.HALF_UP);
                if (dd.compareTo(maxDd) > 0) {
                    maxDd = dd;
                }
            }
        }
        long wins = closed.stream().filter(p -> p.getClosedPnl() != null && p.getClosedPnl().signum() > 0).count();
        BigDecimal pnlSum = closed.stream().map(FuturesPositionDTO::getClosedPnl)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);

        StringBuilder sb = new StringBuilder(prompts.get(lang, "reviewer.label.statsHeader")).append('\n');
        if (maybeTruncated) {
            sb.append(prompts.get(lang, "reviewer.label.statsTruncated",
                    Map.of("limit", CLOSED_FETCH_LIMIT))).append('\n');
        }
        sb.append(prompts.get(lang, "reviewer.label.statsEquity", Map.of(
                "start", start.setScale(2, RoundingMode.HALF_UP),
                "end", end.setScale(2, RoundingMode.HALF_UP),
                "pct", signed(returnPct)))).append('\n');
        sb.append(prompts.get(lang, "reviewer.label.statsDrawdown", Map.of("dd", maxDd))).append('\n');
        if (closed.isEmpty()) {
            sb.append(prompts.get(lang, "reviewer.label.noClosed")).append('\n');
        } else {
            sb.append(prompts.get(lang, "reviewer.label.statsClosed", Map.of(
                    "n", closed.size(), "wins", wins, "losses", closed.size() - wins,
                    "winRate", wins * 100 / closed.size(),
                    "pnl", signed(pnlSum.setScale(2, RoundingMode.HALF_UP))))).append('\n');
        }
        return sb.toString();
    }

    // ==================== 已了结交易配对表 ====================

    private String tradesBlock(List<FuturesPositionDTO> visible, Map<FuturesPositionDTO, AiTraderPlan> planByPos,
                               List<AiTraderPlan> plans, long fromMs, long toMs, AgentLang lang) {
        StringBuilder sb = new StringBuilder(prompts.get(lang, "reviewer.label.tradesHeader")).append('\n');
        int i = 1;
        for (FuturesPositionDTO pos : visible) {
            AiTraderPlan plan = planByPos.get(pos);
            sb.append(i++).append(". ").append(pos.getSymbol()).append(' ').append(pos.getSide());
            if (plan != null && plan.getPlayType() != null) {
                sb.append(" [").append(plan.getPlayType()).append(']');
            }
            sb.append(' ').append(tradeRow(prompts, pos, lang)).append('\n');
            if (plan != null) {
                sb.append("   ").append(planLine(prompts, plan.getSignalsUsed(),
                        plan.getInvalidationCondition(), lang)).append('\n');
            } else {
                sb.append("   ").append(prompts.get(lang, "reviewer.label.noPlan")).append('\n');
            }
        }
        // 窗口内归档却没配对上仓位的计划（多为挂单未成交撤销）：论点没得到执行机会也要留痕；stale 的不出
        Set<AiTraderPlan> paired = new HashSet<>(planByPos.values());
        for (AiTraderPlan plan : plans) {
            if (AiTraderPlan.STATUS_CLOSED.equals(plan.getStatus()) && !paired.contains(plan)
                    && !isStale(plan)
                    && plan.getClosedWakeTime() != null
                    && plan.getClosedWakeTime() > fromMs && plan.getClosedWakeTime() <= toMs) {
                sb.append("· ").append(plan.getSymbol()).append(' ').append(plan.getSide())
                        .append(" [").append(nullSafe(plan.getPlayType())).append("] ")
                        .append(prompts.get(lang, "reviewer.label.orphanPlan",
                                Map.of("signals", nullSafe(plan.getSignalsUsed())))).append('\n');
            }
        }
        if (i == 1 && sb.indexOf("·") < 0) {
            sb.append(prompts.get(lang, "reviewer.label.noClosed")).append('\n');
        }
        return sb.toString();
    }

    /**
     * 一笔已了结交易的行尾（入场→出场/盈亏/持有/了结方式）。
     * 与 plain/signed/nullSafe 同样对同包 {@link PeerInsightService} 开放：同一批数字两处视角，
     * 格式化各写一套迟早口径对不上。做成静态、词表当入参传——调用方不必为了借个格式化器去装配整个 bean。
     */
    static String tradeRow(PromptCatalog prompts, FuturesPositionDTO pos, AgentLang lang) {
        return prompts.get(lang, "reviewer.label.tradeRow", Map.of(
                "entry", plain(pos.getEntryPrice()),
                "exit", plain(pos.getClosedPrice()),
                "pnl", signed(pos.getClosedPnl()),
                "held", humanize(prompts, TradePairing.msOf(pos.getUpdatedAt())
                        - TradePairing.msOf(pos.getCreatedAt()), lang),
                "manner", TradePairing.closeManner(prompts, pos, lang)));
    }

    /** 论点/失效条件那一行，同样对同侪详情开放 */
    static String planLine(PromptCatalog prompts, String signalsUsed, String invalidationCondition,
                           AgentLang lang) {
        return prompts.get(lang, "reviewer.label.planLine", Map.of(
                "signals", nullSafe(signalsUsed),
                "invalidation", nullSafe(invalidationCondition)));
    }

    // ==================== 决策时间线摘编 ====================

    private record TimelineEntry(String line, boolean hasAction) {
    }

    private String timelineBlock(AiTrader t, long fromMs, long toMs, List<AiTraderPlan> plans, AgentLang lang) {
        // 白名单同 hasNewMaterial：时间线是交易行为的摘编，LEARN/REVIEW 进来会虚增"唤醒轮数"，
        // 保守度自检的对照物就失真了
        List<AiTraderDecision> rows = decisionMapper.selectList(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .in(AiTraderDecision::getKind, AiTraderDecision.KIND_TRADE,
                        AiTraderDecision.KIND_ALERT, AiTraderDecision.KIND_MANUAL)
                .gt(AiTraderDecision::getWakeTime, fromMs)
                .le(AiTraderDecision::getWakeTime, toMs)
                .orderByAsc(AiTraderDecision::getWakeTime));
        List<TimelineEntry> entries = new ArrayList<>();
        // <6h 碎观望不逐条列，收进这里、段尾一行汇总（口径7）
        List<Hold> shorts = new ArrayList<>();
        List<String> symbols = Arrays.stream(t.getSymbols().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        int errors = 0;
        int skipped = 0;
        int opens = 0;
        // HOLD 段游标（按币各一个）：同币连续同一等待条件压成一段，遇动作行或条件变化就结算。
        // 15m 档一天 96 轮，行情不动时几十轮等的是同一句话，一轮一行只会把动作行的信号稀释掉。
        // 错误格式（没分段）整块观望占 WHOLE 伪键，与新格式的币键互不干扰
        Map<String, Hold> holds = new LinkedHashMap<>();
        for (AiTraderDecision d : rows) {
            if (AiTraderDecision.STATUS_ERROR.equals(d.getStatus())) {
                errors++;
                continue;
            }
            if (AiTraderDecision.STATUS_SKIPPED.equals(d.getStatus())) {
                skipped++;
                continue;
            }
            // stale 治理：新格式剔段后继续；错误格式落在 stale 生命期内整行剔（唤醒轮数仍按原始行统计）
            String reasoning = decisionText.staleFiltered(d, plans);
            if (reasoning == null) {
                continue;
            }
            String acts = actionSummary(d, plans, lang);
            String tag = AiTraderDecision.KIND_ALERT.equals(d.getKind())
                    ? prompts.get(lang, "reviewer.label.alertTag") + " " : "";
            if (!acts.isEmpty()) {
                // 动作行逐条出、内容不动：它是复盘主菜，判断/依据/等待整块都是"为什么做这一手"的证据。
                // 只结算涉及币（与 WHOLE）的观望游标：无关币的连续等待被别币动作冲碎后，
                // 6h 分层会让它永远凑不满对账块、条件在汇总行里失声
                Set<String> acted = actedSymbols(d.getActionsJson(), plans);
                if (acted == null) {
                    flushHolds(entries, shorts, symbols, holds, lang);
                } else {
                    acted.add(WHOLE);
                    for (String key : acted) {
                        Hold h = holds.remove(key);
                        if (h != null) {
                            flushHold(entries, shorts, symbols, h, lang);
                        }
                    }
                }
                // 数开仓动作按摘要文本认工具名：actionSummary 已过滤成 tool(args) 形态，误中不了正文
                opens += countOccurrences(acts, "open_position(");
                entries.add(new TimelineEntry("- " + TIME_FMT.format(Instant.ofEpochMilli(d.getWakeTime()))
                        + " " + tag + prompts.get(lang, "reviewer.label.actionRow", Map.of(
                                "actions", acts, "conclusion", conclusion(reasoning, lang))), true));
                continue;
            }
            // 观望轮只留等待条件：它有对账物（价格路径能验证到没到），"判断"那段指标读数没有。
            // 警报轮与例行观望不混段：同样条件下被警报叫醒仍按兵不动，这件事本身就是复盘证据
            String kindPrefix = tag.isEmpty() ? "N|" : "A|";
            for (Map.Entry<String, String> w : waitsBySymbol(reasoning, lang).entrySet()) {
                String key = kindPrefix + waitKey(w.getValue());
                Hold h = holds.get(w.getKey());
                if (h != null && h.key.equals(key)) {
                    h.to = d.getWakeTime();
                    h.rounds++;
                } else {
                    if (h != null) {
                        flushHold(entries, shorts, symbols, h, lang);
                    }
                    holds.put(w.getKey(), new Hold(key, d.getKind(), w.getKey(), w.getValue(), d.getWakeTime()));
                }
            }
        }
        flushHolds(entries, shorts, symbols, holds, lang);

        StringBuilder sb = new StringBuilder(prompts.get(lang, "reviewer.label.timelineHeader")).append('\n');
        // 活动统计给保守度自检当对照物：唤醒多动作少是"没信号"还是"吓缩了"，得先有数才能问。
        // 轮数取自原始行而非合并后的段数——合并只是省字，"这期醒了多少次"不能跟着缩水
        sb.append(prompts.get(lang, "reviewer.label.timelineActivity", Map.of(
                "rounds", rows.size(),
                "actionRounds", entries.stream().filter(TimelineEntry::hasAction).count(),
                "opens", opens))).append('\n');
        if (entries.size() > MAX_TIMELINE_ENTRIES) {
            // 动作行全保、无动作 HOLD 从最新往回补足额度：复盘的主菜是动作，观望看最近的就够
            int budget = MAX_TIMELINE_ENTRIES - (int) entries.stream().filter(TimelineEntry::hasAction).count();
            Set<Integer> keep = new HashSet<>();
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (entries.get(i).hasAction()) {
                    keep.add(i);
                } else if (budget > 0) {
                    keep.add(i);
                    budget--;
                }
            }
            sb.append(prompts.get(lang, "reviewer.label.timelineOmitted",
                    Map.of("n", entries.size() - keep.size()))).append('\n');
            List<TimelineEntry> kept = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                if (keep.contains(i)) {
                    kept.add(entries.get(i));
                }
            }
            entries = kept;
        }
        if (entries.isEmpty() && shorts.isEmpty() && errors == 0 && skipped == 0) {
            sb.append(prompts.get(lang, "reviewer.label.timelineEmpty")).append('\n');
        }
        entries.forEach(e -> sb.append(e.line()).append('\n'));
        // 碎观望一行带过：段数/轮数/最长跨度给保守度自检当底数，条件本身从略
        if (!shorts.isEmpty()) {
            long maxSpanMs = shorts.stream().mapToLong(h -> h.to - h.from).max().orElse(0);
            sb.append(prompts.get(lang, "reviewer.label.shortHolds", Map.of(
                    "n", shorts.size(),
                    "rounds", shorts.stream().mapToInt(h -> h.rounds).sum(),
                    "hours", String.format(java.util.Locale.ROOT, "%.1f", maxSpanMs / 3_600_000.0)))).append('\n');
        }
        if (errors > 0 || skipped > 0) {
            List<String> parts = new ArrayList<>(2);
            if (errors > 0) {
                parts.add(prompts.get(lang, "reviewer.label.timelineErrors", Map.of("n", errors)));
            }
            if (skipped > 0) {
                parts.add(prompts.get(lang, "reviewer.label.timelineSkipped", Map.of("n", skipped)));
            }
            sb.append(prompts.get(lang, "reviewer.label.timelineOthers", Map.of("parts",
                    String.join(prompts.get(lang, "reviewer.label.timelineOthersSep"), parts)))).append('\n');
        }
        return sb.toString();
    }

    /**
     * 动作轨迹 JSON → 一行摘要；只取交易动作工具，拒/错标注结果，被忽略交易的动作剔除
     * （否则忽略承诺在动作行上漏水——结论段剔了、开平仓摘要还在）。解析失败当无动作（摘编缺一行不挡复盘）。
     */
    private String actionSummary(AiTraderDecision d, List<AiTraderPlan> plans, AgentLang lang) {
        if (d.getActionsJson() == null || d.getActionsJson().isBlank()) {
            return "";
        }
        try {
            JSONArray arr = JSON.parseArray(d.getActionsJson());
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject a = arr.getJSONObject(i);
                String tool = a.getString("tool");
                if (tool == null || !ACTION_TOOLS.contains(tool)
                        || DecisionText.staleAction(a, d.getWakeTime(), plans)) {
                    continue;
                }
                JSONObject args = a.getJSONObject("args");
                StringBuilder brief = new StringBuilder();
                if (args != null) {
                    for (String k : new String[]{"symbol", "side", "quantity", "positionId"}) {
                        Object v = args.get(k);
                        if (v != null) {
                            brief.append(brief.isEmpty() ? "" : " ").append(v);
                        }
                    }
                }
                // pending＝转成待主人确认的请求，本轮并没有成交，摘编里不标就成了"平了仓"的假事实
                String outcome = a.containsKey("rejected")
                        ? prompts.get(lang, "reviewer.label.outcomeRejected")
                        : "error".equals(a.getString("status"))
                        ? prompts.get(lang, "reviewer.label.outcomeFailed")
                        : "pending".equals(a.getString("status"))
                        ? prompts.get(lang, "reviewer.label.outcomePending") : "";
                parts.add(tool + "(" + brief + ")" + outcome);
            }
            return String.join(prompts.get(lang, "reviewer.label.actionJoin"), parts);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 动作行的结论：结论块整块，截断保头（块内判断在前）。
     * 没有结论块（错误格式）退化为截尾片段——结论在末尾，保头会正好把它切掉。
     */
    private String conclusion(String reasoning, AgentLang lang) {
        if (reasoning == null || reasoning.isBlank()) {
            return "";
        }
        DecisionText.Conclusion c = decisionText.locateConclusion(reasoning, lang);
        if (c == null) {
            String tail = reasoning.strip();
            return (tail.length() > 120 ? "…" + tail.substring(tail.length() - 120) : tail).replace('\n', ' ');
        }
        String flat = c.body(reasoning).replace('\n', ' ');
        return flat.length() > ACTION_MAX_CHARS ? flat.substring(0, ACTION_MAX_CHARS) + "…" : flat;
    }

    /** 错误格式（没分段）整块观望在按币容器里的伪键：没有分段标记时全部条件归它 */
    static final String WHOLE = "";

    /**
     * 观望轮的等待条件按币抽取：新格式（总分结构）每个 [SYMBOL] 段各抽各的，键=币码；
     * 错误格式（没分段）整块抽一条，键={@link #WHOLE}。没有结论块 → 单条 WHOLE 空值（"这轮没给条件"）。
     */
    LinkedHashMap<String, String> waitsBySymbol(String reasoning, AgentLang lang) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (reasoning == null || reasoning.isBlank()) {
            out.put(WHOLE, "");
            return out;
        }
        DecisionText.Conclusion c = decisionText.locateConclusion(reasoning, lang);
        if (c == null) {
            out.put(WHOLE, "");
            return out;
        }
        String body = c.body(reasoning);
        List<DecisionText.ConclusionSegment> segments = DecisionText.splitSegments(body);
        if (segments.isEmpty()) {
            out.put(WHOLE, extractWait(body, c.lang()));
            return out;
        }
        for (DecisionText.ConclusionSegment s : segments) {
            out.put(s.symbol(), extractWait(s.body(), c.lang()));
        }
        return out;
    }

    /**
     * 从一段结论正文里抽"等待"：按标签块切而不是按行取——模型有时把条件写在标签同一行、
     * 有时换行分条列，整段吃到下一个小节标签才两种都接得住。行首锚定防正文里的"等待："被误认。
     * 小节标签按结论块自己那门语言认；没有等待段就是没有，不拿正文冒充条件——
     * 退回正文尾巴的话，对账那步会拿行情叙述当条件判命中，编出假结论。
     */
    private String extractWait(String body, AgentLang lang) {
        Matcher m = waitPattern(lang).matcher(body);
        return m.find() ? m.group(1).replaceAll("\\s+", " ").strip() : "";
    }

    /**
     * 「等待」段正则：{@code 等待条件|等待} 起头，吃到下一个小节标签或块尾。
     * 标签取自 trader 的固定收尾格式，两门语言各一套。
     */
    private Pattern waitPattern(AgentLang lang) {
        return waitPatterns.computeIfAbsent(lang, l -> Pattern.compile(
                "(?ms)^\\s*(?:" + Pattern.quote(prompts.get(l, "trader.mark.waitLong")) + "|"
                        + Pattern.quote(prompts.get(l, "trader.mark.wait")) + ")[：:]\\h*(.*?)"
                        + "(?=^\\s*(?:" + Pattern.quote(prompts.get(l, "trader.mark.judgement")) + "|"
                        + Pattern.quote(prompts.get(l, "trader.mark.action")) + "|"
                        + Pattern.quote(prompts.get(l, "trader.mark.planBasis")) + ")[：:]|\\z)"));
    }

    /**
     * 合并键：只抹掉纯文字注解括号与空白（"（前高）""（观望）"）。
     * 带数字或条件词的括号一律留着——"转空（跌破 63140）"与"转空（跌破 62800）"括号外一模一样，
     * 抹掉就并成一段，而 flushHold 只输出段首那条，后一个价位在对账素材里彻底消失。
     * 宁可少合并几段（多占几行、早段被省略时还会明说省了几段），也不能把两个不同条件说成同一个。
     */
    private static String waitKey(String wait) {
        return wait.replaceAll("[（(](?![^）)]*[且或><≥≤0-9])[^）)]*[）)]", "").replaceAll("\\s+", "");
    }

    /** 真观望分界（口径7）：段跨度 ≥6h 才升格对账块，短于它的碎观望全部收进一行汇总 */
    static final long LONG_HOLD_MS = 6 * 3_600_000L;

    /** 观望段游标：同币连续同一等待条件的多轮压成一段。symbol={@link #WHOLE} 即错误格式（没分段）整块 */
    private static final class Hold {
        final String key;
        final String kind;
        final String symbol;
        final String wait;
        final long from;
        long to;
        int rounds;

        Hold(String key, String kind, String symbol, String wait, long at) {
            this.key = key;
            this.kind = kind;
            this.symbol = symbol;
            this.wait = wait;
            this.from = at;
            this.to = at;
            this.rounds = 1;
        }
    }

    /**
     * 动作行涉及的币（可变集合）：args.symbol 直取，只带 positionId 的动作经计划绑定反查；
     * 任一动作解析不出币 → null，调用方保守结算全部游标（历史无绑定数据的兜底）。
     * WHOLE（错误格式整块）由调用方自行加入——账户级叙述随任何动作作废。
     */
    private static Set<String> actedSymbols(String actionsJson, List<AiTraderPlan> plans) {
        try {
            JSONArray arr = JSON.parseArray(actionsJson);
            Set<String> out = new HashSet<>();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject a = arr.getJSONObject(i);
                if (!ACTION_TOOLS.contains(String.valueOf(a.getString("tool")))) {
                    continue;
                }
                JSONObject args = a.getJSONObject("args");
                String symbol = args == null ? null : args.getString("symbol");
                if (symbol == null) {
                    Long id = args == null ? null : args.getLong("positionId");
                    symbol = id == null ? null : plans.stream()
                            .filter(p -> p.getPositionId() != null && p.getPositionId().longValue() == id)
                            .map(AiTraderPlan::getSymbol).findFirst().orElse(null);
                }
                if (symbol == null) {
                    return null;
                }
                out.add(symbol);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 全部在途观望段一并结算（遇动作行/时间线收尾），按各段起始顺序输出后清空 */
    private void flushHolds(List<TimelineEntry> out, List<Hold> shorts, List<String> symbols,
                            Map<String, Hold> holds, AgentLang lang) {
        holds.values().forEach(h -> flushHold(out, shorts, symbols, h, lang));
        holds.clear();
    }

    /**
     * 结算一个观望段，按跨度分层（口径7）：<6h 碎观望进汇总桶，段尾一行带过；
     * ≥6h 真观望升格对账块——段头（起止/轮数）+ 等待条件全文 + 段起点结构快照，
     * 给 reviewer 显式评估"好观望还是错失"。按币的段带 [币码] 前缀（语言无关）。
     */
    private void flushHold(List<TimelineEntry> out, List<Hold> shorts, List<String> symbols,
                           Hold h, AgentLang lang) {
        if (h.to - h.from < LONG_HOLD_MS) {
            shorts.add(h);
            return;
        }
        String tag = AiTraderDecision.KIND_ALERT.equals(h.kind)
                ? prompts.get(lang, "reviewer.label.alertTag") + " " : "";
        String symbolTag = WHOLE.equals(h.symbol) ? "" : "[" + h.symbol + "] ";
        StringBuilder block = new StringBuilder("- ")
                .append(TIME_FMT.format(Instant.ofEpochMilli(h.from))).append('~')
                .append(TIME_FMT.format(Instant.ofEpochMilli(h.to)))
                .append(prompts.get(lang, "reviewer.label.holdRounds", Map.of("rounds", h.rounds)))
                .append(tag).append(symbolTag)
                .append(prompts.get(lang, "reviewer.label.holdAuditTag")).append('\n');
        // 等待条件全文不截断：它是这段对账的唯一原料
        block.append("  ").append(prompts.get(lang, "reviewer.label.waiting", Map.of(
                "wait", h.wait.isEmpty() ? prompts.get(lang, "reviewer.label.noWait") : h.wait))).append('\n');
        // 段起点结构快照：错误格式整块段不知道在等哪个币，各币都给一行
        for (String symbol : WHOLE.equals(h.symbol) ? symbols : List.of(h.symbol)) {
            block.append("  ").append(structureSnapshot(symbol, h.from, lang)).append('\n');
        }
        out.add(new TimelineEntry(block.substring(0, block.length() - 1), false));
    }

    /**
     * 段起点结构快照：本地 5m 聚合成 1h、as-of 截断到段起点，喂 {@link KlineStructureCalculator}
     * （与 kline_structure 工具同一套计算）后取一行摘要——方向（窗口涨跌）/近端摆动高低/段起点现价。
     * 不算"现价距条件价位的距离"：条件价位藏在自然语言里，程序抽取不可靠，对照是 LLM 的活。
     * 不出 ATR：快照窗口（48×1h）与工具的 192 根窗口不同，同名不同值会误导（见 IndicatorToolkit 告诫）。
     */
    private String structureSnapshot(String symbol, long asOfMs, AgentLang lang) {
        List<KlineBar> hourly = hourlyBars(symbol, asOfMs - MAX_PATH_HOURS * 3_600_000L, asOfMs);
        if (hourly.isEmpty()) {
            return prompts.get(lang, "reviewer.label.holdSnapshotNoBars", Map.of("symbol", symbol));
        }
        Map<String, Object> structure = KlineStructureCalculator.compute(hourly,
                KlineStructureCalculator.Params.defaults());
        Map<?, ?> range = (Map<?, ?>) structure.get("range");
        Map<?, ?> levels = (Map<?, ?>) structure.get("levels");
        return prompts.get(lang, "reviewer.label.holdSnapshot", Map.of(
                "symbol", symbol,
                "close", String.valueOf(range.get("close")),
                "hours", MAX_PATH_HOURS,
                "pct", String.valueOf(range.get("change_pct")),
                "highs", String.valueOf(levels.get("recent_swing_highs")),
                "lows", String.valueOf(levels.get("recent_swing_lows"))));
    }

    // ==================== 各币价格路径 ====================

    /**
     * 价格路径走本地 kline_history 的 5m 现聚合成 1h：复盘看的全是已收盘行情，本地就有
     * （feed 每根 5m 收盘落库），没理由为此打外网——外网抖一下这个币就没了对照物。
     * 与哨兵阈值校准、策略回测同源。
     * 48h 上限兜住首篇复盘（fromMs=0）：真实覆盖范围写进块头，观望对账拿错对照物结论就是假的。
     */
    private String pricePathBlock(AiTrader t, long fromMs, long toMs, AgentLang lang) {
        long effectiveFrom = Math.max(fromMs, toMs - MAX_PATH_HOURS * 3_600_000L);
        StringBuilder sb = new StringBuilder(prompts.get(lang, "reviewer.label.pathHeader", Map.of(
                "from", TIME_FMT.format(Instant.ofEpochMilli(effectiveFrom)),
                "to", TIME_FMT.format(Instant.ofEpochMilli(toMs))))).append('\n');
        if (fromMs == 0) {
            sb.append(prompts.get(lang, "reviewer.label.pathFirstNote")).append('\n');
        }
        for (String symbol : t.getSymbols().split(",")) {
            symbol = symbol.trim();
            if (symbol.isEmpty()) {
                continue;
            }
            List<KlineBar> hourly = hourlyBars(symbol, effectiveFrom, toMs);
            if (hourly.isEmpty()) {
                sb.append("- ").append(symbol).append(": ")
                        .append(prompts.get(lang, "reviewer.label.pathNoBars")).append('\n');
                continue;
            }
            BigDecimal open = hourly.getFirst().open();
            BigDecimal close = hourly.getLast().close();
            BigDecimal high = null;
            BigDecimal low = null;
            long highAt = 0;
            long lowAt = 0;
            StringBuilder closes = new StringBuilder();
            // 逐小时高低必须给：等待条件多是"回踩 63370–63480"这种区间触碰，只有收盘序列
            // 判不出"这一小时探到过没有"，模型要么瞎猜要么编，观望对账就成了假账
            StringBuilder ranges = new StringBuilder();
            for (KlineBar k : hourly) {
                if (high == null || k.high().compareTo(high) > 0) {
                    high = k.high();
                    highAt = k.openTime();
                }
                if (low == null || k.low().compareTo(low) < 0) {
                    low = k.low();
                    lowAt = k.openTime();
                }
                closes.append(closes.isEmpty() ? "" : "→").append(plain(k.close()));
                ranges.append(ranges.isEmpty() ? "" : "→")
                        .append(plain(k.high())).append('/').append(plain(k.low()));
            }
            BigDecimal pct = open.signum() > 0
                    ? close.subtract(open).multiply(BigDecimal.valueOf(100)).divide(open, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            sb.append("- ").append(symbol).append(": ")
                    .append(prompts.get(lang, "reviewer.label.pathRow", Map.of(
                            "open", plain(open), "close", plain(close), "pct", signed(pct),
                            "high", plain(high), "highAt", TIME_FMT.format(Instant.ofEpochMilli(highAt)),
                            "low", plain(low), "lowAt", TIME_FMT.format(Instant.ofEpochMilli(lowAt)))))
                    .append("\n  ").append(prompts.get(lang, "reviewer.label.pathCloses",
                            Map.of("closes", closes)))
                    .append("\n  ").append(prompts.get(lang, "reviewer.label.pathRanges",
                            Map.of("ranges", ranges))).append('\n');
        }
        return sb.toString();
    }

    /**
     * 本地 5m 现聚合成 1h：按整点分组，开=组内首根开、高/低=组内极值、收=组内末根收。
     * 不足 12 根的组照算（库里有缺口或窗口边界切在半路），价格路径要的是形状不是完整性。
     */
    private List<KlineBar> hourlyBars(String symbol, long fromMs, long toMs) {
        List<KlineBar> bars = historyStore.load(symbol, KlineHistoryStore.DEFAULT_INTERVAL, fromMs, toMs);
        List<KlineBar> out = new ArrayList<>();
        long curHour = -1;
        BigDecimal open = null;
        BigDecimal high = null;
        BigDecimal low = null;
        BigDecimal close = null;
        long closeTime = 0;
        for (KlineBar b : bars) {
            long hour = b.openTime() - Math.floorMod(b.openTime(), 3_600_000L);
            if (hour != curHour) {
                if (curHour >= 0) {
                    out.add(new KlineBar(curHour, closeTime, open, high, low, close, BigDecimal.ZERO));
                }
                curHour = hour;
                open = b.open();
                high = b.high();
                low = b.low();
            } else {
                high = high.max(b.high());
                low = low.min(b.low());
            }
            close = b.close();
            closeTime = b.closeTime();
        }
        if (curHour >= 0) {
            out.add(new KlineBar(curHour, closeTime, open, high, low, close, BigDecimal.ZERO));
        }
        return out;
    }

    // ==================== 小工具 ====================
    // msOf/plain/signed/nullSafe/humanize 对同包 PeerInsightService 开放：
    // 同侪详情与复盘素材是同一批数字的两种视角，格式化各写一套迟早会出现"两处口径对不上"

    private static List<FuturesPositionDTO> inWindow(List<FuturesPositionDTO> fetched, long fromMs, long toMs) {
        return fetched.stream()
                .filter(p -> p.getUpdatedAt() != null)
                .filter(p -> {
                    long closedAt = TradePairing.msOf(p.getUpdatedAt());
                    return closedAt > fromMs && closedAt <= toMs;
                })
                .sorted(Comparator.comparing(FuturesPositionDTO::getUpdatedAt))
                .toList();
    }

    static String plain(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }

    static String signed(BigDecimal v) {
        if (v == null) {
            return "?";
        }
        return v.signum() >= 0 ? "+" + v.toPlainString() : v.toPlainString();
    }

    static String nullSafe(String s) {
        return s == null ? "—" : s;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    static String humanize(PromptCatalog prompts, long ms, AgentLang lang) {
        long min = Math.max(0, ms / 60_000);
        if (min < 120) {
            return prompts.get(lang, "reviewer.label.duration.minutes", Map.of("n", min));
        }
        long hours = min / 60;
        return hours < 48
                ? prompts.get(lang, "reviewer.label.duration.hours", Map.of("n", hours))
                : prompts.get(lang, "reviewer.label.duration.days", Map.of("n", hours / 24));
    }
}
