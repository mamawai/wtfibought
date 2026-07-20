package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.dto.CommentDTO;
import com.mawai.wiibcommon.entity.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 评论读写。
 * <p>
 * 列表类查询一律写成 camelCase 别名（{@code c.user_id AS userId}）而不是靠下划线转驼峰：
 * PostgreSQL 会把不带引号的别名折成小写，MyBatis 再做大小写不敏感匹配，两种映射开关下都对。
 */
@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

    /** 原子自增计数，避免并发下读改写丢更新 */
    @Update("UPDATE comment SET like_count = like_count + CASE WHEN #{like} THEN 1 ELSE 0 END, " +
            "dislike_count = dislike_count + CASE WHEN #{like} THEN 0 ELSE 1 END " +
            "WHERE id = #{commentId}")
    int incrementVote(@Param("commentId") long commentId, @Param("like") boolean like);

    @Update("UPDATE comment SET status = 0 WHERE id = #{commentId}")
    int softDelete(@Param("commentId") long commentId);

    /** 根评论被删时级联软删其全部子评论 */
    @Update("UPDATE comment SET status = 0 WHERE root_id = #{rootId}")
    int softDeleteChildren(@Param("rootId") long rootId);

    /**
     * 编辑正文。手写 SQL 显式写 updated_at，不走 updateById——
     * 后者会被全局 MetaObjectHandler 的自动填充接管，控制不住哪次更新该盖时间戳。
     * <p>
     * 条件里再判一次 self_deleted：Service 那次是先 SELECT 再 UPDATE，
     * 中间插进来一个自删就会把占位符改回正文。带上它这个判断才是原子的
     */
    @Update("UPDATE comment SET content = #{content}, updated_at = CURRENT_TIMESTAMP " +
            "WHERE id = #{commentId} AND self_deleted = FALSE")
    int updateContent(@Param("commentId") long commentId, @Param("content") String content);

    /** 用户自删：正文换成占位文案（原文覆盖，不可恢复）。刻意不动 updated_at，占位符不该显示"已编辑" */
    @Update("UPDATE comment SET content = #{placeholder}, self_deleted = TRUE WHERE id = #{commentId}")
    int selfDelete(@Param("commentId") long commentId, @Param("placeholder") String placeholder);

    /**
     * 根评论分页，时间倒序（新话题在上）。
     * childCount 用相关子查询而不是再发一趟批量 COUNT：一页 20 行、每行走一次
     * idx_comment_child，比多一次往返和一次 Map 归并都便宜，代码也少一大截。
     */
    @Select("""
            SELECT c.id, c.user_id AS userId, u.username, u.avatar,
                   c.root_id AS rootId, c.reply_to_user_id AS replyToUserId,
                   c.content, c.like_count AS likeCount, c.dislike_count AS dislikeCount,
                   c.updated_at AS updatedAt, c.self_deleted AS selfDeleted,
                   c.created_at AS createdAt,
                   (SELECT COUNT(*) FROM comment ch
                    WHERE ch.root_id = c.id AND ch.status = 1) AS childCount
            FROM comment c
            JOIN "user" u ON u.id = c.user_id
            WHERE c.root_id IS NULL AND c.status = 1
            ORDER BY c.created_at DESC
            LIMIT #{size} OFFSET #{offset}
            """)
    List<CommentDTO> selectRootPage(@Param("offset") int offset, @Param("size") int size);

    /**
     * 给定这批根评论，各取最早的 N 条子评论，一次查完。
     * <p>
     * 根评论ID由调用方传进来，不在这里重算分页窗口。早先是用 CTE 按同样的 offset/limit
     * 复算一遍，看着少传一个参数，但两次查询之间只要有并发增删，两边算出的窗口就不是同一批，
     * 当页最后一条根评论会静默丢掉预览。
     */
    @Select("""
            <script>
            WITH ranked AS (
                SELECT c.id, c.user_id AS userId, u.username, u.avatar,
                       c.root_id AS rootId, c.reply_to_user_id AS replyToUserId,
                       ru.username AS replyToUsername,
                       c.content, c.like_count AS likeCount, c.dislike_count AS dislikeCount,
                       c.updated_at AS updatedAt, c.self_deleted AS selfDeleted,
                       c.created_at AS createdAt,
                       ROW_NUMBER() OVER (PARTITION BY c.root_id ORDER BY c.created_at) AS rn
                FROM comment c
                JOIN "user" u ON u.id = c.user_id
                LEFT JOIN "user" ru ON ru.id = c.reply_to_user_id
                WHERE c.status = 1 AND c.root_id IN
                <foreach collection="rootIds" item="rid" open="(" separator="," close=")">#{rid}</foreach>
            )
            SELECT id, userId, username, avatar, rootId, replyToUserId, replyToUsername,
                   content, likeCount, dislikeCount, updatedAt, selfDeleted, createdAt
            FROM ranked
            WHERE rn &lt;= #{preview}
            ORDER BY rootId, createdAt
            </script>
            """)
    List<CommentDTO> selectChildPreviews(@Param("rootIds") List<Long> rootIds,
                                         @Param("preview") int preview);

    /**
     * 这个用户在这串里说过话没有（是根评论作者，或在该根下回复过）。
     * 用来卡 replyToUserId：不卡的话随便填个 userId 就能给任意用户推"XX 回复了你"。
     */
    @Select("""
            SELECT EXISTS(
                SELECT 1 FROM comment
                WHERE (id = #{rootId} OR root_id = #{rootId})
                  AND user_id = #{userId} AND status = 1
            )
            """)
    boolean existsInThread(@Param("rootId") long rootId, @Param("userId") long userId);

    /** 子评论分页，时间正序（对话按发生顺序读） */
    @Select("""
            SELECT c.id, c.user_id AS userId, u.username, u.avatar,
                   c.root_id AS rootId, c.reply_to_user_id AS replyToUserId,
                   ru.username AS replyToUsername,
                   c.content, c.like_count AS likeCount, c.dislike_count AS dislikeCount,
                   c.updated_at AS updatedAt, c.self_deleted AS selfDeleted,
                   c.created_at AS createdAt
            FROM comment c
            JOIN "user" u ON u.id = c.user_id
            LEFT JOIN "user" ru ON ru.id = c.reply_to_user_id
            WHERE c.root_id = #{rootId} AND c.status = 1
            ORDER BY c.created_at
            LIMIT #{size} OFFSET #{offset}
            """)
    List<CommentDTO> selectChildren(@Param("rootId") long rootId,
                                    @Param("offset") int offset, @Param("size") int size);

    /** 单条展示体（聚焦视图取根评论用），已删返回 null */
    @Select("""
            SELECT c.id, c.user_id AS userId, u.username, u.avatar,
                   c.root_id AS rootId, c.reply_to_user_id AS replyToUserId,
                   ru.username AS replyToUsername,
                   c.content, c.like_count AS likeCount, c.dislike_count AS dislikeCount,
                   c.updated_at AS updatedAt, c.self_deleted AS selfDeleted,
                   c.created_at AS createdAt,
                   (SELECT COUNT(*) FROM comment ch
                    WHERE ch.root_id = c.id AND ch.status = 1) AS childCount
            FROM comment c
            JOIN "user" u ON u.id = c.user_id
            LEFT JOIN "user" ru ON ru.id = c.reply_to_user_id
            WHERE c.id = #{commentId} AND c.status = 1
            """)
    CommentDTO selectDtoById(@Param("commentId") long commentId);
}
