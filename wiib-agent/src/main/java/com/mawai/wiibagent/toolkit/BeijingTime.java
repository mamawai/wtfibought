package com.mawai.wiibagent.toolkit;

import com.mawai.wiibagent.trader.wakeup.WakeWindow;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 北京时间到分钟（yyyy-MM-dd HH:mm）：工具输出、klines 的 endTime、盯盘条件的时刻都是这一种写法 */
public final class BeijingTime {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private BeijingTime() {
    }

    public static String format(long epochMs) {
        return FMT.withZone(WakeWindow.ZONE).format(Instant.ofEpochMilli(epochMs));
    }

    /** 读回 epoch ms；格式不对抛 DateTimeParseException */
    public static long parse(String text) {
        return LocalDateTime.parse(text.strip(), FMT).atZone(WakeWindow.ZONE).toInstant().toEpochMilli();
    }
}
