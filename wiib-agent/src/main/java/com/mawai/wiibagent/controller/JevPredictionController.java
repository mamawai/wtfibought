package com.mawai.wiibagent.controller;

import com.mawai.wiibcommon.dto.PredictionBetResponse;
import com.mawai.wiibcommon.entity.JevPredictionDecision;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibagent.llm.jev.JevPlatformConfig;
import com.mawai.wiibagent.mapper.JevPredictionDecisionMapper;
import com.mawai.wiibagent.mapper.JevPredictionDecisionMapper.CalibrationBucket;
import com.mawai.wiibagent.mapper.JevPredictionDecisionMapper.CheckpointBrier;
import com.mawai.wiibagent.mapper.JevPredictionDecisionMapper.Stats;
import com.mawai.wiibagent.prediction.JevPredictionAccount;
import com.mawai.wiibagent.prediction.JevPredictionConfig;
import com.mawai.wiibagent.prediction.JevPredictionSwitch;
import com.mawai.wiibagent.prediction.PredictionRules;
import com.mawai.wiibquant.external.sim.SimPredictionClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * Jev 预测员的展示接口（登录即可看，全站一个账户）：概览统计、最近决策带 Jev 的回答、Jev 账户的注单。
 */
@Slf4j
@Tag(name = "Jev 预测员")
@RestController
@RequestMapping("/api/ai/jev-prediction")
@RequiredArgsConstructor
public class JevPredictionController {

    /** 统计只看最近这么久 */
    private static final long STATS_WINDOW_MS = 30L * 24 * 3_600_000;
    private static final int MAX_LIMIT = 200;

    private final JevPlatformConfig platform;
    private final JevPredictionConfig cfg;
    private final JevPredictionSwitch sw;
    private final JevPredictionDecisionMapper mapper;
    private final JevPredictionAccount account;
    private final SimPredictionClient sim;

    /** 一张决策卡要的字段；answers 是两道题的回答原样，没问 Jev 的行为 null */
    public record DecisionView(long id, long windowStart, String checkpoint, long decidedAt,
                               BigDecimal pModel, BigDecimal pJev, BigDecimal pMkt,
                               String jevChoice, BigDecimal jevChoiceP, Integer bookAgeMs,
                               BigDecimal upAsk, BigDecimal downAsk, String action, String reason,
                               Long betId, BigDecimal stake, String outcome, String error, JsonNode answers) {
        static DecisionView of(JevPredictionDecision d) {
            return new DecisionView(d.getId(), d.getWindowStart(), d.getCheckpoint(), d.getDecidedAt(),
                    d.getPModel(), d.getPJev(), d.getPMkt(), d.getJevChoice(), d.getJevChoiceP(), d.getBookAgeMs(),
                    d.getUpAsk(), d.getDownAsk(), d.getAction(), d.getReason(), d.getBetId(), d.getStake(),
                    d.getOutcome(), d.getError(), d.getAnswersJson() == null ? null : MAPPER.readTree(d.getAnswersJson()));
        }
    }

    /** Jev 账户的一笔注单；pnl 和概览的盈亏同一算法（扣买入手续费），没到终态为 null */
    public record BetView(long id, long windowStart, String side, BigDecimal contracts, BigDecimal cost, BigDecimal avgPrice,
                          BigDecimal currentValue, String status, BigDecimal pnl) {
        static BetView of(PredictionBetResponse b) {
            return new BetView(b.getId(), b.getWindowStart(), b.getSide(), b.getContracts(), b.getCost(), b.getAvgPrice(),
                    b.getCurrentValue(), b.getStatus(), PredictionRules.pnl(b));
        }
    }

    /** 页面提示里要写出来的几个阈值 */
    public record Thresholds(double actThreshold, BigDecimal minAsk, BigDecimal maxAsk, double minEdge, double sellEdge) {
    }

    /** enabled=平台 key 配了且 /admin 开关开着；gameBalance 没配 key 或 sim 不可达为 null */
    public record Overview(boolean enabled, String model, Thresholds thresholds, BigDecimal gameBalance,
                           BigDecimal initialGameBalance, Stats stats, List<CheckpointBrier> brierByCheckpoint,
                           List<CalibrationBucket> calibration) {
    }

    /** 余额和注单只看 key 配没配、不看开关：关掉以后页面照样看得到钱包和下过的注；sim 不可达时页面其余部分照常出 */
    @GetMapping("/overview")
    @Operation(summary = "预测员概览：状态、阈值、余额、近 30 天记分（总体 + 按检查点）与校准")
    public Result<Overview> overview() {
        long since = System.currentTimeMillis() - STATS_WINDOW_MS;
        BigDecimal balance = null;
        if (platform.enabled()) {
            try {
                balance = sim.gameBalance(account.userId());
            } catch (Exception e) {
                log.debug("[JevPred] 余额取不到: {}", e.toString());
            }
        }
        boolean enabled = platform.enabled() && sw.isOn();
        Thresholds t = new Thresholds(cfg.getActThreshold(), cfg.getMinAsk(), cfg.getMaxAsk(), cfg.getMinEdge(), cfg.getSellEdge());
        return Result.ok(new Overview(enabled, platform.getModel(), t, balance, JevPredictionAccount.INITIAL_GAME_BALANCE,
                mapper.selectStats(since), mapper.selectBrierByCheckpoint(since), mapper.selectCalibration(since)));
    }

    @GetMapping("/feed")
    @Operation(summary = "最近的决策带 Jev 的回答，新的在前；Jev 页右栏一次拿全")
    public Result<List<DecisionView>> feed(@RequestParam(defaultValue = "51") int limit) {
        int n = Math.max(1, Math.min(MAX_LIMIT, limit));
        return Result.ok(mapper.selectRecent(n).stream().map(DecisionView::of).toList());
    }

    @GetMapping("/bets")
    @Operation(summary = "Jev 账户最近的注单，新的在前；平台没配 key 或 sim 不可达回空")
    public Result<List<BetView>> bets(@RequestParam(defaultValue = "30") int limit) {
        if (!platform.enabled()) {
            return Result.ok(List.of());
        }
        int n = Math.max(1, Math.min(MAX_LIMIT, limit));
        try {
            return Result.ok(sim.recentBets(account.userId(), n).stream().map(BetView::of).toList());
        } catch (Exception e) {
            log.debug("[JevPred] 注单取不到: {}", e.toString());
            return Result.ok(List.of());
        }
    }
}
