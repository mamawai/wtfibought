package com.mawai.wiibquant.agent.toolkit;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibcommon.constant.QuantConstants;
import com.mawai.wiibquant.agent.analysis.ScorecardService;
import com.mawai.wiibquant.agent.quant.domain.MacroContext;
import com.mawai.wiibquant.agent.quant.service.MacroContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 量化预测工具：暴露 research 框架经统计验证的 vol/regime 预测 + 验证战绩记分卡。
 * 铁律：方向字段(directionSign/Confidence)在此层滤掉——方向预测无 edge，不喂给 agent。
 */
@Component
@RequiredArgsConstructor
public class QuantForecastToolkit {

    private final MacroContextService macroContextService;
    private final ScorecardService scorecardService;

    @Tool(name = "vol_forecast", description = """
            Get statistically-validated volatility forecast for a crypto symbol over H6/H12/H24 horizons.
            Returns per-horizon: volTier (NORMAL/ELEVATED/STRESSED), expectedMoveBps,
            trailingPercentile (0-1, how high current vol sits vs history), riskBudgetHint (0-1).
            This is the system's core skill - vol forecasts beat baselines under QLIKE/DM tests.""")
    public String volForecast(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol) {
        MacroContext ctx = macroContextService.computeNow(QuantConstants.normalizeSymbolLenient(symbol), 0);
        if (unavailable(ctx)) {
            return unavailableJson(ctx);
        }
        JSONObject out = base(ctx);
        for (var e : ctx.legs().entrySet()) {
            MacroContext.Leg leg = e.getValue();
            JSONObject h = new JSONObject();
            h.put("volTier", leg.volTier().name());
            h.put("expectedMoveBps", leg.expectedMoveBps());
            h.put("trailingPercentile", leg.trailingPercentile());
            h.put("riskBudgetHint", leg.riskBudgetHint());
            out.put(e.getKey().name(), h);
        }
        return out.toJSONString();
    }

    @Tool(name = "market_regime", description = """
            Get ADX/ATR-based market regime classification for a crypto symbol over H6/H12/H24 horizons.
            Returns per-horizon: regime (TRENDING_UP/TRENDING_DOWN/RANGING/SHOCK) and confidence (0-1).""")
    public String marketRegime(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol) {
        MacroContext ctx = macroContextService.computeNow(QuantConstants.normalizeSymbolLenient(symbol), 0);
        if (unavailable(ctx)) {
            return unavailableJson(ctx);
        }
        JSONObject out = base(ctx);
        for (var e : ctx.legs().entrySet()) {
            MacroContext.Leg leg = e.getValue();
            JSONObject h = new JSONObject();
            h.put("regime", leg.regime().name());
            h.put("confidence", leg.regimeConfidence());
            out.put(e.getKey().name(), h);
        }
        return out.toJSONString();
    }

    @Tool(name = "scorecard", description = """
            Get the live verification scorecard of this system's volatility forecasts:
            QLIKE loss vs naive baseline (improvement>0 means beating baseline), per-point win rate,
            and vol-state (LOW/MID/HIGH) hit rate vs 33.3% random baseline, per horizon H6/H12/H24.
            Also includes 'narrative': the Judge's scenario-probability track record over 12h windows
            (Brier score, lower is better, vs 0.667 uniform-guess baseline; scenario hit rate).
            Use this to honestly answer "how reliable are your forecasts/analyses" - cite real numbers.
            Direction and regime are deliberately NOT scored (validated as no-skill).""")
    public String scorecard(@ToolParam(description = "Symbol, e.g. BTCUSDT") String symbol,
                            @ToolParam(description = "Rolling window in days, 7 or 30, default 7") int windowDays) {
        int days = windowDays == 30 ? 30 : 7;
        return JSON.toJSONString(scorecardService.scorecard(QuantConstants.normalizeSymbolLenient(symbol), days));
    }

    private static boolean unavailable(MacroContext ctx) {
        return ctx == null || ctx.stale() || ctx.qualityFlags().contains("MACRO_CONTEXT_NEUTRAL");
    }

    private static String unavailableJson(MacroContext ctx) {
        JSONObject out = new JSONObject();
        out.put("available", false);
        out.put("reason", ctx == null ? "no data" : "forecast warming up: " + ctx.qualityFlags());
        return out.toJSONString();
    }

    private static JSONObject base(MacroContext ctx) {
        JSONObject out = new JSONObject();
        out.put("available", true);
        out.put("symbol", ctx.symbol());
        out.put("computedAt", ctx.computedAt().toString());
        if (!ctx.qualityFlags().isEmpty()) {
            out.put("qualityFlags", ctx.qualityFlags());
        }
        return out;
    }
}
