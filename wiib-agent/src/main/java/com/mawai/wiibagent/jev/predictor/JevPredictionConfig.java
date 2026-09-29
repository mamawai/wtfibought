package com.mawai.wiibagent.jev.predictor;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/** 预测员的配置（application.yml 的 jev.prediction）。开不开跑看 {@link JevPredictionSwitch} */
@Getter
@Component
public class JevPredictionConfig {

    /** 整点唤醒（v5-3）：开盘后第几秒，升序去重 */
    private final List<Integer> timerSeconds;
    /** 突变唤醒（v5-1、v5-2）：一边的中间价 3 秒内涨到这么多 */
    private final BigDecimal jumpThreshold;
    /** 突变唤醒：那一边的起跳价在这个区间里（含两端） */
    private final BigDecimal jumpFromMin;
    private final BigDecimal jumpFromMax;
    /** 突变唤醒后这么久内到点的整点跳过；两次突变唤醒至少隔这么久 */
    private final long wakeCooldownMs;
    /** 每注本金 */
    private final BigDecimal baseStake;
    /** 新开的局每个账户注资这么多 */
    private final BigDecimal initialBalance;
    /** v5-2：Jev 答"在变弱"不超过这里才买 */
    private final double jumpFadingMax;
    /** v5-3 空仓：领先方卖价在这个区间里才买（含两端） */
    private final BigDecimal timerAskMin;
    private final BigDecimal timerAskMax;
    /** v5-3 空仓：Jev 答"最新一步逆着领先方"到这里才买 */
    private final double timerAgainstMin;
    /** v5-3 持仓：Jev 答手里这一边"会赢"不超过这里就卖 */
    private final double timerSellWinMax;
    /** 定了要成交后等这么久再看盘口，按那时的价成交 */
    private final long fillDelayMs;
    /** 等完的价比看到的差这么多以内照样成交（买贵、卖低都算），再差就算没抢到 */
    private final BigDecimal fillTolerance;
    /** 盘口超过这么久没更新就不问不动 */
    private final long bookMaxAgeMs;

    public JevPredictionConfig(
            @Value("${jev.prediction.timer-seconds:60,90,120,150,180,210,240,270}") List<Integer> timerSeconds,
            @Value("${jev.prediction.jump-threshold:0.15}") BigDecimal jumpThreshold,
            @Value("${jev.prediction.jump-from-min:0.30}") BigDecimal jumpFromMin,
            @Value("${jev.prediction.jump-from-max:0.50}") BigDecimal jumpFromMax,
            @Value("${jev.prediction.wake-cooldown-ms:5000}") long wakeCooldownMs,
            @Value("${jev.prediction.base-stake:5}") BigDecimal baseStake,
            @Value("${jev.prediction.initial-balance:500}") BigDecimal initialBalance,
            @Value("${jev.prediction.jump-fading-max:0.30}") double jumpFadingMax,
            @Value("${jev.prediction.timer-ask-min:0.85}") BigDecimal timerAskMin,
            @Value("${jev.prediction.timer-ask-max:0.96}") BigDecimal timerAskMax,
            @Value("${jev.prediction.timer-against-min:0.70}") double timerAgainstMin,
            @Value("${jev.prediction.timer-sell-win-max:0.10}") double timerSellWinMax,
            @Value("${jev.prediction.fill-delay-ms:1000}") long fillDelayMs,
            @Value("${jev.prediction.fill-tolerance:0.03}") BigDecimal fillTolerance,
            @Value("${jev.prediction.book-max-age-ms:5000}") long bookMaxAgeMs) {
        this.timerSeconds = timerSeconds.stream().distinct().sorted().toList();
        this.jumpThreshold = jumpThreshold;
        this.jumpFromMin = jumpFromMin;
        this.jumpFromMax = jumpFromMax;
        this.wakeCooldownMs = wakeCooldownMs;
        this.baseStake = baseStake;
        this.initialBalance = initialBalance;
        this.jumpFadingMax = jumpFadingMax;
        this.timerAskMin = timerAskMin;
        this.timerAskMax = timerAskMax;
        this.timerAgainstMin = timerAgainstMin;
        this.timerSellWinMax = timerSellWinMax;
        this.fillDelayMs = fillDelayMs;
        this.fillTolerance = fillTolerance;
        this.bookMaxAgeMs = bookMaxAgeMs;
    }
}
