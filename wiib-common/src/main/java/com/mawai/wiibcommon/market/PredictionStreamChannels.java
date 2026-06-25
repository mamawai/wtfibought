package com.mawai.wiibcommon.market;

/**
 * 预测回合事件 Stream 契约（feed↔sim 跨进程）。
 * <p>回合顺序敏感（lock 旧→create 新→settle 旧），用 Stream+消费组保 FIFO + at-least-once，
 * 不用会丢序的 Pub/Sub。feed 的 PolymarketWsClient 发事件，sim 的 PredictionRoundConsumer 消费触发账本。
 */
public final class PredictionStreamChannels {

    private PredictionStreamChannels() {}

    public static final String ROUND_STREAM = "stream:prediction:round";
    public static final String GROUP = "sim";
}
