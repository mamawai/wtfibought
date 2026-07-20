package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.dto.NotificationDTO;
import com.mawai.wiibcommon.entity.CommentNotification;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface CommentNotificationMapper extends BaseMapper<CommentNotification> {

    /**
     * 最近 50 条。取 50 而不是 20 是刻意的：前端要按 type+commentId 合并，
     * 取少了会出现"20条里15条是同一评论的赞、合并后面板看着很空"。
     */
    @Select("""
            SELECT n.id, n.type, n.comment_id AS commentId, n.actor_id AS actorId,
                   u.username AS actorName, n.is_read AS isRead, n.created_at AS createdAt
            FROM comment_notification n JOIN "user" u ON u.id = n.actor_id
            WHERE n.user_id = #{userId}
            ORDER BY n.created_at DESC LIMIT 50
            """)
    List<NotificationDTO> recent(@Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM comment_notification WHERE user_id = #{userId} AND is_read = FALSE")
    long countUnread(@Param("userId") long userId);

    @Update("UPDATE comment_notification SET is_read = TRUE WHERE user_id = #{userId} AND is_read = FALSE")
    int markAllRead(@Param("userId") long userId);

    /** 评论被删时清掉指向它的通知，否则点进去只会看到"评论不存在"，且这条死链永远留在信封里 */
    @Delete("DELETE FROM comment_notification WHERE comment_id = #{commentId}")
    int deleteByCommentId(@Param("commentId") long commentId);

    /** 根评论被删=其子评论一并软删，指向这些子评论的通知也要清 */
    @Delete("DELETE FROM comment_notification WHERE comment_id IN (SELECT id FROM comment WHERE root_id = #{rootId})")
    int deleteByRootId(@Param("rootId") long rootId);
}
