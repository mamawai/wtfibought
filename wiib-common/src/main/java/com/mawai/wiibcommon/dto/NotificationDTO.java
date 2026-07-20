package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 通知条目。前端按 type+commentId 分组合并展示——赞会合并成"A、B 等 N 人赞了你"，
 * 回复因为 commentId 各不相同自然不合并（每条回复内容不同、点击跳转位置也不同）。
 */
@Data
public class NotificationDTO implements Serializable {
    private Long id;
    /** 1=赞 2=回复 */
    private Integer type;
    /** 点击跳转目标评论 */
    private Long commentId;
    private Long actorId;
    private String actorName;
    private Boolean isRead;
    private LocalDateTime createdAt;
}
