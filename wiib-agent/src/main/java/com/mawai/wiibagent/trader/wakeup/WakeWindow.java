package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibcommon.entity.AiTrader;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 唤醒时段（北京时间，5 分钟粒度，两端闭区间，可跨午夜）："21:00-08:30"；null=全天。
 * 只管交易类唤醒（例行/警报）；日线交接的复盘/学习不看它——夜里交易了就该复盘夜里的交易。
 * ZONE 写死 Asia/Shanghai 而不是 systemDefault：UI 上标的是"北京时间"，承诺不该随部署环境 TZ 漂移
 * （容器 TZ=Asia/Singapore 同为 +8、无夏令时）。
 */
@Slf4j
public record WakeWindow(int fromMin, int toMin) {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern FORMAT = Pattern.compile("^(\\d{2}):(\\d{2})-(\\d{2}):(\\d{2})$");
    private static final long DAY_MS = 86_400_000L;

    /**
     * 解析唤醒时间窗口
     */
    public static WakeWindow parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = FORMAT.matcher(text.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("trader.config.window.format");
        }
        int from = minuteOfDay(m.group(1), m.group(2));
        int to = minuteOfDay(m.group(3), m.group(4));
        if (from == to) {
            throw new IllegalArgumentException("trader.config.window.sameEnds");
        }
        return new WakeWindow(from, to);
    }

    private static int minuteOfDay(String hh, String mm) {
        int h = Integer.parseInt(hh);
        int min = Integer.parseInt(mm);
        if (h > 23 || min > 59) {
            throw new IllegalArgumentException("trader.config.window.badTime");
        }
        if (min % 5 != 0) {
            throw new IllegalArgumentException("trader.config.window.minuteStep");
        }
        return h * 60 + min;
    }

    /**
     * 运行时取 trader 的时段。写路径已校验，坏值只可能来自手改库：按全天处理并 warn——
     * 一个 trader 的坏数据不许把同一 K 线事件里其他 trader 的唤醒一起炸掉；每次唤醒 warn 一次正好逼人去修
     */
    public static WakeWindow of(AiTrader trader) {
        try {
            return parse(trader.getWakeWindow());
        } catch (IllegalArgumentException e) {
            log.warn("[WakeWindow] traderId={} wake_window 非法'{}'，按全天处理", trader.getId(), trader.getWakeWindow());
            return null;
        }
    }

    /** 该时刻是否在时段内：换成北京时间的"当日第几分钟"再比；跨午夜时段是"两头"的并集 */
    public boolean contains(long epochMs) {
        LocalTime t = Instant.ofEpochMilli(epochMs).atZone(ZONE).toLocalTime();
        int m = t.getHour() * 60 + t.getMinute();
        return fromMin <= toMin
                ? m >= fromMin && m <= toMin
                : m >= fromMin || m <= toMin;
    }

    /** 从 alignedEpochMs（须已对齐 intervalMs）起、含起点，第一个落在时段内的边界；转一整天都没有 → -1 */
    public long nextBoundaryFrom(long alignedEpochMs, long intervalMs) {
        long t = alignedEpochMs;
        for (long i = 0; i <= DAY_MS / intervalMs; i++, t += intervalMs) {
            if (contains(t)) {
                return t;
            }
        }
        return -1;
    }

    /** 本边界是不是时段内最后一次例行唤醒（下一边界已出时段）——开场白据此提示"接下来要休眠" */
    public boolean isLastBoundary(long boundary, long intervalMs) {
        return contains(boundary) && !contains(boundary + intervalMs);
    }

    public String text() {
        return "%02d:%02d-%02d:%02d".formatted(fromMin / 60, fromMin % 60, toMin / 60, toMin % 60);
    }
}
