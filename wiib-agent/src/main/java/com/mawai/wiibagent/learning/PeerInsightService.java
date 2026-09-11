package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.mapper.AiTraderDecisionMapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.trader.TradePairing;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 同侪只读查询（learning agent 的眼睛）：排行榜快照 + 单 trader 深看详情，两个方法都返回拼好的文本块。
 * 文本块整块进 learning 的用户消息，所以段名跟<b>看的人</b>的语言走（{@code learning.label.peer.*}）；
 * 块里回注的复盘/学习笔记是别人写的原文，是哪门语言就是哪门，不翻译。
 * 只读——不碰账本、不写任何人的数据，包括看的人自己的。
 * <b>同侪池一把尺子</b>（{@link #peers()}）：同意学习 + 未暂停 + 在场（手里有仓，或最近一笔了结在 24h 内）。
 * 调度侧的门槛计数、排行榜、detail 三处同一口径——不在池里的既凑不了人数，也不进任何人的学习素材。
 * 爆仓的凭强平那笔了结在 24h 内留在榜上当前车之鉴，过后自然退场；注册后从没跑过的空壳不占名额。
 * 事实裁定归代码、模型只做甄别：收益率/笔数/论点→结局配对全在这里算死，模型拿到的是既成事实，
 * 它要判断的是"这份战绩值不值得学"，而不是"这个数对不对"。
 * 每行硬带已了结笔数：样本量不摆出来，模型就会把 1 笔的运气当成方法论。
 */
@Component
@RequiredArgsConstructor
public class PeerInsightService {

    /** 初始资金，与 TraderService.INITIAL_BALANCE 同一口径（每局子账户都按这个数注资，收益率的分母） */
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("10000");
    /** 排行榜里复盘摘要的截断长度：一行一句话画像，全文去 detail 看 */
    static final int DIGEST_MAX_CHARS = 80;
    /** 详情页配对表条数上限：够看出手法就行，全部战绩不是这里的活 */
    static final int DETAIL_TRADES = 8;
    /** 在场窗口：空仓的 trader 最近一笔了结距今超过这个时长就不算在场 */
    static final long ACTIVE_WINDOW_MS = 24 * 3600_000L;

    private final AiTraderMapper traderMapper;
    private final AiTraderDecisionMapper decisionMapper;
    private final AiTraderPlanMapper planMapper;
    private final SimTradeClient simTradeClient;
    private final ReviewMaterialAssembler assembler;
    private final PromptCatalog prompts;

    /** 墙钟注入点：在场窗口要可测 */
    java.util.function.LongSupplier nowMs = System::currentTimeMillis;

    /** 榜单一行的已算好事实（排序要先算完再排，所以先落成对象） */
    private record Row(AiTrader trader, BigDecimal returnPct, int closed, String digest) {
    }

    /** 同侪池（按 id 升序）：调度侧数门槛用，与排行榜/detail 同一把尺子 */
    public List<AiTrader> peers() {
        return allTraders().stream().filter(t -> eligible(t, closedPositions(t))).toList();
    }

    /**
     * 本局排行榜快照：同侪池里的全上榜，好的坏的都在。
     * 每行 = 谁 + 什么状态 + 赚亏多少 + 几笔样本 + 一句话复盘画像，selfTraderId 那行标出来。
     * 每个 trader 三次查询（权益/复盘/已平仓）不合并：trader 数量级几十，省这点查询不值得把 SQL 绕复杂。
     */
    public String leaderboard(long selfTraderId, AgentLang lang) {
        List<Row> rows = new ArrayList<>();
        for (AiTrader t : allTraders()) {
            List<FuturesPositionDTO> closed = closedPositions(t);
            if (!eligible(t, closed)) {
                continue;
            }
            AiTraderDecision review = assembler.lastReview(t.getId(), t.getRoundNo());
            rows.add(new Row(t, returnPct(t), closed.size(),
                    review == null ? null : review.getReasoning()));
        }
        rows.sort(Comparator.comparing(Row::returnPct).reversed());

        StringBuilder sb = new StringBuilder(prompts.get(lang, "learning.label.peer.leaderboardHeader"));
        int i = 1;
        for (Row r : rows) {
            sb.append(i++).append(". [id=").append(r.trader().getId()).append("] ")
                    .append(prompts.get(lang, "learning.label.peer.row", Map.of(
                            "name", r.trader().getName(),
                            "status", statusText(r.trader().getStatus(), lang),
                            "pct", ReviewMaterialAssembler.signed(r.returnPct()),
                            "closed", countText(r.closed()),
                            "digest", digest(r.digest(), lang))));
            // 不标出自己那行，模型会把自己的战绩当外人的经验学一遍
            if (r.trader().getId() == selfTraderId) {
                sb.append(prompts.get(lang, "learning.label.peer.self"));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 单 trader 深看：复盘全文 / 学习笔记 / 在场计划 / 最近已了结交易的论点→结局配对。
     * 查无此人返回中文错误文本而不是抛异常——调用方是工具，这段话要原样透传给模型自己纠正。
     */
    public String detail(long traderId, AgentLang lang) {
        AiTrader t = traderMapper.selectById(traderId);
        if (t == null) {
            return prompts.get(lang, "learning.label.peer.notFound", Map.of("id", traderId));
        }
        List<FuturesPositionDTO> closed = closedPositions(t);
        if (!eligible(t, closed)) {
            // 榜上没有它，但模型可能拿着旧笔记里的 id 来查：同样出一段话拒绝，透传给模型自己换人
            return prompts.get(lang, "learning.label.peer.notShared", Map.of("id", traderId));
        }
        // 一次拉本局全部计划在内存里分用：LIVE 的进在场计划块，其余的给已了结交易配对
        List<AiTraderPlan> plans = planMapper.selectList(new LambdaQueryWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getTraderId, t.getId())
                .eq(AiTraderPlan::getRoundNo, t.getRoundNo()));

        StringBuilder sb = new StringBuilder();
        sb.append(prompts.get(lang, "learning.label.peer.header", Map.of(
                "name", t.getName(), "id", t.getId(),
                "status", statusText(t.getStatus(), lang),
                "pct", ReviewMaterialAssembler.signed(returnPct(t)),
                "closed", countText(closed.size())))).append("\n\n");

        AiTraderDecision review = assembler.lastReview(t.getId(), t.getRoundNo());
        sb.append(prompts.get(lang, "learning.label.peer.latestReview")).append('\n')
                .append(blank(review == null ? null : review.getReasoning())
                        ? prompts.get(lang, "learning.label.peer.noReview") : review.getReasoning().strip())
                .append("\n\n");

        sb.append(prompts.get(lang, "learning.label.peer.notes")).append('\n')
                .append(blank(t.getLearningNotes())
                        ? prompts.get(lang, "learning.label.peer.noNotes") : t.getLearningNotes().strip())
                .append("\n\n");

        sb.append(prompts.get(lang, "learning.label.peer.livePlans")).append('\n');
        List<AiTraderPlan> live = plans.stream()
                .filter(p -> AiTraderPlan.STATUS_LIVE.equals(p.getStatus())).toList();
        if (live.isEmpty()) {
            sb.append(prompts.get(lang, "learning.label.peer.noLivePlans")).append('\n');
        }
        for (AiTraderPlan p : live) {
            sb.append("- ").append(p.getSymbol()).append(' ').append(p.getSide())
                    .append(" [").append(ReviewMaterialAssembler.nullSafe(p.getPlayType())).append("]\n")
                    .append("  ").append(ReviewMaterialAssembler.planLine(prompts,
                            p.getSignalsUsed(), p.getInvalidationCondition(), lang))
                    .append('\n');
        }

        sb.append('\n').append(prompts.get(lang, "learning.label.peer.recentTrades",
                Map.of("n", DETAIL_TRADES))).append('\n');
        // sim 侧已按 updatedAt 倒序返回（见 SimTradeClient.getClosedPositions），直接取前 N 就是最近 N 笔
        List<FuturesPositionDTO> recent = closed.stream().limit(DETAIL_TRADES).toList();
        if (recent.isEmpty()) {
            sb.append(prompts.get(lang, "learning.label.peer.noRecentTrades")).append('\n');
        }
        // 配对走 pairAll 统一入口：同一笔交易在同侪详情与复盘/竞技场里必须配到同一份计划
        Map<FuturesPositionDTO, AiTraderPlan> planByPos = TradePairing.pairAll(recent, plans);
        int i = 1;
        for (FuturesPositionDTO pos : recent) {
            AiTraderPlan plan = planByPos.get(pos);
            sb.append(i++).append(". ").append(pos.getSymbol()).append(' ').append(pos.getSide());
            if (plan != null && plan.getPlayType() != null) {
                sb.append(" [").append(plan.getPlayType()).append(']');
            }
            sb.append(' ').append(ReviewMaterialAssembler.tradeRow(prompts, pos, lang)).append('\n');
            if (plan != null) {
                sb.append("   ").append(ReviewMaterialAssembler.planLine(prompts,
                        plan.getSignalsUsed(), plan.getInvalidationCondition(), lang)).append('\n');
            } else {
                sb.append("   ").append(prompts.get(lang, "reviewer.label.noPlan")).append('\n');
            }
        }
        return sb.toString();
    }

    // ==================== 同侪池准入 ====================

    private List<AiTrader> allTraders() {
        return traderMapper.selectList(new LambdaQueryWrapper<AiTrader>().orderByAsc(AiTrader::getId));
    }

    /**
     * 同意学习 + 未暂停 + 在场。在场 = 手里有仓（拿三天的波段单也算），或空仓但最近一笔了结在 24h 内
     * （sim 按 updatedAt 倒序返回，首条即最近；强平也在这份账本里，爆仓当天照样在场）。
     */
    private boolean eligible(AiTrader t, List<FuturesPositionDTO> closed) {
        if (Boolean.FALSE.equals(t.getLearningEnabled()) || AiTrader.STATUS_PAUSED.equals(t.getStatus())) {
            return false;
        }
        if (!simTradeClient.getAllPositions(t.getSimUserId()).isEmpty()) {
            return true;
        }
        return !closed.isEmpty()
                && TradePairing.msOf(closed.get(0).getUpdatedAt()) >= nowMs.getAsLong() - ACTIVE_WINDOW_MS;
    }

    // ==================== 硬事实计算 ====================

    /** 本局收益率% = 最新一条带 equity 的决策行 vs 初始资金；一次没醒过（无决策行）就是 0，不是负 */
    private BigDecimal returnPct(AiTrader t) {
        AiTraderDecision d = decisionMapper.selectOne(new LambdaQueryWrapper<AiTraderDecision>()
                .eq(AiTraderDecision::getTraderId, t.getId())
                .eq(AiTraderDecision::getRoundNo, t.getRoundNo())
                .isNotNull(AiTraderDecision::getEquity)
                .orderByDesc(AiTraderDecision::getWakeTime)
                .last("LIMIT 1"));
        if (d == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return d.getEquity().subtract(INITIAL_BALANCE)
                .multiply(BigDecimal.valueOf(100))
                .divide(INITIAL_BALANCE, 2, RoundingMode.HALF_UP);
    }

    /** 每局一个独立 sim 子账户，所以这个账户的已平仓位就是本局全部战绩，不用再按时间过滤 */
    private List<FuturesPositionDTO> closedPositions(AiTrader t) {
        return simTradeClient.getClosedPositions(t.getSimUserId(), ReviewMaterialAssembler.CLOSED_FETCH_LIMIT);
    }

    /** 顶到拉取上限时写成 "200+"：样本量说小了是保守，说死了是假事实 */
    private static String countText(int closed) {
        return closed >= ReviewMaterialAssembler.CLOSED_FETCH_LIMIT
                ? ReviewMaterialAssembler.CLOSED_FETCH_LIMIT + "+" : String.valueOf(closed);
    }

    /** 状态词：三种状态都得有词，爆仓的同侪照样上榜（前车之鉴） */
    private String statusText(String status, AgentLang lang) {
        String key = switch (status == null ? "" : status) {
            case AiTrader.STATUS_RUNNING -> "running";
            case AiTrader.STATUS_PAUSED -> "paused";
            case AiTrader.STATUS_LIQUIDATED -> "liquidated";
            default -> "unknown";
        };
        return prompts.get(lang, "learning.label.peer.status." + key);
    }

    /** 复盘一句话画像：跳过【本期复盘】/[REVIEW] 这类段标题，取首个有实质内容的行截断 */
    private String digest(String reasoning, AgentLang lang) {
        if (blank(reasoning)) {
            return prompts.get(lang, "learning.label.peer.noReview");
        }
        String line = reasoning.lines()
                .map(l -> stripTitle(l.strip()))
                .filter(l -> !l.isEmpty())
                .findFirst().orElse("");
        if (line.isEmpty()) {
            return prompts.get(lang, "learning.label.peer.noReview");
        }
        return line.length() > DIGEST_MAX_CHARS ? line.substring(0, DIGEST_MAX_CHARS) + "…" : line;
    }

    /**
     * 去掉行首的段标题：标题独占一行就变空行被跳过，标题后接着写正文就只留正文。
     * 中文【】与英文[]两套都剥——复盘是写入时那门语言落库的，只剥一套换语言后画像就变成一行标题。
     */
    private static String stripTitle(String line) {
        char open = line.isEmpty() ? ' ' : line.charAt(0);
        char close = open == '【' ? '】' : open == '[' ? ']' : ' ';
        if (close == ' ') {
            return line;
        }
        int end = line.indexOf(close);
        return end < 0 ? line : line.substring(end + 1).strip();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
