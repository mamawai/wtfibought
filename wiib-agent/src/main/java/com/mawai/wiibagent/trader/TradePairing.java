package com.mawai.wiibagent.trader;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 已了结交易的归因（纯静态，不装 bean）：已平仓位 ⟵配对⟶ 交易计划，以及一笔是怎么走的（止损/止盈/强平/主动平仓）。
 * 复盘、同侪学习、竞技场、唤醒统计四处共用这一套——各配一套、各判一套，同一笔交易在两处会得出不同结论。
 */
public final class TradePairing {

    private TradePairing() {
    }

    /**
     * 统一配对入口：已平仓位 ⟵配对⟶ 本局计划，两趟——先按 position_id 精确 join（开仓即落 id 的
     * 新数据），剩余未绑定的仓位按平仓时刻升序跑 {@link #bestMatch} 时间就近兜底（无 id 的历史行）。
     * 复盘/竞技场/统计/同侪四处共用这一个入口：各配各的、喂入顺序不同（复盘升序、竞技场倒序），
     * 贪心就近对顺序敏感，同一笔交易会在两处配到不同计划。
     * <p>
     * 兜底池只放无 id 的计划：带 id 的计划要么已在精确趟配走，要么它的仓位不在本批——
     * 拿它配别的仓位就是明知故犯的错配。键用对象身份，不依赖仓位 id 非空。
     */
    public static Map<FuturesPositionDTO, AiTraderPlan> pairAll(List<FuturesPositionDTO> positions,
                                                                List<AiTraderPlan> plans) {
        Map<FuturesPositionDTO, AiTraderPlan> out = new IdentityHashMap<>();
        Map<Long, AiTraderPlan> byPosId = new HashMap<>();
        plans.forEach(p -> {
            if (p.getPositionId() != null) {
                byPosId.putIfAbsent(p.getPositionId(), p);
            }
        });
        List<FuturesPositionDTO> unbound = new ArrayList<>();
        for (FuturesPositionDTO pos : positions) {
            AiTraderPlan hit = pos.getId() == null ? null : byPosId.get(pos.getId());
            if (hit != null) {
                out.put(pos, hit);
            } else {
                unbound.add(pos);
            }
        }
        List<AiTraderPlan> unboundPlans = plans.stream().filter(p -> p.getPositionId() == null).toList();
        unbound.sort(Comparator.comparingLong(p -> msOf(p.getUpdatedAt())));
        Set<AiTraderPlan> used = new HashSet<>();
        for (FuturesPositionDTO pos : unbound) {
            AiTraderPlan plan = bestMatch(unboundPlans, pos, used);
            if (plan != null) {
                out.put(pos, plan);
            }
        }
        return out;
    }

    /**
     * 同 symbol/side 里选开仓时刻最贴近该仓位开仓时间的计划（懒归档时刻粗糙，openedWakeTime 才可靠）。
     * 兜底算法：新数据的精确配对与喂入顺序统一都在 {@link #pairAll}，消费端一律走那个入口。
     */
    public static AiTraderPlan bestMatch(List<AiTraderPlan> plans, FuturesPositionDTO pos, Set<AiTraderPlan> used) {
        long posOpen = msOf(pos.getCreatedAt());
        long posClose = msOf(pos.getUpdatedAt());
        return plans.stream()
                .filter(p -> !used.contains(p))
                .filter(p -> pos.getSymbol().equals(p.getSymbol()) && pos.getSide().equals(p.getSide()))
                .filter(p -> p.getOpenedWakeTime() != null && p.getOpenedWakeTime() <= posClose)
                .min(Comparator.comparingLong(p -> Math.abs(p.getOpenedWakeTime() - posOpen)))
                .map(p -> {
                    used.add(p);
                    return p;
                })
                .orElse(null);
    }

    /** 主动平仓的码：竞技场判"要不要挂平仓决策"靠它，别再拿文案字符串比 */
    public static final String MANNER_MANUAL = "manual";
    /** 判不出来时的码：它本身就是码不是文案，两门语言都原样透传 */
    public static final String MANNER_UNKNOWN = "UNKNOWN";

    /**
     * 了结方式推断（返回<b>语言无关的码</b>）：强平看状态；止损/止盈用方向性对照——触发价是探测时的
     * markPrice 会越过挂单价，不能按相等判。保护单实时监控在先，带内成交只能是主动平仓
     * （模型自己调 close_position，没有别的入口）。全平不清保护单列表（sim 只在部分平仓时改写），closed 行上的列表
     * 就是了结时在岗的那组。
     * <p>码与文案分家：竞技场按码做判断（"主动平仓才挂平仓决策"），码不随语言变。
     */
    public static String closeMannerKey(FuturesPositionDTO p) {
        if ("LIQUIDATED".equals(p.getStatus())) {
            return "liquidated";
        }
        BigDecimal cp = p.getClosedPrice();
        if (cp == null) {
            return MANNER_UNKNOWN;
        }
        boolean isLong = "LONG".equals(p.getSide());
        if (p.getStopLosses() != null && p.getStopLosses().stream().anyMatch(sl ->
                isLong ? cp.compareTo(sl.getPrice()) <= 0 : cp.compareTo(sl.getPrice()) >= 0)) {
            return "stopLoss";
        }
        if (p.getTakeProfits() != null && p.getTakeProfits().stream().anyMatch(tp ->
                isLong ? cp.compareTo(tp.getPrice()) >= 0 : cp.compareTo(tp.getPrice()) <= 0)) {
            return "takeProfit";
        }
        return MANNER_MANUAL;
    }

    /** 了结方式文案：码 → 词表；UNKNOWN 没有文案，原样给出去 */
    public static String closeManner(PromptCatalog prompts, FuturesPositionDTO p, AgentLang lang) {
        String key = closeMannerKey(p);
        return MANNER_UNKNOWN.equals(key) ? key
                : prompts.get(lang, "reviewer.label.closeManner." + key);
    }

    /** 仓位时间字段（LocalDateTime）的统一读法：配对按它排序，展示按它算持有时长 */
    public static long msOf(java.time.LocalDateTime t) {
        return t == null ? 0 : t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
