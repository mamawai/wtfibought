package com.mawai.wiibagent.prediction;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/** 预测员的阈值（application.yml 的 jev.prediction），都是起手值，跑出盈亏和记分后再调。开不开跑看 {@link JevPredictionSwitch} */
@Getter
@Component
public class JevPredictionConfig {

    /** 开盘后第几秒问一次 Jev，升序去重 */
    private final List<Integer> checkpointSeconds;
    private final BigDecimal baseStake;
    /** Jev 选的动作概率到这里才执行 */
    private final double actThreshold;
    /** 买入概率到这里、且那边明显便宜（比例到 bigValueRatio）才下两倍 */
    private final double bigThreshold;
    private final double bigValueRatio;
    /** 要买那边的优势比例低于它算偏贵，拦下 */
    private final double minValueRatio;
    /** 卖价在这个区间外不买：太贵尾部风险大，太便宜基本是废票 */
    private final BigDecimal minAsk;
    private final BigDecimal maxAsk;
    /** 盘口超过这么久没推送就不问不动 */
    private final long bookMaxAgeMs;
    /** 后劲满格加在 z 上的量，只用于记分那一列 */
    private final double momentumTilt;

    public JevPredictionConfig(
            @Value("${jev.prediction.checkpoint-seconds:30,45,60,75,90,105,120,135,150,165,180,195,210,225,240,255,270}") List<Integer> checkpointSeconds,
            @Value("${jev.prediction.base-stake:5}") BigDecimal baseStake,
            @Value("${jev.prediction.act-threshold:0.6}") double actThreshold,
            @Value("${jev.prediction.big-threshold:0.85}") double bigThreshold,
            @Value("${jev.prediction.big-value-ratio:0.2}") double bigValueRatio,
            @Value("${jev.prediction.min-value-ratio:-0.05}") double minValueRatio,
            @Value("${jev.prediction.min-ask:0.03}") BigDecimal minAsk,
            @Value("${jev.prediction.max-ask:0.97}") BigDecimal maxAsk,
            @Value("${jev.prediction.book-max-age-ms:5000}") long bookMaxAgeMs,
            @Value("${jev.prediction.momentum-tilt:0.3}") double momentumTilt) {
        this.checkpointSeconds = checkpointSeconds.stream().distinct().sorted().toList();
        this.baseStake = baseStake;
        this.actThreshold = actThreshold;
        this.bigThreshold = bigThreshold;
        this.bigValueRatio = bigValueRatio;
        this.minValueRatio = minValueRatio;
        this.minAsk = minAsk;
        this.maxAsk = maxAsk;
        this.bookMaxAgeMs = bookMaxAgeMs;
        this.momentumTilt = momentumTilt;
    }
}
