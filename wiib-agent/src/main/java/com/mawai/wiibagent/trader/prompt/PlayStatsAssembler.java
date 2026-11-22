package com.mawai.wiibagent.trader.prompt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.learning.CaseFileBuilder;
import com.mawai.wiibagent.trader.trade.PlanEntry;
import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 本局论点战绩统计（纯代码，可单测）：sim 已平仓位 ⟵配对⟶ 本局计划，按论点标签聚合成
 * 统计块，另按入场方式、按币各分一次桶。两份：交易员每轮开场白的（{@link #assemble}），
 * 复盘素材和指令生成器的多一段开仓后不动的重放汇总（{@link #assembleWithReplay}）。配对复用 {@link TradePairing#pairAll}——复盘/竞技场/统计
 * 三处必须同一套算法。事实裁定归代码，模型只许引用不许自算，与复盘战绩表同一条纪律。
 * <p>
 * stale 过滤在<b>配对之后</b>：被忽略的计划仍参与配对占位，先滤后配会让它的仓位
 * 错配到同 symbol/side 的别的计划上。
 * <p>
 * 任何取数/配对失败返回 null——统计块缺席不挡唤醒。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlayStatsAssembler {

    /** 统计窗口：最近配对成功的了结笔数上限 */
    static final int MAX_TRADES = 50;
    /** 单标签低于这个样本量只列笔数不给胜负——样本少时看不出方法好坏 */
    static final int MIN_SAMPLE = 5;
    /** 已平仓位拉取上限，与复盘素材同口径；唤醒取已平仓位也用它，两处口径必须一致 */
    public static final int FETCH_LIMIT = 200;

    private final SimTradeClient simTradeClient;
    private final AiTraderPlanMapper planMapper;
    private final PromptCatalog prompts;
    private final CaseFileBuilder caseFiles;

    /** 一笔已了结、配上了计划的交易 */
    private record Settled(FuturesPositionDTO pos, AiTraderPlan plan) {
    }

    /** 交易员每轮开场白的统计块；本局无可统计交易或任何失败 → null（不注入） */
    public String assemble(AiTrader t, AgentLang lang) {
        return orAbsent(t, () -> block(settled(t), "", lang));
    }

    /** 复盘素材和指令生成器的统计块：比开场白那份多一段开仓后不动的重放汇总，样本够了才带 */
    public String assembleWithReplay(AiTrader t, AgentLang lang) {
        return orAbsent(t, () -> {
            List<Settled> trades = settled(t);
            List<CaseFileBuilder.Replay> replays = trades.stream()
                    .map(s -> caseFiles.replay(s.pos(), s.plan())).filter(Objects::nonNull).toList();
            return block(trades, replays.size() >= MIN_SAMPLE ? replayLines(replays, lang) : "", lang);
        });
    }

    /** 取数、配对出错不挡调用方：这一块缺席 */
    private String orAbsent(AiTrader t, Supplier<String> block) {
        try {
            return block.get();
        } catch (Exception e) {
            log.warn("[PlayStats] 论点战绩统计失败，本轮不注入 traderId={} msg={}", t.getId(), e.toString());
            return null;
        }
    }

    /** 本局最近了结、配上了计划的交易，最近的在前，最多 {@value #MAX_TRADES} 笔；主人标了不算数的不在里面 */
    private List<Settled> settled(AiTrader t) {
        List<FuturesPositionDTO> closed = simTradeClient.getClosedPositions(t.getSimUserId(), FETCH_LIMIT);
        if (closed.isEmpty()) {
            return List.of();
        }
        List<AiTraderPlan> plans = planMapper.selectList(new LambdaQueryWrapper<AiTraderPlan>()
                .eq(AiTraderPlan::getTraderId, t.getId())
                .eq(AiTraderPlan::getRoundNo, t.getRoundNo()));
        // 配对走 pairAll 统一入口；sim 按 updatedAt 倒序返回，顺序遍历即最近优先
        Map<FuturesPositionDTO, AiTraderPlan> planByPos = TradePairing.pairAll(closed, plans);
        List<Settled> out = new ArrayList<>();
        for (FuturesPositionDTO pos : closed) {
            AiTraderPlan plan = planByPos.get(pos);
            if (plan == null || plan.getPlayType() == null || pos.getClosedPnl() == null
                    || Boolean.TRUE.equals(plan.getStale())) {
                continue;
            }
            out.add(new Settled(pos, plan));
            if (out.size() >= MAX_TRADES) {
                break;
            }
        }
        return out;
    }

    /** 成文：标签行、入场方式行、按币行，extra 接在后面，最后是 footer；一笔都没有返回 null */
    private String block(List<Settled> trades, String extra, AgentLang lang) {
        if (trades.isEmpty()) {
            return null;
        }
        Map<String, List<BigDecimal>> byPlay = new LinkedHashMap<>();
        // 同一批了结再按入场方式（限价 / 市价 / 分档）分一次桶；入场上下文里没记的另计笔数
        Map<String, List<BigDecimal>> byEntry = new LinkedHashMap<>();
        int unknownEntry = 0;
        // 再按币分一次桶
        Map<String, List<BigDecimal>> bySymbol = new LinkedHashMap<>();
        for (Settled s : trades) {
            BigDecimal pnl = s.pos().getClosedPnl();
            byPlay.computeIfAbsent(s.plan().getPlayType(), k -> new ArrayList<>()).add(pnl);
            String entry = entryType(s.plan());
            if (entry == null) {
                unknownEntry++;
            } else {
                byEntry.computeIfAbsent(entry, k -> new ArrayList<>()).add(pnl);
            }
            bySymbol.computeIfAbsent(s.pos().getSymbol(), k -> new ArrayList<>()).add(pnl);
        }
        StringBuilder sb = new StringBuilder(prompts.get(lang, "trader.label.playStatsHeader",
                Map.of("n", trades.size()))).append('\n');
        byPlay.entrySet().stream()
                .sorted((a, b) -> b.getValue().size() - a.getValue().size())
                .forEach(e -> sb.append(row(e.getKey(), e.getValue(), lang)).append('\n'));
        if (!byEntry.isEmpty()) {
            sb.append(entryLine(byEntry, unknownEntry, lang)).append('\n');
        }
        sb.append(prompts.get(lang, "trader.label.playStatsSymbolLine", Map.of("rows", bucketRows(
                bySymbol.entrySet().stream().sorted((a, b) -> b.getValue().size() - a.getValue().size()).toList(),
                symbol -> symbol, lang)))).append('\n');
        sb.append(extra);
        sb.append(prompts.get(lang, "trader.label.playStatsFooter")).append('\n');
        return sb.toString();
    }

    /** 首批档的入场方式；旧计划从入场上下文取。 */
    private static String entryType(AiTraderPlan plan) {
        var initial = PlanEntry.read(plan).stream().filter(e -> !e.isAdd()).toList();
        if (initial.size() > 1) return "LADDER";
        if (initial.size() == 1) return initial.getFirst().getLimitPrice() == null ? "MARKET" : "LIMIT";
        if (plan.getEntryCtxJson() == null) {
            return null;
        }
        String type = MAPPER.readTree(plan.getEntryCtxJson()).path("orderType").asString(null);
        return "LIMIT".equals(type) || "MARKET".equals(type) || "LADDER".equals(type) ? type : null;
    }

    private String row(String play, List<BigDecimal> pnls, AgentLang lang) {
        int n = pnls.size();
        if (n < MIN_SAMPLE) {
            return prompts.get(lang, "trader.label.playStatsRowSmall", Map.of("play", play, "n", n));
        }
        long wins = pnls.stream().filter(p -> p.signum() > 0).count();
        return prompts.get(lang, "trader.label.playStatsRow", Map.of(
                "play", play, "n", n, "wins", wins, "losses", n - wins, "pnl", signedSum(pnls)));
    }

    /** 按入场方式一行：限价、市价各自的笔数、胜负、合计；样本不足的只给笔数 */
    private String entryLine(Map<String, List<BigDecimal>> byEntry, int unknown, AgentLang lang) {
        return prompts.get(lang, "trader.label.playStatsEntryLine", Map.of(
                "rows", bucketRows(byEntry.entrySet(),
                        entry -> prompts.get(lang, "trader.label.playStatsEntry." + entry.toLowerCase(Locale.ROOT)), lang),
                "unknown", unknown == 0 ? "" : prompts.get(lang, "trader.label.playStatsEntryUnknown", Map.of("n", unknown))));
    }

    /** 几个桶拼成一行，每桶：名字、笔数、胜负、合计；样本不足的只给笔数 */
    private String bucketRows(Collection<Map.Entry<String, List<BigDecimal>>> buckets, Function<String, String> name, AgentLang lang) {
        StringBuilder rows = new StringBuilder();
        for (Map.Entry<String, List<BigDecimal>> e : buckets) {
            int n = e.getValue().size();
            if (!rows.isEmpty()) {
                rows.append(prompts.get(lang, "trader.label.playStatsEntrySep"));
            }
            if (n < MIN_SAMPLE) {
                rows.append(prompts.get(lang, "trader.label.playStatsBucketRowSmall", Map.of("name", name.apply(e.getKey()), "n", n)));
            } else {
                long wins = e.getValue().stream().filter(p -> p.signum() > 0).count();
                rows.append(prompts.get(lang, "trader.label.playStatsBucketRow", Map.of(
                        "name", name.apply(e.getKey()), "n", n, "wins", wins, "losses", n - wins, "pnl", signedSum(e.getValue()))));
            }
        }
        return rows.toString();
    }

    /**
     * 重放汇总：一行按止损宽度列到目标 / 止损 / 未决各几笔与折算盈亏合计；
     * "实际"和"开仓后不动"两边都有数的笔够了再加一行管理动作。复盘素材的本期汇总也用它。
     */
    public String replayLines(List<CaseFileBuilder.Replay> replays, AgentLang lang) {
        CaseFileBuilder.ReplaySummary s = CaseFileBuilder.summarize(replays);
        StringBuilder rows = new StringBuilder();
        for (CaseFileBuilder.WidthTally w : s.widths()) {
            if (!rows.isEmpty()) {
                rows.append(prompts.get(lang, "trader.label.playStatsEntrySep"));
            }
            rows.append(prompts.get(lang, "trader.label.playStatsReplayRow", Map.of(
                    "width", w.width().compareTo(BigDecimal.ONE) == 0
                            ? prompts.get(lang, "trader.label.playStatsReplayOriginal")
                            : prompts.get(lang, "trader.label.playStatsReplayWidth", Map.of("width", w.width().toPlainString())),
                    "target", w.target(), "stop", w.stop(), "open", w.open(), "r", signed(w.r()))));
        }
        StringBuilder sb = new StringBuilder(prompts.get(lang, "trader.label.playStatsReplayLine",
                Map.of("n", s.trades(), "rows", rows.toString()))).append('\n');
        if (s.managed() > 0) {
            String adds = s.withAdds() == 0 ? ""
                    : prompts.get(lang, "trader.label.playStatsManageAdds", Map.of("n", s.withAdds()));
            sb.append(prompts.get(lang, "trader.label.playStatsManageLine", Map.of(
                    "n", s.managed(), "actual", signed(s.actualR()), "untouched", signed(s.untouchedR()), "adds", adds))).append('\n');
        }
        return sb.toString();
    }

    private static String signedSum(List<BigDecimal> pnls) {
        return signed(pnls.stream().reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP));
    }

    private static String signed(BigDecimal v) {
        return v.signum() >= 0 ? "+" + v.toPlainString() : v.toPlainString();
    }
}
