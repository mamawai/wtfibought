package com.mawai.wiibagent.toolkit;

import com.mawai.wiibagent.trader.wakeup.WakeWindow;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/** 工具输出里的时刻：北京时间，到分钟 */
final class BeijingTime {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(WakeWindow.ZONE);

    private BeijingTime() {
    }

    static String format(long epochMs) {
        return FMT.format(Instant.ofEpochMilli(epochMs));
    }
}
