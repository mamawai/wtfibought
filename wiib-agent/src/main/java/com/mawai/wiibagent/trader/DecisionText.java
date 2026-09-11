package com.mawai.wiibagent.trader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 决策正文的读法：结论块怎么定位、按币怎么分段、被主人标记"不算数"（stale）的交易怎么从正文里剔掉。
 * 唤醒回注、对话回看、复盘摘编三处共用——各剔一套，被否掉的那笔交易会从某个口子漏回去继续教坏模型。
 * <p>
 * 结论标记在 {@link PromptCatalog} 的 {@code trader.mark.*}。
 * <b>读旧决策时两门语言的标记都认</b>：库里的决策是当时那门语言写的，用户切过语言后只认当前这套，
 * 整条时间线会被判成"没给等待条件"，观望对账直接空转。
 */
@Component
@RequiredArgsConstructor
public class DecisionText {

    private final PromptCatalog prompts;

    /** 命中的结论块：块正文 + 它是用哪门语言写的（小节标签得按同一门认） */
    public record Conclusion(AgentLang lang, int index, int markLength) {
        public String body(String reasoning) {
            return reasoning.substring(index + markLength).strip();
        }
    }

    /**
     * 找结论块：先认当前语言的标记，认不到再试别的语言。
     * 决策行按写入时的语言落库，中途切语言不能丢历史；两套标记字面不同，多认一套不误伤。
     */
    public Conclusion locateConclusion(String reasoning, AgentLang lang) {
        Conclusion hit = matchConclusion(reasoning, lang);
        if (hit != null) {
            return hit;
        }
        for (AgentLang other : AgentLang.values()) {
            if (other != lang) {
                hit = matchConclusion(reasoning, other);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private Conclusion matchConclusion(String reasoning, AgentLang lang) {
        String mark = prompts.get(lang, "trader.mark.conclusion");
        int idx = reasoning.lastIndexOf(mark);
        return idx < 0 ? null : new Conclusion(lang, idx, mark.length());
    }

    /** 结论块全文（从标记起到末尾），两门语言的标记都认；没有 → null */
    public String conclusionBlock(String reasoning) {
        if (reasoning == null || reasoning.isBlank()) {
            return null;
        }
        Conclusion c = locateConclusion(reasoning, AgentLang.ZH);
        return c == null ? null : reasoning.substring(c.index());
    }

    // ==================== 结论分段（总分结构） ====================

    /**
     * 结论块内的币种分段标记：方括号币码独占一行（[BTCUSDT]）。语言无关——两门语言的模板同一形状。
     * 只在结论块正文里匹配，[ROUND CONCLUSION] 带空格够不到，[本轮结论]、[警报] 非拉丁字母也够不到。
     */
    private static final Pattern SEGMENT_TAG = Pattern.compile("(?m)^\\s*\\[([A-Z0-9]{2,20})]\\s*$");

    /** 结论块里的一个币种分段：段头币码 + 段身（判断/动作/等待） */
    public record ConclusionSegment(String symbol, String body) {
    }

    /**
     * 结论块正文按 [SYMBOL] 标记切段；无标记（错误格式）返回空列表。
     * 首个标记之前的引子（总评）不绑定任何币，不进结果——归属计算只认分段。
     */
    public static List<ConclusionSegment> splitSegments(String conclusionBody) {
        Matcher m = SEGMENT_TAG.matcher(conclusionBody);
        List<ConclusionSegment> out = new ArrayList<>();
        String symbol = null;
        int start = 0;
        while (m.find()) {
            if (symbol != null) {
                out.add(new ConclusionSegment(symbol, conclusionBody.substring(start, m.start())));
            }
            symbol = m.group(1);
            start = m.end();
        }
        if (symbol != null) {
            out.add(new ConclusionSegment(symbol, conclusionBody.substring(start)));
        }
        return out;
    }

    // ==================== 被标记不算数的交易，从决策正文里剔掉 ====================

    /**
     * 主人把某笔交易标记成"这笔不算数"（stale）之后，这行决策还剩多少字能用。
     * 时间线、唤醒回注、chat 都走这里，免得被否掉的那笔继续教坏模型。
     * 结论块按 [SYMBOL] 分了段的，只剔那个币的段，别的币照留；
     * 错误格式（没按币分段或没写结论块）剔不了段，退化成按轮兜底：这轮落在任一 stale 计划生命期内就整行不要返回 null。
     * 没有 stale 计划就原样返回，一个字不动，错误格式的行也照留。
     */
    public String staleFiltered(AiTraderDecision d, List<AiTraderPlan> plans) {
        String reasoning = d.getReasoning() == null ? "" : d.getReasoning();
        if (plans.stream().noneMatch(p -> Boolean.TRUE.equals(p.getStale()))) {
            return reasoning;
        }
        // 起点语言随便给：locateConclusion 两门语言的标记都会尝试
        Conclusion c = reasoning.isBlank() ? null : locateConclusion(reasoning, AgentLang.ZH);
        if (c != null && SEGMENT_TAG.matcher(c.body(reasoning)).find()) {
            return scrubStaleSegments(reasoning, c, d.getWakeTime(), plans);
        }
        return inStaleLifetime(d.getWakeTime(), plans) ? null : reasoning;
    }

    /** 新格式剔段：结论块里被忽略交易的 [SYMBOL] 段连段头一起剔，引子（总评）与其余段保留 */
    private String scrubStaleSegments(String reasoning, Conclusion c, long wakeTime, List<AiTraderPlan> plans) {
        int bodyStart = c.index() + c.markLength();
        String body = reasoning.substring(bodyStart);
        Matcher m = SEGMENT_TAG.matcher(body);
        StringBuilder out = new StringBuilder(reasoning.substring(0, bodyStart));
        String symbol = null;
        int segStart = 0;
        while (m.find()) {
            if (symbol == null) {
                out.append(body, 0, m.start());
            } else if (!staleSegment(symbol, wakeTime, plans)) {
                out.append(body, segStart, m.start());
            }
            symbol = m.group(1);
            segStart = m.start();
        }
        if (!staleSegment(symbol, wakeTime, plans)) {
            out.append(body, segStart, body.length());
        }
        return out.toString();
    }

    /**
     * 该轮该币的分段是否属于被忽略交易：wake 落在某 stale 计划生命期内，且覆盖该时刻的
     * 该币计划<b>全部</b> stale——双开粒度=币，任一方向没被忽略这段就得留。
     */
    private static boolean staleSegment(String symbol, long wakeTime, List<AiTraderPlan> plans) {
        boolean hasStale = false;
        for (AiTraderPlan p : plans) {
            if (symbol.equals(p.getSymbol()) && covers(p, wakeTime)) {
                if (!Boolean.TRUE.equals(p.getStale())) {
                    return false;
                }
                hasStale = true;
            }
        }
        return hasStale;
    }

    private static boolean covers(AiTraderPlan p, long wakeTime) {
        return p.getOpenedWakeTime() != null && p.getOpenedWakeTime() <= wakeTime
                && wakeTime <= (p.getClosedWakeTime() == null ? Long.MAX_VALUE : p.getClosedWakeTime());
    }

    /** 错误格式按轮兜底：这轮 wakeTime 落在任一 stale 计划的生命期内，时间规则与新格式剔段同一条 */
    private static boolean inStaleLifetime(long wakeTime, List<AiTraderPlan> plans) {
        for (AiTraderPlan p : plans) {
            if (Boolean.TRUE.equals(p.getStale()) && covers(p, wakeTime)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单个动作是否属于被忽略交易（时间线摘要/chat 工具名共用同一识别核心）：
     * positionId 命中 stale 仓位绑定，或该轮是 stale 计划的开仓轮且动作开的正是该币向。
     */
    public static boolean staleAction(JSONObject action, long wakeTime, List<AiTraderPlan> plans) {
        JSONObject args = action == null ? null : action.getJSONObject("args");
        for (AiTraderPlan p : plans) {
            if (!Boolean.TRUE.equals(p.getStale())) {
                continue;
            }
            Long id = args == null ? null : args.getLong("positionId");
            if (p.getPositionId() != null && id != null && id.longValue() == p.getPositionId()) {
                return true;
            }
            if ("open_position".equals(Objects.requireNonNull(action).getString("tool"))
                    && Objects.equals(p.getOpenedWakeTime(), wakeTime)
                    && args != null
                    && p.getSymbol().equals(args.getString("symbol"))
                    && p.getSide().equals(args.getString("side"))) {
                return true;
            }
        }
        return false;
    }

    /** chat 决策行的工具名列表（stale 治理后）：被忽略交易的动作名剔除，数据工具与其余动作照常 */
    public List<String> staleFilteredToolNames(AiTraderDecision d, List<AiTraderPlan> plans) {
        if (d.getActionsJson() == null || d.getActionsJson().isBlank()) {
            return List.of();
        }
        try {
            JSONArray arr = JSON.parseArray(d.getActionsJson());
            List<String> out = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                JSONObject a = arr.getJSONObject(i);
                String tool = a.getString("tool");
                if (tool == null || staleAction(a, d.getWakeTime(), plans)) {
                    continue;
                }
                out.add(tool);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
