package com.mawai.wiibcommon.market;

import lombok.extern.slf4j.Slf4j;

import java.util.function.LongSupplier;

/**
 * Binance 合约站（fapi）回包头里的已用权重读数：X-MBX-USED-WEIGHT-1M，按 IP 算（同 IP 的别的进程也算在内）、每分钟归零。
 * 现货站是另一套计数，不读。每个回包更新一次；超过 60 秒没有新回包，读数当 0。只读不拦。
 */
@Slf4j
public class BinanceWeightMeter {

    public static final String HEADER = "X-MBX-USED-WEIGHT-1M";
    /** 合约站 REQUEST_WEIGHT 每分钟每 IP 的上限，exchangeInfo 里的值 */
    public static final int LIMIT_PER_MINUTE = 2400;
    static final long FRESH_MS = 60_000L;
    /** 到这个比例打 warn，一分钟最多一条 */
    static final int WARN_PERCENT = 50;

    /** 读数、读到的时刻（epoch 毫秒）、上限 */
    public record Reading(long used, long at, int limit) {
    }

    private volatile long used;
    private volatile long at;
    private volatile long warnedMinute = -1;
    /** 墙钟注入点 */
    LongSupplier nowMs = System::currentTimeMillis;

    /** 回包头的值；没这个头或不是整数就不更新 */
    public void record(String headerValue) {
        if (headerValue == null) {
            return;
        }
        long value;
        try {
            value = Long.parseLong(headerValue.strip());
        } catch (NumberFormatException e) {
            return;
        }
        long now = nowMs.getAsLong();
        used = value;
        at = now;
        long minute = now / 60_000L;
        if (value * 100 >= (long) LIMIT_PER_MINUTE * WARN_PERCENT && minute != warnedMinute) {
            warnedMinute = minute;
            log.warn("[Binance] 合约站已用权重 {}/{}（{}%）", value, LIMIT_PER_MINUTE, value * 100 / LIMIT_PER_MINUTE);
        }
    }

    /** 60 秒内的读数；超龄按 0 */
    public Reading reading() {
        long now = nowMs.getAsLong();
        return new Reading(now - at < FRESH_MS ? used : 0, at, LIMIT_PER_MINUTE);
    }
}
