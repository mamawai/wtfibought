package com.mawai.wiibquant.task;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mawai.wiibquant.mapper.EconCalendarMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 财经日历采集轨：拉 TradingView 日历接口（只要 High 级，服务端 minImportance=1 过滤）→ 按事件 id upsert
 * econ_calendar_event。定时每 4h 同步 [now-3d, now+7d]；公布时刻的等待闸（EconCalendarGate）用 {@link #sync}
 * 窄窗口轮询拿实际值。
 * <p>
 * 接口只认 Origin 头，不看 TLS 指纹，Java HttpClient 直连即可。事件 id 跨次拉取稳定，做幂等键：
 * 改期只改时刻、公布填实际值、前值修正落同一行；改期出窗/取消的靠定时轮删窗口内不在回包里的行。
 * <p>
 * 失败语义与快讯采集同款：跳过本轮沿用旧数据——日历事件提前数天可知，旧数据比空表有用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EconCalendarCollector {

    /** 解析后的一条事件；actual/forecast/previous 是显示文本（0.2% / 206K / 1.443M），null=未公布或无数值 */
    public record Event(String sourceId, long eventTime, String country, String currency, String title,
                        String actual, String forecast, String previous) {
    }

    /** 定时同步窗口：往回 3 天（注入要过去 72h 的实际值与修正）、往前 7 天（首页"即将公布"+ 闸提前知道有雷） */
    static final long PAST_MS = 3 * 86_400_000L;
    static final long FUTURE_MS = 7 * 86_400_000L;
    /** 列宽 VARCHAR(200)：外部数据超长会让整批事务回滚，截一刀保住其余行 */
    private static final int MAX_TITLE_LEN = 200;

    private final EconCalendarMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${econ.calendar.enabled:true}")
    boolean enabled;
    @Value("${econ.calendar.url:https://economic-calendar.tradingview.com/events}")
    private String url;

    /** 拉取注入点（完整 URL → 回包）：测试换假源 */
    Function<String, String> http = this::httpGet;

    /** 周期见 econ.calendar.interval-ms（缺省 4h）。initialDelay 让开启动高峰 */
    @Scheduled(fixedDelayString = "${econ.calendar.interval-ms:14400000}", initialDelay = 30_000)
    @Transactional
    public void collect() {
        if (!enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        long from = now - PAST_MS;
        long to = now + FUTURE_MS;
        List<Event> events;
        try {
            events = sync(from, to);
        } catch (Exception e) {
            log.warn("[EconCalendar] 拉取/解析失败跳过本轮，沿用旧数据: {}", e.toString());
            return;
        }
        if (events.isEmpty()) {
            // 十天窗口一条 High 都没有几乎不可能，多半是上游抖了：不拿空回包去删旧行
            log.warn("[EconCalendar] 回包为空，跳过本轮");
            return;
        }
        int pruned = mapper.deleteWindowExcept(from, to, events.stream().map(Event::sourceId).toList());
        log.info("[EconCalendar] 同步 {} 条（删幽灵 {} 条）", events.size(), pruned);
    }

    /** 拉 [fromMs, toMs] 内的 High 事件并逐条 upsert，返回本次事件；拉取/解析失败抛出，由调用方定夺 */
    @Transactional
    public List<Event> sync(long fromMs, long toMs) {
        List<Event> events = parse(http.apply(url + "?from=" + iso(fromMs) + "&to=" + iso(toMs) + "&minImportance=1"));
        for (Event e : events) {
            mapper.upsert(e.sourceId(), e.eventTime(), e.country(), e.currency(), e.title(),
                    e.actual(), e.forecast(), e.previous());
        }
        return events;
    }

    /** 接口要 UTC ISO 时间，截到秒 */
    private static String iso(long ms) {
        return Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /** 回包 → 事件行：ISO 时间转 epoch、数字拼成显示文本；坏行跳过不拖垮整批 */
    static List<Event> parse(String json) {
        JSONArray arr = JSON.parseObject(json).getJSONArray("result");
        List<Event> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JSONObject o = arr.getJSONObject(i);
            try {
                String title = o.getString("title");
                long time = Instant.parse(o.getString("date")).toEpochMilli();
                String scale = o.getString("scale");
                String unit = o.getString("unit");
                // 缺必填字段与坏日期同罪：这里不拦的话会活到 upsert 撞 NOT NULL，整批事务回滚
                out.add(new Event(Objects.requireNonNull(o.getString("id")), time,
                        Objects.requireNonNull(o.getString("country")),
                        Objects.requireNonNull(o.getString("currency")),
                        title.length() > MAX_TITLE_LEN ? title.substring(0, MAX_TITLE_LEN) : title,
                        text(o.getBigDecimal("actual"), scale, unit),
                        text(o.getBigDecimal("forecast"), scale, unit),
                        text(o.getBigDecimal("previous"), scale, unit)));
            } catch (Exception e) {
                log.warn("[EconCalendar] 跳过坏行 id={} title={}", o.get("id"), o.get("title"));
            }
        }
        return out;
    }

    /** 数字 → 显示文本：0 → "0"、1.4430 → "1.443"，再接 K/M/B 与 %。null 保 null（未公布/无数值，0 是合法值） */
    static String text(BigDecimal v, String scale, String unit) {
        if (v == null) {
            return null;
        }
        return v.stripTrailingZeros().toPlainString() + (scale == null ? "" : scale) + ("%".equals(unit) ? "%" : "");
    }

    private String httpGet(String fullUrl) {
        try {
            HttpResponse<String> resp = client.send(HttpRequest.newBuilder(URI.create(fullUrl))
                            .timeout(Duration.ofSeconds(20))
                            // 接口只认这个头，没有就 403
                            .header("Origin", "https://www.tradingview.com")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + resp.statusCode());
            }
            return resp.body();
        } catch (Exception e) {
            throw new IllegalStateException("日历拉取失败: " + e.getMessage(), e);
        }
    }
}
