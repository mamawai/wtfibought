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
 * 跟踪止损的跟随状态，一个仓位一行（键 trader + round + symbol + side）。
 * 止损单本身在 sim 仓位上，这里只记规则和跟到哪了：哨兵看标记价，最有利价每往前走一步长，就把 sim 的止损挪到
 * 最有利价回撤 callbackPct 的位置，只进不退。仓位了结或模型换回固定止损就删行。
 */
@Data
@TableName("ai_trader_trail")
public class AiTraderTrail {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long traderId;

    private Integer roundNo;

    private String symbol;

    /** LONG / SHORT */
    private String side;

    /** sim 仓位 id；限价单还没成交时为空，成交后绑上才开始跟 */
    private Long positionId;

    /** 回调幅度 %：止损挂在最有利价回撤这么多的位置 */
    private BigDecimal callbackPct;

    /** 启动价：标记价到了这里才开始跟；为空＝一绑上仓位就跟 */
    private BigDecimal activationPrice;

    /** 开始跟随后的最有利标记价；还没开始跟为空。只在挪止损时落库 */
    private BigDecimal bestPrice;

    /** 这条规则上次看到或挪到的止损价 */
    private BigDecimal stopPrice;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
