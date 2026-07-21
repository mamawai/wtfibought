package com.mawai.wiibcommon.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 通知条目。评论类前端按 type+commentId 分组合并——赞会合并成"A、B 等 N 人赞了你"，
 * 回复因为 commentId 各不相同自然不合并。交易类每条都是独立事件，一律不合并。
 */
@Data
public class NotificationDTO implements Serializable {
    private Long id;
    /** 1赞 2回复 3逐仓强平 4止损 5止盈 6全仓爆仓 */
    private Integer type;

    // ---- 评论类 ----
    /** 点击跳转目标评论 */
    private Long commentId;
    private Long actorId;
    private String actorName;

    // ---- 交易类（全仓爆仓只有 quantity=仓位数 和 pnl=净结算额）----
    private String symbol;
    private String side;
    private BigDecimal quantity;
    private BigDecimal price;
    private BigDecimal pnl;

    private Boolean isRead;
    private LocalDateTime createdAt;
}
