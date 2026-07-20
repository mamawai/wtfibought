package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/** 留言板评论展示体。根评论带子评论预览与总数，子评论带被回复者昵称。 */
@Data
public class CommentDTO implements Serializable {
    private Long id;
    private Long userId;
    private String username;
    private String avatar;
    /** NULL=根评论 */
    private Long rootId;
    private Long replyToUserId;
    private String replyToUsername;
    private String content;
    private Integer likeCount;
    private Integer dislikeCount;
    /** 当前用户是否已对本条表过态（赞踩共用一次机会）。未登录恒 false */
    private Boolean voted;
    /** 根评论专用：子评论总数 */
    private Integer childCount;
    /** 根评论专用：子评论预览（列表页最多2条；聚焦视图为全量） */
    private List<CommentDTO> children;
    private LocalDateTime createdAt;
}
