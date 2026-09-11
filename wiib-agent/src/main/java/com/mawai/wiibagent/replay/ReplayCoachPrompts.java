package com.mawai.wiibagent.replay;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 复盘 AI 教练的提示词与入参校验：不碰模型不碰网络，单测直接钉。
 * <p>
 * 文本全在 {@link PromptCatalog} 的 {@code coach.*}，按用户的 {@link AgentLang} 取；
 * 骨架（表格拼接、数字格式化）留在这里。
 * <p>
 * 两个硬约束：
 * <ul>
 *   <li><b>只依据给定数据</b>：盲测局的时间是相对标签，系统提示明确禁止猜日期/引用真实历史事件——
 *       模型认得历史行情，一旦联想到具体日期就等于把答案告诉了正在盲测的用户；</li>
 *   <li><b>中性、不下单</b>：提示只讲结构/关键位/量能/风险点和"什么走势会确认或否定"，不给买卖指令；
 *       评估只对着成交与走势评行为，用户不用先写看法（一点就评）。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ReplayCoachPrompts {

    /** 一次最多送多少根 K 线：HINT 一屏上下文够用，REVIEW 前端会把整局聚合到这个数以内 */
    public static final int MAX_BARS = 400;
    public static final int MAX_TRADES = 300;

    private final PromptCatalog prompts;

    /** 入参校验：返回错误文案（会直接抛成 BizException 上屏），合法返回 null */
    public String validate(ReplayCoachRequest r, AgentLang lang) {
        if (r == null) {
            return prompts.get(lang, "coach.error.empty");
        }
        if (!ReplayCoachRequest.MODE_REVIEW.equals(r.mode()) && !ReplayCoachRequest.MODE_HINT.equals(r.mode())) {
            return prompts.get(lang, "coach.error.mode");
        }
        if (r.symbol() == null || r.symbol().isBlank() || r.symbol().length() > 20) {
            return prompts.get(lang, "coach.error.symbol");
        }
        if (r.intervalMin() == null || r.intervalMin() <= 0) {
            return prompts.get(lang, "coach.error.interval");
        }
        if (r.bars() == null || r.bars().isEmpty()) {
            return prompts.get(lang, "coach.error.barsEmpty");
        }
        if (r.bars().size() > MAX_BARS) {
            return prompts.get(lang, "coach.error.barsTooMany", Map.of("limit", MAX_BARS));
        }
        if (r.positions() != null && r.positions().size() > 2) {
            return prompts.get(lang, "coach.error.positions");
        }
        if (r.trades() != null && r.trades().size() > MAX_TRADES) {
            return prompts.get(lang, "coach.error.tradesTooMany", Map.of("limit", MAX_TRADES));
        }
        return null;
    }

    public String system(ReplayCoachRequest r, AgentLang lang) {
        return prompts.get(lang, ReplayCoachRequest.MODE_REVIEW.equals(r.mode())
                ? "coach.review.system" : "coach.hint.system");
    }

    /** 用户消息：紧凑的表格文本，token 省着用（几百根 K 线一行一根） */
    public String user(ReplayCoachRequest r, AgentLang lang) {
        StringBuilder sb = new StringBuilder(64 + r.bars().size() * 48);
        sb.append(prompts.get(lang, "coach.label.header", Map.of(
                "symbol", r.symbol(),
                "interval", r.intervalMin(),
                "timeMode", prompts.get(lang, Boolean.TRUE.equals(r.blind())
                        ? "coach.label.blind" : "coach.label.realTime")))).append('\n');
        if (r.startAt() != null && !r.startAt().isBlank()) {
            sb.append(prompts.get(lang, "coach.label.startAt", Map.of("start", r.startAt()))).append('\n');
        }
        sb.append(prompts.get(lang, "coach.label.barsHeader", Map.of("n", r.bars().size()))).append('\n');
        for (ReplayCoachRequest.Bar b : r.bars()) {
            sb.append(b.t()).append(' ').append(num(b.o())).append(' ').append(num(b.h())).append(' ')
                    .append(num(b.l())).append(' ').append(num(b.c())).append(' ').append(num(b.v())).append('\n');
        }
        List<ReplayCoachRequest.Position> positions = r.positions() == null ? List.of() : r.positions();
        if (ReplayCoachRequest.MODE_HINT.equals(r.mode())) {
            if (r.equity() != null) {
                sb.append(prompts.get(lang, "coach.label.equity",
                        Map.of("value", num(Math.round(r.equity() * 100) / 100.0)))).append('\n');
            }
            sb.append(prompts.get(lang, "coach.label.positionsHeader"));
            if (positions.isEmpty()) {
                sb.append(prompts.get(lang, "coach.label.none"));
            }
            for (ReplayCoachRequest.Position p : positions) {
                sb.append(prompts.get(lang, "coach.label.position", Map.of(
                        "side", side(lang, p.side()),
                        "qty", num(p.qty()),
                        "entry", num(p.entryPrice()),
                        "lev", lev(p.leverage()),
                        "pnl", signed(p.unrealizedPnl()))));
            }
            sb.append('\n');
            return outputLanguage(sb, lang);
        }

        List<ReplayCoachRequest.Trade> trades = r.trades() == null ? List.of() : r.trades();
        sb.append(prompts.get(lang, "coach.label.tradesHeader", Map.of("n", trades.size()))).append('\n');
        if (trades.isEmpty()) {
            sb.append(prompts.get(lang, "coach.label.noTrades")).append('\n');
        }
        int i = 1;
        for (ReplayCoachRequest.Trade t : trades) {
            sb.append(prompts.get(lang, "coach.label.trade", Map.of(
                    "i", i++,
                    "side", side(lang, t.side()),
                    "lev", lev(t.leverage()),
                    "openAt", t.openAt(),
                    "entry", num(t.entryPrice()),
                    "reason", reason(lang, t.reason(), t.partial()),
                    "closeAt", t.closeAt(),
                    "exit", num(t.exitPrice()),
                    "qty", num(t.qty()),
                    "pnl", signed(t.pnl())))).append('\n');
        }
        ReplayCoachRequest.Stats s = r.stats();
        if (s != null) {
            sb.append(prompts.get(lang, "coach.label.stats", Map.of(
                    "initial", num(s.initialBalance()),
                    "finalEquity", num(s.finalEquity()),
                    "net", signed(s.netProfit()),
                    "pct", pct(s.returnPct()),
                    "total", s.totalTrades(),
                    "wins", s.wins(),
                    "losses", s.losses(),
                    "drawdown", pct(s.maxDrawdownPct()),
                    "fees", num(s.totalFees())))).append('\n');
        }
        return outputLanguage(sb, lang);
    }

    /** 输出语言硬收尾：用户消息最末一行。系统提示词里说过一次，这是第二次 */
    private String outputLanguage(StringBuilder sb, AgentLang lang) {
        return sb.append(prompts.get(lang, "coach.label.outputLanguage")).append('\n').toString();
    }

    /** 去掉浮点尾巴：前端已按币种精度取整，这里只负责别把 3450.0 打成 3450.0000000001 */
    static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "-";
        }
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    private static String signed(double v) {
        return (v >= 0 ? "+" : "") + num(Math.round(v * 100) / 100.0);
    }

    private static String pct(double ratio) {
        return (ratio >= 0 ? "+" : "") + num(Math.round(ratio * 10000) / 100.0) + "%";
    }

    /** 有效杠杆：整数照常，加仓换档产生的小数留一位 */
    private static String lev(double leverage) {
        return num(Math.round(leverage * 10) / 10.0) + "x";
    }

    private String side(AgentLang lang, String side) {
        return prompts.get(lang, "SHORT".equals(side) ? "coach.label.side.short" : "coach.label.side.long");
    }

    private String reason(AgentLang lang, String reason, boolean partial) {
        if ("LIQUIDATION".equals(reason)) {
            return prompts.get(lang, "coach.label.reason.liquidation");
        }
        if ("END".equals(reason)) {
            return prompts.get(lang, "coach.label.reason.end");
        }
        return prompts.get(lang, partial ? "coach.label.reason.partial" : "coach.label.reason.close");
    }
}
