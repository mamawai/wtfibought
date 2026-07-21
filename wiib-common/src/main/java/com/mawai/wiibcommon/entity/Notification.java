package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 通知。两类共用一张表，靠 {@code type} 分派，各自只填自己那几列：
 * <ul>
 *   <li><b>评论类</b>(1赞 2回复)：填 actorId + commentId。踩不产生通知；自己赞自己、自己回复自己也不产生。</li>
 *   <li><b>交易类</b>(3逐仓强平 4止损 5止盈 6全仓爆仓)：系统触发，没有 actor 也不指向评论，
 *       填 symbol/side/quantity/price/pnl。前端不做跳转，信息全在通知里。</li>
 * </ul>
 *
 * <p>评论类前端按 {@code type + commentId} 分组，赞会自动合并（多人赞同一条 → commentId 相同）；
 * 交易类每条都是独立事件，按 id 分组不合并。</p>
 */
@Data
@TableName("notification")
public class Notification {

    public static final int TYPE_LIKE = 1;
    public static final int TYPE_REPLY = 2;
    /** 逐仓保证金不足被强平 */
    public static final int TYPE_LIQUIDATION = 3;
    public static final int TYPE_STOP_LOSS = 4;
    public static final int TYPE_TAKE_PROFIT = 5;
    /** 全仓爆仓：一次爆掉该用户所有全仓仓位，合并成一条 */
    public static final int TYPE_CROSS_LIQUIDATION = 6;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收者 */
    private Long userId;

    /** 1赞 2回复 3逐仓强平 4止损 5止盈 6全仓爆仓 */
    private Integer type;

    // ---- 评论类专属 ----

    /** 触发者 */
    private Long actorId;

    /** 点击后要定位的评论 */
    private Long commentId;

    // ---- 交易类专属 ----

    /** 全仓爆仓跨多个币种，为 null */
    private String symbol;

    /** LONG / SHORT；全仓爆仓为 null */
    private String side;

    /** 平掉的数量；type=6 时复用为"爆掉的仓位数" */
    private BigDecimal quantity;

    /** 触发价；全仓爆仓为 null */
    private BigDecimal price;

    /** 已实现盈亏；type=6 时是所有仓位的净结算额 */
    private BigDecimal pnl;

    private Boolean isRead;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
