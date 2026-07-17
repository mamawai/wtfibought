package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 邀请码：本地账号注册凭证，admin 生成时可配使用次数
 */
@Data
@TableName("invite_code")
public class InviteCode {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 邀请码（唯一） */
    private String code;

    /** 最大可用次数 */
    private Integer maxUses;

    /** 已用次数（注册时原子+1，防并发超用） */
    private Integer usedCount;

    /** 是否可用（作废置 false） */
    private Boolean enabled;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
