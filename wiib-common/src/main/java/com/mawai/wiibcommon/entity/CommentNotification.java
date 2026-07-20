package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评论通知。踩不产生通知；自己赞自己、自己回复自己也不产生。
 * <p>
 * 两种类型的跳转目标统一在 {@code commentId} 一个字段上：赞存自己被赞的那条，
 * 回复存对方那条回复。前端按 {@code type + commentId} 分组，赞会自动合并
 * （多人赞同一条 → commentId 相同），回复自然不合并（各是不同的评论）。
 */
@Data
@TableName("comment_notification")
public class CommentNotification {

    public static final int TYPE_LIKE = 1;
    public static final int TYPE_REPLY = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收者 */
    private Long userId;

    /** 触发者 */
    private Long actorId;

    /** 1=赞 2=回复 */
    private Integer type;

    /** 点击后要定位的评论 */
    private Long commentId;

    private Boolean isRead;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
