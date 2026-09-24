package com.mawai.wiibquant.mapper;

import com.mawai.wiibcommon.dto.NewsEventItem;
import lombok.Data;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 快讯存档读写。刻意不建 entity 不继承 BaseMapper：写路就一条 insert 加一条译文回填，
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

    /** 待译行：只取翻译要用的三列 */
    @Data
    class Untranslated {
        private Long sourceId;
        private String title;
        /** 纯文本正文（入库时已去 HTML） */
        private String content;
    }

    /**
     * 只存中文：译文列和 translated_model 留 NULL＝待译，采集轨随后补译。
     * 冲突静默跳过：source_id 唯一键兜底并发与重复窗口，插了多少条以返回值为准。
     */
    @Insert("""
            INSERT INTO news_event (source_id, title, content, url, published_at)
            VALUES (#{sourceId}, #{title}, #{content}, #{url}, #{publishedAt})
            ON CONFLICT (source_id) DO NOTHING
            """)
    int insertIgnore(@Param("sourceId") long sourceId, @Param("title") String title,
                     @Param("content") String content, @Param("url") String url,
                     @Param("publishedAt") long publishedAt);

    /** 待译行（translated_model 为 NULL），新的在前 */
    @Select("""
            SELECT source_id AS sourceId, title, content
              FROM news_event
             WHERE translated_model IS NULL
             ORDER BY published_at DESC
             LIMIT #{limit}
            """)
    List<Untranslated> selectUntranslated(@Param("limit") int limit);

    /** 回填译文：translated_model 写上＝这条处理完了，译文列可以是 NULL（模型没给/正文超长） */
    @Update("""
            UPDATE news_event
               SET title_en = #{titleEn}, content_en = #{contentEn}, translated_model = #{translatedModel}
             WHERE source_id = #{sourceId} AND translated_model IS NULL
            """)
    int updateTranslation(@Param("sourceId") long sourceId, @Param("titleEn") String titleEn,
                          @Param("contentEn") String contentEn, @Param("translatedModel") String translatedModel);

    /** 本批快讯里已入库的那些 id：先筛再插，ON CONFLICT 也会白耗自增序列号 */
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
