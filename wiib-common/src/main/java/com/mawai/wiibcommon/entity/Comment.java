package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 留言板评论。全站唯一一个板，不附着任何标的。
 * <p>
 * 只有两层：{@code rootId} 为空是根评论，否则是该根评论下的子评论。
 * 回复子评论时 rootId 仍指向根，只用 replyToUserId 记被回复者——所以永远不会出现三层嵌套。
 */
@Data
@TableName("comment")
public class Comment {

    public static final int STATUS_OK = 1;
    public static final int STATUS_DELETED = 0;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** NULL=根评论；非NULL=所属根评论ID */
    private Long rootId;

    /** 子评论回复的目标用户，展示"回复 @xxx" */
    private Long replyToUserId;

    private String content;

    /** 只存计数，投票去重靠 Redis Set，不落记录表 */
    private Integer likeCount;

    private Integer dislikeCount;

    /** 1=正常 0=已删 */
    private Integer status;

    /**
     * NULL=从未编辑过。刻意不加 {@code @TableField(fill = FieldFill.UPDATE)}：
     * 全局 MetaObjectHandler 见到该注解会给任何 updateById 自动盖时间戳，
     * 那自删也会被盖上，占位符就成了"已编辑"。这一列只由编辑的手写 SQL 显式写
     */
    private LocalDateTime updatedAt;

    /** 用户自删：内容已被占位文案覆盖。不参与任何过滤，占位符仍是一条正常评论 */
    private Boolean selfDeleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
