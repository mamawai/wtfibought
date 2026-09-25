package com.mawai.wiibagent.prediction;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/** 预测员的配置（application.yml 的 jev.prediction）。开不开跑看 {@link JevPredictionSwitch} */
@Getter
@Component
public class JevPredictionConfig {

    /** 开盘后第几秒问一次 Jev，升序去重 */
    private final List<Integer> checkpointSeconds;
    /** 每注本金 */
    private final BigDecimal baseStake;
    /** 同一回合同一边在持的本金最多这么多：持仓时 Jev 又选这一边就再买一注，加到这里为止 */
    private final BigDecimal maxStakePerWindow;
    /** Jev 选的那一项概率到这里才照做；0.5 = 不小于其余选项加起来 */
    private final double actThreshold;
    /** Jev 拍板后等这么久再看盘口，按那时的价成交 */
    private final long fillDelayMs;
    /** 等完的价比 Jev 看到的差这么多以内照样成交，再差就算没抢到 */
    private final BigDecimal fillTolerance;
    /** 盘口超过这么久没更新就不问不动 */
    private final long bookMaxAgeMs;
    /** UP 中间价 3 秒内涨或跌到这么多才写进 state 的 odds.jump */
    private final double jumpThreshold;

    public JevPredictionConfig(
            @Value("${jev.prediction.checkpoint-seconds:30,45,60,75,90,105,120,135,150,165,180,195,210,225,240,255,270}") List<Integer> checkpointSeconds,
            @Value("${jev.prediction.base-stake:5}") BigDecimal baseStake,
            @Value("${jev.prediction.max-stake-per-window:10}") BigDecimal maxStakePerWindow,
            @Value("${jev.prediction.act-threshold:0.5}") double actThreshold,
            @Value("${jev.prediction.fill-delay-ms:1000}") long fillDelayMs,
            @Value("${jev.prediction.fill-tolerance:0.03}") BigDecimal fillTolerance,
            @Value("${jev.prediction.book-max-age-ms:5000}") long bookMaxAgeMs,
            @Value("${jev.prediction.jump-threshold:0.10}") double jumpThreshold) {
        this.checkpointSeconds = checkpointSeconds.stream().distinct().sorted().toList();
        this.baseStake = baseStake;
        this.maxStakePerWindow = maxStakePerWindow;
        this.actThreshold = actThreshold;
        this.fillDelayMs = fillDelayMs;
        this.fillTolerance = fillTolerance;
        this.bookMaxAgeMs = bookMaxAgeMs;
        this.jumpThreshold = jumpThreshold;
    }
}
