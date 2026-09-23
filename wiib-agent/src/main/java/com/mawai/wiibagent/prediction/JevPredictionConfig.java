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
    /** 按 Jev 胜率算的每份优势（胜率 − 卖价 − 手续费）到这里才买 */
    private final double minEdge;
    /** 每份优势到这里下两倍 */
    private final double bigEdge;
    /** 持仓时买一价扣掉手续费比数学公平价高出这么多就卖，不到就拿到结算 */
    private final double sellEdge;
    /** 卖价低于它不买 */
    private final BigDecimal minAsk;
    /** 盘口超过这么久没更新就不问不动 */
    private final long bookMaxAgeMs;

    public JevPredictionConfig(
            @Value("${jev.prediction.checkpoint-seconds:30,45,60,75,90,105,120,135,150,165,180,195,210,225,240,255,270}") List<Integer> checkpointSeconds,
            @Value("${jev.prediction.base-stake:5}") BigDecimal baseStake,
            @Value("${jev.prediction.min-edge:0.05}") double minEdge,
            @Value("${jev.prediction.big-edge:0.10}") double bigEdge,
            @Value("${jev.prediction.sell-edge:0.06}") double sellEdge,
            @Value("${jev.prediction.min-ask:0.03}") BigDecimal minAsk,
            @Value("${jev.prediction.book-max-age-ms:5000}") long bookMaxAgeMs) {
        this.checkpointSeconds = checkpointSeconds.stream().distinct().sorted().toList();
        this.baseStake = baseStake;
        this.minEdge = minEdge;
        this.bigEdge = bigEdge;
        this.sellEdge = sellEdge;
        this.minAsk = minAsk;
        this.bookMaxAgeMs = bookMaxAgeMs;
    }
}
