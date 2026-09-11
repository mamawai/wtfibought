package com.mawai.wiibquant.task;

import com.mawai.wiibquant.mapper.EconCalendarMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 财经日历采集：TradingView 回包按事件 id upsert，定时轮删窗口内不在回包里的幽灵行；
 * 失败语义与快讯采集同款：跳过本轮沿用旧数据，日历天然耐过期。
 */
class EconCalendarCollectorTest {

    /** TradingView 真实回包形态：数字值（0 是合法值）、scale/unit 分开、讲话类三值全 null */
    private static final String FEED = """
            {"status":"ok","result":[
              {"id":"402825","title":"Retail Sales YoY","country":"CN","currency":"CNY","importance":1,
               "date":"2026-06-16T02:00:00.000Z","actual":-0.6,"previous":0.2,"forecast":0,"unit":"%","scale":null},
              {"id":"410001","title":"Building Permits Prel","country":"US","currency":"USD","importance":1,
               "date":"2026-09-17T12:30:00.000Z","actual":1.443,"previous":1.374,"forecast":1.37,"unit":null,"scale":"M"},
              {"id":"420955","title":"Fed Press Conference","country":"US","currency":"USD","importance":1,
               "date":"2026-09-17T18:30:00.000Z","actual":null,"previous":null,"forecast":null}
            ]}""";

    private final EconCalendarMapper mapper = mock(EconCalendarMapper.class);

    private EconCalendarCollector collector(String feed) {
        EconCalendarCollector c = new EconCalendarCollector(mapper);
        c.enabled = true;
        c.http = _ -> feed;
        return c;
    }

    @Test
    void 解析真实回包_数字拼显示文本_空值保null() {
        List<EconCalendarCollector.Event> events = EconCalendarCollector.parse(FEED);

        assertThat(events).hasSize(3);
        EconCalendarCollector.Event cn = events.getFirst();
        assertThat(cn.sourceId()).isEqualTo("402825");
        assertThat(cn.country()).isEqualTo("CN");
        assertThat(cn.currency()).isEqualTo("CNY");
        assertThat(cn.eventTime()).isEqualTo(Instant.parse("2026-06-16T02:00:00Z").toEpochMilli());
        // 预测 0 是合法值，不能按"假值"丢掉
        assertThat(cn.actual()).isEqualTo("-0.6%");
        assertThat(cn.forecast()).isEqualTo("0%");
        assertThat(cn.previous()).isEqualTo("0.2%");
        // scale 跟在数字后面
        assertThat(events.get(1).actual()).isEqualTo("1.443M");
        assertThat(events.get(1).forecast()).isEqualTo("1.37M");
        // 讲话类三值全 null：库里 NULL 与"没有这个字段"同义
        assertThat(events.get(2).actual()).isNull();
        assertThat(events.get(2).forecast()).isNull();
        assertThat(events.get(2).previous()).isNull();
    }

    @Test
    void 坏行跳过_不拖垮整批() {
        // 坏法覆盖三种：日期不可解析、缺 country、缺 id——它们都不能活到 upsert 撞 NOT NULL 炸整批事务
        String withBad = """
                {"status":"ok","result":[
                  {"id":"1","title":"Good","country":"US","currency":"USD","date":"2026-09-04T12:30:00.000Z"},
                  {"id":"2","title":"NoDate","country":"US","currency":"USD","date":"not-a-date"},
                  {"id":"3","title":"NoCountry","currency":"USD","date":"2026-09-04T12:30:00.000Z"},
                  {"title":"NoId","country":"US","currency":"USD","date":"2026-09-04T12:30:00.000Z"}
                ]}""";

        List<EconCalendarCollector.Event> events = EconCalendarCollector.parse(withBad);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().title()).isEqualTo("Good");
    }

    @Test
    void 定时同步_请求带窗口与High过滤_逐条upsert_删幽灵行() {
        AtomicReference<String> url = new AtomicReference<>();
        EconCalendarCollector c = collector(FEED);
        c.http = u -> {
            url.set(u);
            return FEED;
        };

        c.collect();

        // 只要 High 是服务端过滤
        assertThat(url.get()).contains("minImportance=1");
        verify(mapper).upsert(eq("402825"), eq(Instant.parse("2026-06-16T02:00:00Z").toEpochMilli()),
                eq("CN"), eq("CNY"), eq("Retail Sales YoY"), eq("-0.6%"), eq("0%"), eq("0.2%"));
        verify(mapper).upsert(eq("420955"), anyLong(), eq("US"), eq("USD"), eq("Fed Press Conference"),
                eq(null), eq(null), eq(null));
        // 窗口内不在回包里的行是改期出窗/取消的幽灵，删掉
        verify(mapper).deleteWindowExcept(anyLong(), anyLong(), eq(List.of("402825", "410001", "420955")));
    }

    @Test
    void 窄窗口sync_只upsert不删幽灵() {
        AtomicReference<String> url = new AtomicReference<>();
        EconCalendarCollector c = collector(FEED);
        c.http = u -> {
            url.set(u);
            return FEED;
        };

        List<EconCalendarCollector.Event> events = c.sync(1_757_000_000_000L, 1_757_000_600_000L);

        assertThat(events).hasSize(3);
        assertThat(url.get()).contains("from=2025-09-04T15:33:20Z").contains("to=2025-09-04T15:43:20Z");
        verify(mapper, never()).deleteWindowExcept(anyLong(), anyLong(), anyList());
    }

    @Test
    void 回包为空_不删旧行() {
        collector("{\"status\":\"ok\",\"result\":[]}").collect();

        // 十天窗口不可能没有 High，多半是上游抖了：旧行留着比清空有用
        verify(mapper, never()).deleteWindowExcept(anyLong(), anyLong(), anyList());
        verify(mapper, never()).upsert(anyString(), anyLong(), anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void 拉取失败_跳过本轮不动库() {
        EconCalendarCollector c = collector(FEED);
        c.http = _ -> {
            throw new IllegalStateException("HTTP 503");
        };

        c.collect();

        verify(mapper, never()).upsert(anyString(), anyLong(), anyString(), anyString(), anyString(), any(), any(), any());
        verify(mapper, never()).deleteWindowExcept(anyLong(), anyLong(), anyList());
    }
}
