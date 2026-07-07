package com.mawai.wiibquant.agent.quant.signal;

import com.mawai.wiibquant.agent.quant.domain.AgentVote;
import com.mawai.wiibquant.agent.quant.domain.signal.Signal;
import com.mawai.wiibquant.agent.quant.domain.signal.SignalGroup;
import com.mawai.wiibquant.agent.quant.domain.signal.SignalLean;
import com.mawai.wiibquant.agent.quant.domain.signal.SignalPanel;
import com.mawai.wiibquant.agent.quant.domain.news.FilteredNewsItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mawai.wiibquant.agent.quant.domain.signal.SignalGroup.*;
import static com.mawai.wiibquant.agent.quant.domain.signal.SignalLean.*;

/**
 * 信号面板提取器：把 5 个 agent 产出的 flag/reasonCode + LLM 筛后的新闻条目，
 * 按"信号语义字典"重组为一等公民信号面板（{@link SignalPanel}）。
 *
 * <p><b>白名单策略</b>：只有字典登记过的 flag 才进面板；未知 flag（含 NO_DATA/TIMEOUT/
 * LOW_CONFIDENCE 等数据/错误/横切标记）一律忽略。agent 新增 flag 需同步字典，否则不进面板
 * （降级、不报错）——换"信号丢失"换"零噪音 + 单一可审计语义源"。</p>
 *
 * <p><b>反向解读已在字典内消化</b>：HIGH_FUNDING / LSR_EXTREME_LONG / PERP_PREMIUM_RICH 等
 * "拥挤"类信号 lean=BEARISH，与 agent 内部 score 符号（funding/basis/lsr 取负）一致。</p>
 *
 * <p>无状态、线程安全：字典为静态不可变，可单例复用。</p>
 */
public final class SignalExtractor {

    /** 精确匹配字典：flag → 语义。 */
    private static final Map<String, Meta> EXACT = buildExact();

    /** 前缀匹配字典：带时间框后缀的 flag（如 MA_BULLISH_5M）。prefix 间互不为前缀，无歧义。 */
    private static final List<PrefixMeta> PREFIX = buildPrefix();

    private record Meta(String label, SignalLean lean, SignalGroup group) {}

    private record PrefixMeta(String prefix, String labelPrefix, SignalLean lean, SignalGroup group) {}

    /**
     * @param votes        本轮全部 evidence 票（含 reasonCodes/riskFlags）
     * @param filteredNews LLM 筛后的新闻条目（比 reasonCodes 稳定，用作新闻信号源）
     */
    public SignalPanel extract(List<AgentVote> votes, List<FilteredNewsItem> filteredNews) {
        List<Signal> signals = new ArrayList<>();
        // 跨 agent/horizon 去重：同一 flag/新闻只收一次（多 horizon 会重复产同名 flag）
        Set<String> seen = new LinkedHashSet<>();

        if (votes != null) {
            for (AgentVote vote : votes) {
                collectCodes(vote, signals, seen);
            }
        }

        if (filteredNews != null) {
            for (FilteredNewsItem news : filteredNews) {
                if (news.title() == null || news.title().isBlank()) {
                    continue;
                }
                if (!seen.add("NEWS:" + news.title())) {
                    continue;
                }
                signals.add(new Signal("NEWS", news.title(),
                        SignalLean.fromSentiment(news.sentiment()), NEWS, "news_event",
                        newsEvidence(news)));
            }
        }
        return new SignalPanel(signals);
    }

    private void collectCodes(AgentVote vote, List<Signal> out, Set<String> seen) {
        List<String> codes = new ArrayList<>();
        if (vote.reasonCodes() != null) {
            codes.addAll(vote.reasonCodes());
        }
        if (vote.riskFlags() != null) {
            codes.addAll(vote.riskFlags());
        }
        for (String code : codes) {
            if (code == null || code.isBlank() || !seen.add(code)) {
                continue;
            }
            Signal sig = translate(code, vote.agent());
            if (sig != null) {
                out.add(sig);
            }
        }
    }

    /** 查字典翻译；白名单未命中返回 null（忽略）。 */
    private Signal translate(String code, String sourceAgent) {
        Meta exact = EXACT.get(code);
        if (exact != null) {
            return new Signal(code, exact.label(), exact.lean(), exact.group(), sourceAgent);
        }
        for (PrefixMeta pm : PREFIX) {
            if (code.startsWith(pm.prefix())) {
                String suffix = code.substring(pm.prefix().length()); // 时间框后缀，如 5M / 1H
                String label = suffix.isBlank() ? pm.labelPrefix() : pm.labelPrefix() + "(" + suffix + ")";
                return new Signal(code, label, pm.lean(), pm.group(), sourceAgent);
            }
        }
        return null;
    }

    private static String newsEvidence(FilteredNewsItem news) {
        StringBuilder sb = new StringBuilder();
        if (news.impact() != null && !news.impact().isBlank()) {
            sb.append("影响").append(news.impact());
        }
        if (news.reason() != null && !news.reason().isBlank()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(news.reason());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static Map<String, Meta> buildExact() {
        Map<String, Meta> m = new LinkedHashMap<>();

        // ===== 盘口微结构 MICROSTRUCTURE =====
        m.put("BID_DOMINANT", new Meta("盘口买盘主导", BULLISH, MICROSTRUCTURE));
        m.put("ASK_DOMINANT", new Meta("盘口卖盘主导", BEARISH, MICROSTRUCTURE));
        m.put("AGGRESSIVE_BUY", new Meta("主动买入成交", BULLISH, MICROSTRUCTURE));
        m.put("AGGRESSIVE_SELL", new Meta("主动卖出成交", BEARISH, MICROSTRUCTURE));
        m.put("TAKER_BUY_SURGE", new Meta("吃单买激增", BULLISH, MICROSTRUCTURE));
        m.put("TAKER_SELL_SURGE", new Meta("吃单卖激增", BEARISH, MICROSTRUCTURE));
        m.put("LARGE_BUY_DOMINANT", new Meta("大单买入主导", BULLISH, MICROSTRUCTURE));
        m.put("LARGE_SELL_DOMINANT", new Meta("大单卖出主导", BEARISH, MICROSTRUCTURE));
        m.put("HIGH_TRADE_INTENSITY", new Meta("成交强度骤升", NEUTRAL, MICROSTRUCTURE));
        m.put("SPOT_BID_DOMINANT", new Meta("现货买盘主导", BULLISH, MICROSTRUCTURE));
        m.put("SPOT_ASK_DOMINANT", new Meta("现货卖盘主导", BEARISH, MICROSTRUCTURE));
        m.put("SPOT_STRONGER_THAN_PERP", new Meta("现货强于合约", BULLISH, MICROSTRUCTURE));
        m.put("PERP_STRONGER_THAN_SPOT", new Meta("合约强于现货·杠杆驱动", BEARISH, MICROSTRUCTURE));
        m.put("SPOT_PERP_CONFIRM_UP", new Meta("现货合约同步走强", BULLISH, MICROSTRUCTURE));
        m.put("SPOT_PERP_CONFIRM_DOWN", new Meta("现货合约同步走弱", BEARISH, MICROSTRUCTURE));
        m.put("SPOT_PERP_DIVERGENCE", new Meta("现货合约背离·分歧", RISK, MICROSTRUCTURE));

        // ===== 持仓情绪 POSITIONING（脆弱度·拥挤度腿来源）=====
        m.put("OI_UP_PRICE_UP", new Meta("增仓上涨·多头进场", BULLISH, POSITIONING));
        m.put("OI_UP_PRICE_DOWN", new Meta("增仓下跌·空头进场", BEARISH, POSITIONING));
        m.put("OI_DOWN_PRICE_UP", new Meta("减仓上涨·空头回补", BULLISH, POSITIONING));
        m.put("OI_DOWN_PRICE_DOWN", new Meta("减仓下跌·多头平仓", BEARISH, POSITIONING));
        m.put("HEAVY_LONG_LIQ", new Meta("多头大量爆仓·下行去杠杆", BEARISH, POSITIONING));
        m.put("HEAVY_SHORT_LIQ", new Meta("空头大量爆仓·上行去杠杆", BULLISH, POSITIONING));
        m.put("TOP_ADDING_LONG", new Meta("大户加多", BULLISH, POSITIONING));
        m.put("TOP_ADDING_SHORT", new Meta("大户加空", BEARISH, POSITIONING));
        m.put("HIGH_FUNDING", new Meta("资金费率偏高·多头拥挤(反向偏空)", BEARISH, POSITIONING));
        m.put("LOW_FUNDING", new Meta("资金费率偏低·空头拥挤(反向偏多)", BULLISH, POSITIONING));
        m.put("LSR_EXTREME_LONG", new Meta("多空比极端偏多·拥挤(反向偏空)", BEARISH, POSITIONING));
        m.put("LSR_EXTREME_SHORT", new Meta("多空比极端偏空·拥挤(反向偏多)", BULLISH, POSITIONING));
        m.put("PERP_PREMIUM_RICH", new Meta("合约升水偏高·多头付费(反向偏空)", BEARISH, POSITIONING));
        m.put("PERP_DISCOUNT_DEEP", new Meta("合约贴水偏深·空头付费(反向偏多)", BULLISH, POSITIONING));

        // ===== 趋势动量 MOMENTUM（regime 的 transition/趋势语义并入此组）=====
        m.put("TRANSITION_BREAKING_OUT", new Meta("向上突破", BULLISH, MOMENTUM));
        m.put("TRANSITION_BREAKING_DOWN", new Meta("向下突破", BEARISH, MOMENTUM));
        m.put("TRANSITION_STRENGTHENING", new Meta("趋势强化", NEUTRAL, MOMENTUM));
        m.put("TRANSITION_WEAKENING", new Meta("趋势弱化", RISK, MOMENTUM));
        m.put("TREND_WEAKENING", new Meta("趋势走弱", RISK, MOMENTUM));

        // ===== 波动状态 VOLATILITY（脆弱度·vol-state 腿来源；regime/IV/布林/ATR）=====
        m.put("LIVE_REGIME_TREND_UP", new Meta("当前状态·上升趋势", BULLISH, VOLATILITY));
        m.put("LIVE_REGIME_TREND_DOWN", new Meta("当前状态·下降趋势", BEARISH, VOLATILITY));
        m.put("LIVE_REGIME_RANGE", new Meta("当前状态·区间震荡", NEUTRAL, VOLATILITY));
        m.put("LIVE_REGIME_SQUEEZE", new Meta("当前状态·挤压收敛", RISK, VOLATILITY));
        m.put("LIVE_REGIME_SHOCK", new Meta("当前状态·剧烈波动", RISK, VOLATILITY));
        m.put("LOW_REGIME_CONF", new Meta("市场状态置信度低", RISK, VOLATILITY));
        m.put("RANGE_BOUND", new Meta("区间束缚", NEUTRAL, VOLATILITY));
        m.put("SQUEEZE_WAIT_BREAKOUT", new Meta("挤压待突破", RISK, VOLATILITY));
        m.put("EXTREME_VOLATILITY", new Meta("极端波动", RISK, VOLATILITY));
        m.put("IV_SKEW_CALL_RICH", new Meta("看涨期权偏贵·偏多押注", BULLISH, VOLATILITY));
        m.put("IV_SKEW_PUT_RICH", new Meta("看跌期权偏贵·偏空押注", BEARISH, VOLATILITY));
        m.put("HIGH_IV_SQUEEZE", new Meta("高隐波挤压", RISK, VOLATILITY));
        m.put("ELEVATED_IMPLIED_VOL", new Meta("隐含波动抬升", RISK, VOLATILITY));
        m.put("PRICE_AT_UPPER_BAND", new Meta("价格触布林上轨", RISK, VOLATILITY));
        m.put("PRICE_AT_LOWER_BAND", new Meta("价格触布林下轨", RISK, VOLATILITY));
        m.put("BOLL_UPPER_EXTREME_5M", new Meta("5m布林上轨极值", RISK, VOLATILITY));
        m.put("BOLL_LOWER_EXTREME_5M", new Meta("5m布林下轨极值", RISK, VOLATILITY));
        m.put("BOLL_SQUEEZE_5M", new Meta("5m布林挤压", RISK, VOLATILITY));
        m.put("BOLL_EXPANSION_5M", new Meta("5m布林扩张", RISK, VOLATILITY));
        m.put("BOLL_SQUEEZE_15M", new Meta("15m布林挤压", RISK, VOLATILITY));
        m.put("VOLATILITY_COMPRESSED", new Meta("波动压缩", RISK, VOLATILITY));
        m.put("VOLATILITY_EXPANDED", new Meta("波动扩张", RISK, VOLATILITY));
        m.put("ATR_ACCELERATING", new Meta("ATR加速", RISK, VOLATILITY));
        m.put("ATR_DECELERATING", new Meta("ATR减速", NEUTRAL, VOLATILITY));

        // ===== 新闻事件 NEWS（riskFlags；正常新闻走 filteredNews）=====
        m.put("BLACK_SWAN_RISK", new Meta("黑天鹅风险", RISK, NEWS));
        m.put("REGULATORY_UNCERTAINTY", new Meta("监管不确定性", RISK, NEWS));

        return Map.copyOf(m);
    }

    private static List<PrefixMeta> buildPrefix() {
        // momentum/regime 的 reasonCodes 带时间框后缀（如 MA_BULLISH_5M、RSI_OVERSOLD_15M）。
        return List.of(
                new PrefixMeta("MA_BULLISH_", "均线多头排列", BULLISH, MOMENTUM),
                new PrefixMeta("MA_BEARISH_", "均线空头排列", BEARISH, MOMENTUM),
                new PrefixMeta("MACD_GOLDEN_", "MACD金叉", BULLISH, MOMENTUM),
                new PrefixMeta("MACD_DEATH_", "MACD死叉", BEARISH, MOMENTUM),
                new PrefixMeta("RSI_OVERBOUGHT_", "RSI超买", BEARISH, MOMENTUM),
                new PrefixMeta("RSI_OVERSOLD_", "RSI超卖", BULLISH, MOMENTUM),
                new PrefixMeta("TF_FALLBACK_", "时间框降级", RISK, MOMENTUM)
        );
    }
}
