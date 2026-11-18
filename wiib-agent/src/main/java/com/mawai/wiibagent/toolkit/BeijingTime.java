package com.mawai.wiibagent.toolkit;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 北京时间到分钟：工具输出、klines 的 endTime、盯盘条件、开场白和复盘素材里的时刻都从这里成文。
 * 带年份的 yyyy-MM-dd HH:mm 能读回；MM-dd HH:mm 是只给看的短写法。
 */
public final class BeijingTime {

    /**
     * 写死 Asia/Shanghai 而不是 systemDefault：界面和提示词标的是"北京时间"，不该随部署环境 TZ 漂移
     * （容器 TZ=Asia/Singapore 同为 +8、无夏令时）。
     */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZONE);

    private BeijingTime() {
    }

    public static String format(long epochMs) {
        return FMT.withZone(ZONE).format(Instant.ofEpochMilli(epochMs));
    }

    /** 不带年份的短写法 MM-dd HH:mm */
    public static String shortFormat(long epochMs) {
        return SHORT.format(Instant.ofEpochMilli(epochMs));
    }

    /** 这一刻所在的北京时间当天 0 点 */
    public static long dayStart(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZONE).toLocalDate().atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    /** 读回 epoch ms；格式不对抛 DateTimeParseException */
    public static long parse(String text) {
        return LocalDateTime.parse(text.strip(), FMT).atZone(ZONE).toInstant().toEpochMilli();
    }
}
