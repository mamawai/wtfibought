package com.mawai.wiibcommon.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 交易日推算：+1 天后跳过周末（模拟盘只避开周六日，不做节假日日历）。 */
public final class TradingDayUtil {

    private TradingDayUtil() {
    }

    public static LocalDate nextTradingDay(LocalDate d) {
        if (d == null) return null;
        LocalDate next = d.plusDays(1);
        DayOfWeek dow = next.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY) return next.plusDays(2);
        if (dow == DayOfWeek.SUNDAY) return next.plusDays(1);
        return next;
    }

    /** 时间粒度版本（T+1 结算时刻）：日期按上面规则推进，时分秒保持不变。 */
    public static LocalDateTime nextTradingDay(LocalDateTime t) {
        if (t == null) return null;
        return LocalDateTime.of(nextTradingDay(t.toLocalDate()), t.toLocalTime());
    }
}
