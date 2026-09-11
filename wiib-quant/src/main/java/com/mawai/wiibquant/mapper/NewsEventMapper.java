package com.mawai.wiibquant.mapper;

import com.mawai.wiibcommon.dto.NewsEventItem;
import lombok.Data;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 快讯存档读写。刻意不建 entity 不继承 BaseMapper：写路就一条 insert，
 * 查询按前端契约投影到 DTO，参数直进直出。
 */
@Mapper
public interface NewsEventMapper {

    /** 译文投影行：取用侧拿缓存里的快讯（source_id 对齐）去换字用 */
    @Data
    class Translation {
        private Long sourceId;
        /** 英文标题；null=没译成，回落中文原文 */
        private String titleEn;
        /** 英文正文；null 同上 */
        private String contentEn;
    }

    /** 冲突静默跳过：source_id 唯一键兜底并发与重复窗口，插了多少条以返回值为准。 */
    @Insert("""
            INSERT INTO news_event (source_id, title, content, title_en, content_en,
                                    url, published_at, translated_model)
            VALUES (#{sourceId}, #{title}, #{content}, #{titleEn}, #{contentEn},
                    #{url}, #{publishedAt}, #{translatedModel})
            ON CONFLICT (source_id) DO NOTHING
            """)
    int insertIgnore(@Param("sourceId") long sourceId, @Param("title") String title,
                     @Param("content") String content, @Param("titleEn") String titleEn,
                     @Param("contentEn") String contentEn, @Param("url") String url,
                     @Param("publishedAt") long publishedAt, @Param("translatedModel") String translatedModel);

    /** 本批快讯里已入库的那些 id——先筛后翻译，别为存量白烧模型调用。 */
    @Select("""
            <script>
            SELECT source_id FROM news_event WHERE source_id IN
            <foreach collection="sourceIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Long> selectExistingSourceIds(@Param("sourceIds") List<Long> sourceIds);

    /**
     * 按 BlockBeats 快讯 id 取译文。取用侧（首页快讯卡 / news_search 预取 / 深研判素材）
     * 拿的是 NewsCache 里的实时快讯，译文只在落库那份里，靠 source_id 对上。
     * 没译成的那条列是 NULL，调用方回落中文原文。
     */
    @Select("""
            <script>
            SELECT source_id AS sourceId, title_en AS titleEn, content_en AS contentEn
              FROM news_event WHERE source_id IN
            <foreach collection="sourceIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Translation> selectTranslations(@Param("sourceIds") List<Long> sourceIds);

    /** 最新 N 条（首页快讯卡数据源）：中英两套都投出来，前端按界面语言现选 */
    @Select("""
            SELECT id, title, content, title_en AS titleEn, content_en AS contentEn,
                   url, published_at AS publishedAt
              FROM news_event
             ORDER BY published_at DESC
             LIMIT #{limit}
            """)
    List<NewsEventItem> selectLatest(@Param("limit") int limit);

    /** 时间窗内的快讯（首页快讯卡按天翻）：左闭右开，倒序，投影同 selectLatest */
    @Select("""
            SELECT id, title, content, title_en AS titleEn, content_en AS contentEn,
                   url, published_at AS publishedAt
              FROM news_event
             WHERE published_at >= #{fromMs} AND published_at < #{toMs}
             ORDER BY published_at DESC
             LIMIT #{limit}
            """)
    List<NewsEventItem> selectInRange(@Param("fromMs") long fromMs, @Param("toMs") long toMs,
                                      @Param("limit") int limit);
}
