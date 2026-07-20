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
     * 根评论分页，时间倒序（新话题在上）。
     * childCount 用相关子查询而不是再发一趟批量 COUNT：一页 20 行、每行走一次
     * idx_comment_child，比多一次往返和一次 Map 归并都便宜，代码也少一大截。
     */
    @Select("""
            SELECT c.id, c.user_id AS userId, u.username, u.avatar,
                   c.root_id AS rootId, c.reply_to_user_id AS replyToUserId,
                   c.content, c.like_count AS likeCount, c.dislike_count AS dislikeCount,
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
     * 当页每条根评论下最早的 N 条子评论，一次查完。
     * <p>
     * CTE 里把根评论的分页窗口重算一遍（≤20 行的索引扫描），换掉"先取 id 列表再拼 IN"
     * 那套 foreach 脚本——少一次往返，SQL 也不用写成 XML 片段。
     */
    @Select("""
            WITH roots AS (
                SELECT id FROM comment
                WHERE root_id IS NULL AND status = 1
                ORDER BY created_at DESC
                LIMIT #{size} OFFSET #{offset}
            ), ranked AS (
                SELECT c.id, c.user_id AS userId, u.username, u.avatar,
                       c.root_id AS rootId, c.reply_to_user_id AS replyToUserId,
                       ru.username AS replyToUsername,
                       c.content, c.like_count AS likeCount, c.dislike_count AS dislikeCount,
                       c.created_at AS createdAt,
                       ROW_NUMBER() OVER (PARTITION BY c.root_id ORDER BY c.created_at) AS rn
                FROM comment c
                JOIN roots r ON r.id = c.root_id
                JOIN "user" u ON u.id = c.user_id
                LEFT JOIN "user" ru ON ru.id = c.reply_to_user_id
                WHERE c.status = 1
            )
            SELECT id, userId, username, avatar, rootId, replyToUserId, replyToUsername,
                   content, likeCount, dislikeCount, createdAt
            FROM ranked
            WHERE rn <= #{preview}
            ORDER BY rootId, createdAt
            """)
    List<CommentDTO> selectChildPreviews(@Param("offset") int offset, @Param("size") int size,
                                         @Param("preview") int preview);

    /** 子评论分页，时间正序（对话按发生顺序读） */
    @Select("""
            SELECT c.id, c.user_id AS userId, u.username, u.avatar,
                   c.root_id AS rootId, c.reply_to_user_id AS replyToUserId,
                   ru.username AS replyToUsername,
                   c.content, c.like_count AS likeCount, c.dislike_count AS dislikeCount,
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
