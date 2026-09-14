package com.mawai.wiibquant.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 财经日历读写。与 {@link NewsEventMapper} 同款口径：不建 entity 不继承 BaseMapper，
 * 写路按 TradingView 事件 id upsert，查询按注入方要的形状投影。
 */
@Mapper
public interface EconCalendarMapper {

    /** 注入块用的事件行（唤醒开场白按它成文） */
    @Data
    class Row {
        /** TradingView 事件 id；同一时刻可能有多条（补发多期），前端按它区分 */
        private String sourceId;
        private Long eventTime;
        /** ISO 国家码 US/EU/GB… */
        private String country;
        private String currency;
        private String title;
        /** 实际值显示文本；null=未公布或该事件无数值（讲话/会议类） */
        private String actual;
        private String forecast;
        private String previous;
    }

    /** 按 TradingView 事件 id upsert：改期改时刻、公布填实际值、前值修正都落在同一行 */
    @Insert("""
            INSERT INTO econ_calendar_event (source_id, event_time, country, currency, title, actual, forecast, previous)
            VALUES (#{sourceId}, #{eventTime}, #{country}, #{currency}, #{title}, #{actual}, #{forecast}, #{previous})
            ON CONFLICT (source_id) DO UPDATE SET
                event_time = EXCLUDED.event_time, country = EXCLUDED.country, currency = EXCLUDED.currency,
                title = EXCLUDED.title, actual = EXCLUDED.actual, forecast = EXCLUDED.forecast, previous = EXCLUDED.previous
            """)
    int upsert(@Param("sourceId") String sourceId, @Param("eventTime") long eventTime,
               @Param("country") String country, @Param("currency") String currency,
               @Param("title") String title, @Param("actual") String actual,
               @Param("forecast") String forecast, @Param("previous") String previous);

    /** 删窗口内不在本次回包里的行：改期出窗/取消的事件不留幽灵。keepIds 不能为空（调用方先判） */
    @Delete("""
            <script>
            DELETE FROM econ_calendar_event
             WHERE event_time BETWEEN #{fromMs} AND #{toMs}
               AND source_id NOT IN
            <foreach collection="keepIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int deleteWindowExcept(@Param("fromMs") long fromMs, @Param("toMs") long toMs,
                           @Param("keepIds") List<String> keepIds);

    /** 到点了实际值还没落库的数字型事件数（讲话/会议类无预测无前值，不算）；唤醒前的等待闸看它 */
    @Select("""
            SELECT COUNT(*) FROM econ_calendar_event
             WHERE event_time BETWEEN #{fromMs} AND #{toMs}
               AND actual IS NULL
               AND (forecast IS NOT NULL OR previous IS NOT NULL)
            """)
    int countPendingActual(@Param("fromMs") long fromMs, @Param("toMs") long toMs);

    @Select("""
            SELECT source_id AS sourceId, event_time AS eventTime, country, currency, title, actual, forecast, previous
              FROM econ_calendar_event
             WHERE event_time BETWEEN #{fromMs} AND #{toMs}
             ORDER BY event_time, source_id
            """)
    List<Row> selectWindow(@Param("fromMs") long fromMs, @Param("toMs") long toMs);

    /** 库里最早一条的时刻，空表 null；历史回填看它判断补没补过 */
    @Select("SELECT MIN(event_time) FROM econ_calendar_event")
    Long selectMinEventTime();

    /** 单个指标的历次公布：同一指标换过名的，新旧标题一起传；titles 非空 */
    @Select("""
            <script>
            SELECT source_id AS sourceId, event_time AS eventTime, country, currency, title, actual, forecast, previous
              FROM econ_calendar_event
             WHERE country = #{country}
               AND title IN
            <foreach collection="titles" item="t" open="(" separator="," close=")">#{t}</foreach>
             ORDER BY event_time, source_id
            </script>
            """)
    List<Row> selectSeries(@Param("country") String country, @Param("titles") List<String> titles);
}
