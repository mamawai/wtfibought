package com.mawai.wiibquant.agent;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.annotation.Symbol;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibquant.agent.behavior.BehaviorAnalysisReport;
import com.mawai.wiibquant.agent.behavior.BehaviorAnalysisService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibquant.agent.analysis.ScorecardService;
import com.mawai.wiibquant.agent.toolkit.NewsCache;
import com.mawai.wiibcommon.entity.QuantDeepAnalysis;
import com.mawai.wiibcommon.entity.QuantSnapshot;
import com.mawai.wiibcommon.entity.QuantVolVerification;
import com.mawai.wiibquant.mapper.QuantDeepAnalysisMapper;
import com.mawai.wiibquant.mapper.QuantSnapshotMapper;
import com.mawai.wiibquant.mapper.QuantVolVerificationMapper;

import com.alibaba.fastjson2.JSON;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Agent 查询接口：behavior 分析 + 量化快照/深研判/记分卡（P7 研判工作台数据源）。
 * 旧方向预测管线的端点（analyze-crypto/signals/forecasts/verifications）随 P7 前端切换整体删除，
 * 对话入口在 {@link com.mawai.wiibquant.agent.chat.ChatWorkbenchController}。
 */
@Slf4j
@Tag(name = "AI Agent接口")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiAgentController {

    private final BehaviorAnalysisService behaviorAnalysisService;
    private final QuantSnapshotMapper snapshotMapper;
    private final QuantDeepAnalysisMapper deepAnalysisMapper;
    private final QuantVolVerificationMapper volVerificationMapper;
    private final ScorecardService scorecardService;
    private final NewsCache newsCache;

    @PostMapping("/analyze-behavior")
    @Operation(summary = "用户行为分析")
    public Result<BehaviorAnalysisReport> analyzeBehavior(@CurrentUserId long userId) {
        return behaviorAnalysisService.analyze(userId);
    }

    @GetMapping("/quant/snapshots/latest")
    @Operation(summary = "查最新数值快照（vol三腿/脆弱度/信号面板）")
    public Result<QuantSnapshot> latestSnapshot(@Symbol String symbol) {
        StpUtil.checkLogin();
        QuantSnapshot snapshot = snapshotMapper.selectOne(new LambdaQueryWrapper<QuantSnapshot>()
                .eq(QuantSnapshot::getSymbol, symbol)
                .orderByDesc(QuantSnapshot::getCloseTime)
                .last("LIMIT 1"));
        return snapshot != null ? Result.ok(snapshot) : Result.fail("暂无快照数据");
    }

    @GetMapping("/quant/scorecard")
    @Operation(summary = "查技能记分卡（vol预测QLIKE vs naive基准 + vol-state命中率）")
    public Result<ScorecardService.Scorecard> scorecard(
            @Symbol String symbol,
            @RequestParam(defaultValue = "7") int days) {
        StpUtil.checkLogin();
        return Result.ok(scorecardService.scorecard(symbol, Math.clamp(days, 1, 90)));
    }

    @GetMapping("/quant/analysis/latest")
    @Operation(summary = "查最新深研判（研判叙事/情景分布/失效条件/无方向态）")
    public Result<QuantDeepAnalysis> latestAnalysis(@Symbol String symbol) {
        StpUtil.checkLogin();
        QuantDeepAnalysis analysis = deepAnalysisMapper.selectOne(new LambdaQueryWrapper<QuantDeepAnalysis>()
                .eq(QuantDeepAnalysis::getSymbol, symbol)
                .orderByDesc(QuantDeepAnalysis::getCloseTime)
                .last("LIMIT 1"));
        return analysis != null ? Result.ok(analysis) : Result.fail("暂无深研判数据");
    }

    /** 时间线曲线点：三腿预测 + 脆弱度 + H6 已验证 realized（到期才有，尾部 6h 天然缺）。 */
    public record SnapshotSeriesPoint(long closeTime, BigDecimal lastPrice,
                                      Double h6SigmaBps, Double h12SigmaBps, Double h24SigmaBps,
                                      String volState, Integer fragilityScore, String fragilityLevel,
                                      Integer realizedAbsBps) {
    }

    @GetMapping("/quant/snapshots/series")
    @Operation(summary = "快照时间线序列（vol三腿+脆弱度+H6 realized，工作台画曲线）")
    public Result<List<SnapshotSeriesPoint>> snapshotSeries(
            @Symbol String symbol,
            @RequestParam(defaultValue = "24") int hours) {
        StpUtil.checkLogin();
        long from = System.currentTimeMillis() - Math.clamp(hours, 1, 168) * 3_600_000L;
        List<QuantSnapshot> snaps = snapshotMapper.selectList(new LambdaQueryWrapper<QuantSnapshot>()
                .eq(QuantSnapshot::getSymbol, symbol)
                .ge(QuantSnapshot::getCloseTime, from)
                .orderByAsc(QuantSnapshot::getCloseTime));
        // 预测 vs 实际同图对照：realized 按 snapshotId 挂到各点
        Map<Long, Integer> realizedBySnapshot = new HashMap<>();
        volVerificationMapper.selectList(new LambdaQueryWrapper<QuantVolVerification>()
                        .eq(QuantVolVerification::getSymbol, symbol)
                        .eq(QuantVolVerification::getHorizon, "H6")
                        .ge(QuantVolVerification::getCloseTime, from))
                .forEach(v -> {
                    if (v.getSnapshotId() != null && v.getRealizedReturnBps() != null) {
                        realizedBySnapshot.put(v.getSnapshotId(), Math.abs(v.getRealizedReturnBps()));
                    }
                });
        List<SnapshotSeriesPoint> points = snaps.stream().map(s -> {
            var legs = JSON.parseObject(s.getVolLegsJson());
            var h6 = legs != null ? legs.getJSONObject("H6") : null;
            var h12 = legs != null ? legs.getJSONObject("H12") : null;
            var h24 = legs != null ? legs.getJSONObject("H24") : null;
            return new SnapshotSeriesPoint(
                    s.getCloseTime(), s.getLastPrice(),
                    h6 != null ? h6.getDouble("sigmaBps") : null,
                    h12 != null ? h12.getDouble("sigmaBps") : null,
                    h24 != null ? h24.getDouble("sigmaBps") : null,
                    h6 != null ? h6.getString("volState") : null,
                    s.getFragilityScore(), s.getFragilityLevel(),
                    realizedBySnapshot.get(s.getId()));
        }).toList();
        return Result.ok(points);
    }

    /** 快讯条目：正文脱 HTML 的纯文本，前端直接展示 */
    public record NewsFlashView(long id, String title, String plain, String url, String createTime) {
    }

    @GetMapping("/quant/news")
    @Operation(summary = "重要快讯（BlockBeats 内存缓存：未过期复用不打上游，首页快讯卡数据源）")
    public Result<List<NewsFlashView>> news() {
        StpUtil.checkLogin();
        return Result.ok(newsCache.getFlashes().stream()
                .map(f -> new NewsFlashView(f.id(), f.title(), f.plainContent(), f.url(), f.createTime()))
                .toList());
    }

    @GetMapping("/quant/analysis/list")
    @Operation(summary = "深研判历史列表（时间线标记+详情回看）")
    public Result<List<QuantDeepAnalysis>> analysisList(
            @Symbol String symbol,
            @RequestParam(defaultValue = "20") int limit) {
        StpUtil.checkLogin();
        return Result.ok(deepAnalysisMapper.selectList(new LambdaQueryWrapper<QuantDeepAnalysis>()
                .eq(QuantDeepAnalysis::getSymbol, symbol)
                .orderByDesc(QuantDeepAnalysis::getCloseTime)
                .last("LIMIT " + Math.clamp(limit, 1, 100))));
    }
}
