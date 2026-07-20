package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

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
}
