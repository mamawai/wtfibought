package com.mawai.wiibquant.agent.quant.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Deribit 期权簿(getBookSummaryByCurrency)解析：按到期日升序分组，供 ATM IV / skew / term slope 取值。
 *
 * <p>到期日必须用 dMMMuu + Locale.ENGLISH 解析：Deribit 单位数日期无前导零(如 3JAN26，dd 会解析失败)，
 * 月份缩写是英文(缺 Locale 在中文 JVM 上直接抛异常)。曾因两处各写一份 formatter 漂移，导致 IV 采集静默失败。</p>
 */
public final class DeribitOptionBook {

    public static final DateTimeFormatter EXPIRY_FMT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("dMMMuu").toFormatter(Locale.ENGLISH);

    /** 单档报价。call=false 即 put。 */
    public record Quote(double strike, boolean call, double markIv) {}

    private final double underlyingPrice;
    private final TreeMap<LocalDate, List<Quote>> byExpiry;

    private DeribitOptionBook(double underlyingPrice, TreeMap<LocalDate, List<Quote>> byExpiry) {
        this.underlyingPrice = underlyingPrice;
        this.byExpiry = byExpiry;
    }

    /** 解析 bookSummary JSON。逐行跳过 mark_iv<=0 或命名不合期权格式(BTC-28MAR25-90000-C)的条目，坏行不影响其他行。 */
    public static DeribitOptionBook parse(String bookSummaryJson) {
        TreeMap<LocalDate, List<Quote>> byExpiry = new TreeMap<>();
        double underlying = 0;
        if (bookSummaryJson == null || bookSummaryJson.isBlank()) {
            return new DeribitOptionBook(underlying, byExpiry);
        }
        JSONObject root = JSON.parseObject(bookSummaryJson);
        JSONArray results = root.getJSONArray("result");
        if (results == null) {
            return new DeribitOptionBook(underlying, byExpiry);
        }
        for (int i = 0; i < results.size(); i++) {
            JSONObject item = results.getJSONObject(i);
            double markIv = item.getDoubleValue("mark_iv");
            if (markIv <= 0) {
                continue;
            }
            if (underlying <= 0) {
                underlying = item.getDoubleValue("underlying_price");
            }
            String name = item.getString("instrument_name");
            if (name == null) {
                continue;
            }
            String[] parts = name.split("-");
            if (parts.length < 4) {
                continue;
            }
            boolean call = "C".equals(parts[3]);
            if (!call && !"P".equals(parts[3])) {
                continue;
            }
            double strike;
            LocalDate expiry;
            try {
                strike = Double.parseDouble(parts[2]);
                expiry = LocalDate.parse(parts[1], EXPIRY_FMT);
            } catch (RuntimeException e) {
                continue;
            }
            byExpiry.computeIfAbsent(expiry, k -> new ArrayList<>()).add(new Quote(strike, call, markIv));
        }
        return new DeribitOptionBook(underlying, byExpiry);
    }

    public boolean isEmpty() {
        return byExpiry.isEmpty();
    }

    /** 首个 mark_iv>0 条目的 underlying_price，无有效条目为 0。 */
    public double underlyingPrice() {
        return underlyingPrice;
    }

    /** 到期日升序(按日期而非字符串——字符串序会把 27FEB 排到 3JAN 前面)的分组视图。 */
    public TreeMap<LocalDate, List<Quote>> byExpiry() {
        return byExpiry;
    }

    /** 组内 strike 最接近 spot 的 call 的 mark_iv；无 call 返回 0。 */
    public static double atmCallIv(Collection<Quote> quotes, double spot) {
        double best = 0;
        double bestDist = Double.MAX_VALUE;
        for (Quote q : quotes) {
            if (!q.call()) {
                continue;
            }
            double dist = Math.abs(q.strike() - spot);
            if (dist < bestDist) {
                bestDist = dist;
                best = q.markIv();
            }
        }
        return best;
    }
}
